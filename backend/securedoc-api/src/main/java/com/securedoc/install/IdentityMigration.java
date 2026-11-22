package com.securedoc.install;

import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.jdbc.PostgresGroupGrantStore;
import com.documentplatform.identity.jdbc.PostgresLoginConnectionStore;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.LoginConnection;
import com.securedoc.platform.infrastructure.identity.LexaVaultProduct;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.PrintStream;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * One-off copy of an existing install's login connection and group grants from LexaVault's old tables
 * ({@code enterprise_identity_connections}, {@code tenant_oidc_group_grants}) into platform-identity's {@code idn_}
 * tables (task 0037 step 4). The old tables stay; a later release drops them.
 *
 * <p>Run it after the schema step and setup (setup stores the customer and its entity slots). An entity of the old
 * tables is copied only when its id is the entity UUID of one of the customer's slots (derived from the slot ID);
 * others are listed as skipped. The old tables keep one connection per entity; the new one keeps one per customer and
 * product, so the ACTIVE connections of the matched entities must agree. A connection already stored in {@code idn_}
 * with other values is a conflict and is left as it is.
 *
 * <pre>
 * identity-migration plan     show what would be copied; change nothing
 * identity-migration apply    copy; running it again copies nothing more
 * </pre>
 * Database settings in the environment only: {@code LEXAVAULT_MIGRATE_DB_URL}, {@code _USER}, {@code _PASSWORD}.
 * Exit codes: 0 done (or nothing to do), 1 failed, 2 refused, 3 done except conflicts. Output holds counts, IDs and
 * group names' counts only: never a password, never a secret reference.
 */
public final class IdentityMigration {

    static final String ENV_PREFIX = "LEXAVAULT_MIGRATE";
    static final String CHANGED_BY = "lexavault-identity-migration";

    private final PrintStream out;
    private final PrintStream err;

    IdentityMigration(PrintStream out, PrintStream err) {
        this.out = out;
        this.err = err;
    }

    public static void main(String[] args) {
        QuietLibraries.apply();
        System.exit(new IdentityMigration(System.out, System.err).run(args, System.getenv()));
    }

    /** What the migration found and did. */
    record Result(int connectionsCreated, int connectionsUnchanged, int connectionConflicts, int grantsAdded,
                  int grantsUnchanged, int grantsRefused, List<UUID> skippedEntities) {
        int exitCode() {
            return connectionConflicts > 0 ? 3 : 0;
        }
    }

    int run(String[] args, Map<String, String> env) {
        try {
            if (args.length != 1 || !(args[0].equals("plan") || args[0].equals("apply"))) {
                throw new InstallException(2, "BAD_OPTION", "Usage: identity-migration plan|apply");
            }
            InstallDb db = InstallDb.fromEnvironment(ENV_PREFIX, env);
            Result r = migrate(db, args[0].equals("apply"));
            return r.exitCode();
        } catch (InstallException e) {
            err.println(e.code() + ": " + e.getMessage());
            return e.exitCode();
        }
    }

    private record OldConnection(UUID tenant, String providerId, String providerType, URI issuer,
                                 String browserClientId, String audience, String secretReference) {
        boolean sameAs(LoginConnection c) {
            return providerId.equals(c.providerId()) && providerType.equals(c.providerType().name())
                    && issuer.equals(c.issuer()) && Objects.equals(browserClientId, c.browserClientId())
                    && audience.equals(c.apiAudience()) && Objects.equals(secretReference, c.secretReference());
        }

        boolean sameValues(OldConnection o) {
            return providerId.equals(o.providerId) && providerType.equals(o.providerType) && issuer.equals(o.issuer)
                    && Objects.equals(browserClientId, o.browserClientId) && audience.equals(o.audience)
                    && Objects.equals(secretReference, o.secretReference);
        }
    }

    Result migrate(InstallDb db, boolean apply) {
        String verb = apply ? "apply" : "plan";
        out.println("Identity migration " + verb + ": enterprise_identity_connections and tenant_oidc_group_grants -> idn_ "
                + "(product " + LexaVaultProduct.CODE + "). The old tables are not changed.");
        DataSource ds = new DriverManagerDataSource(db.url(), db.user(), db.password());
        String tenantId;
        Map<UUID, String> slotByEntity = new LinkedHashMap<>();
        List<OldConnection> oldActive = new ArrayList<>();
        Map<UUID, Map<URI, Set<Grant>>> oldGrants = new TreeMap<>();
        try (Connection c = ds.getConnection()) {
            for (String table : List.of("idn_customer", "enterprise_identity_connections", "tenant_oidc_group_grants")) {
                if (!SchemaStep.tableExists(c, table)) {
                    throw new InstallException(2, "TABLES_NOT_READY", "table " + table + " is missing: run the schema step first");
                }
            }
            List<String> customers = strings(c, "select tenant_id from idn_customer order by tenant_id");
            if (customers.size() != 1) {
                throw new InstallException(2, customers.isEmpty() ? "NO_CUSTOMER" : "MORE_THAN_ONE_CUSTOMER",
                        customers.isEmpty() ? "no customer is stored: run setup first (it stores the customer and its entity slots)"
                                : "this database holds " + customers.size() + " customers; the migration is for one customer per database");
            }
            tenantId = customers.get(0);
            try (PreparedStatement ps = c.prepareStatement("select entity_uuid, slot_id from idn_entity where tenant_id = ?")) {
                ps.setString(1, tenantId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) slotByEntity.put(rs.getObject(1, UUID.class), rs.getString(2));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("select tenant_id, provider_id, provider_type, issuer_uri, "
                    + "browser_client_id, api_audience, secret_reference from enterprise_identity_connections "
                    + "where status = 'ACTIVE' order by tenant_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    oldActive.add(new OldConnection(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                            URI.create(rs.getString(4)), blankToNull(rs.getString(5)), rs.getString(6),
                            blankToNull(rs.getString(7))));
                }
            }
            try (PreparedStatement ps = c.prepareStatement("select tenant_id, issuer_uri, external_group, product_role "
                    + "from tenant_oidc_group_grants order by tenant_id, issuer_uri, external_group, product_role");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    oldGrants.computeIfAbsent(rs.getObject(1, UUID.class), t -> new LinkedHashMap<>())
                            .computeIfAbsent(URI.create(rs.getString(2)), i -> new LinkedHashSet<>())
                            .add(new Grant(rs.getString(3), rs.getString(4)));
                }
            }
        } catch (SQLException e) {
            throw new InstallException(1, "MIGRATION_FAILED", "reading the tables failed (SQL state " + e.getSQLState() + ")");
        }

        Set<UUID> skipped = new TreeSet<>();
        oldActive.stream().map(OldConnection::tenant).filter(t -> !slotByEntity.containsKey(t)).forEach(skipped::add);
        oldGrants.keySet().stream().filter(t -> !slotByEntity.containsKey(t)).forEach(skipped::add);

        // The connection: one per customer and product.
        List<OldConnection> matched = oldActive.stream().filter(o -> slotByEntity.containsKey(o.tenant())).toList();
        PostgresLoginConnectionStore connections = new PostgresLoginConnectionStore(ds);
        int created = 0, unchanged = 0, conflicts = 0;
        if (!matched.isEmpty()) {
            OldConnection first = matched.get(0);
            if (matched.stream().anyMatch(o -> !o.sameValues(first))) {
                out.println("  conflict    login connection: the entities' ACTIVE connections differ; nothing copied. "
                        + "Make them equal in the old screen, or store the connection with setup.");
                conflicts++;
            } else {
                Optional<LoginConnection> stored = connections.active(tenantId, LexaVaultProduct.CODE);
                if (stored.isPresent() && first.sameAs(stored.get())) {
                    out.println("  unchanged   login connection " + LexaVaultProduct.CODE + ": provider " + first.providerId()
                            + ", issuer " + first.issuer());
                    unchanged++;
                } else if (stored.isPresent()) {
                    out.println("  conflict    login connection " + LexaVaultProduct.CODE + ": idn_ already holds another "
                            + "ACTIVE connection; it is kept. Setup (--replace-connection) changes it.");
                    conflicts++;
                } else {
                    out.println("  create      login connection " + LexaVaultProduct.CODE + ": provider " + first.providerId()
                            + ", issuer " + first.issuer() + ", API audience " + first.audience());
                    if (apply) {
                        connections.insert(new LoginConnection(UUID.randomUUID(), tenantId, LexaVaultProduct.CODE,
                                first.providerId(), IdentityProviderType.valueOf(first.providerType()), first.issuer(),
                                first.browserClientId(), first.audience(), first.secretReference(),
                                IdentityConnectionStatus.ACTIVE, 1), ChangeSource.MIGRATION, CHANGED_BY);
                    }
                    created++;
                }
            }
        }

        // The grants: per entity slot and product.
        PostgresGroupGrantStore grantStore = new PostgresGroupGrantStore(ds);
        int added = 0, kept = 0, refused = 0;
        for (var entity : oldGrants.entrySet()) {
            String slot = slotByEntity.get(entity.getKey());
            if (slot == null) continue;
            for (var byIssuer : entity.getValue().entrySet()) {
                Set<Grant> accepted = new LinkedHashSet<>();
                for (Grant g : byIssuer.getValue()) {
                    if (LexaVaultProduct.grantRole(g.role()) == null) refused++;
                    else accepted.add(g);
                }
                Set<Grant> existing = grantStore.grants(slot, LexaVaultProduct.CODE, byIssuer.getKey());
                int missing = (int) accepted.stream().filter(g -> !existing.contains(g)).count();
                kept += accepted.size() - missing;
                if (missing > 0) {
                    out.println("  create      " + missing + " grant(s) " + slot + " " + LexaVaultProduct.CODE + " (issuer "
                            + byIssuer.getKey() + ")");
                    if (apply) grantStore.add(slot, LexaVaultProduct.CODE, byIssuer.getKey(), accepted);
                    added += missing;
                }
            }
        }
        if (refused > 0) {
            out.println("  not copied  " + refused + " grant(s) whose role is not one of LexaVault's entity roles "
                    + "(or the platform role); they never admitted anyone.");
        }
        for (UUID t : skipped) {
            out.println("  skipped     entity " + t + ": it is not the entity UUID of any slot of " + tenantId
                    + "; its rows are not copied");
        }
        Result r = new Result(created, unchanged, conflicts, added, kept, refused, List.copyOf(skipped));
        out.println("Identity migration " + (apply ? "done" : "plan") + ": connections created=" + created
                + " unchanged=" + unchanged + " conflict=" + conflicts + "; grants added=" + added + " unchanged=" + kept
                + " not_copied=" + refused + "; entities skipped=" + skipped.size() + ".");
        return r;
    }

    private static List<String> strings(Connection c, String sql) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(rs.getString(1));
        }
        return out;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}

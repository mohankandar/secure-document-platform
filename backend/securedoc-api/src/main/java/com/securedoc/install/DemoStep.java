package com.securedoc.install;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.jdbc.LicenceSlots;
import com.documentplatform.identity.jdbc.PostgresEntityStore;
import com.documentplatform.identity.jdbc.PostgresLoginConnectionStore;
import com.documentplatform.identity.store.ActivationOutcome;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.EntityRecord;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.identity.store.SlotActivation;
import com.documentplatform.licensing.ClockGuard;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.TrustedKeys;
import com.documentplatform.licensing.jdbc.PostgresLastSeenStore;
import com.documentplatform.licensing.jdbc.PostgresLicenceStore;
import com.documentplatform.licensing.jdbc.PostgresUsageLedger;
import com.securedoc.platform.application.port.out.BusinessIdentifierDefinitionRepository;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.infrastructure.identity.LexaVaultProduct;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataTenantCapabilityRepository;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataTenantRepository;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * LexaVault's demo step (task 0037 step 5): the demo-only records that setup does not make, written once by a command
 * after setup, never at startup. It replaces {@code LocalDemoBootstrap} (and, for the local OIDC start,
 * {@code OidcConnectionBootstrap}).
 *
 * <ol>
 *   <li>Activates the demo entity slot (default {@code ENT-DEMO-0001}) with its name, through platform-identity's
 *       {@link SlotActivation} under the stored licence: what a Business Admin does in the app.</li>
 *   <li>Writes LexaVault's own demo records for that entity ({@link DemoAppRecords}).</li>
 *   <li>With {@code --oidc-issuer} (local OIDC development only): makes the customer's LexaVault login connection point
 *       at the local development login system. Setup cannot load it: the sign-in file takes only {@code https}
 *       issuers, and the local Keycloak runs on {@code http://localhost}.</li>
 * </ol>
 *
 * <pre>
 * demo-step apply --keys development-trusted-keys.json [--slot ENT-DEMO-0001] [--name "Local Demo Tenant"]
 *                 [--slug local-demo] [--scanner-client-principal scanner-bridge-local]
 *                 [--oidc-issuer URL --oidc-provider-id ID --oidc-audience AUD [--oidc-browser-client-id ID]]
 * demo-step installed [--slot ENT-DEMO-0001]
 * </pre>
 * {@code installed} answers whether the demo step has made the demo entity (its LexaVault entity row exists): exit 0
 * yes, 4 no. The local start runs setup with the demo files only while the answer is no (task 0037 fix 1), so a
 * database whose demo step stopped half-way gets setup again (it changes nothing) and then the demo step again.
 *
 * <p>An existing database (task 0037 fix 1) may hold the fixed demo tenant {@code 0000...0001} that
 * {@code LocalDemoBootstrap} made, with slug {@code local-demo} and name "Local Demo Tenant". That row and its data are
 * never changed. When the requested slug or name is held by another entity, the demo entity takes
 * {@code <slug>-<slot>} and {@code <name> (<slot>)} instead ({@link DemoNaming}), so the two can be told apart.
 *
 * <p>Database settings in the environment only: {@code LEXAVAULT_DEMO_DB_URL}, {@code _USER}, {@code _PASSWORD}.
 * Exit codes: 0 done (a rerun changes nothing), 1 failed, 2 refused, 4 not done yet ({@code installed} only). Output
 * never holds a password.
 */
public final class DemoStep {

    static final String ENV_PREFIX = "LEXAVAULT_DEMO";
    static final String ACTOR = "lexavault-demo-step";
    static final String DEFAULT_SLOT = "ENT-DEMO-0001";

    /** Options of one run. */
    record Options(Path keys, String slot, String name, String slug, String scannerPrincipal, URI oidcIssuer,
                   String oidcProviderId, String oidcAudience, String oidcBrowserClientId) {
    }

    private final PrintStream out;
    private final PrintStream err;
    private final Clock clock;

    DemoStep(PrintStream out, PrintStream err, Clock clock) {
        this.out = out;
        this.err = err;
        this.clock = clock;
    }

    public static void main(String[] args) {
        QuietLibraries.apply();
        System.exit(new DemoStep(System.out, System.err, Clock.systemUTC()).run(args, System.getenv()));
    }

    int run(String[] args, Map<String, String> env) {
        try {
            if (args.length > 0 && "installed".equals(args[0])) {
                String slot = parseInstalled(args);
                return installed(InstallDb.fromEnvironment(ENV_PREFIX, env), slot) ? 0 : 4;
            }
            Options options = parse(args);
            InstallDb db = InstallDb.fromEnvironment(ENV_PREFIX, env);
            apply(db, options);
            return 0;
        } catch (InstallException e) {
            err.println(e.code() + ": " + e.getMessage());
            return e.exitCode();
        }
    }

    static String parseInstalled(String[] args) {
        if (args.length == 1) return DEFAULT_SLOT;
        if (args.length != 3 || !"--slot".equals(args[1]) || !EntityUuid.isSlotId(args[2])) {
            throw usage("installed takes only --slot ENT-...");
        }
        return args[2];
    }

    /** Whether the demo step has made the slot's demo entity: its LexaVault entity row exists. */
    boolean installed(InstallDb db, String slot) {
        UUID entity = EntityUuid.fromSlotId(slot);
        try (Connection c = new DriverManagerDataSource(db.url(), db.user(), db.password()).getConnection()) {
            if (!SchemaStep.tableExists(c, "tenants")) {
                throw new InstallException(2, "TABLES_NOT_READY", "run the schema step first");
            }
            try (PreparedStatement ps = c.prepareStatement("select count(*) from tenants where id = ?")) {
                ps.setObject(1, entity);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    boolean done = rs.getLong(1) > 0;
                    out.println("Demo step for entity slot " + slot + " (entity " + entity + "): "
                            + (done ? "done" : "not done yet"));
                    return done;
                }
            }
        } catch (SQLException e) {
            throw new InstallException(1, "DB_UNREACHABLE", "the database could not be read (SQL state " + e.getSQLState() + ")");
        }
    }

    static Options parse(String[] args) {
        if (args.length == 0 || !"apply".equals(args[0])) throw usage("the first word must be apply");
        Map<String, String> o = new HashMap<>();
        List<String> known = List.of("--keys", "--slot", "--name", "--slug", "--scanner-client-principal",
                "--oidc-issuer", "--oidc-provider-id", "--oidc-audience", "--oidc-browser-client-id");
        for (int i = 1; i < args.length; i += 2) {
            if (!known.contains(args[i]) || i + 1 >= args.length || o.containsKey(args[i])) {
                throw usage("unknown, repeated or empty option " + args[i]);
            }
            o.put(args[i], args[i + 1]);
        }
        if (!o.containsKey("--keys")) throw usage("--keys is required (the trusted-keys file that verifies the stored licence)");
        String slot = o.getOrDefault("--slot", DEFAULT_SLOT);
        if (!EntityUuid.isSlotId(slot)) throw usage("--slot must be an entity slot ID (ENT-...)");
        URI issuer = null;
        if (o.containsKey("--oidc-issuer")) {
            if (!o.containsKey("--oidc-provider-id") || !o.containsKey("--oidc-audience")) {
                throw usage("--oidc-issuer needs --oidc-provider-id and --oidc-audience");
            }
            try {
                issuer = URI.create(o.get("--oidc-issuer").trim());
            } catch (IllegalArgumentException e) {
                throw usage("--oidc-issuer is not an address");
            }
            if (!issuer.isAbsolute() || issuer.getHost() == null) throw usage("--oidc-issuer must be an absolute address");
        } else if (o.containsKey("--oidc-provider-id") || o.containsKey("--oidc-audience") || o.containsKey("--oidc-browser-client-id")) {
            throw usage("the --oidc-* options need --oidc-issuer");
        }
        return new Options(Path.of(o.get("--keys")), slot, o.getOrDefault("--name", "Local Demo Tenant"),
                o.getOrDefault("--slug", "local-demo"), o.getOrDefault("--scanner-client-principal", "scanner-bridge-local"),
                issuer, trimmed(o.get("--oidc-provider-id")), trimmed(o.get("--oidc-audience")),
                trimmed(o.get("--oidc-browser-client-id")));
    }

    void apply(InstallDb db, Options options) {
        TrustedKeys keys;
        try {
            keys = TrustedKeys.parse(Files.readString(options.keys(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            throw new InstallException(2, "KEYS_UNREADABLE", "the trusted-keys file " + options.keys() + " could not be read");
        }
        DataSource ds = new DriverManagerDataSource(db.url(), db.user(), db.password());
        String tenantId = customer(ds);
        UUID entity = EntityUuid.fromSlotId(options.slot());
        out.println("Demo step for customer " + tenantId + ", entity slot " + options.slot() + " (entity " + entity + "):");

        // 0. A name and slug no other entity holds (an existing database may hold the old fixed demo tenant).
        DemoNaming.Choice naming = DemoNaming.choose(ds, entity, options.slot(), options.name(), options.slug());
        naming.notes().forEach(note -> line("note", note));
        String name = naming.name();

        // 1. Activate the slot under the stored licence, as a Business Admin would.
        LicenceService licences = new LicenceService(new PostgresLicenceStore(ds), new LicenceVerifier(keys),
                new ClockGuard(clock, new PostgresLastSeenStore(ds)), new PostgresUsageLedger(ds),
                LexaVaultProduct.CODE, tenantId);
        PostgresEntityStore entities = new PostgresEntityStore(ds);
        ActivationOutcome outcome = new SlotActivation(entities, LicenceSlots.of(licences), clock)
                .activate(options.slot(), name, ACTOR);
        switch (outcome.result()) {
            case ACTIVATED -> line("create", "activation of " + options.slot() + " as \"" + name + "\"");
            case ALREADY_ACTIVATED -> finishEarlierActivation(entities, outcome, options.slot(), name, naming.entityRowExists());
            default -> throw new InstallException(2, "DEMO_ACTIVATION_REFUSED", options.slot() + ": "
                    + outcome.result() + " (run setup with the demo licence first)");
        }

        // 2. LexaVault's own demo records.
        try (ConfigurableApplicationContext ctx = context(db)) {
            new DemoAppRecords(ctx.getBean(SpringDataTenantRepository.class),
                    ctx.getBean(SpringDataTenantCapabilityRepository.class),
                    ctx.getBean(BusinessIdentifierDefinitionRepository.class),
                    ctx.getBean(TenantRedactionPolicyRepository.class),
                    ctx.getBean(IntegrationConnectionRepository.class),
                    new TransactionTemplate(ctx.getBean(PlatformTransactionManager.class)), out)
                    .ensure(entity, name, naming.slug(), options.scannerPrincipal());
        } catch (InstallException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new InstallException(1, "DEMO_STEP_FAILED", "the demo records could not be written: "
                    + e.getClass().getSimpleName());
        }

        // 3. Local OIDC development only: the login connection to the local login system.
        if (options.oidcIssuer() != null) oidcConnection(ds, tenantId, options);
        out.println("Demo step done.");
    }

    /**
     * The slot was activated before. When an earlier demo step activated it but stopped before writing the entity row
     * (task 0037 fix 1: the owner's database), the name it gave may be the one that clashes; it is corrected to the
     * chosen name. Anything else (a Business Admin's activation or rename, or a finished demo step) is left as it is.
     */
    private void finishEarlierActivation(PostgresEntityStore entities, ActivationOutcome outcome, String slot, String name,
                                         boolean entityRowExists) {
        EntityRecord record = outcome.entity();
        if (entityRowExists || record == null || !ACTOR.equals(record.activatedBy()) || name.equals(record.displayName())) {
            line("unchanged", "activation of " + slot);
            return;
        }
        ActivationOutcome renamed = entities.rename(slot, name);
        switch (renamed.result()) {
            case RENAMED -> line("update", "name of " + slot + " from \"" + record.displayName() + "\" to \"" + name
                    + "\" (an earlier demo step stopped half-way)");
            case UNCHANGED -> line("unchanged", "activation of " + slot);
            default -> throw new InstallException(2, "DEMO_ACTIVATION_REFUSED", slot + ": rename " + renamed.result());
        }
    }

    private void oidcConnection(DataSource ds, String tenantId, Options o) {
        PostgresLoginConnectionStore store = new PostgresLoginConnectionStore(ds);
        Optional<LoginConnection> active = store.active(tenantId, LexaVaultProduct.CODE);
        String what = "login connection " + LexaVaultProduct.CODE + ": provider " + o.oidcProviderId() + ", issuer "
                + o.oidcIssuer() + ", API audience " + o.oidcAudience();
        if (active.isEmpty()) {
            store.insert(new LoginConnection(UUID.randomUUID(), tenantId, LexaVaultProduct.CODE, o.oidcProviderId(),
                    IdentityProviderType.OIDC, o.oidcIssuer(), o.oidcBrowserClientId(), o.oidcAudience(), null,
                    IdentityConnectionStatus.ACTIVE, 1), ChangeSource.APP, ACTOR);
            line("create", what);
            return;
        }
        LoginConnection c = active.get();
        if (c.providerId().equals(o.oidcProviderId()) && c.issuer().equals(o.oidcIssuer())
                && c.apiAudience().equals(o.oidcAudience()) && Objects.equals(c.browserClientId(), o.oidcBrowserClientId())) {
            line("unchanged", what);
            return;
        }
        LoginConnection changed = new LoginConnection(c.connectionId(), tenantId, LexaVaultProduct.CODE, o.oidcProviderId(),
                IdentityProviderType.OIDC, o.oidcIssuer(), o.oidcBrowserClientId(), o.oidcAudience(), c.secretReference(),
                IdentityConnectionStatus.ACTIVE, c.configurationVersion());
        if (store.update(changed, c.configurationVersion(), ChangeSource.APP, ACTOR).isEmpty()) {
            throw new InstallException(1, "DEMO_STEP_FAILED", "the login connection changed while the demo step ran; run it again");
        }
        line("update", what);
    }

    private static String customer(DataSource ds) {
        try (Connection c = ds.getConnection()) {
            if (!SchemaStep.tableExists(c, "idn_customer")) {
                throw new InstallException(2, "TABLES_NOT_READY", "run the schema step first");
            }
            List<String> ids = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("select tenant_id from idn_customer order by tenant_id");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) ids.add(rs.getString(1));
            }
            if (ids.size() != 1) {
                throw new InstallException(2, ids.isEmpty() ? "NO_CUSTOMER" : "MORE_THAN_ONE_CUSTOMER",
                        ids.isEmpty() ? "no customer is stored: run setup with the demo files first"
                                : "this database holds " + ids.size() + " customers");
            }
            return ids.get(0);
        } catch (SQLException e) {
            throw new InstallException(1, "DB_UNREACHABLE", "the database could not be read (SQL state " + e.getSQLState() + ")");
        }
    }

    private static ConfigurableApplicationContext context(InstallDb db) {
        SpringApplication app = new SpringApplication(DemoStepContext.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        Map<String, Object> props = new HashMap<>();
        // Not the app's configuration files: only what this step needs.
        props.put("spring.config.name", "lexavault-demo-step");
        props.put("spring.datasource.url", db.url());
        props.put("spring.datasource.username", db.user());
        props.put("spring.datasource.password", db.password());
        props.put("spring.jpa.hibernate.ddl-auto", "none");
        props.put("spring.jpa.open-in-view", "false");
        props.put("spring.liquibase.enabled", "false");
        props.put("spring.flyway.enabled", "false");
        props.put("spring.autoconfigure.exclude", String.join(",",
                "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration",
                "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
                "org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration",
                "org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration",
                "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration"));
        props.put("logging.level.root", "WARN");
        app.setDefaultProperties(props);
        return app.run();
    }

    private void line(String action, String what) {
        out.println("  " + String.format("%-11s", action) + " " + what);
    }

    private static String trimmed(String s) {
        return s == null ? null : s.trim();
    }

    private static InstallException usage(String why) {
        return new InstallException(2, "BAD_OPTION", why + ". Usage: demo-step apply --keys FILE [--slot ENT-...] "
                + "[--name NAME] [--slug SLUG] [--scanner-client-principal P] [--oidc-issuer URL --oidc-provider-id ID "
                + "--oidc-audience AUD [--oidc-browser-client-id ID]], or demo-step installed [--slot ENT-...]");
    }
}

package com.securedoc.platform.infrastructure.identity;

import com.documentplatform.identity.admission.ConnectionKey;
import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.jdbc.PostgresIdentityLookup;
import com.documentplatform.identity.jdbc.PostgresMachineEntityBindings;
import com.securedoc.platform.application.port.out.ActiveOidcMachineTenantBindings;
import com.securedoc.platform.application.port.out.ActiveOidcTenantCandidates;
import com.securedoc.platform.application.port.out.PlatformOidcGroupGrants;
import com.securedoc.platform.application.port.out.TenantOidcGroupGrantRepository;
import com.securedoc.platform.domain.error.CodedStateException;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Every sign-in read of LexaVault, through platform-identity's {@code idn_} stores (task 0037 step 4): the customer's
 * one login connection per product (shared by its entities) and the grants per entity slot and product. It replaces
 * the reads of {@code enterprise_identity_connections} and {@code tenant_oidc_group_grants}.
 *
 * <p>LexaVault's own rules on top, unchanged from before:
 * <ul>
 *   <li>an entity counts only when LexaVault's own entity row ({@code tenants}) exists and is ACTIVE, besides being
 *       ACTIVE in {@code idn_entity};</li>
 *   <li>a grant's role must be one of LexaVault's entity roles written exactly, or one of today's identity-name aliases
 *       written exactly ({@link LexaVaultProduct#grantRole}); anything else, the platform role included, refuses the
 *       lookup (LV-AUT-007), as before;</li>
 *   <li>platform-wide grants ({@code idn_platform_grant}, task 0054) are passed on exactly as stored; the sign-in check
 *       (platform-identity's PlatformGroupAuthorization) takes only the platform role from them;</li>
 *   <li>a machine binds only through an enabled PUSH_API (or, on the scanner routes, SCANNER) ingestion source whose authentication proves a bearer-token
 *       client (JWT or OAuth2 client credentials).</li>
 * </ul>
 * When a store cannot be read, the candidate, grant and machine lookups give null, so admission refuses (fails closed).
 */
@Repository
public class PlatformIdentityReads implements ActiveOidcTenantCandidates, TenantOidcGroupGrantRepository,
        ActiveOidcMachineTenantBindings, PlatformOidcGroupGrants {

    private static final Logger log = LoggerFactory.getLogger(PlatformIdentityReads.class);

    static final String ENABLED_PUSH_CLIENTS = """
            select distinct i.tenant_id
            from integration_connections i
            join tenants t on t.id = i.tenant_id
            where t.status = 'ACTIVE'
              and i.enabled = true and i.purpose = 'INGESTION_SOURCE'
              and i.provider_type = 'PUSH_API'
              and i.authentication_type in ('JWT', 'OAUTH2_CLIENT_CREDENTIALS')
              and i.client_principal = ?
            """;

    /** Task 0075: the same rule for a scanner source. */
    static final String ENABLED_SCANNER_CLIENTS = ENABLED_PUSH_CLIENTS.replace("'PUSH_API'", "'SCANNER'");

    private final JdbcTemplate jdbc;
    private final PostgresIdentityLookup lookup;
    private final PostgresMachineEntityBindings machines;
    private final PostgresMachineEntityBindings scanners;

    public PlatformIdentityReads(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.lookup = new PostgresIdentityLookup(Objects.requireNonNull(jdbc.getDataSource(), "dataSource"),
                LexaVaultProduct.CODE);
        this.machines = new PostgresMachineEntityBindings(jdbc.getDataSource(), LexaVaultProduct.CODE,
                this::entitiesWithEnabledPushClient);
        this.scanners = new PostgresMachineEntityBindings(jdbc.getDataSource(), LexaVaultProduct.CODE,
                this::entitiesWithEnabledScannerClient);
    }

    @Override
    public Set<TenantId> findCandidates(String providerId, URI issuer, String audience) {
        ConnectionKey key = key(providerId, issuer, audience);
        if (key == null) return Set.of();
        Set<EntityId> found = lookup.find(key);
        return found == null ? null : activeTenants(found);
    }

    @Override
    public Map<TenantId, Map<String, Set<String>>> activeGrants(String providerId, URI issuer, String audience) {
        Objects.requireNonNull(issuer, "issuer");
        ConnectionKey key = key(providerId, issuer, audience);
        if (key == null) return Map.of();
        Map<EntityId, Map<String, Set<String>>> grants = lookup.activeGrants(key);
        if (grants == null) return null;
        Set<TenantId> active = activeTenants(grants.keySet());
        Map<TenantId, Map<String, Set<String>>> out = new LinkedHashMap<>();
        grants.forEach((entity, groups) -> {
            TenantId tenant = TenantId.of(entity.value());
            if (!active.contains(tenant)) return;
            Map<String, Set<String>> perGroup = new LinkedHashMap<>();
            groups.forEach((group, roles) -> {
                Set<String> canonical = new LinkedHashSet<>();
                for (String stored : roles) {
                    String role = LexaVaultProduct.grantRole(stored);
                    if (role == null) {
                        throw new CodedStateException(LvErrorCode.AUT_007,
                                "Invalid or platform-level tenant group role configuration");
                    }
                    canonical.add(role);
                }
                perGroup.put(group, Set.copyOf(canonical));
            });
            out.put(tenant, Map.copyOf(perGroup));
        });
        return Map.copyOf(out);
    }

    @Override
    public Map<String, Set<String>> activePlatformGrants(String providerId, URI issuer, String audience) {
        ConnectionKey key = key(providerId, issuer, audience);
        return key == null ? Map.of() : lookup.activePlatformGrants(key);
    }

    @Override
    public Set<TenantId> findEnabledPushTenants(String providerId, URI issuer, String audience, String clientPrincipal) {
        return bound(machines, providerId, issuer, audience, clientPrincipal);
    }

    @Override
    public Set<TenantId> findEnabledScannerTenants(String providerId, URI issuer, String audience, String clientPrincipal) {
        return bound(scanners, providerId, issuer, audience, clientPrincipal);
    }

    private Set<TenantId> bound(PostgresMachineEntityBindings bindings, String providerId, URI issuer, String audience,
                                String clientPrincipal) {
        ConnectionKey key = key(providerId, issuer, audience);
        if (key == null || clientPrincipal == null || clientPrincipal.isBlank()) return Set.of();
        Set<EntityId> found = bindings.find(key, clientPrincipal.trim());
        return found == null ? null : activeTenants(found);
    }

    /** The app's side of the machine binding: entities with an enabled bearer-token PUSH_API source for the client. */
    Set<UUID> entitiesWithEnabledPushClient(String clientPrincipal) {
        return entitiesWithEnabledClient(ENABLED_PUSH_CLIENTS, clientPrincipal);
    }

    /** The same for a SCANNER source. */
    Set<UUID> entitiesWithEnabledScannerClient(String clientPrincipal) {
        return entitiesWithEnabledClient(ENABLED_SCANNER_CLIENTS, clientPrincipal);
    }

    private Set<UUID> entitiesWithEnabledClient(String sql, String clientPrincipal) {
        try {
            Set<UUID> out = new LinkedHashSet<>();
            jdbc.query(sql, (org.springframework.jdbc.core.RowCallbackHandler)
                    rs -> out.add(rs.getObject("tenant_id", UUID.class)), clientPrincipal);
            return Set.copyOf(out);
        } catch (DataAccessException e) {
            log.error("MACHINE_BINDINGS_UNREADABLE: the integration table could not be read; machine sign-in is refused");
            return null;
        }
    }

    /** The given entities whose LexaVault entity row exists and is ACTIVE. */
    private Set<TenantId> activeTenants(Collection<EntityId> entities) {
        if (entities.isEmpty()) return Set.of();
        UUID[] ids = entities.stream().map(EntityId::value).toArray(UUID[]::new);
        Set<TenantId> out = new LinkedHashSet<>();
        jdbc.query(con -> {
            var ps = con.prepareStatement("select id from tenants where status = 'ACTIVE' and id = any(?)");
            ps.setArray(1, con.createArrayOf("uuid", ids));
            return ps;
        }, (org.springframework.jdbc.core.RowCallbackHandler) rs -> out.add(TenantId.of(rs.getObject("id", UUID.class))));
        return Set.copyOf(out);
    }

    private static ConnectionKey key(String providerId, URI issuer, String audience) {
        if (providerId == null || providerId.isBlank() || issuer == null || audience == null || audience.isBlank()) {
            return null;
        }
        return new ConnectionKey(providerId.trim(), issuer, audience.trim());
    }
}

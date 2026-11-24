package com.securedoc.install;

import com.documentplatform.identity.mapping.Grant;
import com.securedoc.platform.domain.error.CodedStateException;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.identity.PlatformIdentityReads;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0037 step 4 on PostgreSQL: every sign-in read through platform-identity's idn_ tables, with LexaVault's own
 * rules on top (its entity row must be ACTIVE; exact roles or today's aliases). Throwaway server only.
 */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
class PlatformIdentityReadsPostgresTest {

    private static final String TENANT = "TEN-READS-0001";
    private static final String OTHER_CUSTOMER = "TEN-READS-0002";
    private static final URI ISSUER = URI.create("https://login.reads.example/oauth2/default");
    private static final String PROVIDER = "reads-okta";
    private static final String AUDIENCE = "api://lexavault-reads";

    private static ThrowawayDatabase db;
    private static IdnRows rows;
    private static PlatformIdentityReads reads;
    private static UUID a, b, suspended, lexaInactive;

    @BeforeAll
    static void setUp() throws SQLException {
        db = ThrowawayDatabase.create("reads");
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(0, new SchemaStep(quiet, quiet).run(new String[] {"apply"}, db.env(SchemaStep.ENV_PREFIX)));
        rows = new IdnRows(db);
        rows.customer(TENANT);
        a = rows.slot(TENANT, "ENT-READS-A", "ACTIVE");
        b = rows.slot(TENANT, "ENT-READS-B", "ACTIVE");
        suspended = rows.slot(TENANT, "ENT-READS-S", "SUSPENDED");
        rows.slot(TENANT, "ENT-READS-N", "NOT_ACTIVATED");
        lexaInactive = rows.slot(TENANT, "ENT-READS-I", "ACTIVE");
        for (UUID id : new UUID[] {a, b, suspended}) rows.lexaTenant(id, "ACTIVE");
        rows.lexaTenant(lexaInactive, "SUSPENDED");
        rows.connection(TENANT, PROVIDER, ISSUER, AUDIENCE);
        rows.grants("ENT-READS-A", ISSUER, new Grant("Finance Staff", "DOCUMENT_SUPERVISOR"),
                new Grant("Finance Admins", "BUSINESS_ADMIN"), new Grant("Old Reviewers", "REVIEWER"));
        rows.grants("ENT-READS-B", ISSUER, new Grant("Legal Staff", "DOCUMENT_PROCESSOR"));
        rows.grants("ENT-READS-S", ISSUER, new Grant("Suspended Staff", "DOCUMENT_PROCESSOR"));
        rows.grants("ENT-READS-I", ISSUER, new Grant("Inactive Staff", "DOCUMENT_PROCESSOR"));
        // Another customer on the same login system: never mixed in (separate customer, separate connection).
        rows.customer(OTHER_CUSTOMER);
        UUID other = rows.slot(OTHER_CUSTOMER, "ENT-READS-O", "ACTIVE");
        rows.lexaTenant(other, "ACTIVE");
        reads = new PlatformIdentityReads(new JdbcTemplate(rows.ds));
    }

    @AfterAll
    static void drop() throws SQLException {
        if (db != null) db.close();
    }

    @Test
    void candidatesAreTheCustomersActiveEntitiesWhoseLexaVaultRowIsActive() {
        assertEquals(Set.of(TenantId.of(a), TenantId.of(b)), reads.findCandidates(PROVIDER, ISSUER, AUDIENCE));
        assertEquals(Set.of(TenantId.of(a), TenantId.of(b)), reads.findCandidates(" " + PROVIDER + " ", ISSUER, AUDIENCE + " "));
        // Values are compared exactly otherwise: another issuer, audience or provider finds nothing.
        assertEquals(Set.of(), reads.findCandidates(PROVIDER, URI.create(ISSUER + "/"), AUDIENCE));
        assertEquals(Set.of(), reads.findCandidates(PROVIDER, ISSUER, "api://other"));
        assertEquals(Set.of(), reads.findCandidates("other", ISSUER, AUDIENCE));
    }

    @Test
    void grantsComeFromTheActiveEntitiesOnlyWithAliasesReadAsTheirRole() {
        Map<TenantId, Map<String, Set<String>>> grants = reads.activeGrants(PROVIDER, ISSUER, AUDIENCE);
        assertEquals(Set.of(TenantId.of(a), TenantId.of(b)), grants.keySet());
        assertEquals(Set.of("DOCUMENT_SUPERVISOR"), grants.get(TenantId.of(a)).get("Finance Staff"));
        assertEquals(Set.of("DOCUMENT_PROCESSOR"), grants.get(TenantId.of(a)).get("Old Reviewers"));
        assertEquals(Set.of("DOCUMENT_PROCESSOR"), grants.get(TenantId.of(b)).get("Legal Staff"));
        assertTrue(grants.values().stream().noneMatch(g -> g.containsKey("Suspended Staff") || g.containsKey("Inactive Staff")));
    }

    @Test
    void aSingleActiveEntityGivesABindingSeveralGiveNone() throws SQLException {
        // Two ACTIVE entities share the connection: the person chooses, so there is no single binding.
        assertTrue(reads.findActive(PROVIDER, ISSUER, AUDIENCE).isEmpty());
        ThrowawayDatabase one = ThrowawayDatabase.create("bind");
        try (one) {
            PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
            assertEquals(0, new SchemaStep(quiet, quiet).run(new String[] {"apply"}, one.env(SchemaStep.ENV_PREFIX)));
            IdnRows r = new IdnRows(one);
            r.customer(TENANT);
            UUID only = r.slot(TENANT, "ENT-READS-A", "ACTIVE");
            r.slot(TENANT, "ENT-READS-B", "NOT_ACTIVATED");
            r.lexaTenant(only, "ACTIVE");
            r.connection(TENANT, PROVIDER, ISSUER, AUDIENCE);
            PlatformIdentityReads single = new PlatformIdentityReads(new JdbcTemplate(r.ds));
            assertEquals(only, single.findActive(PROVIDER, ISSUER, AUDIENCE).orElseThrow().tenantId());
            assertTrue(single.findActive(PROVIDER, ISSUER, "api://other").isEmpty());
        }
    }

    @Test
    void aMachineBindsThroughAnEnabledBearerTokenPushSourceOfAnActiveEntity() throws SQLException {
        rows.exec("""
                insert into integration_connections (id, tenant_id, name, purpose, direction, provider_type,
                    authentication_type, client_principal, timeout_seconds, retry_max_attempts, acknowledgement_mode,
                    enabled, configuration_version, health_status, created_at, updated_at)
                values (?, ?, 'Push A', 'INGESTION_SOURCE', 'INBOUND', 'PUSH_API', 'JWT', 'svc-a', 5, 0, 'NONE',
                    true, 1, 'UNKNOWN', now(), now())
                """, UUID.randomUUID(), a);
        rows.exec("""
                insert into integration_connections (id, tenant_id, name, purpose, direction, provider_type,
                    authentication_type, client_principal, timeout_seconds, retry_max_attempts, acknowledgement_mode,
                    enabled, configuration_version, health_status, created_at, updated_at)
                values (?, ?, 'Push N', 'INGESTION_SOURCE', 'INBOUND', 'PUSH_API', 'NONE', 'svc-none', 5, 0, 'NONE',
                    true, 1, 'UNKNOWN', now(), now())
                """, UUID.randomUUID(), b);
        assertEquals(Set.of(TenantId.of(a)), reads.findEnabledPushTenants(PROVIDER, ISSUER, AUDIENCE, "svc-a"));
        assertEquals(Set.of(), reads.findEnabledPushTenants(PROVIDER, ISSUER, AUDIENCE, "svc-none"));
        assertEquals(Set.of(), reads.findEnabledPushTenants(PROVIDER, ISSUER, "api://other", "svc-a"));
    }

    @Test
    void aGrantWithAnUnknownRoleOrThePlatformRoleRefusesTheLookupAsBefore() throws SQLException {
        ThrowawayDatabase bad = ThrowawayDatabase.create("badrole");
        try (bad) {
            PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
            assertEquals(0, new SchemaStep(quiet, quiet).run(new String[] {"apply"}, bad.env(SchemaStep.ENV_PREFIX)));
            IdnRows r = new IdnRows(bad);
            r.customer(TENANT);
            UUID only = r.slot(TENANT, "ENT-READS-A", "ACTIVE");
            r.lexaTenant(only, "ACTIVE");
            r.connection(TENANT, PROVIDER, ISSUER, AUDIENCE);
            PlatformIdentityReads badReads = new PlatformIdentityReads(new JdbcTemplate(r.ds));
            r.grantRow("ENT-READS-A", ISSUER, "Ops", "PLATFORM_OPERATIONS_ADMIN");
            assertThrows(CodedStateException.class, () -> badReads.activeGrants(PROVIDER, ISSUER, AUDIENCE));
            r.exec("delete from idn_group_grant");
            r.grantRow("ENT-READS-A", ISSUER, "Lower", "document_processor");
            assertThrows(CodedStateException.class, () -> badReads.activeGrants(PROVIDER, ISSUER, AUDIENCE));
        }
    }

    /** Task 0054: platform-wide grants through the real tables and platform-identity's check, as sign-in uses them. */
    @Test
    void platformWideGrantsReachEveryActiveEntityAndOnlyThePlatformRole() throws SQLException {
        try (ThrowawayDatabase own = ThrowawayDatabase.create("platformgrants")) {
            PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
            assertEquals(0, new SchemaStep(quiet, quiet).run(new String[] {"apply"}, own.env(SchemaStep.ENV_PREFIX)));
            IdnRows r = new IdnRows(own);
            r.customer(TENANT);
            UUID one = r.slot(TENANT, "ENT-READS-A", "ACTIVE");
            UUID two = r.slot(TENANT, "ENT-READS-B", "ACTIVE");
            UUID three = r.slot(TENANT, "ENT-READS-S", "SUSPENDED");
            for (UUID id : new UUID[] {one, two, three}) r.lexaTenant(id, "ACTIVE");
            r.connection(TENANT, PROVIDER, ISSUER, AUDIENCE);
            r.grants("ENT-READS-A", ISSUER, new Grant("Finance Staff", "DOCUMENT_SUPERVISOR"));
            PlatformIdentityReads ownReads = new PlatformIdentityReads(new JdbcTemplate(r.ds));
            assertEquals(Map.of(), ownReads.activePlatformGrants(PROVIDER, ISSUER, AUDIENCE));
            new com.documentplatform.identity.jdbc.PostgresGroupGrantStore(r.ds).changePlatform(TENANT, "LEXAVAULT", ISSUER,
                    Set.of(new Grant("Ops", "PLATFORM_OPERATIONS_ADMIN")), Set.of(),
                    com.documentplatform.identity.store.GrantAuthor.app("test"));
            assertEquals(Map.of("Ops", Set.of("PLATFORM_OPERATIONS_ADMIN")), ownReads.activePlatformGrants(PROVIDER, ISSUER, AUDIENCE));
            assertEquals(Map.of(), ownReads.activePlatformGrants(PROVIDER, URI.create("https://other.example"), AUDIENCE));

            var properties = new com.securedoc.platform.api.security.SecurityProperties();
            properties.getOidc().setProviderId(PROVIDER);
            properties.getOidc().setAudience(AUDIENCE);
            properties.getOidc().setSharedIssuerTenantSelectionEnabled(true);
            var signIn = new com.securedoc.platform.api.security.oidc.OidcTenantMembershipResolver(ownReads, ownReads, ownReads, properties);
            var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("t").header("alg", "none").issuer(ISSUER.toString())
                    .subject("s").build();
            assertEquals(Map.of(TenantId.of(one), Set.of("DOCUMENT_SUPERVISOR", "PLATFORM_OPERATIONS_ADMIN"),
                            TenantId.of(two), Set.of("PLATFORM_OPERATIONS_ADMIN")),
                    signIn.authorizedTenants(jwt, Set.of("Finance Staff", "Ops")), "every ACTIVE entity, no suspended one");
            assertEquals(Map.of(TenantId.of(one), Set.of("DOCUMENT_SUPERVISOR")), signIn.authorizedTenants(jwt, Set.of("Finance Staff", "ops")));

            // A platform-wide grant of an entity role (a row written by hand; setup refuses it) is refused.
            r.exec("insert into idn_platform_grant (tenant_id, product_code, issuer_uri, external_group, role) values (?, ?, ?, ?, ?)",
                    TENANT, "LEXAVAULT", ISSUER.toString(), "Wrong", "BUSINESS_ADMIN");
            var refused = assertThrows(com.securedoc.platform.api.context.SecurityContextException.class,
                    () -> signIn.authorizedTenants(jwt, Set.of("Wrong")));
            assertEquals("Only Platform Operations can be granted through a platform-wide grant", refused.getMessage());
            // An entity grant of the platform role is refused as before.
            r.grantRow("ENT-READS-B", ISSUER, "Sneaky", "PLATFORM_OPERATIONS_ADMIN");
            assertThrows(CodedStateException.class, () -> signIn.authorizedTenants(jwt, Set.of("Sneaky")));
        }
    }

    @Test
    void unreadableTablesGiveNoAnswerSoAdmissionRefuses() throws SQLException {
        ThrowawayDatabase empty = ThrowawayDatabase.create("noidn");
        try (empty) {
            PlatformIdentityReads noTables = new PlatformIdentityReads(new JdbcTemplate(new IdnRows(empty).ds));
            assertNull(noTables.findCandidates(PROVIDER, ISSUER, AUDIENCE));
            assertNull(noTables.activeGrants(PROVIDER, ISSUER, AUDIENCE));
            assertNull(noTables.activePlatformGrants(PROVIDER, ISSUER, AUDIENCE));
        }
    }
}

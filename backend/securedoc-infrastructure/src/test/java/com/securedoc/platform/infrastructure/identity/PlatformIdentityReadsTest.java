package com.securedoc.platform.infrastructure.identity;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0037 step 4, without a database: the roles a grant may carry, and the app's side of the machine binding.
 * (Replaces JdbcActiveOidcMachineTenantBindingsTest, whose class was removed with the old table reads; its two checks
 * are kept here for the new query.)
 */
class PlatformIdentityReadsTest {

    private static final class RecordingJdbc extends JdbcTemplate {
        String sql;
        Object[] parameters;

        RecordingJdbc() {
            super(new DriverManagerDataSource("jdbc:postgresql://127.0.0.1:1/none"));
        }

        @Override
        public void query(String statement, RowCallbackHandler handler, Object... args) {
            sql = statement;
            parameters = args;
        }
    }

    @Test
    void aGrantNamesAnEntityRoleExactlyOrOneOfTodaysAliases() {
        for (String role : LexaVaultProduct.ENTITY_ROLES) assertEquals(role, LexaVaultProduct.grantRole(role));
        assertEquals("DOCUMENT_PROCESSOR", LexaVaultProduct.grantRole("USER"));
        assertEquals("DOCUMENT_PROCESSOR", LexaVaultProduct.grantRole("REVIEWER"));
        assertEquals("DOCUMENT_SUPERVISOR", LexaVaultProduct.grantRole("APPROVER"));
        // Fail closed: another case, a typo, the platform role, nothing.
        assertNull(LexaVaultProduct.grantRole("document_processor"));
        assertNull(LexaVaultProduct.grantRole("user"));
        assertNull(LexaVaultProduct.grantRole("BUSINES_ADMIN"));
        assertNull(LexaVaultProduct.grantRole(LexaVaultProduct.PLATFORM_ROLE));
        assertNull(LexaVaultProduct.grantRole(null));
        assertEquals("LEXAVAULT", LexaVaultProduct.CODE);
    }

    @Test
    void onlyTokenAuthenticatedPushSourcesOfActiveEntitiesAreEligibleForMachineBinding() {
        RecordingJdbc jdbc = new RecordingJdbc();
        var reads = new PlatformIdentityReads(jdbc);
        assertEquals(Set.of(), reads.entitiesWithEnabledPushClient("service-client"));
        assertTrue(jdbc.sql.contains("i.enabled = true"));
        assertTrue(jdbc.sql.contains("i.purpose = 'INGESTION_SOURCE'"));
        assertTrue(jdbc.sql.contains("i.provider_type = 'PUSH_API'"));
        assertTrue(jdbc.sql.contains("i.authentication_type in ('JWT', 'OAUTH2_CLIENT_CREDENTIALS')"));
        assertTrue(jdbc.sql.contains("t.status = 'ACTIVE'"));
        assertArrayEquals(new Object[] {"service-client"}, jdbc.parameters);
    }

    @Test
    void blankMachineIdentityOrConnectionKeyNeverQueries() {
        RecordingJdbc jdbc = new RecordingJdbc();
        var reads = new PlatformIdentityReads(jdbc);
        URI issuer = URI.create("https://identity.example.test");
        assertTrue(reads.findEnabledPushTenants("customer-oidc", issuer, "lexavault-api", " ").isEmpty());
        assertTrue(reads.findEnabledPushTenants(" ", issuer, "lexavault-api", "client").isEmpty());
        assertTrue(reads.findCandidates("customer-oidc", issuer, "").isEmpty());
        assertTrue(reads.activeGrants("customer-oidc", issuer, " ").isEmpty());
        assertTrue(reads.findCandidates("customer-oidc", null, "lexavault-api").isEmpty());
        assertNull(jdbc.sql);
    }
}

package com.securedoc.platform.infrastructure.identity;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.net.URI;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class JdbcActiveOidcMachineTenantBindingsTest {
    private static final class RecordingJdbc extends JdbcTemplate {
        String sql;
        Object[] parameters;

        @Override
        public void query(String statement, RowCallbackHandler handler, Object... args) {
            sql = statement;
            parameters = args;
        }
    }

    @Test void onlyTokenAuthenticatedPushSourcesAreEligibleForMachineBinding() {
        RecordingJdbc jdbc = new RecordingJdbc();
        var repository = new JdbcActiveOidcMachineTenantBindings(jdbc);
        assertEquals(Set.of(), repository.findEnabledPushTenants(
                "customer-oidc", URI.create("https://identity.example.test"),
                "lexavault-api", "service-client"));
        assertNotNull(jdbc.sql);
        assertTrue(jdbc.sql.contains("i.enabled = true"));
        assertTrue(jdbc.sql.contains("i.provider_type = 'PUSH_API'"));
        assertTrue(jdbc.sql.contains("i.authentication_type in ('JWT', 'OAUTH2_CLIENT_CREDENTIALS')"));
        assertArrayEquals(new Object[] {
                "customer-oidc", "https://identity.example.test", "lexavault-api", "service-client"
        }, jdbc.parameters);
    }

    @Test void blankMachineIdentityNeverQueriesBindings() {
        RecordingJdbc jdbc = new RecordingJdbc();
        var repository = new JdbcActiveOidcMachineTenantBindings(jdbc);
        assertTrue(repository.findEnabledPushTenants(
                "customer-oidc", URI.create("https://identity.example.test"),
                "lexavault-api", " ").isEmpty());
        assertNull(jdbc.sql);
    }
}

package com.securedoc.platform.infrastructure.identity;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SharedIssuerActivationCompatibilityTest {
    private static final class CollisionJdbcTemplate extends JdbcTemplate {
        private Object[] arguments;
        private String statement;

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            this.statement = sql;
            this.arguments = args.clone();
            return requiredType.cast(Integer.valueOf(1));
        }
    }

    @Test void legacyModeRejectsActiveBindingOwnedByAnotherTenant() throws Exception {
        CollisionJdbcTemplate jdbc = new CollisionJdbcTemplate();
        var repository = new JdbcOidcConnectionBindingRepository(jdbc);
        Method check = JdbcOidcConnectionBindingRepository.class.getDeclaredMethod(
                "requireCompatibleIssuerBinding", UUID.class,
                JdbcOidcConnectionBindingRepository.ConnectionAdminView.class);
        check.setAccessible(true);
        UUID tenant = UUID.randomUUID();
        var target = new JdbcOidcConnectionBindingRepository.ConnectionAdminView(
                UUID.randomUUID(), tenant, "customer-oidc", "OIDC", "Tenant",
                "https://id.example.test", null, "lexavault-api", "VALIDATED", 2,
                Instant.now(), Instant.now(), Instant.now(), "PASSED", null);
        InvocationTargetException error = assertThrows(InvocationTargetException.class,
                () -> check.invoke(repository, tenant, target));
        assertInstanceOf(IllegalStateException.class, error.getCause());
        assertNotNull(jdbc.statement);
        assertTrue(jdbc.statement.contains("tenant_id<>?"));
        assertTrue(Arrays.equals(new Object[] {
                tenant, "customer-oidc", "https://id.example.test", "lexavault-api"
        }, jdbc.arguments));
    }
}

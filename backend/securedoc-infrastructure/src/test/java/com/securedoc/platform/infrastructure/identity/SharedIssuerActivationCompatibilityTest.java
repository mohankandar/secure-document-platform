package com.securedoc.platform.infrastructure.identity;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SharedIssuerActivationCompatibilityTest {
    @Test void legacyModeRejectsActiveBindingOwnedByAnotherTenant() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any(), any(), any()))
                .thenReturn(1);
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
        verify(jdbc).queryForObject(anyString(), eq(Integer.class),
                eq(tenant), eq("customer-oidc"), eq("https://id.example.test"), eq("lexavault-api"));
    }
}

package com.securedoc.platform.infrastructure.identity;

import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

import java.lang.reflect.Proxy;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One bad row no longer fails the lookup for every entity on the login system (task 0014, step A6). */
class JdbcTenantOidcGroupGrantRepositoryTest {

    static final UUID A = UUID.fromString("a1a1a1a1-0000-4000-8000-00000000000a");
    static final UUID B = UUID.fromString("b2b2b2b2-0000-4000-8000-00000000000b");

    /** Feeds the given rows (tenant, group, role) to the row callback. */
    private static final class RowsJdbc extends JdbcTemplate {
        private final List<Object[]> rows;

        RowsJdbc(List<Object[]> rows) {
            this.rows = rows;
        }

        @Override
        public void query(String sql, RowCallbackHandler handler, Object... args) {
            for (Object[] row : rows) {
                try {
                    handler.processRow(resultSet(row));
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        }

        private static ResultSet resultSet(Object[] row) {
            return (ResultSet) Proxy.newProxyInstance(ResultSet.class.getClassLoader(), new Class<?>[] {ResultSet.class},
                    (proxy, method, params) -> switch (method.getName()) {
                        case "getObject" -> row[0];
                        case "getString" -> "external_group".equals(params[0]) ? row[1] : row[2];
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
        }
    }

    private static Map<TenantId, Map<String, Set<String>>> read(Object[]... rows) {
        return new JdbcTenantOidcGroupGrantRepository(new RowsJdbc(List.of(rows)))
                .activeGrants("customer-oidc", URI.create("https://issuer"), "lexavault-api");
    }

    @Test
    void validRowsPassUnchanged() {
        var grants = read(new Object[] {A, "a-staff", "DOCUMENT_SUPERVISOR"}, new Object[] {B, "b-staff", "REPORT_VIEWER"});
        assertEquals(Map.of("a-staff", Set.of("DOCUMENT_SUPERVISOR")), grants.get(TenantId.of(A)));
        assertEquals(Map.of("b-staff", Set.of("REPORT_VIEWER")), grants.get(TenantId.of(B)));
    }

    @Test
    void aBadRowInOneEntityNoLongerFailsTheWholeLookup() {
        var grants = read(new Object[] {A, "a-staff", "DOCUMENT_SUPERVISOR"}, new Object[] {B, "b-staff", "DOCUMENT_PROCESOR"});
        assertEquals(Map.of("a-staff", Set.of("DOCUMENT_SUPERVISOR")), grants.get(TenantId.of(A)));
        assertEquals(Map.of("b-staff", Set.of("UNRECOGNISED:DOCUMENT_PROCESOR")), grants.get(TenantId.of(B)));
    }

    @Test
    void aliasesAndLowerCaseAreNeverReadAsValidRoles() {
        var grants = read(new Object[] {A, "g1", "USER"}, new Object[] {A, "g2", "document_processor"});
        assertEquals(Map.of("g1", Set.of("UNRECOGNISED:USER"), "g2", Set.of("UNRECOGNISED:document_processor")),
                grants.get(TenantId.of(A)));
    }

    @Test
    void thePlatformRoleIsPassedOnSoTheLibraryRefusesIt() {
        var grants = read(new Object[] {A, "g", "PLATFORM_OPERATIONS_ADMIN"});
        assertEquals(Map.of("g", Set.of("PLATFORM_OPERATIONS_ADMIN")), grants.get(TenantId.of(A)));
    }
}

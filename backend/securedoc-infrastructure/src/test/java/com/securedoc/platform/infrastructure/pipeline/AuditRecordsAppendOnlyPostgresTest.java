package com.securedoc.platform.infrastructure.pipeline;

import liquibase.Contexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Hardening (task 0110): the audit table is protected by the database. The app's database user, an ordinary role that
 * ran the changelog itself (so it owns the tables), may insert and read audit rows, never change, delete or empty them.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class AuditRecordsAppendOnlyPostgresTest {

    @Test
    void theAppsUserMayAddAndReadAuditRowsButNeverChangeDeleteOrEmptyThem() throws Exception {
        String adminUrl = System.getenv("LEXAVAULT_TEST_PG_URL");
        String adminUser = System.getenv("LEXAVAULT_TEST_PG_USER");
        String adminPassword = System.getenv("LEXAVAULT_TEST_PG_PASSWORD");
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        String role = "lv_t_app_" + suffix;
        String name = "lv_t_audit_" + suffix;
        byte[] random = new byte[16];
        new SecureRandom().nextBytes(random);
        String password = HexFormat.of().formatHex(random);
        try (Connection admin = DriverManager.getConnection(adminUrl, adminUser, adminPassword);
             Statement s = admin.createStatement()) {
            s.execute("CREATE ROLE " + role + " LOGIN NOSUPERUSER PASSWORD '" + password + "'");
            s.execute("CREATE DATABASE " + name + " OWNER " + role);
        }
        String url = adminUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1");
        try {
            try (Connection app = DriverManager.getConnection(url, role, password)) {
                PostgresTestDatabase.liquibase(app).update(new Contexts());
            }
            try (Connection app = DriverManager.getConnection(url, role, password);
                 Statement s = app.createStatement()) {
                String id = UUID.randomUUID().toString();
                s.execute("INSERT INTO audit_records (id, tenant_id, actor_id, action, resource_type, resource_id, outcome,"
                        + " correlation_id, detail, occurred_at, created_at) VALUES ('" + id + "', '" + UUID.randomUUID()
                        + "', '" + UUID.randomUUID() + "', 'TEST', 'Test', 'x', 'SUCCESS', '" + UUID.randomUUID()
                        + "', 'test', now(), now())");
                try (var rows = s.executeQuery("SELECT count(*) FROM audit_records WHERE id = '" + id + "'")) {
                    rows.next();
                    assertEquals(1, rows.getInt(1), "an audit row is added and read");
                }
                for (String change : new String[]{
                        "UPDATE audit_records SET detail = 'changed' WHERE id = '" + id + "'",
                        "DELETE FROM audit_records WHERE id = '" + id + "'",
                        "TRUNCATE audit_records"}) {
                    SQLException refused = assertThrows(SQLException.class, () -> s.execute(change), change);
                    assertEquals("42501", refused.getSQLState(), "refused by the database's privileges: " + change);
                }
                try (var rows = s.executeQuery("SELECT detail FROM audit_records WHERE id = '" + id + "'")) {
                    rows.next();
                    assertEquals("test", rows.getString(1), "the row is unchanged");
                }
            }
        } finally {
            try (Connection admin = DriverManager.getConnection(adminUrl, adminUser, adminPassword);
                 Statement s = admin.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
                s.execute("DROP ROLE IF EXISTS " + role);
            }
        }
    }
}

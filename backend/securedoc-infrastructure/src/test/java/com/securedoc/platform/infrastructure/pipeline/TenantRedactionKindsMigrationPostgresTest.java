package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.identity.TenantId;
import liquibase.Contexts;
import liquibase.Liquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Connection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0043: migration 047 adds tenant_redaction_kinds, one row per entity and opt-in redaction kind. An entity's
 * existing policy row is not touched and every new kind starts off; rolling back drops only the new table. Task 0062
 * replaced the table (070, {@link RedactionCatalogMigrationPostgresTest}); this looks at the storage of 050.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class TenantRedactionKindsMigrationPostgresTest {

    @Test
    void anExistingPolicyIsUntouchedAndEveryNewKindStartsOff() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.empty("m047")) {
            try (Connection connection = db.connection()) {
                Liquibase liquibase = PostgresTestDatabase.liquibase(connection);
                liquibase.update(new Contexts());
                PostgresTestDatabase.rollback(liquibase, connection, 15); // back to 046 (081, 071, 080, 070, 065 twice, 064, 063, 062, 061, 060, 050, 049, 048, then 047): no tenant_redaction_kinds yet
            }
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            assertEquals(0, tableCount(jdbc), "046: no table yet");
            TenantId tenant = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            insertPolicy(jdbc, tenant);
            String before = policyRow(jdbc, tenant);

            try (Connection connection = db.connection()) {
                PostgresTestDatabase.liquibase(connection).update(new Contexts());
            }
            db.backTo050();

            assertEquals(1, tableCount(jdbc));
            assertEquals(before, policyRow(jdbc, tenant), "the V1 columns and values are unchanged");
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tenant_redaction_kinds",
                    new MapSqlParameterSource(), Integer.class), "no data moved");
        }
    }

    @Test
    void rollingBack047DropsOnlyTheNewTable() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m047rb")) {
            db.backTo050();
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            insertPolicy(jdbc, tenant);
            String before = policyRow(jdbc, tenant);
            jdbc.update("INSERT INTO tenant_redaction_kinds (tenant_id, kind_key, enabled, updated_at)"
                    + " VALUES (:t, 'PASSPORT_NUMBER', true, now())", new MapSqlParameterSource("t", tenant.value()));
            try (Connection connection = db.connection()) {
                PostgresTestDatabase.rollback(PostgresTestDatabase.liquibase(connection), connection, 4); // 050, 049, 048, then 047
            }
            assertEquals(0, tableCount(jdbc));
            assertEquals(before, policyRow(jdbc, tenant));
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM databasechangelog WHERE id = '047-01-tenant-redaction-kinds'",
                    new MapSqlParameterSource(), Integer.class) == 0);
        }
    }

    private static void insertPolicy(NamedParameterJdbcTemplate jdbc, TenantId tenant) {
        jdbc.update("""
                INSERT INTO tenant_redaction_policies (tenant_id, auto_redact_threshold, review_mode, ssn_enabled,
                    date_of_birth_enabled, credit_card_number_enabled, credit_score_enabled, mask_ssn_last_four, updated_at)
                VALUES (:t, 0.85, 'UNCERTAIN_ONLY', true, false, true, false, true, now())
                """, new MapSqlParameterSource("t", tenant.value()));
    }

    private static String policyRow(NamedParameterJdbcTemplate jdbc, TenantId tenant) {
        return jdbc.queryForObject("""
                SELECT auto_redact_threshold || '|' || review_mode || '|' || ssn_enabled || '|' || date_of_birth_enabled
                    || '|' || credit_card_number_enabled || '|' || credit_score_enabled || '|' || mask_ssn_last_four
                    || '|' || updated_at
                  FROM tenant_redaction_policies WHERE tenant_id = :t
                """, new MapSqlParameterSource("t", tenant.value()), String.class);
    }

    private static int tableCount(NamedParameterJdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'tenant_redaction_kinds'",
                new MapSqlParameterSource(), Integer.class);
    }
}

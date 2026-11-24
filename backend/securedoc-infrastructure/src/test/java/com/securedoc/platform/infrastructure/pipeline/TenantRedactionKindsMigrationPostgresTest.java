package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.adapter.JdbcTenantRedactionKindStore;
import liquibase.Contexts;
import liquibase.Liquibase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Connection;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0043: migration 047 adds tenant_redaction_kinds, one row per entity and opt-in redaction kind. An entity's
 * existing policy row is not touched and every new kind starts off; the rows switch kinds on and off; unknown or V1
 * keys in the table never switch anything on; rolling back drops only the new table.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class TenantRedactionKindsMigrationPostgresTest {

    @Test
    void anExistingPolicyIsUntouchedAndEveryNewKindStartsOff() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.empty("m047")) {
            try (Connection connection = db.connection()) {
                Liquibase liquibase = PostgresTestDatabase.liquibase(connection);
                liquibase.update(new Contexts());
                PostgresTestDatabase.rollback(liquibase, connection, 7); // back to 046 (062, 061, 060, 050, 049, 048, then 047): no tenant_redaction_kinds yet
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

            assertEquals(1, tableCount(jdbc));
            assertEquals(before, policyRow(jdbc, tenant), "the V1 columns and values are unchanged");
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tenant_redaction_kinds",
                    new MapSqlParameterSource(), Integer.class), "no data moved");
            assertEquals(Set.of(), new JdbcTenantRedactionKindStore(jdbc).enabledKinds(tenant), "every new kind off");
        }
    }

    @Test
    void theStoreSwitchesKindsOnAndOffAndIgnoresKeysItDoesNotOwn() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m047store")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = TenantId.generate();
            TenantId other = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            TestRows.tenant(jdbc, other);
            JdbcTenantRedactionKindStore store = new JdbcTenantRedactionKindStore(jdbc);

            store.save(tenant, EnumSet.of(EntityType.SSN, EntityType.BANK_ROUTING_NUMBER, EntityType.ITIN), Instant.now());
            assertEquals(EnumSet.of(EntityType.BANK_ROUTING_NUMBER, EntityType.ITIN), store.enabledKinds(tenant));
            assertEquals(6, rows(jdbc, tenant), "one row per opt-in kind; the V1 kinds are not written here");
            assertEquals(Set.of(), store.enabledKinds(other), "another entity is not affected");

            store.save(tenant, EnumSet.of(EntityType.EIN), Instant.now());
            assertEquals(EnumSet.of(EntityType.EIN), store.enabledKinds(tenant));
            assertEquals(6, rows(jdbc, tenant), "updated in place");

            // Keys the store does not own never switch anything on (fail closed).
            for (String key : List.of("SSN", "FINANCIAL_ACCOUNT", "NOT_A_KIND")) {
                jdbc.update("INSERT INTO tenant_redaction_kinds (tenant_id, kind_key, enabled, updated_at)"
                        + " VALUES (:t, :k, true, now())", new MapSqlParameterSource("t", tenant.value()).addValue("k", key));
            }
            assertEquals(EnumSet.of(EntityType.EIN), store.enabledKinds(tenant));

            assertThrows(DataIntegrityViolationException.class, () -> store.save(TenantId.generate(),
                    EnumSet.of(EntityType.EIN), Instant.now()), "the entity must exist");
            assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                    "INSERT INTO tenant_redaction_kinds (tenant_id, kind_key, enabled, updated_at)"
                            + " VALUES (:t, 'EIN', true, now())", new MapSqlParameterSource("t", tenant.value())),
                    "one row per entity and kind");
            assertEquals("false", jdbc.queryForObject("SELECT column_default FROM information_schema.columns"
                    + " WHERE table_name = 'tenant_redaction_kinds' AND column_name = 'enabled'",
                    new MapSqlParameterSource(), String.class), "a row written without the flag is off");
        }
    }

    @Test
    void rollingBack047DropsOnlyTheNewTable() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m047rb")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            insertPolicy(jdbc, tenant);
            String before = policyRow(jdbc, tenant);
            new JdbcTenantRedactionKindStore(jdbc).save(tenant, EnumSet.of(EntityType.PASSPORT_NUMBER), Instant.now());
            try (Connection connection = db.connection()) {
                PostgresTestDatabase.rollback(PostgresTestDatabase.liquibase(connection), connection, 7); // 062, 061, 060, 050, 049, 048, then 047
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

    private static int rows(NamedParameterJdbcTemplate jdbc, TenantId tenant) {
        return jdbc.queryForObject("SELECT count(*) FROM tenant_redaction_kinds WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant.value()), Integer.class);
    }

    private static int tableCount(NamedParameterJdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name = 'tenant_redaction_kinds'",
                new MapSqlParameterSource(), Integer.class);
    }
}

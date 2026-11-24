package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.adapter.JdbcTenantRedactionKindStore;
import liquibase.Contexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Connection;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0047, item 1: migration 048 moves "searchable redacted pages" from the IMAGE_ONLY_REDACTED_PAGES row of
 * tenant_redaction_kinds (task 0044; enabled = true meant searchable off) to the column
 * tenant_redaction_policies.searchable_redacted_pages (default true). Every stored value is copied, the rows are removed,
 * a row with no policy to copy it to halts the migration, and the rollback restores the 0044 rows.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class SearchableRedactedPagesSettingPostgresTest {
    private static final String ROW = "IMAGE_ONLY_REDACTED_PAGES";

    @Test
    void upgradingFrom047CopiesEveryValueRemovesTheRowsAndLeavesTheKindsAlone() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.empty("m048up")) {
            migrateThenRollBack(db, 3); // back to 047 (049, then 048): the 0044 storage
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId turnedOff = tenantWithPolicy(jdbc);
            TenantId savedOn = tenantWithPolicy(jdbc);
            TenantId neverSaved = tenantWithPolicy(jdbc);
            switchRow(jdbc, turnedOff, ROW, true);
            switchRow(jdbc, savedOn, ROW, false);
            switchRow(jdbc, turnedOff, "EIN", true);
            String before = policyRow(jdbc, turnedOff);

            update(db);

            assertFalse(searchable(jdbc, turnedOff), "a stored 'off' is copied");
            assertTrue(searchable(jdbc, savedOn), "a stored 'on' is copied");
            assertTrue(searchable(jdbc, neverSaved), "no row: on, the default");
            assertEquals(0, count(jdbc, "SELECT count(*) FROM tenant_redaction_kinds WHERE kind_key = '" + ROW + "'"),
                    "the switch rows are removed");
            assertEquals(1, count(jdbc, "SELECT count(*) FROM tenant_redaction_kinds"), "the kind row stays");
            assertEquals(EnumSet.of(EntityType.EIN), new JdbcTenantRedactionKindStore(jdbc).enabledKinds(turnedOff));
            assertEquals(before, policyRow(jdbc, turnedOff), "the other policy columns are unchanged");
        }
    }

    @Test
    void aSwitchRowWithNoPolicyToCopyItToHaltsTheMigration() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.empty("m048halt")) {
            migrateThenRollBack(db, 3); // 049, then 048
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            switchRow(jdbc, tenant, ROW, true); // "off", but no policy row

            assertThrows(Exception.class, () -> update(db), "fail closed: the 'off' would be lost");
            assertEquals(0, columnCount(jdbc), "the column was not added");
            assertEquals(1, count(jdbc, "SELECT count(*) FROM tenant_redaction_kinds WHERE kind_key = '" + ROW + "'"),
                    "the row is still there");
        }
    }

    @Test
    void rollingBack048RestoresThe0044RowsAndUpgradingAgainKeepsEveryValue() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m048rb")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId off = tenantWithPolicy(jdbc);
            TenantId on = tenantWithPolicy(jdbc);
            jdbc.update("UPDATE tenant_redaction_policies SET searchable_redacted_pages = false WHERE tenant_id = :t",
                    new MapSqlParameterSource("t", off.value()));

            try (Connection connection = db.connection()) {
                PostgresTestDatabase.rollback(PostgresTestDatabase.liquibase(connection), connection, 3); // 050, 049, then 048
            }
            assertEquals(0, columnCount(jdbc), "the column is dropped");
            assertEquals(1, count(jdbc, "SELECT count(*) FROM tenant_redaction_kinds WHERE kind_key = '" + ROW
                    + "' AND enabled AND tenant_id = '" + off.value() + "'"), "'off' is a row again");
            assertEquals(0, count(jdbc, "SELECT count(*) FROM tenant_redaction_kinds WHERE tenant_id = '"
                    + on.value() + "'"), "'on' needs no row");

            update(db);
            assertFalse(searchable(jdbc, off));
            assertTrue(searchable(jdbc, on));
        }
    }

    @Test
    void aNewPolicyRowIsSearchableAndALeftoverSwitchRowIsNeverAKind() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m048def")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = tenantWithPolicy(jdbc); // written without the column
            assertTrue(searchable(jdbc, tenant));
            switchRow(jdbc, tenant, ROW, true);
            assertEquals(Set.of(), new JdbcTenantRedactionKindStore(jdbc).enabledKinds(tenant),
                    "an unknown key never switches anything on");
            assertTrue(searchable(jdbc, tenant), "the old row no longer means anything");
        }
    }

    private static void migrateThenRollBack(PostgresTestDatabase db, int changeSets) throws Exception {
        try (Connection connection = db.connection()) {
            var liquibase = PostgresTestDatabase.liquibase(connection);
            liquibase.update(new Contexts());
            PostgresTestDatabase.rollback(liquibase, connection, changeSets);
        }
    }

    private static void update(PostgresTestDatabase db) throws Exception {
        try (Connection connection = db.connection()) {
            PostgresTestDatabase.liquibase(connection).update(new Contexts());
        }
    }

    private static TenantId tenantWithPolicy(NamedParameterJdbcTemplate jdbc) {
        TenantId tenant = TenantId.generate();
        TestRows.tenant(jdbc, tenant);
        jdbc.update("""
                INSERT INTO tenant_redaction_policies (tenant_id, auto_redact_threshold, review_mode, ssn_enabled,
                    date_of_birth_enabled, credit_card_number_enabled, credit_score_enabled, mask_ssn_last_four, updated_at)
                VALUES (:t, 0.85, 'UNCERTAIN_ONLY', true, false, true, false, true, now())
                """, new MapSqlParameterSource("t", tenant.value()));
        return tenant;
    }

    private static void switchRow(NamedParameterJdbcTemplate jdbc, TenantId tenant, String key, boolean enabled) {
        jdbc.update("INSERT INTO tenant_redaction_kinds (tenant_id, kind_key, enabled, updated_at) VALUES (:t, :k, :e, now())",
                new MapSqlParameterSource("t", tenant.value()).addValue("k", key).addValue("e", enabled));
    }

    private static boolean searchable(NamedParameterJdbcTemplate jdbc, TenantId tenant) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT searchable_redacted_pages FROM tenant_redaction_policies WHERE tenant_id = :t",
                new MapSqlParameterSource("t", tenant.value()), Boolean.class));
    }

    private static String policyRow(NamedParameterJdbcTemplate jdbc, TenantId tenant) {
        return jdbc.queryForObject("""
                SELECT auto_redact_threshold || '|' || review_mode || '|' || ssn_enabled || '|' || date_of_birth_enabled
                    || '|' || credit_card_number_enabled || '|' || credit_score_enabled || '|' || mask_ssn_last_four
                    || '|' || updated_at
                  FROM tenant_redaction_policies WHERE tenant_id = :t
                """, new MapSqlParameterSource("t", tenant.value()), String.class);
    }

    private static int columnCount(NamedParameterJdbcTemplate jdbc) {
        return count(jdbc, "SELECT count(*) FROM information_schema.columns WHERE table_name = 'tenant_redaction_policies'"
                + " AND column_name = 'searchable_redacted_pages'");
    }

    private static int count(NamedParameterJdbcTemplate jdbc, String sql) {
        return jdbc.queryForObject(sql, new MapSqlParameterSource(), Integer.class);
    }
}

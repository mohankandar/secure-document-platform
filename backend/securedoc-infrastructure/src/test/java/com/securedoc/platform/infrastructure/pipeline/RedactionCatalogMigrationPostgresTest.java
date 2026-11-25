package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.business.RedactionCatalog;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.adapter.JdbcRedactionCatalogStore;
import liquibase.Contexts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.Connection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0062: migration 070 moves every entity's switches into the catalog and changes nothing else. Every combination
 * of the four columns, with and without opt-in kinds, reads back as exactly the kinds that were on; an entity without a
 * policy row keeps the built-in defaults of before; no entity gets a locked item, a pack or a style; rolling back
 * restores the old storage.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class RedactionCatalogMigrationPostgresTest {

    private static final List<EntityType> FOUR = List.of(EntityType.SSN, EntityType.DATE_OF_BIRTH,
            EntityType.CREDIT_CARD_NUMBER, EntityType.CREDIT_SCORE);

    @Test
    void everyExistingEntityKeepsExactlyItsKindsAndTheWayBackRestoresThem() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m070")) {
            db.backTo050();
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId[] tenants = new TenantId[16];
            for (int mask = 0; mask < 16; mask++) {
                tenants[mask] = TenantId.generate();
                TestRows.tenant(jdbc, tenants[mask]);
                jdbc.update("""
                        INSERT INTO tenant_redaction_policies (tenant_id, auto_redact_threshold, review_mode, ssn_enabled,
                            date_of_birth_enabled, credit_card_number_enabled, credit_score_enabled, mask_ssn_last_four,
                            searchable_redacted_pages, page_batch_size, page_batch_overlap, updated_at)
                        VALUES (:t, 0.83, 'REVIEW_ALL', :a, :b, :c, :d, false, false, 30, 3, TIMESTAMPTZ '2026-11-01 10:00:00Z')
                        """, new MapSqlParameterSource("t", tenants[mask].value()).addValue("a", (mask & 1) != 0)
                        .addValue("b", (mask & 2) != 0).addValue("c", (mask & 4) != 0).addValue("d", (mask & 8) != 0));
                for (String kind : List.of("EIN", "ITIN")) {
                    jdbc.update("INSERT INTO tenant_redaction_kinds (tenant_id, kind_key, enabled, updated_at)"
                            + " VALUES (:t, :k, :e, now())", new MapSqlParameterSource("t", tenants[mask].value())
                            .addValue("k", kind).addValue("e", kind.equals("EIN") && mask % 3 == 0));
                }
                jdbc.update("INSERT INTO tenant_redaction_kinds (tenant_id, kind_key, enabled, updated_at)"
                        + " VALUES (:t, 'NOT_A_KIND', true, now())", new MapSqlParameterSource("t", tenants[mask].value()));
            }
            TenantId noPolicy = TenantId.generate();
            TestRows.tenant(jdbc, noPolicy);
            String oldStorage = oldStorage(jdbc);

            update(db);

            JdbcRedactionCatalogStore store = new JdbcRedactionCatalogStore(jdbc);
            for (int mask = 0; mask < 16; mask++) {
                EnumSet<EntityType> expected = EnumSet.noneOf(EntityType.class);
                for (int n = 0; n < 4; n++) if ((mask & (1 << n)) != 0) expected.add(FOUR.get(n));
                if (mask % 3 == 0) expected.add(EntityType.EIN);
                JdbcRedactionCatalogStore.Stored stored = store.load(tenants[mask]);
                assertEquals(expected, stored.enabled(), "entity " + mask);
                assertEquals(Set.of(), stored.keepLastFour());
                assertEquals(Set.of(), stored.packs());
                assertEquals("0.83|REVIEW_ALL|false|false|30|3|false", jdbc.queryForObject("""
                        SELECT auto_redact_threshold || '|' || review_mode || '|' || mask_ssn_last_four || '|'
                            || searchable_redacted_pages || '|' || page_batch_size || '|' || page_batch_overlap
                            || '|' || locked_items_on FROM tenant_redaction_policies WHERE tenant_id = :t
                        """, new MapSqlParameterSource("t", tenants[mask].value()), String.class));
            }
            assertEquals(java.util.Set.of(EntityType.SSN, EntityType.DATE_OF_BIRTH,
                    EntityType.CREDIT_CARD_NUMBER, EntityType.CREDIT_SCORE), store.load(noPolicy).enabled(),
                    "no policy row: the built-in defaults of before");
            assertEquals("0.8|UNCERTAIN_ONLY|true|true|20|2|false", jdbc.queryForObject("""
                    SELECT auto_redact_threshold || '|' || review_mode || '|' || mask_ssn_last_four || '|'
                        || searchable_redacted_pages || '|' || page_batch_size || '|' || page_batch_overlap
                        || '|' || locked_items_on FROM tenant_redaction_policies WHERE tenant_id = :t
                    """, new MapSqlParameterSource("t", noPolicy.value()), String.class));

            TenantId newEntity = TenantId.generate();
            TestRows.tenant(jdbc, newEntity);
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM tenant_redaction_policies WHERE tenant_id = :t",
                    new MapSqlParameterSource("t", newEntity.value()), Integer.class), "a new entity uses the new defaults");

            db.backTo050();
            jdbc.update("DELETE FROM tenant_redaction_policies WHERE tenant_id = :t",
                    new MapSqlParameterSource("t", noPolicy.value()));
            assertEquals(oldStorage, oldStorage(jdbc), "rolled back: the old storage, every switch");
            assertTrue(oldStorage.contains("EIN"));
        }
    }

    private static String oldStorage(NamedParameterJdbcTemplate jdbc) {
        return String.valueOf(jdbc.queryForList("""
                SELECT tenant_id, ssn_enabled, date_of_birth_enabled, credit_card_number_enabled, credit_score_enabled
                  FROM tenant_redaction_policies ORDER BY tenant_id""", new MapSqlParameterSource()))
                + jdbc.queryForList("SELECT tenant_id, kind_key, enabled FROM tenant_redaction_kinds"
                        + " WHERE kind_key <> 'NOT_A_KIND' ORDER BY 1, 2", new MapSqlParameterSource());
    }

    private static void update(PostgresTestDatabase db) throws Exception {
        try (Connection connection = db.connection()) {
            PostgresTestDatabase.liquibase(connection).update(new Contexts());
        }
    }
}

package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.adapter.JdbcTenantRedactionKindStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0044: "searchable redacted pages" is stored as the IMAGE_ONLY_REDACTED_PAGES row of tenant_redaction_kinds (no
 * migration). No row means searchable (the default); the row never turns a redaction kind on.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class SearchableRedactedPagesSettingPostgresTest {

    @Test
    void onWithoutARowOffWhenSavedOffAndNeverAKind() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m044")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = TenantId.generate();
            TenantId other = TenantId.generate();
            TestRows.tenant(jdbc, tenant);
            TestRows.tenant(jdbc, other);
            JdbcTenantRedactionKindStore store = new JdbcTenantRedactionKindStore(jdbc);

            assertTrue(store.searchableRedactedPages(tenant), "no row: searchable, the default");
            store.saveSearchableRedactedPages(tenant, false, Instant.now());
            assertFalse(store.searchableRedactedPages(tenant));
            assertTrue(store.searchableRedactedPages(other), "another entity is not affected");
            assertEquals(Set.of(), store.enabledKinds(tenant), "the switch is not a redaction kind");

            store.save(tenant, EnumSet.of(EntityType.EIN), Instant.now());
            assertFalse(store.searchableRedactedPages(tenant), "saving kinds leaves the switch alone");
            store.saveSearchableRedactedPages(tenant, true, Instant.now());
            assertTrue(store.searchableRedactedPages(tenant));
            assertEquals(EnumSet.of(EntityType.EIN), store.enabledKinds(tenant));
            assertEquals(7, jdbc.queryForObject("SELECT count(*) FROM tenant_redaction_kinds WHERE tenant_id = :t",
                    new MapSqlParameterSource("t", tenant.value()), Integer.class), "six kinds and the switch");
        }
    }
}

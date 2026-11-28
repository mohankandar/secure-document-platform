package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.casepage.CasePageSettings;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.adapter.JdbcCasePageSettingsStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Task 0108: migration 092, one Cases-page settings row per entity, saved again in place; the table refuses bad ranges. */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class CasePageSettingsStorePostgresTest {

    @Test
    void savesReadsAndReplacesPerEntity() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m092")) {
            TenantId tenant = TenantId.generate();
            TenantId other = TenantId.generate();
            TestRows.tenant(db.jdbc(), tenant);
            TestRows.tenant(db.jdbc(), other);
            JdbcCasePageSettingsStore store = new JdbcCasePageSettingsStore(db.jdbc());

            assertEquals(Optional.empty(), store.find(tenant));
            assertEquals(OptionalInt.empty(), store.shortestExpiryDays());
            store.save(tenant, ActorId.generate(), new CasePageSettings(240, 480, 7));
            store.save(tenant, ActorId.generate(), new CasePageSettings(60, 120, 14));
            store.save(other, ActorId.generate(), new CasePageSettings(30, 45, 3));

            assertEquals(Optional.of(new CasePageSettings(60, 120, 14)), store.find(tenant));
            assertEquals(OptionalInt.of(3), store.shortestExpiryDays());
            assertEquals(2, db.jdbc().queryForObject("select count(*) from case_page_settings",
                    new MapSqlParameterSource(), Integer.class));

            // The table itself refuses red before amber, whatever writes it.
            assertThrows(DataIntegrityViolationException.class, () -> db.jdbc().update(
                    "update case_page_settings set wait_red_minutes = 10 where tenant_id = :t",
                    new MapSqlParameterSource("t", tenant.value())));
        }
    }
}

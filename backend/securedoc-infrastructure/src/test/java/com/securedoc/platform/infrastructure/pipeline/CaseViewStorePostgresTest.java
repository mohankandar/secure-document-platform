package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.caseview.CaseViewSettings;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.adapter.JdbcCaseViewStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Task 0108: migration 089 and the per-person View settings, kept per entity, one row per person, saved again in place. */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class CaseViewStorePostgresTest {

    @Test
    void savesReadsAndReplacesPerPersonAndEntity() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m089")) {
            TenantId tenant = TenantId.generate();
            TenantId other = TenantId.generate();
            TestRows.tenant(db.jdbc(), tenant);
            TestRows.tenant(db.jdbc(), other);
            ActorId person = ActorId.generate();
            JdbcCaseViewStore store = new JdbcCaseViewStore(db.jdbc());

            assertEquals(Optional.empty(), store.find(tenant, person));
            store.save(tenant, person, new CaseViewSettings("priority", "desc", null, "compact"));
            store.save(tenant, person, new CaseViewSettings("field:contract_number", "asc",
                    List.of("branch", "source"), "comfortable"));
            store.save(other, person, new CaseViewSettings("received", "asc", List.of(), "compact"));

            assertEquals(Optional.of(new CaseViewSettings("field:contract_number", "asc", List.of("branch", "source"),
                    "comfortable")), store.find(tenant, person));
            assertEquals(Optional.of(new CaseViewSettings("received", "asc", List.of(), "compact")),
                    store.find(other, person));
            assertEquals(Optional.empty(), store.find(tenant, ActorId.generate()));
            assertEquals(2, db.jdbc().queryForObject("select count(*) from user_case_view",
                    new MapSqlParameterSource(), Integer.class));

            // The table itself refuses an order or density the app never writes.
            assertThrows(DataIntegrityViolationException.class,
                    () -> store.save(tenant, person, new CaseViewSettings("received", "up", null, "compact")));
        }
    }
}

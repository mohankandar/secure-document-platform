package com.securedoc.install;

import com.documentplatform.identity.store.LicensedSlots;
import com.securedoc.platform.api.identity.EntitySlotService;
import com.securedoc.platform.api.identity.EntitySlotService.Answer;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.identity.EntitySlotStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0075 on PostgreSQL: the install command's activation of the first entity. Throwaway server only. */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
class FirstEntityPostgresTest {

    private final List<AuditRecord> audits = new ArrayList<>();
    private final Map<TenantId, TenantRedactionPolicy> policies = new HashMap<>();
    private boolean licence = true;

    @Test
    void theFirstEntityOnlyAndAgainChangesNothing() throws Exception {
        try (ThrowawayDatabase db = ThrowawayDatabase.create("firstentity")) {
            PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
            assertEquals(0, new SchemaStep(quiet, quiet).run(new String[] {"apply"}, db.env(SchemaStep.ENV_PREFIX)));
            IdnRows rows = new IdnRows(db);
            EntitySlotStore store = new EntitySlotStore(new JdbcTemplate(rows.ds));
            TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(rows.ds));
            EntitySlotService service = new EntitySlotService(store, () -> licence ? Optional.of(new LicensedSlots.Current(
                    "TEN-FIRST", Set.of("ENT-FIRST-A", "ENT-FIRST-B"), LicensedSlots.Standing.IN_FORCE)) : Optional.empty(),
                    repository(), new AuditService(audits::add), Clock.systemUTC());

            assertEquals("NOT_ONE_CUSTOMER", activate(tx, service, "ENT-FIRST-A", "Northwind").code());
            rows.customer("TEN-FIRST");
            TenantId a = TenantId.of(rows.slot("TEN-FIRST", "ENT-FIRST-A", "NOT_ACTIVATED"));
            rows.slot("TEN-FIRST", "ENT-FIRST-B", "NOT_ACTIVATED");
            assertEquals("NO_SUCH_SLOT", activate(tx, service, "ENT-NOWHERE", "Northwind").code());
            licence = false;
            assertEquals("NO_LICENCE", activate(tx, service, "ENT-FIRST-A", "Northwind").code());
            licence = true;
            assertFalse(store.hasEntityRow(a.value()));
            assertTrue(audits.isEmpty(), "no entity has a log yet");

            Answer done = activate(tx, service, "ENT-FIRST-A", "Northwind");
            assertTrue(done.done(), done.toString());
            assertTrue(store.active(a));
            assertTrue(policies.containsKey(a));
            assertEquals(1, audits.size());
            assertEquals(AuditAction.ENTITY_ACTIVATED, audits.get(0).action());
            assertEquals(a, audits.get(0).tenantId());
            assertEquals("code=ACTIVATED, source=INSTALL", audits.get(0).detail());

            assertEquals("UNCHANGED", activate(tx, service, "ENT-FIRST-A", "Northwind").code());
            assertEquals("NOT_THE_FIRST", activate(tx, service, "ENT-FIRST-A", "Another name").code());
            assertEquals("NOT_THE_FIRST", activate(tx, service, "ENT-FIRST-B", "Second").code());
            rows.exec("update idn_entity set status = 'SUSPENDED' where slot_id = ?", "ENT-FIRST-A");
            assertEquals("NOT_THE_FIRST", activate(tx, service, "ENT-FIRST-A", "Northwind").code(), "never resumes one");
            assertEquals("NOT_THE_FIRST", activate(tx, service, "ENT-FIRST-B", "Second").code());
            assertEquals(1, audits.size());
        }
    }

    private static Answer activate(TransactionTemplate tx, EntitySlotService service, String slot, String name) {
        return tx.execute(status -> service.activateFirst(slot, name, CorrelationId.generate()));
    }

    private TenantRedactionPolicyRepository repository() {
        return new TenantRedactionPolicyRepository() {
            @Override
            public Optional<TenantRedactionPolicy> findByTenantId(TenantId tenantId) {
                return Optional.ofNullable(policies.get(tenantId));
            }

            @Override
            public void save(TenantRedactionPolicy policy) {
                policies.put(policy.tenantId(), policy);
            }
        };
    }
}

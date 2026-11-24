package com.securedoc.install;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.identity.EnteredEntityController;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.identity.LastEntityStore;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 0058 step 2 on PostgreSQL: the entity a person worked in last, and the record of every switch. Throwaway server only. */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
class LastEntityPostgresTest {

    private static ThrowawayDatabase db;
    private static IdnRows rows;
    private static TenantId a, b;

    @BeforeAll
    static void create() throws SQLException {
        db = ThrowawayDatabase.create("lastentity");
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(0, new SchemaStep(quiet, quiet).run(new String[] {"apply"}, db.env(SchemaStep.ENV_PREFIX)));
        rows = new IdnRows(db);
        a = TenantId.of(UUID.randomUUID());
        b = TenantId.of(UUID.randomUUID());
        rows.lexaTenant(a.value(), "ACTIVE");
        rows.lexaTenant(b.value(), "ACTIVE");
    }

    @AfterAll
    static void drop() throws SQLException {
        if (db != null) db.close();
    }

    @Test
    void everySwitchIsRecordedInBothEntitiesLogsAndTheSameEntityAgainIsNone() {
        LastEntityStore store = new LastEntityStore(new JdbcTemplate(rows.ds));
        ActorId person = ActorId.of(UUID.randomUUID());
        ActorId other = ActorId.of(UUID.randomUUID());
        List<AuditRecord> audits = new ArrayList<>();
        assertTrue(store.find(person).isEmpty());

        enter(store, audits, person, a);
        assertEquals(Optional.of(a), store.find(person));
        assertEquals(List.of(a + " direction=ENTERED, first=true"), lines(audits));
        enter(store, audits, person, a);
        assertEquals(1, audits.size(), "the same entity again is no switch");
        enter(store, audits, person, b);
        assertEquals(Optional.of(b), store.find(person));
        assertEquals(List.of(b + " direction=ENTERED", a + " direction=LEFT"), lines(audits).subList(1, 3));
        // Neither log names the other entity.
        assertTrue(audits.stream().allMatch(r -> r.action() == AuditAction.ENTITY_SWITCHED && r.resourceId().equals(r.tenantId().toString())));
        assertTrue(store.find(other).isEmpty(), "each person has their own");
    }

    private static List<String> lines(List<AuditRecord> audits) {
        return audits.stream().map(r -> r.tenantId() + " " + r.detail()).toList();
    }

    private static void enter(LastEntityStore store, List<AuditRecord> audits, ActorId person, TenantId entity) {
        RequestContextResolver resolver = mock(RequestContextResolver.class);
        when(resolver.resolvePrincipal(any())).thenReturn(AuthenticatedPrincipal.of(person, entity, "someone", Set.of("DOCUMENT_PROCESSOR")));
        when(resolver.resolveCorrelationId(any())).thenReturn(CorrelationId.generate());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/me/entity");
        assertEquals(204, new EnteredEntityController(resolver, store, new AuditService(audits::add)).entered(request)
                .getStatusCode().value());
    }
}

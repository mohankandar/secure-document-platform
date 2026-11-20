package com.securedoc.platform.api.compliance;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.controller.AuditRecordCountController;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.api.security.ProductRole;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.audit.RequestActorDisplayName;
import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.application.compliance.ComplianceReportService;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditOutcome;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.tenant.Tenant;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 0019 A1: {@code GET /api/v1/audit-records/count} counts exactly the records the full audit trail appendix
 * prints, for the signed-in entity, with the compliance report's period rules, and needs COMPLIANCE_REPORT_FULL.
 */
class AuditRecordCountApiTest {

    private static final Clock NOV_19 = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);
    private static final String SYSTEM = RequestActorDisplayName.SYSTEM_ACTOR_ID.toString();

    private final Tenant entityA = Tenant.create("Residential lending", "residential-lending");
    private final Tenant entityB = Tenant.create("Commercial lending", "commercial-lending");
    private final List<AuditRecord> source = new CopyOnWriteArrayList<>();

    private ComplianceReportService service(Tenant readsFor) {
        return InMemoryComplianceReads.service(source, new ArrayList<>(), List.of(entityA, entityB), readsFor.id(),
                new RequestActorDisplayName(), NOV_19);
    }

    private AuditRecordCountController controller(Tenant tenant) {
        AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(ActorId.generate(), tenant.id(), "it@example.test",
                Set.of(ProductRole.TENANT_IT_ADMIN.name()));
        RequestContextResolver resolver = mock(RequestContextResolver.class);
        when(resolver.resolvePrincipal(any())).thenReturn(principal);
        when(resolver.resolveCorrelationId(any())).thenReturn(CorrelationId.generate());
        return new AuditRecordCountController(service(tenant), resolver);
    }

    private void add(Tenant tenant, String at, AuditOutcome outcome) {
        source.add(new AuditRecord(UUID.randomUUID(), tenant.id(), ActorId.of(UUID.fromString(SYSTEM)),
                AuditAction.PACKAGE_CREATED, "DocumentPackage", UUID.randomUUID().toString(), outcome,
                CorrelationId.generate(), "d", Instant.parse(at), null, null));
    }

    private long count(Tenant tenant, String period, String from, String to, String zone) {
        Map<String, Long> body = controller(tenant).count(period, from, to, zone, mock(HttpServletRequest.class)).getBody();
        return body.get("count");
    }

    @Test
    void countsEveryRecordOfTheEntityInThePeriodAsTheAppendixPrintsThem() {
        // Last quarter on Nov 19 in New York: Jul 1 to Sep 30, local time.
        add(entityA, "2026-07-01T04:00:00Z", AuditOutcome.SUCCESS);   // 00:00 New York, first instant: in
        add(entityA, "2026-08-12T15:00:00Z", AuditOutcome.FAILURE);   // any outcome
        add(entityA, "2026-08-12T15:00:00Z", AuditOutcome.DENIED);
        add(entityA, "2026-10-01T03:59:59Z", AuditOutcome.SUCCESS);   // 23:59:59 Sep 30 New York: in
        add(entityA, "2026-10-01T04:00:00Z", AuditOutcome.SUCCESS);   // Oct 1 New York: out
        add(entityA, "2026-07-01T03:59:59Z", AuditOutcome.SUCCESS);   // Jun 30 New York: out
        add(entityB, "2026-08-12T15:00:00Z", AuditOutcome.SUCCESS);   // another entity: never

        assertEquals(4, count(entityA, "LAST_QUARTER", null, null, "America/New_York"));
        assertEquals(1, count(entityB, "LAST_QUARTER", null, null, "America/New_York"));

        // The same records the appendix reads.
        InMemoryComplianceReads reads = new InMemoryComplianceReads(source, entityA.id());
        List<AuditRecordSummary> appendix = new ArrayList<>();
        ZoneId ny = ZoneId.of("America/New_York");
        reads.forEachRecord(entityA.id(), java.time.LocalDate.of(2026, 7, 1).atStartOfDay(ny).toInstant(),
                java.time.LocalDate.of(2026, 10, 1).atStartOfDay(ny).toInstant(), appendix::add);
        assertEquals(appendix.size(), count(entityA, "LAST_QUARTER", null, null, "America/New_York"));

        assertEquals(2, count(entityA, "CUSTOM", "2026-08-12", "2026-08-12", "UTC"));
        assertEquals(2, count(entityA, "LAST_MONTH", null, null, "UTC"), "October in UTC holds the two Oct 1 UTC records");
        assertEquals(1, count(entityA, "LAST_MONTH", null, null, "America/New_York"), "October in New York holds one");
    }

    @Test
    void thePeriodIsCheckedAsForTheReport() {
        assertThrows(ValidationException.class, () -> count(entityA, null, null, null, "UTC"));
        assertThrows(ValidationException.class, () -> count(entityA, "LAST_WEEK", null, null, "UTC"));
        assertThrows(ValidationException.class, () -> count(entityA, "CUSTOM", "2026-08-01", null, "UTC"));
        assertThrows(ValidationException.class, () -> count(entityA, "CUSTOM", "2026-08-01", "2026-11-19", "UTC"), "must end before today");
        assertThrows(ValidationException.class, () -> count(entityA, "CUSTOM", "01/08/2026", "2026-08-02", "UTC"));
        assertThrows(ValidationException.class, () -> count(entityA, "LAST_MONTH", "2026-08-01", "2026-08-02", "UTC"));
        assertThrows(ValidationException.class, () -> count(entityA, "LAST_MONTH", null, null, "+02:00"));
    }

    @Test
    void needsTheFullAuditTrailPermissionWhichOnlyTheItAdministratorHas() throws Exception {
        RequireCapability required = AuditRecordCountController.class
                .getMethod("count", String.class, String.class, String.class, String.class, HttpServletRequest.class)
                .getAnnotation(RequireCapability.class);
        assertArrayEquals(new UserCapability[]{UserCapability.COMPLIANCE_REPORT_FULL}, required.value());
        ProductAuthorizationPolicy policy = new ProductAuthorizationPolicy();
        for (ProductRole role : ProductRole.values()) {
            AuthenticatedPrincipal p = AuthenticatedPrincipal.of(ActorId.generate(), entityA.id(), "x@example.test",
                    Set.of(role.name()));
            assertEquals(role == ProductRole.TENANT_IT_ADMIN, policy.has(p, UserCapability.COMPLIANCE_REPORT_FULL), role.name());
        }
    }
}

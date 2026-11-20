package com.securedoc.platform.application.compliance;

import com.securedoc.platform.application.audit.RequestActorDisplayName;
import com.securedoc.platform.application.compliance.ComplianceReportContent.CountRow;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.in.GetOperationalReportsQuery;
import com.securedoc.platform.application.port.out.ComplianceReportPdfPort;
import com.securedoc.platform.application.port.out.ComplianceReportReadPort;
import com.securedoc.platform.application.port.out.TenantLifecyclePolicyRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditOutcome;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;
import com.securedoc.platform.domain.tenant.Tenant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.securedoc.platform.application.compliance.ComplianceFakes.record;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0017 B1–B4 and C1: where every number comes from, the fingerprint, and what is audited. */
class ComplianceReportServiceTest {

    private static final String SYSTEM = AuditSentences.SYSTEM_ACTOR_ID;
    private static final Instant IN_PERIOD = Instant.parse("2026-08-12T10:42:00Z");
    private static final String BALANCED_TO_LIGHT = "Sensitive-data policy changed: enabledCount=2, threshold=0.70, "
            + "reviewMode=UNCERTAIN_ONLY, maskSsnLastFour=true, enabledTypes=CREDIT_SCORE,SSN, preset=LIGHT, "
            + "previous=[enabledCount=2, threshold=0.80, reviewMode=UNCERTAIN_ONLY, maskSsnLastFour=true, "
            + "enabledTypes=CREDIT_SCORE,SSN, preset=BALANCED]";

    private final Tenant tenant = Tenant.create("Residential lending", "residential-lending");
    private final AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(ActorId.generate(), tenant.id(),
            "pat@example.test", Set.of("BUSINESS_ADMIN"));
    private final ComplianceFakes.ReadPort reads = new ComplianceFakes.ReadPort();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final RequestActorDisplayName names = new RequestActorDisplayName();
    private final AtomicReference<ComplianceReportContent> rendered = new AtomicReference<>();
    private final AtomicReference<long[]> reportNumbers = new AtomicReference<>(new long[]{0, 0, 0, 0});
    private TenantRedactionPolicy currentPolicy;
    private TenantLifecyclePolicy currentLifecycle;

    /** The PDF stand-in: the canonical text, so the bytes change exactly when the content does. */
    private final ComplianceReportPdfPort pdf = (content, fingerprint) -> {
        rendered.set(content);
        return (fingerprint + "\n" + content.canonicalText()).getBytes(StandardCharsets.UTF_8);
    };

    private final GetOperationalReportsQuery reportsQuery = (p, from, to, source, outcome) -> {
        long[] n = reportNumbers.get();
        return ComplianceFakes.reports(from, to, n[0], n[1], n[2], n[3]);
    };

    private ComplianceReportService service(Clock clock) {
        TenantRepository tenants = new TenantRepository() {
            public Optional<Tenant> findById(TenantId id) { return id.equals(tenant.id()) ? Optional.of(tenant) : Optional.empty(); }
            public Optional<Tenant> findBySlug(String slug) { return Optional.empty(); }
            public List<Tenant> findAll() { return List.of(tenant); }
            public void save(Tenant t) { }
        };
        TenantRedactionPolicyRepository policies = new TenantRedactionPolicyRepository() {
            public Optional<TenantRedactionPolicy> findByTenantId(TenantId id) { return Optional.ofNullable(currentPolicy); }
            public void save(TenantRedactionPolicy p) { }
        };
        TenantLifecyclePolicyRepository lifecycle = new TenantLifecyclePolicyRepository() {
            public Optional<TenantLifecyclePolicy> findByTenantId(TenantId id) { return Optional.ofNullable(currentLifecycle); }
            public void save(TenantLifecyclePolicy p) { }
        };
        return new ComplianceReportService(reads, pdf, reportsQuery, tenants, policies, lifecycle,
                new AuditService(audits::add, names), names, clock);
    }

    private static Clock nov19() {
        return Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);
    }

    private ComplianceReportService.CreatedReport create(ComplianceEdition edition) {
        return service(nov19()).create(principal, CorrelationId.generate(),
                new ComplianceReportService.Request("LAST_QUARTER", null, null, "America/New_York"), edition,
                edition == ComplianceEdition.FULL ? "IT Administrator" : "Business Administrator");
    }

    @AfterEach
    void clear() {
        names.clear();
    }

    @Test
    void anEntityWithNoActivityGivesAValidReportWithZerosAndUnchangedWording() {
        ComplianceReportService.CreatedReport report = create(ComplianceEdition.SUMMARY);
        ComplianceReportContent c = rendered.get();

        assertEquals("Jul 1 – Sep 30, 2026", c.periodWords());
        assertEquals("compliance-report-residential-lending-2026-07-01-2026-09-30.pdf", report.fileName());
        assertTrue(c.policy().unchangedSinceBefore());
        assertTrue(c.policy().changes().isEmpty());
        assertEquals("Social Security Number, Date of birth, Credit card number, Credit score",
                c.policy().atEnd().get(0).value(), "no record and no saved policy: the defaults");
        assertTrue(c.retention().unchangedSinceBefore());
        assertEquals(0, c.retention().due());
        assertTrue(c.retention().note().endsWith("No case was still within its keep time on Sep 30."));
        assertEquals("Delivered", c.cases().outcomes().get(1).label());
        c.cases().outcomes().forEach(r -> assertEquals(0, r.count()));
        assertEquals("—", c.cases().outcomes().get(0).share(), "nothing to divide by");
        assertEquals(9, c.activity().size());
        c.activity().forEach(r -> assertEquals(0, r.count(), r.label()));
        assertTrue(c.appendix().isEmpty());
        assertEquals("pat@example.test, Business Administrator", c.createdBy(), "no display name bound: the username");
        assertEquals("Nov 19, 2026, 7:00 AM EST", c.createdAt(), "in the requested time zone");
    }

    @Test
    void theAuditRecordHoldsBothHashesAndThePeriod() {
        ComplianceReportService.CreatedReport report = create(ComplianceEdition.SUMMARY);
        AuditRecord saved = audits.stream().filter(r -> r.action() == AuditAction.COMPLIANCE_REPORT_CREATED)
                .findFirst().orElseThrow();
        assertEquals(AuditOutcome.SUCCESS, saved.outcome());
        assertEquals("period=2026-07-01..2026-09-30, edition=SUMMARY, fingerprint=" + report.fingerprint()
                + ", fileSha256=" + report.fileSha256(), saved.detail());
        assertEquals(ComplianceReportService.sha256(report.pdf()), report.fileSha256());
        assertEquals(rendered.get().fingerprint(), report.fingerprint());
        assertTrue(report.fingerprint().matches("[0-9a-f]{64}"));
        assertNotEquals(report.fingerprint(), report.fileSha256());
        assertEquals(tenant.id().toString(), saved.resourceId());
    }

    @Test
    void theFingerprintIsStableForTheSameContentAndChangesWithOneNumber() {
        String first = create(ComplianceEdition.SUMMARY).fingerprint();
        String again = create(ComplianceEdition.SUMMARY).fingerprint();
        assertEquals(first, again);

        reads.retention = new ComplianceReportReadPort.RetentionCounts(1, 1, 0);
        String oneMoreDeletion = create(ComplianceEdition.SUMMARY).fingerprint();
        assertNotEquals(first, oneMoreDeletion);

        reads.retention = new ComplianceReportReadPort.RetentionCounts(0, 0, 0);
        names.bind(principal.actorId(), "Pat Example", "pat@example.test");
        assertNotEquals(first, create(ComplianceEdition.SUMMARY).fingerprint(), "the creator is part of it");
        assertNotEquals(first, create(ComplianceEdition.FULL).fingerprint(), "the edition is part of it");
    }

    @Test
    void casesSectionUsesTheReportsNumbersForTheSamePeriod() {
        reportNumbers.set(new long[]{20, 16, 6, 10});
        reads.cases = new ComplianceReportReadPort.CaseCounts(1, 3, 2);
        create(ComplianceEdition.SUMMARY);
        List<CountRow> outcomes = rendered.get().cases().outcomes();
        assertEquals(List.of(new CountRow("Received", 20, "100%"), new CountRow("Delivered", 16, "80%"),
                new CountRow("Rejected by a supervisor", 1, "5%"), new CountRow("Still open on Sep 30", 3, "15%")), outcomes);
        assertEquals(List.of(new CountRow("No review needed", 10, "63%"), new CountRow("Checked by a reviewer", 6, "38%"),
                new CountRow("Finished by hand after a processing stop", 2, "13%")), rendered.get().cases().ofDelivered());
    }

    @Test
    void policyAtTheEndAndTheChangesComeFromTheRecords() {
        reads.records.add(record("SENSITIVE_DATA_POLICY_CHANGED", "SUCCESS", UUID.randomUUID().toString(), "Pat Example",
                "t", BALANCED_TO_LIGHT, IN_PERIOD, null));
        reads.records.add(record("SENSITIVE_DATA_POLICY_CHANGED", "SUCCESS", UUID.randomUUID().toString(), "Pat Example",
                "t", BALANCED_TO_LIGHT.replace("threshold=0.70", "threshold=0.75"), Instant.parse("2026-10-05T00:00:00Z"), null));
        create(ComplianceEdition.SUMMARY);
        ComplianceReportContent c = rendered.get();
        assertFalse(c.policy().unchangedSinceBefore());
        assertEquals("70% confidence or higher", c.policy().atEnd().get(2).value(), "the change in the period, not the later one");
        assertEquals("Social Security Number, Credit score", c.policy().atEnd().get(0).value());
        assertEquals(List.of("Preset: Balanced → Light", "Redacted without review at: 80% → 70%"),
                c.policy().changes().stream().map(ComplianceReportContent.ChangeRow::what).toList());
        assertEquals("Aug 12, 06:42", c.policy().changes().get(0).when(), "in the requested time zone");
        assertEquals("Pat Example", c.policy().changes().get(0).who());
        assertEquals(1, c.activity().get(0).count());
    }

    @Test
    void aPolicyChangedOnlyAfterThePeriodIsReadFromThatChangesPreviousValues() {
        reads.records.add(record("SENSITIVE_DATA_POLICY_CHANGED", "SUCCESS", UUID.randomUUID().toString(), null,
                "t", BALANCED_TO_LIGHT, Instant.parse("2026-10-05T00:00:00Z"), null));
        create(ComplianceEdition.SUMMARY);
        assertTrue(rendered.get().policy().unchangedSinceBefore());
        assertEquals("80% confidence or higher", rendered.get().policy().atEnd().get(2).value());
    }

    @Test
    void deliveredAfterSupervisorApprovalOnlyWhenRequiredForTheWholePeriod() {
        reads.records.add(record("LIFECYCLE_POLICY_CHANGED", "SUCCESS", UUID.randomUUID().toString(), null, "t",
                "Lifecycle policy changed: originalRetentionHours=72, redactedCacheHours=24, neverExportedHoldHours=336, "
                        + "finalApprovalRequired=true", Instant.parse("2026-05-01T00:00:00Z"), null));
        create(ComplianceEdition.SUMMARY);
        assertEquals("Delivered after supervisor approval", rendered.get().cases().outcomes().get(1).label());
        assertEquals("72 hours after delivery", rendered.get().retention().settings().get(0).value());
        assertEquals("14 days", rendered.get().retention().settings().get(2).value());
        assertEquals("Required", rendered.get().retention().settings().get(3).value());

        reads.records.add(record("LIFECYCLE_POLICY_CHANGED", "SUCCESS", UUID.randomUUID().toString(), null, "t",
                "Lifecycle policy changed: originalRetentionHours=72, redactedCacheHours=24, neverExportedHoldHours=336, "
                        + "finalApprovalRequired=false", IN_PERIOD, null));
        reads.records.add(record("LIFECYCLE_POLICY_CHANGED", "SUCCESS", UUID.randomUUID().toString(), null, "t",
                "Lifecycle policy changed: originalRetentionHours=72, redactedCacheHours=24, neverExportedHoldHours=336, "
                        + "finalApprovalRequired=true", IN_PERIOD.plusSeconds(60), null));
        create(ComplianceEdition.SUMMARY);
        assertEquals("Delivered", rendered.get().cases().outcomes().get(1).label(), "off for part of the period");
        assertFalse(rendered.get().retention().unchangedSinceBefore());
    }

    @Test
    void activityRowsCountTheirActions() {
        String person = UUID.randomUUID().toString();
        reads.records.add(record("FINDING_CONFIRMED", "SUCCESS", person, null, "f", "type=SSN", IN_PERIOD, null));
        reads.records.add(record("FINDING_DISMISSED", "SUCCESS", person, null, "f", "type=SSN", IN_PERIOD, null));
        reads.records.add(record("FINDING_CONFIRMED", "SUCCESS", SYSTEM, null, "f", "type=SSN", IN_PERIOD, null));
        reads.records.add(record("REPORT_CSV_EXPORTED", "SUCCESS", person, null, "r", "x", IN_PERIOD, null));
        reads.records.add(record("AUDIT_EXPORTED", "SUCCESS", person, null, "CSV", "x", IN_PERIOD, null));
        reads.records.add(record("AUDIT_EXPORTED", "SUCCESS", person, null, "JSON", "x", IN_PERIOD, null));
        reads.records.add(record("DOCUMENT_DOWNLOADED", "DENIED", person, null, "a", "x", IN_PERIOD, null));
        reads.records.add(record("COMPLIANCE_REPORT_CREATED", "DENIED", person, null, "t", "x", IN_PERIOD, null));
        reads.records.add(record("ORIGINAL_ARTIFACT_PURGED", "SUCCESS", SYSTEM, null, "p", "x", IN_PERIOD, null));
        reads.records.add(record("PACKAGE_FINAL_APPROVED", "SUCCESS", person, null, "p", "x", IN_PERIOD, null));
        reads.records.add(record("ORIGINAL_ARTIFACT_PURGED", "SUCCESS", SYSTEM, null, "p", "x",
                Instant.parse("2026-10-01T05:00:00Z"), null)); // after the period (New York midnight is 04:00Z)
        create(ComplianceEdition.SUMMARY);
        List<Long> counts = rendered.get().activity().stream().map(CountRow::count).toList();
        assertEquals(List.of(0L, 0L, 2L, 1L, 0L, 1L, 2L, 0L, 2L), counts);
    }

    @Test
    void theAppendixIsInTheFullEditionOnlyAndPrintsOnlyAllowedDetails() {
        reads.records.add(record("DOCUMENT_EXPORTED", "SUCCESS", SYSTEM, null, "d",
                "Document exported: destination=LOCAL, filename=MARKER-FILE-7731.pdf", IN_PERIOD, "case-1"));
        reads.records.add(record("PACKAGE_SUBMITTED", "SUCCESS", principal.actorId().toString(), "Pat Example", "p",
                "Package submitted: documents=2", IN_PERIOD.plusSeconds(1), null));
        create(ComplianceEdition.SUMMARY);
        assertTrue(rendered.get().appendix().isEmpty());

        create(ComplianceEdition.FULL);
        List<ComplianceReportContent.AppendixRow> rows = rendered.get().appendix();
        assertEquals(2, rows.size());
        assertEquals(new ComplianceReportContent.AppendixRow("2026-08-12 06:42:00", "Exported a document",
                "LexaVault (automatic)", "Success", "case-1", "—"), rows.get(0));
        assertEquals("Package submitted: documents=2", rows.get(1).detail());
        assertEquals("—", rows.get(1).caseId());
        assertFalse(rendered.get().canonicalText().contains("MARKER-FILE-7731"));
    }

    @Test
    void refusalsAndFailuresAreAudited() {
        service(nov19()).recordRefused(principal, CorrelationId.generate(), ComplianceEdition.FULL);
        AuditRecord refused = audits.get(audits.size() - 1);
        assertEquals(AuditOutcome.DENIED, refused.outcome());
        assertEquals(AuditAction.COMPLIANCE_REPORT_CREATED, refused.action());
        assertEquals("edition=FULL, refused=ROLE", refused.detail());

        assertThrows(ValidationException.class, () -> service(nov19()).create(principal, CorrelationId.generate(),
                new ComplianceReportService.Request("CUSTOM", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
                        "UTC"), ComplianceEdition.SUMMARY, "Business Administrator"));
        AuditRecord failed = audits.get(audits.size() - 1);
        assertEquals(AuditOutcome.FAILURE, failed.outcome());
        assertEquals("period=invalid, edition=SUMMARY, failure=VALIDATION", failed.detail());

        ComplianceReportService broken = new ComplianceReportService(reads, (c, f) -> {
            throw new IllegalStateException("boom: some external text");
        }, reportsQuery, new TenantRepository() {
            public Optional<Tenant> findById(TenantId id) { return Optional.of(tenant); }
            public Optional<Tenant> findBySlug(String slug) { return Optional.empty(); }
            public List<Tenant> findAll() { return List.of(); }
            public void save(Tenant t) { }
        }, new TenantRedactionPolicyRepository() {
            public Optional<TenantRedactionPolicy> findByTenantId(TenantId id) { return Optional.empty(); }
            public void save(TenantRedactionPolicy p) { }
        }, new TenantLifecyclePolicyRepository() {
            public Optional<TenantLifecyclePolicy> findByTenantId(TenantId id) { return Optional.empty(); }
            public void save(TenantLifecyclePolicy p) { }
        }, new AuditService(audits::add), names, nov19());
        assertThrows(IllegalStateException.class, () -> broken.create(principal, CorrelationId.generate(),
                new ComplianceReportService.Request("LAST_MONTH", null, null, null), ComplianceEdition.SUMMARY, "x"));
        AuditRecord internal = audits.get(audits.size() - 1);
        assertEquals("period=2026-10-01..2026-10-31, edition=SUMMARY, failure=INTERNAL", internal.detail());
        assertFalse(internal.detail().contains("boom"), "never an exception's text");
    }
}

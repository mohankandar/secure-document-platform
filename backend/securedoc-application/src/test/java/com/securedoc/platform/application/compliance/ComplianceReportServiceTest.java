package com.securedoc.platform.application.compliance;

import com.securedoc.platform.application.audit.RequestActorDisplayName;
import com.securedoc.platform.application.compliance.ComplianceReportContent.CountRow;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.operations.model.OperationalReportSnapshot;
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

    /** Task 0020: the "What LexaVault did for you" numbers Reports gives for the period; null = the defaults. */
    private final AtomicReference<OperationalReportSnapshot.TimeSaved> timeSaved = new AtomicReference<>();

    private final GetOperationalReportsQuery reportsQuery = (p, from, to, source, outcome) -> {
        long[] n = reportNumbers.get();
        return timeSaved.get() == null ? ComplianceFakes.reports(from, to, n[0], n[1], n[2], n[3])
                : ComplianceFakes.reports(from, to, n[0], n[1], n[2], n[3], timeSaved.get());
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
                new AuditService(audits::add, names), names,
                id -> id.equals(tenant.id()) ? Optional.ofNullable(licenceCustomer) : Optional.empty(), clock);
    }

    /** The installed licence's customerName; null = no licence. */
    private String licenceCustomer;

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
        assertEquals("Social Security Number, Date of birth, Credit card number, Credit score, Card security code (CVV), PIN",
                c.policy().atEnd().get(0).value(), "no record and no saved policy: the defaults");
        assertTrue(c.retention().unchangedSinceBefore());
        assertEquals(0, c.retention().due());
        assertTrue(c.retention().note().endsWith("No case was still within its keep time on Sep 30."));
        assertEquals("Delivered", c.cases().outcomes().get(1).label());
        c.cases().outcomes().forEach(r -> assertEquals(0, r.count()));
        assertEquals("—", c.cases().outcomes().get(0).share(), "nothing to divide by");
        assertEquals(8, c.activity().size(), "\"Cases rejected\" is hidden (task 0018 A3)");
        c.activity().forEach(r -> assertEquals(0, r.count(), r.label()));
        assertTrue(c.appendix().isEmpty());
        assertEquals("pat@example.test, Business Administrator", c.createdBy(), "no display name bound: the username");
        assertEquals("Nov 19, 2026, 7:00 AM EST", c.createdAt(), "in the requested time zone");
    }

    /** 1 of 4 delivered cases needed no person; 121 pages in it; 4 items redacted; the setting now is 1.5. */
    private static OperationalReportSnapshot.TimeSaved didForYou() {
        return new OperationalReportSnapshot.TimeSaved(4, 1, 25, 201, 7, 4,
                List.of("Social Security Number", "Credit card number"), 121, 1.5, 181);
    }

    private static String targets(String now, String previous) {
        String keys = "dimensions=0; deliveryTargetMinutes=240; deliveryTargetPercent=95.0; straightThroughTargetPercent=90.0; "
                + "reviewWaitTargetMinutes=60; reviewWaitTargetPercent=80";
        return "Reporting configuration changed: " + keys + (now == null ? "" : "; minutesPerPage=" + now)
                + (previous == null ? "" : "; previous=[" + keys + "; minutesPerPage=" + previous + "]");
    }

    private void targetsSaved(String detail, Instant at) {
        reads.records.add(record("REPORTING_CONFIGURATION_CHANGED", "SUCCESS", UUID.randomUUID().toString(), null,
                "tenant", detail, at, null));
    }

    @Test
    void whatLexaVaultDidForYouIsTheLastTableOfSection3WithItsGreyLine() {
        reportNumbers.set(new long[]{6, 4, 2, 2});
        timeSaved.set(didForYou());
        targetsSaved(targets("2.5", "1.0"), IN_PERIOD);
        create(ComplianceEdition.SUMMARY);
        ComplianceReportContent.Cases cases = rendered.get().cases();

        assertEquals(List.of(
                new ComplianceReportContent.ValueRow("Handled without a person", "1", "25%"),
                new ComplianceReportContent.ValueRow("Pages checked (in 7 documents)", "201", null),
                new ComplianceReportContent.ValueRow("Sensitive items redacted", "4", null),
                new ComplianceReportContent.ValueRow("Review time saved (estimate)", "≈ 5 h", null)), cases.didForYou(),
                "121 pages × 2.5 (the setting at the period end) = 302.5 minutes, rounded down: 5 h");
        assertEquals("Estimate: pages in cases that needed no person × 2.5 minutes per page, rounded down. "
                + "Pages a person reviewed count as zero.", cases.didForYouNote());
        String text = rendered.get().canonicalText();
        assertTrue(text.contains("cases.didForYou=Handled without a person|1|25%\n"));
        assertTrue(text.contains("cases.didForYou=Review time saved (estimate)|≈ 5 h|\n"));
        assertTrue(text.contains("cases.didForYouNote=Estimate: pages in cases that needed no person × 2.5 minutes per page"));
        assertTrue(text.indexOf("cases.ofDelivered=") < text.indexOf("cases.didForYou="), "after the delivered table");
    }

    @Test
    void timeSavedChangesTheFingerprint() {
        reportNumbers.set(new long[]{6, 4, 2, 2});
        timeSaved.set(didForYou());
        String first = create(ComplianceEdition.SUMMARY).fingerprint();
        assertEquals(first, create(ComplianceEdition.SUMMARY).fingerprint(), "same content, same fingerprint");
        timeSaved.set(new OperationalReportSnapshot.TimeSaved(4, 1, 25, 201, 7, 4, List.of(), 160, 1.5, 240));
        assertNotEquals(first, create(ComplianceEdition.SUMMARY).fingerprint(), "more pages with no person: 4 h, not 3 h (the fingerprint covers what is printed)");
        timeSaved.set(didForYou());
        targetsSaved(targets("3.0", "1.0"), IN_PERIOD);
        assertNotEquals(first, create(ComplianceEdition.SUMMARY).fingerprint(), "another minutes per page");
    }

    @Test
    void minutesPerPageIsTheSettingAtThePeriodEndFromTheHistory() {
        reportNumbers.set(new long[]{6, 4, 2, 2});
        timeSaved.set(didForYou()); // the setting now: 1.5

        // No record at all: no change ever, so the stored setting.
        create(ComplianceEdition.SUMMARY);
        assertTrue(rendered.get().cases().didForYouNote().contains("× 1.5 minutes"));

        // Changed only after the period: the value that change replaced.
        targetsSaved(targets("1.5", "4.0"), Instant.parse("2026-11-01T00:00:00Z"));
        create(ComplianceEdition.SUMMARY);
        assertTrue(rendered.get().cases().didForYouNote().contains("× 4 minutes"), rendered.get().cases().didForYouNote());
        assertEquals("≈ 8 h", rendered.get().cases().didForYou().get(3).value(), "121 × 4 = 484 minutes");

        // Changed in the period: that value, whatever came later.
        targetsSaved(targets("1.0", "0.5"), IN_PERIOD);
        create(ComplianceEdition.SUMMARY);
        assertEquals("Estimate: pages in cases that needed no person × 1 minute per page, rounded down. "
                + "Pages a person reviewed count as zero.", rendered.get().cases().didForYouNote());
        assertEquals("≈ 2 h", rendered.get().cases().didForYou().get(3).value());
    }

    @Test
    void olderReportTargetRecordsWithoutTheKeyAreSkipped() {
        reportNumbers.set(new long[]{6, 4, 2, 2});
        timeSaved.set(didForYou());
        // A record from before task 0020 in the period, then a new one after it that records what it replaced.
        targetsSaved(targets(null, null), IN_PERIOD);
        targetsSaved(targets("1.5", "2.0"), Instant.parse("2026-10-15T00:00:00Z"));
        create(ComplianceEdition.SUMMARY);
        assertTrue(rendered.get().cases().didForYouNote().contains("× 2 minutes"), rendered.get().cases().didForYouNote());
    }

    @Test
    void noDeliveredCasesGivesZerosAndADash() {
        timeSaved.set(new OperationalReportSnapshot.TimeSaved(0, 0, null, 0, 0, 0, List.of(), 0, 1.0, 0));
        create(ComplianceEdition.SUMMARY);
        List<ComplianceReportContent.ValueRow> rows = rendered.get().cases().didForYou();
        assertEquals("0", rows.get(0).value());
        assertEquals("—", rows.get(0).share());
        assertEquals("Pages checked (in 0 documents)", rows.get(1).label());
        assertEquals("0 min", rows.get(3).value());
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
                new CountRow("Still open on Sep 30", 3, "15%")), outcomes, "no rejected row (task 0018 A3)");
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
        assertEquals(List.of(0L, 0L, 2L, 1L, 1L, 2L, 0L, 2L), counts);
    }

    /* ---------------- task 0018 ---------------- */

    @Test
    void customerNameIsTheInstalledLicencesCustomerNameAndAbsentWithoutALicence() {
        create(ComplianceEdition.SUMMARY);
        assertEquals(null, rendered.get().customerName(), "no licence: the entity name alone");
        assertEquals("Residential lending", rendered.get().entityName());
        String without = rendered.get().fingerprint();

        licenceCustomer = "Acme Lending Group";
        create(ComplianceEdition.SUMMARY);
        assertEquals("Acme Lending Group", rendered.get().customerName());
        assertEquals("Residential lending", rendered.get().entityName());
        assertTrue(rendered.get().canonicalText().contains("customer=Acme Lending Group\n"));
        assertNotEquals(without, rendered.get().fingerprint(), "the customer name is part of the fingerprint");
    }

    @Test
    void rejectedRowsAreHiddenFromTheContentAndTheFingerprint() {
        reads.cases = new ComplianceReportReadPort.CaseCounts(4, 0, 0);
        reads.records.add(record("PACKAGE_REJECTED", "SUCCESS", UUID.randomUUID().toString(), null, "p", "x", IN_PERIOD, null));
        create(ComplianceEdition.SUMMARY);
        String canonical = rendered.get().canonicalText();
        assertFalse(canonical.contains("Rejected by a supervisor"), canonical);
        assertFalse(canonical.contains("Cases rejected"), canonical);
        assertFalse(ComplianceReportService.SHOW_REJECTED_ROWS, "one switch brings both rows back");
    }

    @Test
    void theDeliveredNoteIsUnderTheFirstTableAndInTheFingerprint() {
        create(ComplianceEdition.SUMMARY);
        String note = "Delivered counts cases first delivered in the period, whenever they were received, as on the "
                + "Reports page. Shares are of cases received.";
        assertEquals(note, rendered.get().cases().outcomesNote());
        assertTrue(rendered.get().canonicalText().contains("cases.outcomesNote=" + note + "\n"));
    }

    @Test
    void lifecycleChangesInTheNewFormatAreWordedFromTheirPreviousValues() {
        // previous= holds 96 hours: proof the recorded value is used, not the rebuilt one (72 by default).
        reads.records.add(record("LIFECYCLE_POLICY_CHANGED", "SUCCESS", UUID.randomUUID().toString(), "Pat Example", "t",
                "Lifecycle policy changed: originalRetentionHours=48, redactedCacheHours=24, neverExportedHoldHours=168, "
                        + "finalApprovalRequired=false, previous=[originalRetentionHours=96, redactedCacheHours=24, "
                        + "neverExportedHoldHours=168, finalApprovalRequired=true]", IN_PERIOD, null));
        create(ComplianceEdition.SUMMARY);
        ComplianceReportContent.Retention r = rendered.get().retention();
        assertEquals(List.of("Originals kept for: 96 → 48 hours",
                        "Supervisor approval before delivery: Required → Not required"),
                r.changes().stream().map(ComplianceReportContent.ChangeRow::what).toList());
        assertEquals("Aug 12, 06:42", r.changes().get(0).when());
        assertEquals("Pat Example", r.changes().get(0).who());
        assertEquals("48 hours after delivery", r.settings().get(0).value());
        assertTrue(rendered.get().canonicalText().contains(
                "retention.change=Aug 12, 06:42|Pat Example|Originals kept for: 96 → 48 hours\n"));
    }

    @Test
    void aFirstLifecycleSaveAndALaterOnlyChangeUseThePreviousKey() {
        reads.records.add(record("LIFECYCLE_POLICY_CHANGED", "SUCCESS", UUID.randomUUID().toString(), null, "t",
                "Lifecycle policy changed: originalRetentionHours=48, redactedCacheHours=24, neverExportedHoldHours=168, "
                        + "finalApprovalRequired=false, previous=none", IN_PERIOD, null));
        create(ComplianceEdition.SUMMARY);
        assertEquals(List.of("Policy saved for the first time"),
                rendered.get().retention().changes().stream().map(ComplianceReportContent.ChangeRow::what).toList());

        reads.records.clear();
        reads.records.add(record("LIFECYCLE_POLICY_CHANGED", "SUCCESS", UUID.randomUUID().toString(), null, "t",
                "Lifecycle policy changed: originalRetentionHours=48, redactedCacheHours=24, neverExportedHoldHours=168, "
                        + "finalApprovalRequired=false, previous=[originalRetentionHours=120, redactedCacheHours=24, "
                        + "neverExportedHoldHours=168, finalApprovalRequired=true]", Instant.parse("2026-10-05T00:00:00Z"), null));
        create(ComplianceEdition.SUMMARY);
        assertTrue(rendered.get().retention().changes().isEmpty(), "no change in the period: no table");
        assertEquals("120 hours after delivery", rendered.get().retention().settings().get(0).value(),
                "the period-end policy is the later change's previous=");
        assertEquals("Delivered after supervisor approval", rendered.get().cases().outcomes().get(1).label());
    }

    @Test
    void olderLifecycleRecordsWithoutPreviousAreRebuiltAsBefore() {
        String old = "Lifecycle policy changed: originalRetentionHours=72, redactedCacheHours=24, neverExportedHoldHours=336, ";
        reads.records.add(record("LIFECYCLE_POLICY_CHANGED", "SUCCESS", UUID.randomUUID().toString(), null, "t",
                old + "finalApprovalRequired=true", Instant.parse("2026-05-01T00:00:00Z"), null));
        reads.records.add(record("LIFECYCLE_POLICY_CHANGED", "SUCCESS", UUID.randomUUID().toString(), null, "t",
                old + "finalApprovalRequired=false", IN_PERIOD, null));
        reads.records.add(record("LIFECYCLE_POLICY_CHANGED", "SUCCESS", UUID.randomUUID().toString(), null, "t",
                old.replace("=72", "=48") + "finalApprovalRequired=false", IN_PERIOD.plusSeconds(60), null));
        create(ComplianceEdition.SUMMARY);
        assertEquals(List.of("Supervisor approval before delivery: Required → Not required",
                        "Originals kept for: 72 → 48 hours"),
                rendered.get().retention().changes().stream().map(ComplianceReportContent.ChangeRow::what).toList());
    }

    @Test
    void anUnreadableLifecycleRecordIsShownAsChangedWithoutGuessing() {
        reads.records.add(record("LIFECYCLE_POLICY_CHANGED", "SUCCESS", UUID.randomUUID().toString(), null, "t",
                "Lifecycle policy changed: originalRetentionHours=oops", IN_PERIOD, null));
        create(ComplianceEdition.SUMMARY);
        assertEquals(List.of("Changed (this record does not hold the earlier values)"),
                rendered.get().retention().changes().stream().map(ComplianceReportContent.ChangeRow::what).toList());
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

    /** Task 0058: a request without a zone uses the entity's; one that names a zone keeps it; without either UTC, as before. */
    @Test
    void theEntitysTimeZoneAppliesWhenTheRequestNamesNone() {
        Clock clock = Clock.fixed(Instant.parse("2026-12-01T02:00:00Z"), java.time.ZoneOffset.UTC); // still November 30 in New York
        var none = new ComplianceReportService.Request("LAST_MONTH", null, null, null);
        var period = service(clock).create(principal, CorrelationId.generate(), none, ComplianceEdition.SUMMARY, "x").period();
        assertEquals(LocalDate.of(2026, 11, 1), period.from());
        var zoned = service(clock).withEntityTimeZones(entity -> Optional.of(java.time.ZoneId.of("America/New_York")));
        period = zoned.create(principal, CorrelationId.generate(), none, ComplianceEdition.SUMMARY, "x").period();
        assertEquals(LocalDate.of(2026, 10, 1), period.from());
        // October ends with the hour of October's own offset and November's starts after the clocks went back.
        assertEquals(Instant.parse("2026-10-01T04:00:00Z"), period.start());
        assertEquals(Instant.parse("2026-11-01T04:00:00Z"), period.endExclusive());
        assertEquals(LocalDate.of(2026, 11, 1), zoned.create(principal, CorrelationId.generate(),
                new ComplianceReportService.Request("LAST_MONTH", null, null, "UTC"), ComplianceEdition.SUMMARY, "x").period().from());
    }

    @Test
    void refusalsAndFailuresAreAudited() {
        service(nov19()).recordRefused(principal, CorrelationId.generate(), ComplianceEdition.FULL);
        AuditRecord refused = audits.get(audits.size() - 1);
        assertEquals(AuditOutcome.DENIED, refused.outcome());
        assertEquals(AuditAction.COMPLIANCE_REPORT_CREATED, refused.action());
        assertEquals("code=LV-AUT-006, detail=ACCESS_DENIED, edition=FULL, refused=ROLE", refused.detail());

        assertThrows(ValidationException.class, () -> service(nov19()).create(principal, CorrelationId.generate(),
                new ComplianceReportService.Request("CUSTOM", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 30),
                        "UTC"), ComplianceEdition.SUMMARY, "Business Administrator"));
        AuditRecord failed = audits.get(audits.size() - 1);
        assertEquals(AuditOutcome.FAILURE, failed.outcome());
        assertEquals("code=LV-CFG-001, detail=VALIDATION, period=invalid, edition=SUMMARY, failure=VALIDATION", failed.detail());

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
        assertEquals("code=LV-SYS-001, detail=INTERNAL, period=2026-10-01..2026-10-31, edition=SUMMARY, failure=INTERNAL", internal.detail());
        assertFalse(internal.detail().contains("boom"), "never an exception's text");
    }
}

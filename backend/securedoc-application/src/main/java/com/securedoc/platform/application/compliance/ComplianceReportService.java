package com.securedoc.platform.application.compliance;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.application.audit.RequestActorDisplayName;
import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.application.compliance.ComplianceReportContent.AppendixRow;
import com.securedoc.platform.application.compliance.ComplianceReportContent.ChangeRow;
import com.securedoc.platform.application.compliance.ComplianceReportContent.CountRow;
import com.securedoc.platform.application.compliance.ComplianceReportContent.LabelValue;
import com.securedoc.platform.application.compliance.ComplianceReportContent.ValueRow;
import com.securedoc.platform.application.compliance.PolicyHistory.LifecycleChange;
import com.securedoc.platform.application.compliance.PolicyHistory.LifecyclePolicy;
import com.securedoc.platform.application.compliance.PolicyHistory.SensitiveChange;
import com.securedoc.platform.application.compliance.PolicyHistory.SensitivePolicy;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.ApplicationException;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.operations.TimeSavedRule;
import com.securedoc.platform.application.operations.model.OperationalReportSnapshot;
import com.securedoc.platform.application.port.in.GetOperationalReportsQuery;
import com.securedoc.platform.application.port.out.ComplianceReportPdfPort;
import com.securedoc.platform.application.port.out.ComplianceReportReadPort;
import com.securedoc.platform.application.port.out.ComplianceReportReadPort.ActivityQuery;
import com.securedoc.platform.application.port.out.TenantLifecyclePolicyRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.finding.AiModel;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;
import com.securedoc.platform.domain.tenant.Tenant;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Builds the compliance report PDF for the entity the person is signed in to (task 0017 B1–B5) and records
 * {@code COMPLIANCE_REPORT_CREATED} with the content fingerprint and the SHA-256 of the file. The PDF is built in
 * memory and returned; it is never put in storage.
 *
 * <p>Nothing in the report, its audit detail or the logs holds a borrower name, file name, loan number, business
 * identifier value, found text or redacted value: every number is a count, every word a setting, type name or
 * fixed text, and the appendix prints a record's detail only for actions on {@link AppendixDetailAllowList}.
 */
public final class ComplianceReportService {

    static final String RESOURCE_TYPE = "ComplianceReport";
    private static final Set<String> SUCCESS = Set.of("SUCCESS");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US);
    private static final DateTimeFormatter DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US);
    private static final DateTimeFormatter CREATED_AT = DateTimeFormatter.ofPattern("MMM d, yyyy, h:mm a z", Locale.US);
    private static final DateTimeFormatter CHANGE_TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm", Locale.US);
    private static final DateTimeFormatter CHANGE_TIME_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy, HH:mm", Locale.US);
    private static final DateTimeFormatter APPENDIX_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.US);

    /**
     * Task 0018 A3: "Rejected by a supervisor" (section 3) and "Cases rejected" (section 4) are hidden until rejecting
     * a case exists in the product. Turning this on brings both rows back, in the PDF and in the fingerprint.
     */
    static final boolean SHOW_REJECTED_ROWS = false;

    /** Task 0018 A5: the note under the first table of section 3. */
    static final String OUTCOMES_NOTE = "Delivered counts cases first delivered in the period, whenever they were "
            + "received, as on the Reports page. Shares are of cases received.";

    private final ComplianceReportReadPort readPort;
    private final ComplianceReportPdfPort pdfPort;
    private final GetOperationalReportsQuery reportsQuery;
    private final TenantRepository tenantRepository;
    private final TenantRedactionPolicyRepository redactionPolicyRepository;
    private final TenantLifecyclePolicyRepository lifecyclePolicyRepository;
    private final AuditService auditService;
    private final RequestActorDisplayName actorDisplayName;
    private final CustomerNames customerNames;
    private final Clock clock;
    private com.securedoc.platform.application.port.out.EntityTimeZonePort zones = com.securedoc.platform.application.port.out.EntityTimeZonePort.NONE;

    private Integer backupRetentionDays;

    /** The customer name of the entity's installed licence (task 0018 A1); empty without a licence. */
    @FunctionalInterface
    public interface CustomerNames {
        Optional<String> installedCustomerName(TenantId tenantId);

        CustomerNames NONE = tenantId -> Optional.empty();
    }

    /** Without a licence lookup: the report shows the entity name alone. */
    public ComplianceReportService(ComplianceReportReadPort readPort, ComplianceReportPdfPort pdfPort,
                                   GetOperationalReportsQuery reportsQuery, TenantRepository tenantRepository,
                                   TenantRedactionPolicyRepository redactionPolicyRepository,
                                   TenantLifecyclePolicyRepository lifecyclePolicyRepository,
                                   AuditService auditService, RequestActorDisplayName actorDisplayName, Clock clock) {
        this(readPort, pdfPort, reportsQuery, tenantRepository, redactionPolicyRepository, lifecyclePolicyRepository,
                auditService, actorDisplayName, CustomerNames.NONE, clock);
    }

    public ComplianceReportService(ComplianceReportReadPort readPort, ComplianceReportPdfPort pdfPort,
                                   GetOperationalReportsQuery reportsQuery, TenantRepository tenantRepository,
                                   TenantRedactionPolicyRepository redactionPolicyRepository,
                                   TenantLifecyclePolicyRepository lifecyclePolicyRepository,
                                   AuditService auditService, RequestActorDisplayName actorDisplayName,
                                   CustomerNames customerNames, Clock clock) {
        this.customerNames = Objects.requireNonNull(customerNames);
        this.readPort = Objects.requireNonNull(readPort);
        this.pdfPort = Objects.requireNonNull(pdfPort);
        this.reportsQuery = Objects.requireNonNull(reportsQuery);
        this.tenantRepository = Objects.requireNonNull(tenantRepository);
        this.redactionPolicyRepository = Objects.requireNonNull(redactionPolicyRepository);
        this.lifecyclePolicyRepository = Objects.requireNonNull(lifecyclePolicyRepository);
        this.auditService = Objects.requireNonNull(auditService);
        this.actorDisplayName = Objects.requireNonNull(actorDisplayName);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Task 0058: the entity's time zone, for a request that names none (before: UTC, which it still is without one). */
    public ComplianceReportService withEntityTimeZones(com.securedoc.platform.application.port.out.EntityTimeZonePort zones) {
        this.zones = Objects.requireNonNull(zones);
        return this;
    }

    /** Task 0060: how long this installation keeps its backups (platform.erase-log.backup-retention-days). */
    public ComplianceReportService withBackupRetention(int days) {
        this.backupRetentionDays = days;
        return this;
    }

    /** The period in force when the report is made, then the erasures of a restored backup are done again (task 0060). */
    private List<LabelValue> retentionSettings(LabelValue... settings) {
        List<LabelValue> rows = new ArrayList<>(List.of(settings));
        if (backupRetentionDays != null) rows.add(new LabelValue("Backups kept for", backupRetentionDays + " days"));
        return List.copyOf(rows);
    }

    private ZoneId zone(TenantId tenantId, String requested) {
        if (requested == null || requested.isBlank()) {
            return zones.effective(tenantId).orElseGet(() -> CompliancePeriod.zone(null));
        }
        return CompliancePeriod.zone(requested);
    }

    /** The request body, unvalidated. The entity is never taken from it. */
    public record Request(String period, LocalDate from, LocalDate to, String timeZone) { }

    public record CreatedReport(byte[] pdf, String fileName, String fileSha256, String fingerprint,
                                CompliancePeriod period, ComplianceEdition edition) { }

    /**
     * Creates the report. The caller has already checked the capability for {@code edition}. {@code creatorRole}
     * is the role words printed after the creator's name ("Business Administrator").
     */
    public CreatedReport create(AuthenticatedPrincipal principal, CorrelationId correlationId, Request request,
                                ComplianceEdition edition, String creatorRole) {
        Objects.requireNonNull(principal);
        Objects.requireNonNull(edition);
        CompliancePeriod period = null;
        try {
            if (request == null) throw new ValidationException(LvErrorCode.CFG_001, "A request body is required");
            ZoneId zone = zone(principal.tenantId(), request.timeZone());
            period = CompliancePeriod.resolve(CompliancePeriod.type(request.period()), request.from(), request.to(),
                    zone, clock);
            Tenant tenant = tenantRepository.findById(principal.tenantId())
                    .orElseThrow(() -> new NotFoundException("Tenant", principal.tenantId().toString()));

            ComplianceReportContent content = content(principal, tenant, period, edition, creatorRole);
            String fingerprint = content.fingerprint();
            byte[] pdf = pdfPort.render(content, fingerprint);
            String fileSha256 = sha256(pdf);
            auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.COMPLIANCE_REPORT_CREATED,
                    RESOURCE_TYPE, principal.tenantId().toString(), correlationId,
                    "period=" + period.from() + ".." + period.to() + ", edition=" + edition.name()
                            + ", fingerprint=" + fingerprint + ", fileSha256=" + fileSha256);
            return new CreatedReport(pdf, fileName(tenant, period), fileSha256, fingerprint, period, edition);
        } catch (RuntimeException e) {
            // The failure is recorded by category only, never by an exception's text.
            String failure = e instanceof ValidationException ? "VALIDATION"
                    : e instanceof NotFoundException ? "NOT_FOUND" : "INTERNAL";
            auditService.recordFailure(principal.tenantId(), principal.actorId(), AuditAction.COMPLIANCE_REPORT_CREATED,
                    RESOURCE_TYPE, principal.tenantId().toString(), correlationId,
                    com.securedoc.platform.application.error.LvErrorCodes.auditDetail(
                            com.securedoc.platform.application.error.LvErrorCodes.optional(e)
                                    .orElse(com.securedoc.platform.domain.error.LvErrorCode.SYS_001), failure, null) + ", "
                    + (period == null ? "period=invalid" : "period=" + period.from() + ".." + period.to())
                            + ", edition=" + edition.name() + ", failure=" + failure);
            if (e instanceof ApplicationException) throw e;
            throw new IllegalStateException("The compliance report could not be created", e);
        }
    }

    /**
     * Task 0019 A1: how many rows the full edition's appendix would print for this period: every audit record of the
     * entity in it, read with the same period rules as {@link #create}. Read-only and not audited.
     */
    public long appendixRecordCount(TenantId tenantId, Request request) {
        Objects.requireNonNull(tenantId);
        if (request == null) throw new ValidationException(LvErrorCode.CFG_001, "A period is required");
        ZoneId zone = zone(tenantId, request.timeZone());
        CompliancePeriod period = CompliancePeriod.resolve(CompliancePeriod.type(request.period()), request.from(),
                request.to(), zone, clock);
        return readPort.count(tenantId, ALL_RECORDS, period.start(), period.endExclusive());
    }

    /** No action, outcome, actor or resource filter: exactly the records {@code forEachRecord} reads. */
    static final ActivityQuery ALL_RECORDS = new ActivityQuery(Set.of(), Set.of(), false, null);

    /** Records a refused request: the person's role does not allow {@code requested}. */
    public void recordRefused(AuthenticatedPrincipal principal, CorrelationId correlationId, ComplianceEdition requested) {
        auditService.recordDenied(principal.tenantId(), principal.actorId(), AuditAction.COMPLIANCE_REPORT_CREATED,
                RESOURCE_TYPE, principal.tenantId().toString(), correlationId,
                "code=" + com.securedoc.platform.domain.error.LvErrorCode.AUT_006.code() + ", detail=ACCESS_DENIED, edition="
                        + requested.name() + ", refused=ROLE");
    }

    ComplianceReportContent content(AuthenticatedPrincipal principal, Tenant tenant, CompliancePeriod period,
                                    ComplianceEdition edition, String creatorRole) {
        TenantId tenantId = principal.tenantId();
        Instant start = period.start();
        Instant end = period.endExclusive();
        ZoneId zone = period.zone();
        boolean multiYear = period.from().getYear() != period.to().getYear();
        String endWords = (multiYear ? DAY_YEAR : DAY).format(period.to());

        // 1. Redaction policy
        List<AuditRecordSummary> policyChanges = readPort.successes(tenantId,
                AuditAction.SENSITIVE_DATA_POLICY_CHANGED.name(), start, end);
        SensitivePolicy policyAtEnd = sensitivePolicyAt(tenantId, end);
        List<ChangeRow> changeRows = new ArrayList<>();
        for (AuditRecordSummary r : policyChanges) {
            SensitiveChange change = PolicyHistory.parseSensitive(r.detail());
            List<String> lines = change == null
                    ? List.of("Changed (this record does not hold the earlier values)")
                    : PolicyHistory.changeLines(change);
            String when = (multiYear ? CHANGE_TIME_YEAR : CHANGE_TIME).format(r.occurredAt().atZone(zone));
            String who = AuditSentences.person(r.actorId(), r.actorDisplayName());
            for (String line : lines) changeRows.add(new ChangeRow(when, who, line));
        }
        ComplianceReportContent.Policy policy = new ComplianceReportContent.Policy(
                policyChanges.isEmpty(), policyRows(policyAtEnd), changeRows);

        // 2. Keeping and deleting
        List<AuditRecordSummary> lifecycleChanges = readPort.successes(tenantId,
                AuditAction.LIFECYCLE_POLICY_CHANGED.name(), start, end);
        LifecyclePolicy lifecycleAtStart = lifecyclePolicyAt(tenantId, start);
        LifecyclePolicy lifecycleAtEnd = lifecyclePolicyAt(tenantId, end);
        boolean approvalWholePeriod = lifecycleAtStart.finalApprovalRequired() && lifecycleChanges.stream()
                .map(r -> PolicyHistory.parseLifecycle(r.detail()))
                .allMatch(p -> p != null && p.finalApprovalRequired());
        List<ChangeRow> lifecycleChangeRows = new ArrayList<>();
        LifecyclePolicy running = lifecycleAtStart;
        for (AuditRecordSummary r : lifecycleChanges) {
            LifecycleChange change = PolicyHistory.parseLifecycleChange(r.detail());
            List<String> lines;
            if (change == null) {
                lines = List.of("Changed (this record does not hold the earlier values)");
            } else {
                // Task 0018 A4: the recorded previous= values when there; older records are rebuilt from the
                // record before them (or the policy at the period start), as before.
                LifecyclePolicy before = change.previousRecorded() ? change.previous() : running;
                lines = PolicyHistory.lifecycleChangeLines(before, change.now());
                running = change.now();
            }
            String when = (multiYear ? CHANGE_TIME_YEAR : CHANGE_TIME).format(r.occurredAt().atZone(zone));
            String who = AuditSentences.person(r.actorId(), r.actorDisplayName());
            for (String line : lines) lifecycleChangeRows.add(new ChangeRow(when, who, line));
        }
        ComplianceReportReadPort.RetentionCounts retention = readPort.retention(tenantId, start, end);
        ComplianceReportContent.Retention retentionSection = new ComplianceReportContent.Retention(
                lifecycleChanges.isEmpty(),
                retentionSettings(new LabelValue("Originals kept for",
                                lifecycleAtEnd.originalRetentionHours() + " hours after delivery"),
                        new LabelValue("Redacted copies kept for", PolicyHistory.hoursWords(lifecycleAtEnd.redactedCacheHours())),
                        new LabelValue("Stopped cases kept for", PolicyHistory.hoursWords(lifecycleAtEnd.neverExportedHoldHours())),
                        new LabelValue("Supervisor approval before delivery",
                                PolicyHistory.requiredWords(lifecycleAtEnd.finalApprovalRequired()))),
                lifecycleChangeRows,
                retention.due(), retention.onTime(), retention.lateOrFailed(),
                retentionNote(retention.stillWithinKeep(), lifecycleAtEnd.originalRetentionHours(), endWords));

        // 3. Cases and review: the Reports query and definitions for the same period.
        OperationalReportSnapshot reports = reportsQuery.execute(principal, start, end, "ALL", "ALL");
        ComplianceReportReadPort.CaseCounts caseCounts = readPort.cases(tenantId, start, end);
        long received = reports.executive().batches();
        long delivered = reports.executive().delivered();
        List<CountRow> outcomes = new ArrayList<>();
        outcomes.add(new CountRow("Received", received, share(received, received)));
        outcomes.add(new CountRow(approvalWholePeriod ? "Delivered after supervisor approval" : "Delivered",
                delivered, share(delivered, received)));
        if (SHOW_REJECTED_ROWS) {
            outcomes.add(new CountRow("Rejected by a supervisor", caseCounts.rejected(),
                    share(caseCounts.rejected(), received)));
        }
        outcomes.add(new CountRow("Still open on " + endWords, caseCounts.stillOpenAtEnd(),
                share(caseCounts.stillOpenAtEnd(), received)));
        double minutesPerPageAtEnd = minutesPerPageAt(tenantId, end, reports.timeSaved().minutesPerPage());
        ComplianceReportContent.Cases cases = new ComplianceReportContent.Cases(
                outcomes,
                OUTCOMES_NOTE,
                List.of(new CountRow("No review needed", reports.usage().straightThroughPackages(),
                                share(reports.usage().straightThroughPackages(), delivered)),
                        new CountRow("Checked by a reviewer", reports.usage().reviewedPackages(),
                                share(reports.usage().reviewedPackages(), delivered)),
                        new CountRow("Finished by hand after a processing stop", caseCounts.finishedByHand(),
                                share(caseCounts.finishedByHand(), delivered))),
                didForYouRows(reports.timeSaved(), minutesPerPageAtEnd),
                didForYouNote(minutesPerPageAtEnd));

        // 4. Audit activity
        List<CountRow> activity = new ArrayList<>();
        for (ActivityRow row : ActivityRow.values()) {
            if (row.rejectedRow && !SHOW_REJECTED_ROWS) continue;
            long count = 0;
            for (ActivityQuery q : row.queries) count += readPort.count(tenantId, q, start, end);
            activity.add(new CountRow(row.label, count, null));
        }

        // Appendix (full edition only)
        List<AppendixRow> appendix = new ArrayList<>();
        if (edition == ComplianceEdition.FULL) {
            readPort.forEachRecord(tenantId, start, end, r -> appendix.add(new AppendixRow(
                    APPENDIX_TIME.format(r.occurredAt().atZone(zone)),
                    AuditSentences.whatHappened(r.action(), r.detail()),
                    AuditSentences.person(r.actorId(), r.actorDisplayName()),
                    AuditSentences.resultWord(r.outcome()),
                    r.caseId() == null || r.caseId().isBlank() ? "—" : r.caseId(),
                    AppendixDetailAllowList.printable(r.action(), r.detail()))));
        }

        String creatorName = actorDisplayName.displayNameFor(principal.actorId()).orElse(principal.username());
        String customerName = customerNames.installedCustomerName(tenantId).orElse(null);
        return new ComplianceReportContent(edition, customerName, tenant.name(), periodWords(period), endWords,
                creatorName + ", " + creatorRole,
                CREATED_AT.format(ZonedDateTime.now(clock).withZoneSameInstant(zone)),
                policy, retentionSection, cases, activity, appendix,
                reading(readPort.pageReading(tenantId, start, end)));
    }

    /** Section 5 (task 0064). */
    static ComplianceReportContent.Reading reading(ComplianceReportReadPort.PageReading r) {
        if (r.nothing()) return ComplianceReportContent.Reading.NONE;
        long all = r.textPages() + r.visionPages() + r.visionPagesToPerson();
        List<CountRow> pages = List.of(
                new CountRow("Read as text", r.textPages(), share(r.textPages(), all)),
                new CountRow("Of these, also checked for signatures", r.signaturePages(),
                        share(r.signaturePages(), all)),
                new CountRow("Read by the vision model", r.visionPages(), share(r.visionPages(), all)),
                new CountRow("Needed the vision model, sent to a person", r.visionPagesToPerson(),
                        share(r.visionPagesToPerson(), all)));
        List<ComplianceReportContent.ModelRow> models = r.models().stream().map(use -> {
            AiModel model = AiModel.decode(use.model());
            return new ComplianceReportContent.ModelRow(model.name(), model.version(), model.digest(), use.documents());
        }).toList();
        return new ComplianceReportContent.Reading(pages, models,
                "Why a page was sent to the vision model, with its text-reading confidence, is in the audit trail of "
                        + "its search. A digest of “" + AiModel.NO_DIGEST + "” means the model’s server states none.");
    }

    /**
     * The section 4 rows and the {@link AuditAction}s each counts, all in the period and the entity.
     */
    enum ActivityRow {
        POLICY_CHANGES("Redaction policy changes",
                query(Set.of(AuditAction.SENSITIVE_DATA_POLICY_CHANGED), SUCCESS, false, null)),
        LIFECYCLE_CHANGES("Keeping and deleting changes",
                query(Set.of(AuditAction.LIFECYCLE_POLICY_CHANGED), SUCCESS, false, null)),
        FINDINGS_DECIDED("Findings decided by a reviewer",
                query(Set.of(AuditAction.FINDING_CONFIRMED, AuditAction.FINDING_DISMISSED, AuditAction.FINDING_OVERRIDDEN,
                                AuditAction.REVIEW_PAGE_CHECKED),
                        SUCCESS, true, null)),
        CASES_APPROVED("Cases approved for delivery",
                query(Set.of(AuditAction.PACKAGE_FINAL_APPROVED), SUCCESS, false, null)),
        CASES_REJECTED("Cases rejected", true,
                query(Set.of(AuditAction.PACKAGE_REJECTED, AuditAction.REVIEW_REJECTED), SUCCESS, false, null)),
        ORIGINALS_DELETED("Originals deleted",
                query(Set.of(AuditAction.ORIGINAL_ARTIFACT_PURGED), SUCCESS, false, null)),
        // Task 0069: the label says what it means for the copy.
        SIGNED_ORIGINALS("Originals digitally signed; signature not on the redacted copy",
                query(Set.of(AuditAction.DOCUMENT_SIGNATURE_REMOVED), SUCCESS, false, null)),
        REPORT_EXPORTS("Report exports (CSV)",
                query(Set.of(AuditAction.REPORT_CSV_EXPORTED), SUCCESS, false, null),
                query(Set.of(AuditAction.AUDIT_EXPORTED), SUCCESS, false, "CSV")),
        COMPLIANCE_REPORTS("Compliance reports created",
                query(Set.of(AuditAction.COMPLIANCE_REPORT_CREATED), SUCCESS, false, null)),
        ACCESS_REFUSED("Access refused (wrong role)",
                query(Set.of(), Set.of("DENIED"), false, null),
                query(Set.of(AuditAction.AUTHORIZATION_FAILED), Set.of("SUCCESS", "FAILURE"), false, null));

        final String label;
        /** A "rejected" row, shown only when {@link #SHOW_REJECTED_ROWS} is on. */
        final boolean rejectedRow;
        final List<ActivityQuery> queries;

        ActivityRow(String label, ActivityQuery... queries) {
            this(label, false, queries);
        }

        ActivityRow(String label, boolean rejectedRow, ActivityQuery... queries) {
            this.label = label;
            this.rejectedRow = rejectedRow;
            this.queries = List.of(queries);
        }

        private static ActivityQuery query(Set<AuditAction> actions, Set<String> outcomes, boolean excludeSystem,
                                           String resourceId) {
            return new ActivityQuery(actions.stream().map(Enum::name).collect(Collectors.toSet()), outcomes,
                    excludeSystem, resourceId);
        }
    }

    private SensitivePolicy sensitivePolicyAt(TenantId tenantId, Instant end) {
        Optional<AuditRecordSummary> latest = readPort.latestSuccess(tenantId,
                AuditAction.SENSITIVE_DATA_POLICY_CHANGED.name(), end);
        if (latest.isPresent()) {
            SensitiveChange change = PolicyHistory.parseSensitive(latest.get().detail());
            if (change != null) return change.now();
        }
        // No change at or before the end: the policy then is the one the first later change replaced, if any.
        Optional<AuditRecordSummary> later = readPort.successes(tenantId,
                AuditAction.SENSITIVE_DATA_POLICY_CHANGED.name(), end, Instant.now(clock).plusSeconds(1)).stream().findFirst();
        if (later.isPresent()) {
            SensitiveChange change = PolicyHistory.parseSensitive(later.get().detail());
            if (change != null && change.previousRecorded() && change.previous() != null) return change.previous();
            if (change != null && change.previousRecorded()) {
                // Task 0062: a first save from before the catalog replaced the defaults of that time.
                boolean catalog = change.now().types() != null && change.now().types().contains("CVV");
                return policyValues(catalog ? TenantRedactionPolicy.defaults(tenantId)
                        : TenantRedactionPolicy.defaultsBeforeCatalog(tenantId));
            }
        }
        return policyValues(redactionPolicyRepository.findByTenantId(tenantId)
                .orElse(TenantRedactionPolicy.defaults(tenantId)));
    }

    private static SensitivePolicy policyValues(TenantRedactionPolicy p) {
        return SensitivePolicy.of(p.enabledEntityTypes().stream().map(Enum::name).collect(Collectors.toSet()),
                p.autoRedactThreshold(), p.reviewMode(), p.maskSsnLastFour())
                .with(p.packs().stream().map(Enum::name).collect(Collectors.toSet()),
                        p.keepLastFour().stream().map(Enum::name).collect(Collectors.toSet()))
                .withVision(p.vision()).withSecondCheck(p.secondCheck())
                .withDocumentContext(p.documentContext());
    }

    private LifecyclePolicy lifecyclePolicyAt(TenantId tenantId, Instant at) {
        Optional<AuditRecordSummary> latest = readPort.latestSuccess(tenantId,
                AuditAction.LIFECYCLE_POLICY_CHANGED.name(), at);
        if (latest.isPresent()) {
            LifecyclePolicy parsed = PolicyHistory.parseLifecycle(latest.get().detail());
            if (parsed != null) return parsed;
        }
        Optional<AuditRecordSummary> later = readPort.successes(tenantId, AuditAction.LIFECYCLE_POLICY_CHANGED.name(),
                at, Instant.now(clock).plusSeconds(1)).stream().findFirst();
        if (later.isPresent()) {
            // Task 0018 A4: the first later change holds the values it replaced (previous=none: nothing was saved).
            LifecycleChange change = PolicyHistory.parseLifecycleChange(later.get().detail());
            if (change != null && change.previousRecorded() && change.previous() != null) return change.previous();
        }
        boolean changedLater = later.isPresent();
        // Only a recorded save changes the lifecycle policy, so before the first one it was the default.
        TenantLifecyclePolicy p = changedLater ? TenantLifecyclePolicy.defaults(tenantId)
                : lifecyclePolicyRepository.findByTenantId(tenantId).orElse(TenantLifecyclePolicy.defaults(tenantId));
        return new LifecyclePolicy(p.originalRetentionHours(), p.redactedCacheHours(), p.neverExportedHoldHours(),
                p.finalApprovalRequired());
    }

    private static List<LabelValue> policyRows(SensitivePolicy p) {
        String types;
        if (p.types() == null) {
            types = (p.enabledCount() == null ? "Some" : p.enabledCount()) + " types (their names were not recorded at the time)";
        } else if (p.types().isEmpty()) {
            types = "None";
        } else {
            types = p.types().stream().sorted(SensitiveTypeNames.order()).map(SensitiveTypeNames::name)
                    .collect(Collectors.joining(", "));
        }
        String packs = p.packs().isEmpty() ? "None" : p.packs().stream().map(PolicyHistory::packName).sorted()
                .collect(Collectors.joining(", "));
        // Only kinds that are on have a style in force.
        String kept = p.keepLastFour().stream().filter(t -> p.types() == null || p.types().contains(t))
                .sorted(SensitiveTypeNames.order()).map(SensitiveTypeNames::name).collect(Collectors.joining(", "));
        return List.of(new LabelValue("Sensitive types found and redacted", types),
                new LabelValue("Sent to a person", PolicyHistory.reviewModeWords(p.reviewMode())),
                new LabelValue("Redacted without review at",
                        p.threshold() == null ? "Not recorded" : PolicyHistory.percent(p.threshold()) + " confidence or higher"),
                new LabelValue("Labeled last-four SSN treated as sensitive",
                        p.maskSsnLastFour() == null ? "Not recorded" : PolicyHistory.yesNo(p.maskSsnLastFour())),
                new LabelValue("Regulation packs in force", packs),
                new LabelValue("Style", kept.isEmpty() ? "Every value is removed in full"
                        : "Last 4 digits kept for: " + kept + ". Every other value is removed in full"));
    }

    /**
     * Task 0020 B4: the period's Reports numbers (cases first delivered in the period); the time saved uses the
     * minutes per page as it stood at the period end.
     */
    static List<ValueRow> didForYouRows(OperationalReportSnapshot.TimeSaved t, double minutesPerPage) {
        Integer percent = TimeSavedRule.percentRoundedDown(t.casesNoPerson(), t.casesDelivered());
        return List.of(
                new ValueRow("Handled without a person", number(t.casesNoPerson()), percent == null ? "—" : percent + "%"),
                new ValueRow("Pages checked (in " + number(t.documentsChecked())
                        + (t.documentsChecked() == 1 ? " document)" : " documents)"), number(t.pagesChecked()), null),
                new ValueRow("Sensitive items redacted", number(t.itemsRedacted()), null),
                new ValueRow("Review time saved (estimate)",
                        TimeSavedRule.timeWords(TimeSavedRule.minutesSaved(t.pagesNoPerson(), minutesPerPage)), null));
    }

    static String didForYouNote(double minutesPerPage) {
        String m = TimeSavedRule.minutesPerPageWords(minutesPerPage);
        return "Estimate: pages in cases that needed no person × " + m + (minutesPerPage == 1.0 ? " minute" : " minutes")
                + " per page, rounded down. Pages a person reviewed count as zero.";
    }

    private static String number(long n) {
        return String.format(Locale.US, "%,d", n);
    }

    /**
     * Minutes per page at {@code end}, from the {@code REPORTING_CONFIGURATION_CHANGED} history like the other
     * settings: the latest change before the end that records it; otherwise the value the first later change that
     * records it replaced; otherwise no change since, so the stored setting ({@code current}).
     */
    private double minutesPerPageAt(TenantId tenantId, Instant end, double current) {
        String action = AuditAction.REPORTING_CONFIGURATION_CHANGED.name();
        Optional<AuditRecordSummary> latest = readPort.latestSuccess(tenantId, action, end);
        if (latest.isPresent()) {
            Double saved = PolicyHistory.minutesPerPage(latest.get().detail(), false);
            if (saved != null) return saved;
        }
        for (AuditRecordSummary later : readPort.successes(tenantId, action, end, Instant.now(clock).plusSeconds(1))) {
            Double before = PolicyHistory.minutesPerPage(later.detail(), true);
            if (before != null) return before;
        }
        return current;
    }

    static String retentionNote(long stillWithinKeep, int originalHours, String endWords) {
        String first = "Each deletion removes the original together with its extracted text and its text recognition output.";
        if (stillWithinKeep == 0) {
            return first + " No case was still within its keep time on " + endWords + ".";
        }
        return first + " " + stillWithinKeep + (stillWithinKeep == 1 ? " case" : " cases")
                + " delivered in the last " + originalHours + " hours of the period "
                + (stillWithinKeep == 1 ? "was still within its keep time" : "were still within their keep time")
                + " on " + endWords + "; " + (stillWithinKeep == 1 ? "it is" : "they are")
                + " counted in the next period's report.";
    }

    /** Whole percent; "—" when there is nothing to divide by. */
    static String share(long part, long whole) {
        if (whole <= 0) return "—";
        return Math.round(part * 100.0 / whole) + "%";
    }

    /** "Jul 1 – Sep 30, 2026", or "Dec 1, 2025 – Feb 28, 2026" across years. */
    static String periodWords(CompliancePeriod period) {
        LocalDate from = period.from();
        LocalDate to = period.to();
        if (from.equals(to)) return DAY_YEAR.format(from);
        if (from.getYear() == to.getYear()) return DAY.format(from) + " – " + DAY_YEAR.format(to);
        return DAY_YEAR.format(from) + " – " + DAY_YEAR.format(to);
    }

    /** {@code compliance-report-<entity-slug>-<from>-<to>.pdf}; the slug is already [a-z0-9-] only. */
    static String fileName(Tenant tenant, CompliancePeriod period) {
        String slug = tenant.slug() == null ? "entity" : tenant.slug().replaceAll("[^a-z0-9-]", "");
        if (slug.isBlank()) slug = "entity";
        return "compliance-report-" + slug + "-" + period.from() + "-" + period.to() + ".pdf";
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}

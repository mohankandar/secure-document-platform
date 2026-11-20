package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.csv.CsvCells;
import com.securedoc.platform.api.dto.DashboardColumnResponse;
import com.securedoc.platform.api.dto.DashboardIdentifierResponse;
import com.securedoc.platform.api.dto.DashboardItemResponse;
import com.securedoc.platform.api.dto.DashboardOverviewResponse;
import com.securedoc.platform.api.dto.OperationalReportsResponse;
import com.securedoc.platform.api.dto.OperationsDashboardResponse;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.application.operations.model.DashboardStage;
import com.securedoc.platform.application.port.in.GetOperationalReportsQuery;
import com.securedoc.platform.application.port.in.GetOperationsDashboardQuery;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {
    private final GetOperationsDashboardQuery dashboardQuery;
    private final GetOperationalReportsQuery reportsQuery;
    private final RequestContextResolver contextResolver;
    private final AuditService auditService;
    private final ProductAuthorizationPolicy authorizationPolicy;

    public OperationsController(GetOperationsDashboardQuery dashboardQuery,
                                GetOperationalReportsQuery reportsQuery,
                                RequestContextResolver contextResolver,
                                AuditService auditService,
                                ProductAuthorizationPolicy authorizationPolicy) {
        this.dashboardQuery = dashboardQuery;
        this.reportsQuery = reportsQuery;
        this.contextResolver = contextResolver;
        this.auditService = auditService;
        this.authorizationPolicy = authorizationPolicy;
    }

    @GetMapping("/dashboard")
    @RequireCapability(UserCapability.DASHBOARD_ACCESS)
    @Transactional(readOnly = true)
    public ResponseEntity<OperationsDashboardResponse> dashboard(
            @RequestParam(defaultValue = "ALL") String stage,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "25") int limit,
            @RequestParam(defaultValue = "receivedAt") String sort,
            @RequestParam(defaultValue = "desc") String direction,
            HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        DashboardStage parsedStage;
        try {
            parsedStage = DashboardStage.valueOf(stage.trim().toUpperCase());
        } catch (Exception ignored) {
            parsedStage = DashboardStage.ALL;
        }
        var page = dashboardQuery.execute(principal, parsedStage, search, offset, limit, sort,
                !"asc".equalsIgnoreCase(direction));
        var overview = page.overview();
        return ResponseEntity.ok(new OperationsDashboardResponse(
                new DashboardOverviewResponse(overview.total(), overview.processing(), overview.needsReview(),
                        overview.needsApproval(), overview.exported(), overview.technicalAttention(), overview.rejected()),
                page.columns().stream().map(c -> new DashboardColumnResponse(
                        c.key(), c.label(), c.kind(), c.primary(), c.searchable(), c.defaultVisible(), c.displayOrder())).toList(),
                page.items().stream().map(item -> new DashboardItemResponse(
                        item.packageId().toString(), item.packageType(), item.status(), item.stage().name(), item.attention(),
                        item.identifiers().stream().map(i -> new DashboardIdentifierResponse(
                                i.key(), i.label(), i.value(), i.primary(), i.displayOrder())).toList(),
                        item.documentCount(), item.successfulExportCycles(), item.lastExportedAt(), item.originalDeleteAt(),
                        item.technicalHoldUntil(), item.createdAt(), item.updatedAt())).toList(),
                page.totalItems(), page.offset(), page.limit()));
    }

    @GetMapping("/reports")
    @RequireCapability(UserCapability.REPORT_EXECUTIVE_VIEW)
    @Transactional(readOnly = true)
    public ResponseEntity<OperationalReportsResponse> reports(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "ALL") String source,
            @RequestParam(defaultValue = "ALL") String outcome,
            HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var report = reportsQuery.execute(principal, from, to, source, outcome);
        return ResponseEntity.ok(toResponse(report,
                authorizationPolicy.has(principal, UserCapability.REPORT_OPERATIONAL_VIEW),
                authorizationPolicy.has(principal, UserCapability.TENANT_POLICY_MANAGE)));
    }

    @GetMapping(value = "/reports/export", produces = "text/csv")
    @RequireCapability(UserCapability.REPORT_EXPORT)
    @Transactional
    public ResponseEntity<byte[]> exportReports(
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "ALL") String source,
            @RequestParam(defaultValue = "ALL") String outcome,
            HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var report = reportsQuery.execute(principal, from, to, source, outcome);
        String csv = "metric,value\r\n" +
                row("Generated at", report.generatedAt(), "") +
                row("Period from", report.period().from(), "") +
                row("Period to", report.period().to(), "") +
                row("Source filter", report.period().source(), "") +
                row("Outcome filter", report.period().outcome(), "") +
                row("Period batches", report.executive().batches(), "") +
                row("Documents", report.executive().documents(), "") +
                row("Pages", report.executive().pages(), "") +
                row("Manual upload packages", report.intake().manual(), "") +
                row("API packages", report.intake().api(), "") +
                row("Watched folder packages", report.intake().watchedFolder(), "") +
                row("Scanner intake packages", report.intake().scanner(), "") +
                row("Completed packages", report.executive().completed(), "") +
                row("Delivered during period", report.executive().delivered(), "") +
                row("Selected-period intake delivered by period end", report.executive().periodIntakeDelivered(), "") +
                row("Completion rate", report.executive().completionRate(), "%") +
                row("Delivery throughput vs intake", report.executive().deliveryRate(), "%") +
                row("Selected-period intake delivery conversion", report.executive().periodIntakeDeliveryRate(), "%") +
                row("Straight-through rate", report.executive().straightThroughRate(), "%") +
                row("Review rate", report.executive().reviewRate(), "%") +
                row("Median turnaround hours", report.timing().medianTurnaroundHours(), "") +
                row("P95 turnaround hours", report.timing().p95TurnaroundHours(), "") +
                row("Median active processing hours", report.timing().medianActiveProcessingHours(), "") +
                row("P95 active processing hours", report.timing().p95ActiveProcessingHours(), "") +
                row("Median review wait hours", report.timing().medianReviewWaitHours(), "") +
                row("Median review handling hours", report.timing().medianReviewHandlingHours(), "") +
                row("Turnaround samples", report.timing().turnaroundSamples(), "") +
                row("Active processing samples", report.timing().activeProcessingSamples(), "") +
                row("Current backlog", report.executive().currentBacklog(), "") +
                row("Backlog under 1 hour", report.backlog().underOneHour(), "") +
                row("Backlog 1-4 hours", report.backlog().oneToFourHours(), "") +
                row("Backlog 4-24 hours", report.backlog().fourToTwentyFourHours(), "") +
                row("Backlog 1-3 days", report.backlog().oneToThreeDays(), "") +
                row("Backlog over 3 days", report.backlog().overThreeDays(), "") +
                row("Current attention", report.executive().currentAttention(), "") +
                row("Reprocessed packages", report.usage().reprocessedPackages(), "") +
                row("Delivery SLA target minutes", report.serviceLevel().deliveryTargetMinutes(), "") +
                row("Delivery within target rate", report.serviceLevel().deliveryWithinTargetRate(), "%") +
                row("Delivery SLA target percent", report.serviceLevel().deliveryTargetPercent(), "%") +
                row("Delivery SLA breaches", report.serviceLevel().deliveryBreaches(), "") +
                row("Straight-through target percent", report.serviceLevel().straightThroughTargetPercent(), "%") +
                row("Review wait target minutes", report.serviceLevel().reviewWaitTargetMinutes(), "") +
                row("Review wait target percent", report.serviceLevel().reviewWaitTargetPercent(), "%") +
                row("Review wait within target rate", report.serviceLevel().reviewWithinTargetRate(), "%");
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.REPORT_CSV_EXPORTED,
                "OperationalReport", report.period().from() + "/" + report.period().to(),
                contextResolver.resolveCorrelationId(request), "Aggregate report CSV exported; source="
                        + report.period().source() + "; outcome=" + report.period().outcome());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=secure-document-report.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    /** One "label,value" line; the value is neutralised so a spreadsheet never reads it as a formula (task 0017). */
    static String row(String label, Object value, String suffix) {
        return label + "," + CsvCells.neutralise(String.valueOf(value) + suffix) + "\r\n";
    }

    private OperationalReportsResponse toResponse(com.securedoc.platform.application.operations.model.OperationalReportSnapshot report,
                                                   boolean includeOperations, boolean includeBusiness) {
        return new OperationalReportsResponse(
                report.generatedAt(),
                new OperationalReportsResponse.Period(report.period().from(), report.period().to(),
                        report.period().previousFrom(), report.period().previousTo(), report.period().source(),
                        report.period().outcome()),
                new OperationalReportsResponse.Executive(
                        report.executive().batches(), report.executive().previousBatches(),
                        report.executive().documents(), report.executive().pages(),
                        report.executive().completed(), report.executive().previousCompleted(),
                        report.executive().delivered(), report.executive().previousDelivered(),
                        report.executive().periodIntakeDelivered(), report.executive().previousPeriodIntakeDelivered(),
                        report.executive().completionRate(), report.executive().deliveryRate(), report.executive().periodIntakeDeliveryRate(),
                        report.executive().medianDeliveryHours(), report.executive().p95DeliveryHours(),
                        report.executive().straightThroughRate(), report.executive().reviewRate(),
                        report.executive().currentBacklog(), report.executive().currentAttention(),
                        report.executive().oldestBacklogHours(),
                        report.executive().trend().stream().map(t -> new OperationalReportsResponse.TrendPoint(
                                t.bucket(), t.received(), t.delivered())).toList()),
                new OperationalReportsResponse.Intake(report.intake().totalBatches(), report.intake().manual(),
                        report.intake().api(), report.intake().watchedFolder(), report.intake().scanner(), report.intake().waitingForTrigger(),
                        includeOperations ? report.intake().duplicateObservations() : 0,
                        includeOperations ? report.intake().rejectedObservations() : 0),
                new OperationalReportsResponse.Processing(includeOperations ? report.processing().processing() : 0,
                        report.processing().needsReview(),
                        report.processing().needsApproval(), report.processing().completed(), report.processing().technicalAttention(),
                        includeOperations ? report.processing().rejected() : 0),
                new OperationalReportsResponse.Export(report.export().exportedPackages(),
                        includeOperations ? report.export().successfulExportCycles() : 0,
                        report.export().pendingFinalApproval()),
                new OperationalReportsResponse.Retention(
                        includeOperations ? report.retention().originalsDueWithin24Hours() : 0,
                        includeOperations ? report.retention().redactedCopiesDueWithin24Hours() : 0,
                        includeOperations ? report.retention().technicalHoldsDueWithin24Hours() : 0,
                        includeOperations ? report.retention().purgedPackages() : 0,
                        includeOperations ? report.retention().originalsOverdue() : 0,
                        includeOperations ? report.retention().redactedCopiesOverdue() : 0),
                new OperationalReportsResponse.Timing(
                        report.timing().medianTurnaroundHours(), report.timing().p95TurnaroundHours(),
                        report.timing().medianActiveProcessingHours(), report.timing().p95ActiveProcessingHours(),
                        report.timing().medianReviewWaitHours(), report.timing().medianReviewHandlingHours(),
                        report.timing().turnaroundSamples(), report.timing().activeProcessingSamples(),
                        report.timing().reviewWaitSamples(), report.timing().reviewHandlingSamples()),
                new OperationalReportsResponse.Backlog(
                        report.backlog().underOneHour(), report.backlog().oneToFourHours(),
                        report.backlog().fourToTwentyFourHours(), report.backlog().oneToThreeDays(),
                        report.backlog().overThreeDays(), report.backlog().oldestHours()),
                new OperationalReportsResponse.Usage(
                        report.usage().packages(), report.usage().documents(), report.usage().pages(),
                        report.usage().averageDocumentsPerPackage(), report.usage().averagePagesPerPackage(),
                        report.usage().reviewedPackages(), report.usage().straightThroughPackages(),
                        report.usage().reprocessedPackages()),
                new OperationalReportsResponse.ServiceLevel(
                        report.serviceLevel().deliveryTargetMinutes(), report.serviceLevel().deliveryTargetPercent(),
                        report.serviceLevel().deliveryWithinTargetRate(), report.serviceLevel().deliveryEligible(),
                        report.serviceLevel().deliveryBreaches(), report.serviceLevel().straightThroughTargetPercent(),
                        report.serviceLevel().straightThroughActualPercent(), report.serviceLevel().reviewWaitTargetMinutes(),
                        report.serviceLevel().reviewWaitTargetPercent(), report.serviceLevel().reviewWithinTargetRate(), report.serviceLevel().reviewEligible(),
                        report.serviceLevel().reviewBreaches()),
                includeBusiness ? report.dimensions().stream().map(d -> new OperationalReportsResponse.DimensionBreakdown(
                        d.key(), d.label(), d.values().stream().map(v -> new OperationalReportsResponse.DimensionValue(
                                v.value(), v.packages(), v.delivered(), v.deliveryRate(), v.medianTurnaroundHours())).toList())).toList()
                        : java.util.List.of(),
                timeSaved(report.timeSaved())
        );
    }

    /** Same capability as the headline (REPORT_EXECUTIVE_VIEW): counts only, never a found value or file name. */
    static OperationalReportsResponse.TimeSaved timeSaved(
            com.securedoc.platform.application.operations.model.OperationalReportSnapshot.TimeSaved t) {
        return new OperationalReportsResponse.TimeSaved(t.casesDelivered(), t.casesNoPerson(), t.percentNoPerson(),
                t.pagesChecked(), t.documentsChecked(), t.itemsRedacted(), t.itemTypes(), t.pagesNoPerson(),
                t.minutesPerPage(), t.minutesSaved());
    }
}

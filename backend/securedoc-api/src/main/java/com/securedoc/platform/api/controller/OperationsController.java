package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
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
                authorizationPolicy.has(principal, UserCapability.REPORT_OPERATIONAL_VIEW)));
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
                "Generated at," + report.generatedAt() + "\r\n" +
                "Period from," + report.period().from() + "\r\n" +
                "Period to," + report.period().to() + "\r\n" +
                "Source filter," + report.period().source() + "\r\n" +
                "Outcome filter," + report.period().outcome() + "\r\n" +
                "Period batches," + report.executive().batches() + "\r\n" +
                "Documents," + report.executive().documents() + "\r\n" +
                "Pages," + report.executive().pages() + "\r\n" +
                "Completed packages," + report.executive().completed() + "\r\n" +
                "Delivered packages," + report.executive().delivered() + "\r\n" +
                "Completion rate," + report.executive().completionRate() + "%\r\n" +
                "Delivery rate," + report.executive().deliveryRate() + "%\r\n" +
                "Current backlog," + report.executive().currentBacklog() + "\r\n" +
                "Current attention," + report.executive().currentAttention() + "\r\n";
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.REPORT_CSV_EXPORTED,
                "OperationalReport", report.period().from() + "/" + report.period().to(),
                contextResolver.resolveCorrelationId(request), "Aggregate report CSV exported; source="
                        + report.period().source() + "; outcome=" + report.period().outcome());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=secure-document-report.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private OperationalReportsResponse toResponse(com.securedoc.platform.application.operations.model.OperationalReportSnapshot report,
                                                   boolean includeOperations) {
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
                        report.executive().completionRate(), report.executive().deliveryRate(),
                        report.executive().medianDeliveryHours(), report.executive().p95DeliveryHours(),
                        report.executive().straightThroughRate(), report.executive().reviewRate(),
                        report.executive().currentBacklog(), report.executive().currentAttention(),
                        report.executive().oldestBacklogHours(),
                        report.executive().trend().stream().map(t -> new OperationalReportsResponse.TrendPoint(
                                t.bucket(), t.received(), t.delivered())).toList()),
                new OperationalReportsResponse.Intake(report.intake().totalBatches(), report.intake().manual(),
                        report.intake().api(), report.intake().watchedFolder(), report.intake().waitingForTrigger(),
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
                        includeOperations ? report.retention().redactedCopiesOverdue() : 0)
        );
    }
}

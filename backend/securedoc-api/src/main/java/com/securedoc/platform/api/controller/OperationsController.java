package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.dto.DashboardColumnResponse;
import com.securedoc.platform.api.dto.DashboardIdentifierResponse;
import com.securedoc.platform.api.dto.DashboardItemResponse;
import com.securedoc.platform.api.dto.DashboardOverviewResponse;
import com.securedoc.platform.api.dto.OperationalReportsResponse;
import com.securedoc.platform.api.dto.OperationsDashboardResponse;
import com.securedoc.platform.application.operations.model.DashboardStage;
import com.securedoc.platform.application.port.in.GetOperationalReportsQuery;
import com.securedoc.platform.application.port.in.GetOperationsDashboardQuery;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {
    private final GetOperationsDashboardQuery dashboardQuery;
    private final GetOperationalReportsQuery reportsQuery;
    private final RequestContextResolver contextResolver;

    public OperationsController(GetOperationsDashboardQuery dashboardQuery,
                                GetOperationalReportsQuery reportsQuery,
                                RequestContextResolver contextResolver) {
        this.dashboardQuery = dashboardQuery;
        this.reportsQuery = reportsQuery;
        this.contextResolver = contextResolver;
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
    @Transactional(readOnly = true)
    public ResponseEntity<OperationalReportsResponse> reports(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var report = reportsQuery.execute(principal);
        return ResponseEntity.ok(new OperationalReportsResponse(
                report.generatedAt(),
                new OperationalReportsResponse.Intake(report.intake().totalBatches(), report.intake().manual(),
                        report.intake().api(), report.intake().watchedFolder(), report.intake().waitingForTrigger(),
                        report.intake().duplicateObservations(), report.intake().rejectedObservations()),
                new OperationalReportsResponse.Processing(report.processing().processing(), report.processing().needsReview(),
                        report.processing().needsApproval(), report.processing().completed(), report.processing().technicalAttention(),
                        report.processing().rejected()),
                new OperationalReportsResponse.Export(report.export().exportedPackages(), report.export().successfulExportCycles(),
                        report.export().pendingFinalApproval()),
                new OperationalReportsResponse.Retention(report.retention().originalsDueWithin24Hours(),
                        report.retention().redactedCopiesDueWithin24Hours(), report.retention().technicalHoldsDueWithin24Hours(),
                        report.retention().purgedPackages())
        ));
    }
}

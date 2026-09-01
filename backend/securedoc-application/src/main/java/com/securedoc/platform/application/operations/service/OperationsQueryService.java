package com.securedoc.platform.application.operations.service;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.operations.model.DashboardPage;
import com.securedoc.platform.application.operations.model.DashboardStage;
import com.securedoc.platform.application.operations.model.OperationalReportSnapshot;
import com.securedoc.platform.application.port.in.GetOperationalReportsQuery;
import com.securedoc.platform.application.port.in.GetOperationsDashboardQuery;
import com.securedoc.platform.application.port.out.OperationsReadPort;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/** Validates dashboard query inputs while keeping persistence/reporting details at the edge. */
public final class OperationsQueryService implements GetOperationsDashboardQuery, GetOperationalReportsQuery {
    private static final Set<String> SORT_FIELDS = Set.of("receivedAt", "updatedAt", "stage");
    private final OperationsReadPort readPort;

    public OperationsQueryService(OperationsReadPort readPort) {
        this.readPort = Objects.requireNonNull(readPort);
    }

    @Override
    public DashboardPage execute(AuthenticatedPrincipal principal, DashboardStage stage, String search,
                                 int offset, int limit, String sortField, boolean descending) {
        Objects.requireNonNull(principal);
        DashboardStage safeStage = stage == null ? DashboardStage.ALL : stage;
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(100, limit <= 0 ? 25 : limit));
        String safeSearch = search == null ? "" : search.trim();
        if (safeSearch.length() > 120) safeSearch = safeSearch.substring(0, 120);
        String safeSort = SORT_FIELDS.contains(sortField) ? sortField : "receivedAt";
        return readPort.loadDashboard(principal.tenantId(), safeStage, safeSearch,
                safeOffset, safeLimit, safeSort, descending);
    }

    @Override
    public OperationalReportSnapshot execute(AuthenticatedPrincipal principal, Instant from, Instant to,
                                             String source, String outcome) {
        Objects.requireNonNull(principal);
        Instant safeTo = to == null ? Instant.now() : to;
        Instant safeFrom = from == null ? safeTo.minusSeconds(30L * 24 * 60 * 60) : from;
        if (!safeFrom.isBefore(safeTo)) throw new IllegalArgumentException("Report start must be before end");
        if (safeFrom.isBefore(safeTo.minusSeconds(366L * 24 * 60 * 60))) {
            throw new IllegalArgumentException("Report range cannot exceed 366 days");
        }
        String safeSource = source == null ? "ALL" : source.trim().toUpperCase();
        if (!Set.of("ALL", "MANUAL_UPLOAD", "API_SUBMISSION", "FOLDER_INTAKE").contains(safeSource)) {
            throw new IllegalArgumentException("Unsupported report source");
        }
        String safeOutcome = outcome == null ? "ALL" : outcome.trim().toUpperCase();
        if (!Set.of("ALL", "PROCESSING", "NEEDS_REVIEW", "NEEDS_APPROVAL", "COMPLETED",
                "TECHNICAL_ATTENTION", "REJECTED").contains(safeOutcome)) {
            throw new IllegalArgumentException("Unsupported report outcome");
        }
        return readPort.loadReports(principal.tenantId(), Instant.now(), safeFrom, safeTo, safeSource, safeOutcome);
    }
}

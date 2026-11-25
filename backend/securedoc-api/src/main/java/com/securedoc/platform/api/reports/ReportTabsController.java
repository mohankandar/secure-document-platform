package com.securedoc.platform.api.reports;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.platform.PipelineQueueController;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.operations.daily.DailyReport;
import com.securedoc.platform.application.operations.daily.DailyReportService;
import com.securedoc.platform.application.operations.model.ReportScope;
import com.securedoc.platform.application.port.in.GetOperationalReportsQuery;
import com.securedoc.platform.application.port.out.EntityTimeZonePort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

/** Task 0081: the Reports tabs. Always of the caller's own entity. */
@RestController
@RequestMapping("/api/v1/operations/reports")
public class ReportTabsController {
    private final DailyReportService daily;
    private final GetOperationalReportsQuery reports;
    private final PipelineQueueController queue;
    private final ProductAuthorizationPolicy authorization;
    private final RequestContextResolver contextResolver;
    private final AuditService audit;
    private final EntityTimeZonePort zones;

    public ReportTabsController(DailyReportService daily, GetOperationalReportsQuery reports, PipelineQueueController queue,
                                ProductAuthorizationPolicy authorization, RequestContextResolver contextResolver,
                                AuditService audit, EntityTimeZonePort zones) {
        this.daily = daily;
        this.reports = reports;
        this.queue = queue;
        this.authorization = authorization;
        this.contextResolver = contextResolver;
        this.audit = audit;
        this.zones = zones;
    }

    /** The source systems the filter offers. */
    @GetMapping("/sources")
    @RequireCapability(UserCapability.REPORT_EXECUTIVE_VIEW)
    @Transactional(readOnly = true)
    public List<DailyReport.SourceName> sources(HttpServletRequest request) {
        return daily.sources(contextResolver.resolvePrincipal(request));
    }

    @GetMapping("/daily")
    @RequireCapability(UserCapability.REPORT_EXECUTIVE_VIEW)
    @Transactional(readOnly = true)
    public DailyReport daily(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                             @RequestParam(defaultValue = "ALL") String sourceSystem,
                             @RequestParam(defaultValue = "ALL") String level, HttpServletRequest request) {
        return daily.report(contextResolver.resolvePrincipal(request), from, to, new ReportScope(sourceSystem, level));
    }

    /** Task 0078's stage view for the Operations tab: this entity's, never all of them. */
    @GetMapping("/queue")
    @RequireCapability(UserCapability.REPORT_OPERATIONAL_VIEW)
    public PipelineQueueController.Queue queue(HttpServletRequest request) {
        return queue.of(contextResolver.resolvePrincipal(request).tenantId());
    }

    @GetMapping(value = "/overview/export", produces = "text/csv")
    @RequireCapability(UserCapability.REPORT_EXPORT)
    @Transactional
    public ResponseEntity<byte[]> exportOverview(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                                 @RequestParam(defaultValue = "ALL") String sourceSystem,
                                                 @RequestParam(defaultValue = "ALL") String level, HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        ReportScope scope = new ReportScope(sourceSystem, level);
        var report = reports.execute(principal, from, to, "ALL", "ALL", scope);
        return csv(principal, "overview", report.period().from(), report.period().to(), scope,
                ReportCsv.overview(report, scope, zone(principal), name(principal, scope)), request);
    }

    @GetMapping(value = "/daily/export", produces = "text/csv")
    @RequireCapability(UserCapability.REPORT_EXPORT)
    @Transactional
    public ResponseEntity<byte[]> exportDaily(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                              @RequestParam(defaultValue = "ALL") String sourceSystem,
                                              @RequestParam(defaultValue = "ALL") String level, HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        ReportScope scope = new ReportScope(sourceSystem, level);
        DailyReport report = daily.report(principal, from, to, scope);
        return csv(principal, "daily", report.from(), report.to(), scope,
                ReportCsv.daily(report, scope, zone(principal), name(principal, scope)), request);
    }

    @GetMapping(value = "/operations/export", produces = "text/csv")
    @RequireCapability(UserCapability.REPORT_EXPORT)
    @Transactional
    public ResponseEntity<byte[]> exportOperations(@RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to,
                                                   @RequestParam(defaultValue = "ALL") String sourceSystem,
                                                   @RequestParam(defaultValue = "ALL") String level, HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        if (!authorization.has(principal, UserCapability.REPORT_OPERATIONAL_VIEW)) {
            throw new AccessDeniedException("Operations is not part of this person's reports");
        }
        ReportScope scope = new ReportScope(sourceSystem, level);
        var report = reports.execute(principal, from, to, "ALL", "ALL", scope);
        return csv(principal, "operations", report.period().from(), report.period().to(), scope,
                ReportCsv.operations(report, queue.of(principal.tenantId()), scope, zone(principal), name(principal, scope)), request);
    }

    private ResponseEntity<byte[]> csv(AuthenticatedPrincipal principal, String tab, Instant from, Instant to, ReportScope scope,
                                       String csv, HttpServletRequest request) {
        audit.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.REPORT_CSV_EXPORTED, "OperationalReport",
                from + "/" + to, contextResolver.resolveCorrelationId(request),
                "Aggregate report CSV exported; tab=" + tab + "; sourceSystem=" + scope.sourceSystem() + "; level=" + scope.level());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=lexavault-report-" + tab + ".csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csv.getBytes(StandardCharsets.UTF_8));
    }

    private String zone(AuthenticatedPrincipal principal) {
        return zones.effective(principal.tenantId()).map(ZoneId::getId).orElse("not set");
    }

    /** A connection's name; a key that is not this entity's says nothing. */
    private String name(AuthenticatedPrincipal principal, ReportScope scope) {
        return scope.everySource() ? null : daily.sources(principal).stream().filter(s -> s.key().equals(scope.sourceSystem()))
                .map(DailyReport.SourceName::name).findFirst().orElse("not known");
    }
}

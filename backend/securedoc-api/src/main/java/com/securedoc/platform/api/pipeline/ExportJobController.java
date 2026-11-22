package com.securedoc.platform.api.pipeline;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.pipeline.ExportJobService;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Export jobs, API only (task 0027; task 0028 added resend all, grouped IT alerts and the network alert; the screens
 * are task 0029). Codes, ids and times only.
 *
 * <ul>
 *   <li>"Waiting for you", resend and resend all: {@code EXPORT_RESEND} (Processor and Supervisor).</li>
 *   <li>The IT error queue, destination states and the IT retries: the existing technical capabilities.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1")
public class ExportJobController {
    private final ExportJobService service;
    private final RequestContextResolver context;

    public ExportJobController(ExportJobService service, RequestContextResolver context) {
        this.service = service;
        this.context = context;
    }

    /** Grouped by destination, with the number of cases per code. */
    @GetMapping("/export-jobs/waiting-for-you")
    @RequireCapability(UserCapability.EXPORT_RESEND)
    public WaitingForYouResponse waitingForYou(HttpServletRequest request) {
        List<ExportJobService.WaitingGroup> groups = service.waitingForYou(context.resolvePrincipal(request).tenantId());
        return new WaitingForYouResponse(groups.stream().mapToInt(ExportJobService.WaitingGroup::total).sum(), groups);
    }

    /** Resend single cases now; each is one attempt. */
    @PostMapping("/export-jobs/resend")
    @RequireCapability(UserCapability.EXPORT_RESEND)
    public List<ExportJobService.JobResult> resend(@RequestBody JobIdsRequest body, HttpServletRequest request) {
        var principal = context.resolvePrincipal(request);
        return service.resend(principal.tenantId(), ids(body), principal.actorId(),
                context.resolveCorrelationId(request));
    }

    /** Resend all for one destination: its waiting cases back to the queue, one attempt each, in their order. */
    @PostMapping("/export-jobs/resend-all")
    @RequireCapability(UserCapability.EXPORT_RESEND)
    public ResendAllResponse resendAll(@RequestBody DestinationRequest body, HttpServletRequest request) {
        var principal = context.resolvePrincipal(request);
        UUID destination = destinationId(body);
        List<UUID> queued = service.resendAll(principal.tenantId(), IntegrationConnectionId.of(destination),
                principal.actorId(), context.resolveCorrelationId(request));
        return new ResendAllResponse(destination, queued.size(), queued);
    }

    @GetMapping("/packages/{packageId}/export-jobs")
    @RequireCapability(UserCapability.DASHBOARD_ACCESS)
    public CaseExportResponse caseExport(@PathVariable String packageId, HttpServletRequest request) {
        var tenantId = context.resolvePrincipal(request).tenantId();
        PackageId id = packageId(packageId);
        return new CaseExportResponse(service.caseExportState(tenantId, id).name(), service.jobsOfCase(tenantId, id),
                service.timings());
    }

    /** The IT error queue, grouped: one alert per destination and code. */
    @GetMapping("/support/export-jobs/needs-it")
    @RequireCapability(UserCapability.TECHNICAL_EXCEPTION_VIEW)
    public NeedsItResponse needsIt(HttpServletRequest request) {
        List<ExportJobService.ItGroup> groups = service.needsItGroups(context.resolvePrincipal(request).tenantId());
        return new NeedsItResponse(groups.stream().mapToInt(ExportJobService.ItGroup::count).sum(), groups,
                service.timings());
    }

    @GetMapping("/support/export-destinations")
    @RequireCapability(UserCapability.TECHNICAL_EXCEPTION_VIEW)
    public List<ExportJobService.DestinationView> destinations(HttpServletRequest request) {
        return service.destinationStates(context.resolvePrincipal(request).tenantId());
    }

    /** "LexaVault can't reach its destinations" (LV-DLV-001, ALL_DESTINATIONS_UNREACHABLE), raised or not. */
    @GetMapping("/support/export-destinations/alert")
    @RequireCapability(UserCapability.TECHNICAL_EXCEPTION_VIEW)
    public ExportJobService.AlertView alert(HttpServletRequest request) {
        return service.reachabilityAlert(context.resolvePrincipal(request).tenantId());
    }

    /** IT retry: NEEDS_IT jobs back to READY (attempts reset); optionally their destinations back to WORKING. */
    @PostMapping("/support/export-jobs/retry")
    @RequireCapability(UserCapability.TECHNICAL_RETRY)
    public List<ExportJobService.JobResult> itRetry(@RequestBody JobIdsRequest body, HttpServletRequest request) {
        var principal = context.resolvePrincipal(request);
        return service.itRetry(principal.tenantId(), ids(body), Boolean.TRUE.equals(body.resumeDestinations()),
                principal.actorId(), context.resolveCorrelationId(request));
    }

    /** IT retries one group: every NEEDS_IT job of the destination with the code. 409 when there is none. */
    @PostMapping("/support/export-jobs/retry-group")
    @RequireCapability(UserCapability.TECHNICAL_RETRY)
    public List<ExportJobService.JobResult> retryGroup(@RequestBody GroupRequest body, HttpServletRequest request) {
        var principal = context.resolvePrincipal(request);
        if (body == null || body.code() == null || !body.code().matches("LV-[A-Z]{3}-\\d{3}")) {
            throw new ValidationException(LvErrorCode.CFG_001, "code is required");
        }
        UUID destination = body.destinationId() == null || body.destinationId().isBlank() ? null
                : uuid(body.destinationId(), "Invalid destination id");
        List<ExportJobService.JobResult> results = service.retryGroup(principal.tenantId(),
                destination == null ? null : IntegrationConnectionId.of(destination), body.code(), principal.actorId(),
                context.resolveCorrelationId(request));
        if (results.isEmpty()) {
            throw new ValidationException(LvErrorCode.CFG_011, "No export waits for IT with this destination and code");
        }
        return results;
    }

    @PostMapping("/support/export-destinations/{destinationId}/working")
    @RequireCapability(UserCapability.TECHNICAL_RETRY)
    public DestinationChange setWorking(@PathVariable String destinationId, HttpServletRequest request) {
        var principal = context.resolvePrincipal(request);
        UUID id = uuid(destinationId, "Invalid destination id");
        boolean changed = service.setDestinationWorking(principal.tenantId(), IntegrationConnectionId.of(id),
                principal.actorId(), context.resolveCorrelationId(request));
        if (!changed) {
            throw new ValidationException(LvErrorCode.CFG_011, "The destination is not paused or waiting for IT");
        }
        return new DestinationChange(id, "WORKING");
    }

    /**
     * Task 0029, Support's "Check now": runs the destination's health check at once. A paused destination whose check
     * passes is working again (its waiting jobs go out in order). Answers whether the check passed; LV-CFG-001 for a
     * destination that is not one of the tenant's.
     */
    @PostMapping("/support/export-destinations/{destinationId}/check")
    @RequireCapability(UserCapability.TECHNICAL_RETRY)
    public ExportJobService.CheckNow checkNow(@PathVariable String destinationId, HttpServletRequest request) {
        var principal = context.resolvePrincipal(request);
        UUID id = uuid(destinationId, "Invalid destination id");
        return service.checkDestinationNow(principal.tenantId(), IntegrationConnectionId.of(id), principal.actorId(),
                        context.resolveCorrelationId(request))
                .orElseThrow(() -> new ValidationException(LvErrorCode.CFG_001, "Unknown export destination"));
    }

    private static List<UUID> ids(JobIdsRequest body) {
        if (body == null || body.jobIds() == null || body.jobIds().isEmpty()) {
            throw new ValidationException(LvErrorCode.CFG_001, "jobIds is required");
        }
        return body.jobIds().stream().map(v -> uuid(v, "Invalid job id")).toList();
    }

    private static UUID destinationId(DestinationRequest body) {
        if (body == null || body.destinationId() == null || body.destinationId().isBlank()) {
            throw new ValidationException(LvErrorCode.CFG_001, "destinationId is required");
        }
        return uuid(body.destinationId(), "Invalid destination id");
    }

    private static UUID uuid(String value, String message) {
        try {
            return UUID.fromString(value);
        } catch (RuntimeException e) {
            throw new ValidationException(LvErrorCode.CFG_001, message);
        }
    }

    private static PackageId packageId(String value) {
        return PackageId.of(uuid(value, "Invalid package id"));
    }

    public record JobIdsRequest(List<String> jobIds, Boolean resumeDestinations) { }

    public record DestinationRequest(String destinationId) { }

    public record GroupRequest(String destinationId, String code) { }

    public record WaitingForYouResponse(int total, List<ExportJobService.WaitingGroup> destinations) { }

    public record ResendAllResponse(UUID destinationId, int queued, List<UUID> jobIds) { }

    /** Task 0036: with the timings the Support screen names (probe interval, retry span). */
    public record NeedsItResponse(int total, List<ExportJobService.ItGroup> groups, ExportJobService.ExportTimings timings) { }

    /** Task 0036: with the timings the case panel names (how often a paused destination is checked). */
    public record CaseExportResponse(String exportState, List<ExportJobService.JobView> jobs,
                                     ExportJobService.ExportTimings timings) { }

    public record DestinationChange(UUID destinationId, String state) { }
}

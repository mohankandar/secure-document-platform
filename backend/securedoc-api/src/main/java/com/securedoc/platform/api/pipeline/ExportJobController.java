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
 * Task 0027: export jobs, API only (the screens are task 0028). Codes, ids and times only.
 *
 * <ul>
 *   <li>"Waiting for you" and the bulk resend: the capability of today's manual send.</li>
 *   <li>The IT error queue, destination states and the IT retry: the existing technical capabilities.</li>
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

    @GetMapping("/export-jobs/waiting-for-you")
    @RequireCapability(UserCapability.PACKAGE_APPROVE_DELIVERY)
    public List<ExportJobService.JobView> waitingForYou(HttpServletRequest request) {
        return service.waitingForYou(context.resolvePrincipal(request).tenantId());
    }

    /** Resend many jobs at once; each is one attempt. */
    @PostMapping("/export-jobs/resend")
    @RequireCapability(UserCapability.PACKAGE_APPROVE_DELIVERY)
    public List<ExportJobService.JobResult> resend(@RequestBody JobIdsRequest body, HttpServletRequest request) {
        var principal = context.resolvePrincipal(request);
        return service.resend(principal.tenantId(), ids(body), principal.actorId(),
                context.resolveCorrelationId(request));
    }

    @GetMapping("/packages/{packageId}/export-jobs")
    @RequireCapability(UserCapability.DASHBOARD_ACCESS)
    public CaseExportResponse caseExport(@PathVariable String packageId, HttpServletRequest request) {
        var tenantId = context.resolvePrincipal(request).tenantId();
        PackageId id = packageId(packageId);
        return new CaseExportResponse(service.caseExportState(tenantId, id).name(), service.jobsOfCase(tenantId, id));
    }

    @GetMapping("/support/export-jobs/needs-it")
    @RequireCapability(UserCapability.TECHNICAL_EXCEPTION_VIEW)
    public List<ExportJobService.JobView> needsIt(HttpServletRequest request) {
        return service.needsIt(context.resolvePrincipal(request).tenantId());
    }

    @GetMapping("/support/export-destinations")
    @RequireCapability(UserCapability.TECHNICAL_EXCEPTION_VIEW)
    public List<ExportJobService.DestinationView> destinations(HttpServletRequest request) {
        return service.destinationStates(context.resolvePrincipal(request).tenantId());
    }

    /** IT retry: NEEDS_IT jobs back to READY (attempts reset); optionally their destinations back to WORKING. */
    @PostMapping("/support/export-jobs/retry")
    @RequireCapability(UserCapability.TECHNICAL_RETRY)
    public List<ExportJobService.JobResult> itRetry(@RequestBody JobIdsRequest body, HttpServletRequest request) {
        var principal = context.resolvePrincipal(request);
        return service.itRetry(principal.tenantId(), ids(body), Boolean.TRUE.equals(body.resumeDestinations()),
                principal.actorId(), context.resolveCorrelationId(request));
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

    private static List<UUID> ids(JobIdsRequest body) {
        if (body == null || body.jobIds() == null || body.jobIds().isEmpty()) {
            throw new ValidationException(LvErrorCode.CFG_001, "jobIds is required");
        }
        return body.jobIds().stream().map(v -> uuid(v, "Invalid job id")).toList();
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

    public record CaseExportResponse(String exportState, List<ExportJobService.JobView> jobs) { }

    public record DestinationChange(UUID destinationId, String state) { }
}

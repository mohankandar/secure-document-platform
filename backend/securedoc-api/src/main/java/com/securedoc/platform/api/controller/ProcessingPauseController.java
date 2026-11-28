package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.application.port.out.PipelineJobRepository;
import com.securedoc.platform.application.processing.service.AiServerOutage;
import com.securedoc.platform.application.processing.service.StagedProcessingService;
import com.securedoc.platform.api.security.UserCapability;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * Task 0116: the Cases banner while processing waits for the AI servers ("Processing paused since 21:40: AI server
 * unreachable · 37 cases waiting"). The time, a reason code and the entity's own count only.
 */
@RestController
@RequestMapping("/api/v1/operations")
public class ProcessingPauseController {

    /** @param pausedSince null while nothing waits */
    public record ProcessingPauseResponse(Instant pausedSince, String reasonCode, int casesWaiting) { }

    private final StagedProcessingService staged;
    private final PipelineJobRepository jobs;
    private final RequestContextResolver contextResolver;

    public ProcessingPauseController(StagedProcessingService staged, PipelineJobRepository jobs,
                                     RequestContextResolver contextResolver) {
        this.staged = staged;
        this.jobs = jobs;
        this.contextResolver = contextResolver;
    }

    @GetMapping("/processing-pause")
    @RequireCapability(UserCapability.DASHBOARD_ACCESS)
    public ResponseEntity<ProcessingPauseResponse> pause(HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        Instant since = staged.aiWorkPausedSince().orElse(null);
        if (since == null) return ResponseEntity.ok(new ProcessingPauseResponse(null, null, 0));
        return ResponseEntity.ok(new ProcessingPauseResponse(since, "AI_SERVER_UNREACHABLE",
                jobs.countCasesWaiting(principal.tenantId(), AiServerOutage.WAITING_FOR_AI_SERVER)));
    }
}

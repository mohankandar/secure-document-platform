package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireRole;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.port.in.FinalizePackageUseCase;
import com.securedoc.platform.application.port.in.TriggerPackageProcessingUseCase;
import com.securedoc.platform.application.processing.command.FinalizePackageCommand;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.processing.result.FinalizePackageResult;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/**
 * Controller for package workflow operations (process trigger, finalize).
 * Uses application use case ports, not infrastructure directly.
 */
@RestController
@RequestMapping("/api/v1/packages")
public class PackageWorkflowController {

    private final TriggerPackageProcessingUseCase triggerPackageProcessingUseCase;
    private final FinalizePackageUseCase finalizePackageUseCase;
    private final RequestContextResolver contextResolver;

    public PackageWorkflowController(TriggerPackageProcessingUseCase triggerPackageProcessingUseCase,
                                      FinalizePackageUseCase finalizePackageUseCase,
                                      RequestContextResolver contextResolver) {
        this.triggerPackageProcessingUseCase = triggerPackageProcessingUseCase;
        this.finalizePackageUseCase = finalizePackageUseCase;
        this.contextResolver = contextResolver;
    }

    /**
     * Internal/local continuation endpoint. Normal manual intake automatically starts processing
     * on submit; this endpoint remains for explicit recovery/continuation after review while the
     * asynchronous worker/orchestration model is still evolving. Long-running AI/redaction I/O
     * is intentionally not wrapped in a controller-level database transaction.
     * Supports: SUBMITTED, PROCESSING, PENDING_REVIEW, and IN_REVIEW where domain rules allow it.
     */
    @PostMapping("/{packageId}/process")
    @RequireRole({"APPROVER"})
    public ResponseEntity<Map<String, String>> triggerProcessing(
            @PathVariable String packageId,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        PackageId pkgId = PackageId.of(UUID.fromString(packageId));

        TriggerPackageProcessingCommand command = TriggerPackageProcessingCommand.of(
                pkgId, principal.tenantId(), principal.actorId(), correlationId);
        triggerPackageProcessingUseCase.execute(command);

        return ResponseEntity.ok(Map.of(
                "packageId", packageId,
                "status", "PROCESSING_TRIGGERED"));
    }

    /**
     * Finalizes/approves a package after verifying all documents are safe.
     * Validates: all docs PROCESSED, no unresolved findings, no unredacted confirmations.
     */
    @PostMapping("/{packageId}/finalize")
    @Transactional
    @RequireRole({"APPROVER"})
    public ResponseEntity<Map<String, String>> finalizePackage(
            @PathVariable String packageId,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        PackageId pkgId = PackageId.of(UUID.fromString(packageId));

        FinalizePackageCommand command = FinalizePackageCommand.of(
                pkgId, principal.tenantId(), principal.actorId(), correlationId);
        FinalizePackageResult result = finalizePackageUseCase.execute(command);

        return ResponseEntity.ok(Map.of(
                "packageId", result.packageId().toString(),
                "status", result.status().name()));
    }
}

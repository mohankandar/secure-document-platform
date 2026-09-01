package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.port.in.GetTechnicalExceptionQuery;
import com.securedoc.platform.application.port.in.RetryTechnicalFailureUseCase;
import com.securedoc.platform.application.technical.command.RetryTechnicalFailureCommand;
import com.securedoc.platform.application.technical.model.TechnicalExceptionDetail;
import com.securedoc.platform.domain.identity.PackageId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.env.Environment;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Safe technical-exception workflow for tenant IT and platform operations. */
@RestController
@RequestMapping("/api/v1/technical-exceptions")
public class TechnicalExceptionController {
    private final GetTechnicalExceptionQuery technicalExceptionQuery;
    private final RetryTechnicalFailureUseCase retryTechnicalFailureUseCase;
    private final RequestContextResolver contextResolver;
    private final ProductAuthorizationPolicy authorizationPolicy;
    private final Environment environment;

    public TechnicalExceptionController(GetTechnicalExceptionQuery technicalExceptionQuery,
                                        RetryTechnicalFailureUseCase retryTechnicalFailureUseCase,
                                        RequestContextResolver contextResolver,
                                        ProductAuthorizationPolicy authorizationPolicy,
                                        Environment environment) {
        this.technicalExceptionQuery = technicalExceptionQuery;
        this.retryTechnicalFailureUseCase = retryTechnicalFailureUseCase;
        this.contextResolver = contextResolver;
        this.authorizationPolicy = authorizationPolicy;
        this.environment = environment;
    }

    @GetMapping("/{packageId}")
    @RequireCapability(UserCapability.TECHNICAL_EXCEPTION_VIEW)
    public ResponseEntity<TechnicalExceptionResponse> detail(@PathVariable String packageId,
                                                              HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        TechnicalExceptionDetail detail = technicalExceptionQuery.execute(
                PackageId.of(UUID.fromString(packageId)), principal.tenantId());
        OperatorDiagnostics operatorDiagnostics = authorizationPolicy.has(principal, UserCapability.PLATFORM_OPERATIONS)
                ? new OperatorDiagnostics(
                        detail.dependency(),
                        detail.failureCode(),
                        detail.attempts(),
                        environment.getProperty("spring.profiles.active", "default"))
                : null;
        return ResponseEntity.ok(TechnicalExceptionResponse.from(detail, operatorDiagnostics));
    }

    @PostMapping("/{packageId}/retry")
    @RequireCapability(UserCapability.TECHNICAL_RETRY)
    public ResponseEntity<Map<String, Object>> retry(@PathVariable String packageId,
                                                      HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        retryTechnicalFailureUseCase.execute(new RetryTechnicalFailureCommand(
                PackageId.of(UUID.fromString(packageId)), principal.tenantId(), principal.actorId(),
                contextResolver.resolveCorrelationId(request)));
        return ResponseEntity.accepted().body(Map.of(
                "packageId", packageId,
                "status", "RETRY_SCHEDULED"));
    }

    public record OperatorDiagnostics(String dependency, String internalErrorCode, int attempts, String environment) {}

    public record TechnicalExceptionResponse(
            String packageId,
            String documentId,
            String status,
            String failureCategory,
            String failedStage,
            boolean retryable,
            Instant failedAt,
            String correlationId,
            Instant originalRetainedUntil,
            String explanation,
            OperatorDiagnostics operatorDiagnostics) {
        static TechnicalExceptionResponse from(TechnicalExceptionDetail detail, OperatorDiagnostics diagnostics) {
            return new TechnicalExceptionResponse(
                    detail.packageId().toString(),
                    detail.documentId() == null ? null : detail.documentId().toString(),
                    detail.status(),
                    detail.failureCategory(),
                    detail.failedStage(),
                    detail.retryable(),
                    detail.failedAt(),
                    detail.correlationId(),
                    detail.originalRetainedUntil(),
                    detail.explanation(),
                    diagnostics);
        }
    }
}

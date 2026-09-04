package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.port.in.AssignReviewTaskUseCase;
import com.securedoc.platform.application.port.in.CompleteReviewTaskUseCase;
import com.securedoc.platform.application.port.in.CreateReviewTaskUseCase;
import com.securedoc.platform.application.port.in.StartReviewTaskUseCase;
import com.securedoc.platform.application.review.command.AssignReviewTaskCommand;
import com.securedoc.platform.application.review.command.CompleteReviewTaskCommand;
import com.securedoc.platform.application.review.command.CreateReviewTaskCommand;
import com.securedoc.platform.application.review.command.StartReviewTaskCommand;
import com.securedoc.platform.application.review.result.ReviewTaskResult;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.ReviewTaskId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/review-tasks")
public class ReviewController {

    private final CreateReviewTaskUseCase createReviewTaskUseCase;
    private final AssignReviewTaskUseCase assignReviewTaskUseCase;
    private final StartReviewTaskUseCase startReviewTaskUseCase;
    private final CompleteReviewTaskUseCase completeReviewTaskUseCase;
    private final RequestContextResolver contextResolver;

    public ReviewController(CreateReviewTaskUseCase createReviewTaskUseCase,
                            AssignReviewTaskUseCase assignReviewTaskUseCase,
                            StartReviewTaskUseCase startReviewTaskUseCase,
                            CompleteReviewTaskUseCase completeReviewTaskUseCase,
                            RequestContextResolver contextResolver) {
        this.createReviewTaskUseCase = createReviewTaskUseCase;
        this.assignReviewTaskUseCase = assignReviewTaskUseCase;
        this.startReviewTaskUseCase = startReviewTaskUseCase;
        this.completeReviewTaskUseCase = completeReviewTaskUseCase;
        this.contextResolver = contextResolver;
    }

    @PostMapping
    @Transactional
    @RequireCapability(UserCapability.MANUAL_REVIEW)
    public ResponseEntity<Map<String, Object>> createReviewTask(
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        PackageId packageId = PackageId.of(UUID.fromString(body.get("packageId")));

        CreateReviewTaskCommand command = CreateReviewTaskCommand.of(
                packageId, principal.tenantId(), correlationId);
        ReviewTaskResult result = createReviewTaskUseCase.execute(command);

        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(result));
    }

    @PostMapping("/{taskId}/assign")
    @Transactional
    @RequireCapability(UserCapability.MANUAL_REVIEW)
    public ResponseEntity<Map<String, Object>> assignTask(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        ReviewTaskId reviewTaskId = ReviewTaskId.fromString(taskId);

        AssignReviewTaskCommand command = AssignReviewTaskCommand.of(
                reviewTaskId, principal.tenantId(), principal.actorId(), correlationId);
        ReviewTaskResult result = assignReviewTaskUseCase.execute(command);

        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{taskId}/start")
    @Transactional
    @RequireCapability(UserCapability.MANUAL_REVIEW)
    public ResponseEntity<Map<String, Object>> startTask(
            @PathVariable String taskId,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        ReviewTaskId reviewTaskId = ReviewTaskId.fromString(taskId);

        StartReviewTaskCommand command = StartReviewTaskCommand.of(
                reviewTaskId, principal.tenantId(), principal.actorId(), correlationId);
        ReviewTaskResult result = startReviewTaskUseCase.execute(command);

        return ResponseEntity.ok(toResponse(result));
    }

    @PostMapping("/{taskId}/complete")
    @Transactional
    @RequireCapability(UserCapability.REVIEW_SUBMIT)
    public ResponseEntity<Map<String, Object>> completeTask(
            @PathVariable String taskId,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        ReviewTaskId reviewTaskId = ReviewTaskId.fromString(taskId);
        String comment = body != null ? body.get("comment") : null;

        CompleteReviewTaskCommand command = CompleteReviewTaskCommand.of(
                reviewTaskId, principal.tenantId(), principal.actorId(), comment, correlationId);
        ReviewTaskResult result = completeReviewTaskUseCase.execute(command);

        return ResponseEntity.ok(toResponse(result));
    }

    private Map<String, Object> toResponse(ReviewTaskResult result) {
        return Map.of(
                "reviewTaskId", result.reviewTaskId().toString(),
                "packageId", result.packageId().toString(),
                "status", result.status().name(),
                "assignedTo", result.assignedTo() != null ? result.assignedTo().toString() : "",
                "createdAt", result.createdAt().toString(),
                "updatedAt", result.updatedAt().toString()
        );
    }
}

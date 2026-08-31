package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireRole;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.port.in.ConfirmFindingUseCase;
import com.securedoc.platform.application.port.in.DismissFindingUseCase;
import com.securedoc.platform.application.port.in.UpdateFindingBoundingBoxUseCase;
import com.securedoc.platform.application.review.command.ConfirmFindingCommand;
import com.securedoc.platform.application.review.command.DismissFindingCommand;
import com.securedoc.platform.application.review.command.UpdateFindingBoundingBoxCommand;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.ReviewTaskId;
import jakarta.servlet.http.HttpServletRequest;
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
@RequestMapping("/api/v1/findings")
public class FindingReviewController {

    private final ConfirmFindingUseCase confirmFindingUseCase;
    private final DismissFindingUseCase dismissFindingUseCase;
    private final UpdateFindingBoundingBoxUseCase updateFindingBoundingBoxUseCase;
    private final RequestContextResolver contextResolver;

    public FindingReviewController(ConfirmFindingUseCase confirmFindingUseCase,
                                    DismissFindingUseCase dismissFindingUseCase,
                                    UpdateFindingBoundingBoxUseCase updateFindingBoundingBoxUseCase,
                                    RequestContextResolver contextResolver) {
        this.confirmFindingUseCase = confirmFindingUseCase;
        this.dismissFindingUseCase = dismissFindingUseCase;
        this.updateFindingBoundingBoxUseCase = updateFindingBoundingBoxUseCase;
        this.contextResolver = contextResolver;
    }

    @PostMapping("/{findingId}/confirm")
    @Transactional
    @RequireRole({"REVIEWER"})
    public ResponseEntity<Map<String, String>> confirmFinding(
            @PathVariable String findingId,
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        FindingId id = FindingId.of(UUID.fromString(findingId));
        ReviewTaskId reviewTaskId = ReviewTaskId.fromString(body.get("reviewTaskId"));
        String reason = body.get("reason");

        ConfirmFindingCommand command = ConfirmFindingCommand.of(
                id, reviewTaskId, principal.tenantId(), principal.actorId(), reason, correlationId);
        confirmFindingUseCase.execute(command);

        return ResponseEntity.ok(Map.of("findingId", findingId, "status", "CONFIRMED"));
    }

    @PostMapping("/{findingId}/dismiss")
    @Transactional
    @RequireRole({"REVIEWER"})
    public ResponseEntity<Map<String, String>> dismissFinding(
            @PathVariable String findingId,
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        FindingId id = FindingId.of(UUID.fromString(findingId));
        ReviewTaskId reviewTaskId = ReviewTaskId.fromString(body.get("reviewTaskId"));
        String reason = body.get("reason");

        DismissFindingCommand command = DismissFindingCommand.of(
                id, reviewTaskId, principal.tenantId(), principal.actorId(), reason, correlationId);
        dismissFindingUseCase.execute(command);

        return ResponseEntity.ok(Map.of("findingId", findingId, "status", "DISMISSED"));
    }

    @PostMapping("/{findingId}/bounding-box")
    @Transactional
    @RequireRole({"REVIEWER"})
    public ResponseEntity<Map<String, String>> updateBoundingBox(
            @PathVariable String findingId,
            @RequestBody Map<String, Object> body,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        FindingId id = FindingId.of(UUID.fromString(findingId));
        ReviewTaskId reviewTaskId = ReviewTaskId.fromString((String) body.get("reviewTaskId"));

        UpdateFindingBoundingBoxCommand command = UpdateFindingBoundingBoxCommand.of(
                id, reviewTaskId, principal.tenantId(), principal.actorId(),
                ((Number) body.get("left")).doubleValue(),
                ((Number) body.get("top")).doubleValue(),
                ((Number) body.get("width")).doubleValue(),
                ((Number) body.get("height")).doubleValue(),
                correlationId);
        updateFindingBoundingBoxUseCase.execute(command);

        return ResponseEntity.ok(Map.of("findingId", findingId, "status", "BOUNDING_BOX_UPDATED"));
    }
}

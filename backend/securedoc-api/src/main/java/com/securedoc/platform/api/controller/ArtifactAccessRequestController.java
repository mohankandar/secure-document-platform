package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.port.in.DecideArtifactAccessRequestUseCase;
import com.securedoc.platform.application.port.in.GetArtifactAccessRequestQuery;
import com.securedoc.platform.application.port.in.ListArtifactAccessRequestsQuery;
import com.securedoc.platform.application.port.in.RequestArtifactAccessUseCase;
import com.securedoc.platform.application.vault.command.DecideArtifactAccessRequestCommand;
import com.securedoc.platform.application.vault.command.RequestArtifactAccessCommand;
import com.securedoc.platform.application.vault.result.ArtifactAccessRequestResult;
import com.securedoc.platform.domain.identity.AccessRequestId;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.VaultEntryId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@ConditionalOnProperty(name = "securedoc.security.mode", havingValue = "LOCAL")
@RequestMapping("/api/v1/artifact-access-requests")
public class ArtifactAccessRequestController {

    private final RequestArtifactAccessUseCase requestAccessUseCase;
    private final DecideArtifactAccessRequestUseCase decideAccessUseCase;
    private final GetArtifactAccessRequestQuery getAccessRequestQuery;
    private final ListArtifactAccessRequestsQuery listAccessRequestsQuery;
    private final RequestContextResolver contextResolver;

    public ArtifactAccessRequestController(RequestArtifactAccessUseCase requestAccessUseCase,
                                            DecideArtifactAccessRequestUseCase decideAccessUseCase,
                                            GetArtifactAccessRequestQuery getAccessRequestQuery,
                                            ListArtifactAccessRequestsQuery listAccessRequestsQuery,
                                            RequestContextResolver contextResolver) {
        this.requestAccessUseCase = requestAccessUseCase;
        this.decideAccessUseCase = decideAccessUseCase;
        this.getAccessRequestQuery = getAccessRequestQuery;
        this.listAccessRequestsQuery = listAccessRequestsQuery;
        this.contextResolver = contextResolver;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<Map<String, Object>> requestAccess(
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);

        VaultEntryId vaultEntryId = VaultEntryId.of(UUID.fromString(body.get("vaultEntryId")));
        ArtifactId artifactId = ArtifactId.of(UUID.fromString(body.get("artifactId")));
        String justification = body.get("justification");
        long durationHours = Long.parseLong(body.getOrDefault("durationHours", "24"));

        RequestArtifactAccessCommand command = RequestArtifactAccessCommand.of(
                principal.tenantId(), vaultEntryId, artifactId, principal.actorId(),
                justification, Duration.ofHours(durationHours), correlationId);

        ArtifactAccessRequestResult result = requestAccessUseCase.execute(command);
        return ResponseEntity.ok(toResponseMap(result));
    }

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listRequests(HttpServletRequest httpRequest) {
        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        List<ArtifactAccessRequestResult> results = listAccessRequestsQuery.execute(principal.tenantId());
        return ResponseEntity.ok(results.stream().map(this::toResponseMap).toList());
    }

    @GetMapping("/{requestId}")
    public ResponseEntity<Map<String, Object>> getRequest(
            @PathVariable String requestId,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        AccessRequestId id = AccessRequestId.of(UUID.fromString(requestId));
        ArtifactAccessRequestResult result = getAccessRequestQuery.execute(id, principal.tenantId());
        return ResponseEntity.ok(toResponseMap(result));
    }

    @PostMapping("/{requestId}/approve")
    @Transactional
    @RequireCapability(UserCapability.PACKAGE_APPROVE_DELIVERY)
    public ResponseEntity<Map<String, Object>> approve(
            @PathVariable String requestId,
            @RequestBody(required = false) Map<String, String> body,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        String reason = body != null ? body.get("reason") : null;

        DecideArtifactAccessRequestCommand command = DecideArtifactAccessRequestCommand.of(
                AccessRequestId.of(UUID.fromString(requestId)),
                principal.tenantId(), principal.actorId(), true, reason, correlationId);

        ArtifactAccessRequestResult result = decideAccessUseCase.execute(command);
        return ResponseEntity.ok(toResponseMap(result));
    }

    @PostMapping("/{requestId}/deny")
    @Transactional
    @RequireCapability(UserCapability.PACKAGE_APPROVE_DELIVERY)
    public ResponseEntity<Map<String, Object>> deny(
            @PathVariable String requestId,
            @RequestBody Map<String, String> body,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        String reason = body.get("reason");

        DecideArtifactAccessRequestCommand command = DecideArtifactAccessRequestCommand.of(
                AccessRequestId.of(UUID.fromString(requestId)),
                principal.tenantId(), principal.actorId(), false, reason, correlationId);

        ArtifactAccessRequestResult result = decideAccessUseCase.execute(command);
        return ResponseEntity.ok(toResponseMap(result));
    }

    private Map<String, Object> toResponseMap(ArtifactAccessRequestResult result) {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("id", result.id().toString());
        map.put("tenantId", result.tenantId().toString());
        map.put("vaultEntryId", result.vaultEntryId().toString());
        map.put("artifactId", result.artifactId().toString());
        map.put("requestedBy", result.requestedBy().toString());
        map.put("status", result.status().name());
        map.put("requestedAt", result.requestedAt().toString());
        map.put("expiresAt", result.expiresAt().toString());
        if (result.decidedAt() != null) {
            map.put("decidedAt", result.decidedAt().toString());
        }
        return map;
    }
}

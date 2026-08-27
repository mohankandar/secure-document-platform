package com.securedoc.platform.application.vault.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.in.DecideArtifactAccessRequestUseCase;
import com.securedoc.platform.application.port.in.GetArtifactAccessRequestQuery;
import com.securedoc.platform.application.port.in.ListArtifactAccessRequestsQuery;
import com.securedoc.platform.application.port.in.RequestArtifactAccessUseCase;
import com.securedoc.platform.application.port.out.AccessRequestRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.VaultEntryRepository;
import com.securedoc.platform.application.review.service.ReviewTextSanitizer;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.vault.command.DecideArtifactAccessRequestCommand;
import com.securedoc.platform.application.vault.command.RequestArtifactAccessCommand;
import com.securedoc.platform.application.vault.result.ArtifactAccessRequestResult;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.event.AccessRequestDecidedEvent;
import com.securedoc.platform.domain.identity.AccessRequestId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.vault.AccessRequest;
import com.securedoc.platform.domain.vault.AccessRequestStatus;
import com.securedoc.platform.domain.vault.VaultEntry;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Application service for artifact access request lifecycle.
 * Handles creation, approval, and denial of access requests for restricted artifacts.
 *
 * <p>Never stores raw sensitive values in audit/events.
 * Justifications and reasons are sanitized before persistence.
 */
public final class ArtifactAccessService implements RequestArtifactAccessUseCase,
        DecideArtifactAccessRequestUseCase, GetArtifactAccessRequestQuery,
        ListArtifactAccessRequestsQuery {

    private final AccessRequestRepository accessRequestRepository;
    private final VaultEntryRepository vaultEntryRepository;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public ArtifactAccessService(AccessRequestRepository accessRequestRepository,
                                  VaultEntryRepository vaultEntryRepository,
                                  DomainEventPublisher eventPublisher,
                                  AuditService auditService) {
        this.accessRequestRepository = Objects.requireNonNull(accessRequestRepository);
        this.vaultEntryRepository = Objects.requireNonNull(vaultEntryRepository);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public ArtifactAccessRequestResult execute(RequestArtifactAccessCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        // 1. Validate vault entry exists and belongs to tenant
        VaultEntry vaultEntry = vaultEntryRepository
                .findByIdAndTenantId(command.vaultEntryId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("VaultEntry", command.vaultEntryId().toString()));

        // 2. Validate artifact belongs to vault entry
        if (!vaultEntry.artifactId().equals(command.artifactId())) {
            throw new ValidationException("Artifact does not belong to the specified vault entry");
        }

        // 3. Only ORIGINAL artifacts require access requests
        if (vaultEntry.artifactType() != ArtifactType.ORIGINAL) {
            throw new ValidationException("Access requests are only supported for original artifacts");
        }

        // 3. Sanitize justification
        String safeJustification = ReviewTextSanitizer.sanitizeReason(command.justification());

        // 4. Create access request
        Instant expiresAt = Instant.now().plus(command.accessDuration());
        AccessRequest request = AccessRequest.create(
                command.tenantId(), command.vaultEntryId(), command.artifactId(),
                command.requesterId(), safeJustification, expiresAt);

        accessRequestRepository.save(request);

        // 5. Audit
        auditService.recordSuccess(command.tenantId(), command.requesterId(),
                AuditAction.ACCESS_REQUESTED, "AccessRequest", request.id().toString(),
                command.correlationId(),
                "Access requested: vaultEntryId=" + command.vaultEntryId()
                        + ", artifactType=" + vaultEntry.artifactType());

        return ArtifactAccessRequestResult.from(request);
    }

    @Override
    public ArtifactAccessRequestResult execute(DecideArtifactAccessRequestCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        // 1. Load access request
        AccessRequest request = accessRequestRepository
                .findByIdAndTenantId(command.requestId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("AccessRequest", command.requestId().toString()));

        // 2. Sanitize reason
        String safeReason = ReviewTextSanitizer.sanitizeReason(command.reason());

        // 3. Approve or deny
        if (command.approved()) {
            request.approve(command.deciderId(), safeReason);
        } else {
            if (safeReason == null || safeReason.isBlank()) {
                throw new ValidationException("Denial reason is required");
            }
            request.deny(command.deciderId(), safeReason);
        }

        accessRequestRepository.save(request);

        // 4. Publish event
        AccessRequestDecidedEvent event = AccessRequestDecidedEvent.of(
                request.id(), request.vaultEntryId(), request.artifactId(),
                command.tenantId(), command.deciderId(),
                request.status(), command.correlationId());
        eventPublisher.publish(event);

        // 5. Audit
        AuditAction auditAction = command.approved() ? AuditAction.ACCESS_APPROVED : AuditAction.ACCESS_DENIED;
        auditService.recordSuccess(command.tenantId(), command.deciderId(),
                auditAction, "AccessRequest", request.id().toString(),
                command.correlationId(),
                "Access request " + (command.approved() ? "approved" : "denied")
                        + ": artifactId=" + request.artifactId());

        return ArtifactAccessRequestResult.from(request);
    }

    @Override
    public ArtifactAccessRequestResult execute(AccessRequestId requestId, TenantId tenantId) {
        Objects.requireNonNull(requestId, "RequestId must not be null");
        Objects.requireNonNull(tenantId, "TenantId must not be null");

        AccessRequest request = accessRequestRepository
                .findByIdAndTenantId(requestId, tenantId)
                .orElseThrow(() -> new NotFoundException("AccessRequest", requestId.toString()));

        return ArtifactAccessRequestResult.from(request);
    }

    @Override
    public List<ArtifactAccessRequestResult> execute(TenantId tenantId) {
        Objects.requireNonNull(tenantId, "TenantId must not be null");

        return accessRequestRepository.findByTenantId(tenantId)
                .stream()
                .map(ArtifactAccessRequestResult::from)
                .toList();
    }
}

package com.securedoc.platform.application.vault.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.in.DownloadArtifactUseCase;
import com.securedoc.platform.application.port.in.RegisterVaultEntryUseCase;
import com.securedoc.platform.application.port.out.AccessRequestRepository;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.VaultEntryRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.vault.command.DownloadArtifactCommand;
import com.securedoc.platform.application.vault.command.RegisterVaultEntryCommand;
import com.securedoc.platform.application.vault.result.DownloadArtifactResult;
import com.securedoc.platform.application.vault.result.RegisterVaultEntryResult;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.event.ArtifactAccessedEvent;
import com.securedoc.platform.domain.event.VaultEntryCreatedEvent;
import com.securedoc.platform.domain.vault.AccessRequest;
import com.securedoc.platform.domain.vault.RetentionPolicy;
import com.securedoc.platform.domain.vault.VaultEntry;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Application service for vault registration and artifact download.
 * Registers processed artifacts into the vault and serves content securely.
 *
 * <p>Never exposes file paths, raw filenames, or sensitive values.
 */
public final class VaultService implements RegisterVaultEntryUseCase, DownloadArtifactUseCase {

    private final VaultEntryRepository vaultEntryRepository;
    private final ArtifactRepository artifactRepository;
    private final DocumentRepository documentRepository;
    private final PackageRepository packageRepository;
    private final AccessRequestRepository accessRequestRepository;
    private final StoragePort storagePort;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public VaultService(VaultEntryRepository vaultEntryRepository,
                         ArtifactRepository artifactRepository,
                         DocumentRepository documentRepository,
                         PackageRepository packageRepository,
                         AccessRequestRepository accessRequestRepository,
                         StoragePort storagePort,
                         DomainEventPublisher eventPublisher,
                         AuditService auditService) {
        this.vaultEntryRepository = Objects.requireNonNull(vaultEntryRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.accessRequestRepository = Objects.requireNonNull(accessRequestRepository);
        this.storagePort = Objects.requireNonNull(storagePort);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public RegisterVaultEntryResult execute(RegisterVaultEntryCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        // 1. Load and validate package
        DocumentPackage pkg = packageRepository
                .findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));

        // Package must be APPROVED or VAULTED to register artifacts
        if (pkg.status() != PackageStatus.APPROVED && pkg.status() != PackageStatus.VAULTED) {
            throw new ValidationException("Package must be in APPROVED or VAULTED status to register vault entries");
        }

        // 2. Load and validate document
        Document document = documentRepository
                .findByIdAndTenantId(command.documentId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Document", command.documentId().toString()));

        if (!document.packageId().equals(command.packageId())) {
            throw new ValidationException("Document does not belong to the specified package");
        }

        if (document.status() != DocumentStatus.PROCESSED) {
            throw new ValidationException("Document must be in PROCESSED status to register in vault");
        }

        // 3. Load and validate artifact
        Artifact artifact = artifactRepository
                .findByIdAndTenantId(command.artifactId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Artifact", command.artifactId().toString()));

        if (!artifact.documentId().equals(command.documentId())) {
            throw new ValidationException("Artifact does not belong to the specified document");
        }

        if (!artifact.isAvailable()) {
            throw new ValidationException("Artifact must be in AVAILABLE status to register in vault");
        }

        // 4. Only REDACTED and ORIGINAL artifacts can be vaulted
        if (artifact.artifactType() != ArtifactType.REDACTED
                && artifact.artifactType() != ArtifactType.ORIGINAL) {
            throw new ValidationException("Only REDACTED or ORIGINAL artifacts can be registered in vault");
        }

        // 5. Check for duplicate vault entry
        Optional<VaultEntry> existing = vaultEntryRepository
                .findByDocumentIdAndArtifactTypeAndTenantId(
                        command.documentId(), artifact.artifactType(), command.tenantId());
        if (existing.isPresent()) {
            throw new ValidationException("A vault entry already exists for this document and artifact type");
        }

        // 6. Register vault entry
        VaultEntry entry = VaultEntry.register(
                command.tenantId(), command.packageId(), command.documentId(),
                command.artifactId(), artifact.artifactType(),
                artifact.checksum(), RetentionPolicy.indefinite());

        vaultEntryRepository.save(entry);

        // 7. Publish event
        VaultEntryCreatedEvent event = VaultEntryCreatedEvent.of(
                entry.id(), command.tenantId(), command.packageId(),
                command.documentId(), command.artifactId(), artifact.artifactType(),
                command.correlationId());
        eventPublisher.publish(event);

        // 8. Audit
        auditService.recordSuccess(command.tenantId(), command.actorId(),
                AuditAction.VAULT_ENTRY_REGISTERED, "VaultEntry", entry.id().toString(),
                command.correlationId(),
                "Vault entry registered: artifactType=" + artifact.artifactType()
                        + ", documentId=" + command.documentId());

        return RegisterVaultEntryResult.from(entry);
    }

    @Override
    public DownloadArtifactResult execute(DownloadArtifactCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        // 1. Deny EXTRACTED_TEXT access by default
        if (command.artifactType() == ArtifactType.EXTRACTED_TEXT) {
            auditService.recordDenied(command.tenantId(), command.actorId(),
                    AuditAction.DOCUMENT_DOWNLOADED, "Artifact", command.documentId().toString(),
                    command.correlationId(), "Extracted text access denied by policy");
            throw new ValidationException("Access to extracted text artifacts is not permitted");
        }

        // 2. Find the vault entry for this document + artifact type
        Optional<VaultEntry> vaultEntryOpt = vaultEntryRepository
                .findByDocumentIdAndArtifactTypeAndTenantId(
                        command.documentId(), command.artifactType(), command.tenantId());
        if (vaultEntryOpt.isEmpty()) {
            auditService.recordDenied(command.tenantId(), command.actorId(),
                    AuditAction.DOCUMENT_DOWNLOADED, "Artifact", command.documentId().toString(),
                    command.correlationId(),
                    "Artifact download denied: no vault entry for documentId=" + command.documentId()
                            + ", type=" + command.artifactType());
            throw new ValidationException("No vault entry found for the requested document and artifact type");
        }
        VaultEntry vaultEntry = vaultEntryOpt.get();

        // 3. Enforce access rules
        if (command.artifactType() == ArtifactType.ORIGINAL) {
            // Original requires at least one approved, non-expired access request
            List<AccessRequest> approvedRequests = accessRequestRepository
                    .findApprovedByArtifactIdAndRequesterAndTenantId(
                            vaultEntry.artifactId(), command.actorId(), command.tenantId());

            boolean hasValidAccess = approvedRequests.stream()
                    .anyMatch(req -> !req.isAccessExpired());

            if (approvedRequests.isEmpty()) {
                auditService.recordDenied(command.tenantId(), command.actorId(),
                        AuditAction.DOCUMENT_DOWNLOADED, "Artifact", vaultEntry.artifactId().toString(),
                        command.correlationId(), "Original artifact access denied: no approved request");
                throw new ValidationException("Access to original artifact requires an approved access request");
            }
            if (!hasValidAccess) {
                auditService.recordDenied(command.tenantId(), command.actorId(),
                        AuditAction.DOCUMENT_DOWNLOADED, "Artifact", vaultEntry.artifactId().toString(),
                        command.correlationId(), "Original artifact access denied: all approved requests have expired");
                throw new ValidationException("Access to original artifact denied: approved request has expired");
            }
        }
        // REDACTED is allowed for authorized tenant actors (already authenticated at API level)

        // 4. Load artifact and retrieve content
        Optional<Artifact> artifactOpt = artifactRepository
                .findByIdAndTenantId(vaultEntry.artifactId(), command.tenantId());
        if (artifactOpt.isEmpty()) {
            auditService.recordDenied(command.tenantId(), command.actorId(),
                    AuditAction.DOCUMENT_DOWNLOADED, "Artifact", vaultEntry.artifactId().toString(),
                    command.correlationId(),
                    "Artifact download denied: artifact not found for artifactId=" + vaultEntry.artifactId());
            throw new ValidationException("Artifact not found");
        }
        Artifact artifact = artifactOpt.get();

        if (!artifact.isAvailable() || artifact.storageReference() == null) {
            auditService.recordDenied(command.tenantId(), command.actorId(),
                    AuditAction.DOCUMENT_DOWNLOADED, "Artifact", artifact.id().toString(),
                    command.correlationId(),
                    "Artifact download denied: artifact unavailable, type=" + artifact.artifactType());
            throw new ValidationException("Artifact content is not available");
        }

        InputStream content = storagePort.retrieve(artifact.storageReference());

        // 5. Publish access event
        ArtifactAccessedEvent event = ArtifactAccessedEvent.of(
                vaultEntry.id(), artifact.id(), artifact.artifactType(),
                command.documentId(), command.tenantId(), command.actorId(),
                command.correlationId());
        eventPublisher.publish(event);

        // 6. Audit successful access
        auditService.recordSuccess(command.tenantId(), command.actorId(),
                AuditAction.DOCUMENT_DOWNLOADED, "Artifact", artifact.id().toString(),
                command.correlationId(),
                "Artifact downloaded: type=" + artifact.artifactType()
                        + ", documentId=" + command.documentId());

        // 7. Return safe result (no file path exposure)
        String safeFilename = DownloadArtifactResult.safeFilename(command.documentId(), command.artifactType());
        return new DownloadArtifactResult(
                artifact.id(), command.documentId(), artifact.artifactType(),
                artifact.mimeType().value(), artifact.sizeBytes(),
                safeFilename, content);
    }
}

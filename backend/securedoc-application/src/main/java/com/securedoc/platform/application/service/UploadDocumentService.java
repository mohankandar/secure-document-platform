package com.securedoc.platform.application.service;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.intake.command.UploadDocumentCommand;
import com.securedoc.platform.application.licensing.TenantLicensingService;
import com.securedoc.platform.application.intake.command.UploadDocumentResult;
import com.securedoc.platform.application.port.in.UploadDocumentUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.event.DocumentUploadedEvent;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.tenant.Capability;
import com.securedoc.platform.domain.tenant.Tenant;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Objects;
import java.util.Set;

/**
 * Application service implementing the upload-document use case.
 */
public final class UploadDocumentService implements UploadDocumentUseCase {

    /**
     * V1 supports PDF upload only. Other document types remain modeled in the
     * domain for future expansion, but are intentionally rejected at intake.
     */
    private static final Set<String> ALLOWED_MIME_TYPES = Set.of("application/pdf");

    private final TenantContextService tenantContextService;
    private final CapabilityEnforcementService capabilityEnforcement;
    private final PackageRepository packageRepository;
    private final TenantLicensingService tenantLicensingService;
    private final DocumentRepository documentRepository;
    private final ArtifactRepository artifactRepository;
    private final StoragePort storagePort;
    private final ChecksumPort checksumPort;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public UploadDocumentService(TenantContextService tenantContextService,
                                  CapabilityEnforcementService capabilityEnforcement,
                                  TenantLicensingService tenantLicensingService,
                                  PackageRepository packageRepository,
                                  DocumentRepository documentRepository,
                                  ArtifactRepository artifactRepository,
                                  StoragePort storagePort,
                                  ChecksumPort checksumPort,
                                  DomainEventPublisher eventPublisher,
                                  AuditService auditService) {
        this.tenantContextService = Objects.requireNonNull(tenantContextService);
        this.capabilityEnforcement = Objects.requireNonNull(capabilityEnforcement);
        this.tenantLicensingService = Objects.requireNonNull(tenantLicensingService);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.storagePort = Objects.requireNonNull(storagePort);
        this.checksumPort = Objects.requireNonNull(checksumPort);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public UploadDocumentResult execute(UploadDocumentCommand command, AuthenticatedPrincipal principal) {
        Objects.requireNonNull(command, "Command must not be null");
        Objects.requireNonNull(principal, "Principal must not be null");

        // 1. Validate tenant context
        Tenant tenant = tenantContextService.resolveAndValidate(principal);
        capabilityEnforcement.require(tenant, Capability.DOCUMENT_UPLOAD);
        tenantLicensingService.requireDocumentProcessing(principal.tenantId(), principal.actorId(), command.correlationId());

        // 2. Validate package exists and belongs to tenant
        DocumentPackage documentPackage = packageRepository
                .findByIdAndTenantId(command.packageId(), principal.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));

        // 3. Validate MIME type and file name
        validateMimeType(command.mimeType());
        FileName fileName = FileName.of(command.fileName());
        MimeType mimeType = MimeType.of(command.mimeType());

        // 4. Compute checksum from a fresh stream (stream 1) — safely closed
        Checksum checksum;
        try (InputStream checksumStream = command.content().openStream()) {
            checksum = checksumPort.compute(checksumStream);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to compute checksum", e);
        }

        // 5. Create Document entity (generates DocumentId) — includes tenantId
        DocumentType documentType = resolveDocumentType(mimeType);
        Document document = Document.create(
                principal.tenantId(),
                command.packageId(),
                fileName,
                mimeType,
                command.contentLength(),
                checksum,
                documentType
        );

        // 6. Create Artifact entity before storage so artifact.id() is available for the path
        Artifact artifact = Artifact.create(
                principal.tenantId(),
                document.id(),
                ArtifactType.ORIGINAL,
                mimeType,
                command.contentLength(),
                checksum
        );

        // 7. Store using ID-only path (no raw file name) — fresh stream (stream 2) safely closed
        String storagePath = buildStoragePath(
                principal.tenantId().toString(),
                command.packageId().toString(),
                document.id().toString(),
                ArtifactType.ORIGINAL.name(),
                artifact.id().toString()
        );
        ArtifactStorageReference storageRef;
        try (InputStream storageStream = command.content().openStream()) {
            storageRef = storagePort.storeForPackage(
                    principal.tenantId(),
                    command.packageId(),
                    storagePath,
                    storageStream,
                    command.contentLength(),
                    command.mimeType()
            );
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to store artifact", e);
        }

        // 8. Mark artifact as available with storage reference
        artifact.markAvailable(storageRef);

        // 9. Add document to package
        documentPackage.addDocument(document);

        // 10. Persist all
        packageRepository.save(documentPackage);
        documentRepository.save(document);
        artifactRepository.save(artifact);

        // 11. Publish domain event
        CorrelationId correlationId = command.correlationId();
        DocumentUploadedEvent event = DocumentUploadedEvent.of(
                document.id(),
                command.packageId(),
                principal.tenantId(),
                artifact.id(),
                correlationId
        );
        eventPublisher.publish(event);

        // 12. Audit — safe metadata only, no raw file name
        auditService.recordSuccess(
                principal.tenantId(),
                principal.actorId(),
                AuditAction.DOCUMENT_UPLOADED,
                "Document",
                document.id().toString(),
                correlationId,
                "Document uploaded: mimeType=" + command.mimeType() + ", sizeBytes=" + command.contentLength()
        );

        // 13. Return result
        return UploadDocumentResult.of(document.id(), artifact.id(), document.status());
    }

    private void validateMimeType(String mimeType) {
        if (!ALLOWED_MIME_TYPES.contains(mimeType)) {
            throw new ValidationException("Unsupported MIME type: " + mimeType
                    + ". V1 supports PDF uploads only: application/pdf");
        }
    }

    private DocumentType resolveDocumentType(MimeType mimeType) {
        if (mimeType.isPdf()) {
            return DocumentType.PDF;
        }
        if (mimeType.isImage()) {
            return DocumentType.IMAGE;
        }
        String type = mimeType.value();
        if (type.contains("word") || type.contains("wordprocessingml")) {
            return DocumentType.WORD;
        }
        if ("text/plain".equals(type)) {
            return DocumentType.TEXT;
        }
        return DocumentType.OTHER;
    }

    /**
     * Builds ID-only storage path. No raw file name is used.
     * Format: {tenantId}/{packageId}/{documentId}/{artifactType}/{artifactId}
     */
    private String buildStoragePath(String tenantId, String packageId, String documentId,
                                     String artifactType, String artifactId) {
        return tenantId + "/" + packageId + "/" + documentId + "/" + artifactType + "/" + artifactId;
    }
}

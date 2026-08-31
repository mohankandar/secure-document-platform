package com.securedoc.platform.application.export.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.export.command.ExportRedactedDocumentCommand;
import com.securedoc.platform.application.export.result.ExportRedactedDocumentResult;
import com.securedoc.platform.application.port.in.ExportRedactedDocumentUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.application.port.out.DeliveryPort.DeliveryReference;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;

import java.io.InputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * @deprecated The active V1 processing path exports complete packages atomically via
 * {@code ExportPackageDocumentsUseCase}. Retained temporarily for compatibility only.
 *
 * Application service that exports a redacted document to the configured delivery
 * destination (local output folder in V1), preserving the customer's original filename.
 *
 * <p>Only documents with an available REDACTED artifact are exported. No sensitive
 * values are recorded in audit — only IDs, filename, and destination metadata.
 */
@Deprecated
public final class DocumentExportService implements ExportRedactedDocumentUseCase {

    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    private static final String PDF_MIME_TYPE = "application/pdf";

    private final DocumentRepository documentRepository;
    private final ArtifactRepository artifactRepository;
    private final StoragePort storagePort;
    private final DeliveryPort deliveryPort;
    private final AuditService auditService;

    public DocumentExportService(DocumentRepository documentRepository,
                                  ArtifactRepository artifactRepository,
                                  StoragePort storagePort,
                                  DeliveryPort deliveryPort,
                                  AuditService auditService) {
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.storagePort = Objects.requireNonNull(storagePort);
        this.deliveryPort = Objects.requireNonNull(deliveryPort);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public ExportRedactedDocumentResult execute(ExportRedactedDocumentCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        Document document = documentRepository
                .findByIdAndTenantId(command.documentId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("Document", command.documentId().toString()));

        if (!document.packageId().equals(command.packageId())) {
            throw new ValidationException("Document does not belong to the specified package");
        }

        // Locate the available REDACTED artifact.
        Artifact redactedArtifact = artifactRepository
                .findByDocumentIdAndTenantId(document.id(), command.tenantId())
                .stream()
                .filter(a -> a.artifactType() == ArtifactType.REDACTED && a.isAvailable())
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Redacted artifact for document",
                        command.documentId().toString()));

        String originalFileName = document.fileName().value();

        DeliveryReference reference;
        try (InputStream content = storagePort.retrieve(redactedArtifact.storageReference())) {
            reference = deliveryPort.deliver(
                    command.tenantId(), command.packageId(), command.documentId(), originalFileName, content,
                    redactedArtifact.sizeBytes(), PDF_MIME_TYPE);
        } catch (Exception e) {
            auditService.recordFailure(
                    command.tenantId(), SYSTEM_ACTOR,
                    AuditAction.DOCUMENT_EXPORT_FAILED, "Document", command.documentId().toString(),
                    command.correlationId(), "Export failed");
            throw new RuntimeException("Failed to export redacted document", e);
        }

        auditService.recordSuccess(
                command.tenantId(), SYSTEM_ACTOR,
                AuditAction.DOCUMENT_EXPORTED, "Document", command.documentId().toString(),
                command.correlationId(),
                "Document exported: destination=" + reference.destinationType()
                        + ", filename=" + originalFileName);

        return ExportRedactedDocumentResult.of(command.documentId(), true, reference.destinationType());
    }
}

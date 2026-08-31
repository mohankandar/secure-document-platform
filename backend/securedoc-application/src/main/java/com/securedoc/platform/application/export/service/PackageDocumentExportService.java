package com.securedoc.platform.application.export.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.export.command.ExportPackageDocumentsCommand;
import com.securedoc.platform.application.export.result.ExportPackageDocumentsResult;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.port.in.ExportPackageDocumentsUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.application.port.out.DeliveryPort.BatchDeliveryReference;
import com.securedoc.platform.application.port.out.DeliveryPort.DeliveryItem;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.lifecycle.PackageExportRecord;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Builds one atomic delivery batch from every processed document in a package. Successful
 * delivery is recorded as exactly one business export cycle; technical retries before success
 * do not consume a cycle.
 */
public final class PackageDocumentExportService implements ExportPackageDocumentsUseCase {

    private static final Logger log = LoggerFactory.getLogger(PackageDocumentExportService.class);
    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    private static final String PDF_MIME_TYPE = "application/pdf";

    private final DocumentRepository documentRepository;
    private final ArtifactRepository artifactRepository;
    private final StoragePort storagePort;
    private final DeliveryPort deliveryPort;
    private final PackageLifecycleService lifecycleService;
    private final AuditService auditService;

    public PackageDocumentExportService(DocumentRepository documentRepository,
                                        ArtifactRepository artifactRepository,
                                        StoragePort storagePort,
                                        DeliveryPort deliveryPort,
                                        PackageLifecycleService lifecycleService,
                                        AuditService auditService) {
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.storagePort = Objects.requireNonNull(storagePort);
        this.deliveryPort = Objects.requireNonNull(deliveryPort);
        this.lifecycleService = Objects.requireNonNull(lifecycleService);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public ExportPackageDocumentsResult execute(ExportPackageDocumentsCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        long startedNanos = System.nanoTime();

        List<Document> documents = documentRepository
                .findByPackageIdAndTenantId(command.packageId(), command.tenantId())
                .stream()
                .sorted(Comparator.comparing(document -> document.id().toString()))
                .toList();
        if (documents.isEmpty()) {
            throw new ValidationException("Package has no documents to export");
        }
        if (documents.stream().anyMatch(document -> document.status() != DocumentStatus.PROCESSED)) {
            throw new ValidationException("Every package document must be PROCESSED before batch delivery");
        }

        int exportCycle;
        try {
            exportCycle = lifecycleService.nextExportCycle(command.packageId(), command.tenantId());
        } catch (IllegalStateException e) {
            throw new ValidationException(e.getMessage());
        }

        List<DeliveryItem> items = new ArrayList<>(documents.size());
        Map<String, Artifact> artifactByDocumentId = new HashMap<>();
        for (Document document : documents) {
            Artifact redactedArtifact = artifactRepository
                    .findByDocumentIdAndTenantId(document.id(), command.tenantId())
                    .stream()
                    .filter(a -> a.artifactType() == ArtifactType.REDACTED && a.isAvailable())
                    .max(Comparator.comparing(Artifact::createdAt))
                    .orElseThrow(() -> new NotFoundException("Redacted artifact for document",
                            document.id().toString()));
            artifactByDocumentId.put(document.id().toString(), redactedArtifact);
            items.add(new DeliveryItem(
                    document.id(),
                    document.fileName().value(),
                    () -> storagePort.retrieve(redactedArtifact.storageReference()),
                    redactedArtifact.sizeBytes(),
                    PDF_MIME_TYPE));
        }

        BatchDeliveryReference batchReference;
        try {
            batchReference = deliveryPort.deliverBatch(command.tenantId(), command.packageId(), exportCycle, items);
        } catch (TechnicalDependencyException e) {
            auditService.recordFailure(
                    command.tenantId(), SYSTEM_ACTOR,
                    AuditAction.DOCUMENT_EXPORT_FAILED, "DocumentPackage", command.packageId().toString(),
                    command.correlationId(), "Atomic package export failed: dependency=" + e.dependency()
                            + ", code=" + e.failureCode() + ", retryable=" + e.retryable()
                            + ", attempts=" + e.attempts());
            throw e;
        } catch (Exception e) {
            auditService.recordFailure(
                    command.tenantId(), SYSTEM_ACTOR,
                    AuditAction.DOCUMENT_EXPORT_FAILED, "DocumentPackage", command.packageId().toString(),
                    command.correlationId(), "Atomic package export failed");
            throw new RuntimeException("Failed to export redacted package", e);
        }

        Map<String, DeliveryPort.DeliveryReference> referenceByDocumentId = new HashMap<>();
        for (DeliveryPort.DeliveryReference reference : batchReference.documents()) {
            referenceByDocumentId.put(reference.documentId().toString(), reference);
        }
        List<PackageExportRecord.ExportedDocument> exportDocuments = new ArrayList<>(documents.size());
        for (Document document : documents) {
            Artifact artifact = artifactByDocumentId.get(document.id().toString());
            DeliveryPort.DeliveryReference reference = referenceByDocumentId.get(document.id().toString());
            if (reference == null) {
                throw new IllegalStateException("Delivery result is missing a document reference");
            }
            exportDocuments.add(new PackageExportRecord.ExportedDocument(
                    document.id(), document.fileName().value(), artifact.sizeBytes(), artifact.checksum(), reference.location()));
            auditService.recordSuccess(
                    command.tenantId(), SYSTEM_ACTOR,
                    AuditAction.DOCUMENT_EXPORTED, "Document", document.id().toString(),
                    command.correlationId(),
                    "Document exported in atomic package delivery: destination="
                            + batchReference.destinationType() + ", cycle=" + exportCycle
                            + ", filename=" + document.fileName().value());
        }

        // Consume the business export cycle only after the destination has confirmed the entire batch.
        lifecycleService.recordSuccessfulExport(command.packageId(), command.tenantId(), exportCycle,
                batchReference.destinationType(), exportDocuments, command.correlationId());

        log.info("PACKAGE_DELIVERY_COMPLETED package={} documents={} destination={} cycle={} durationMs={}",
                command.packageId(), documents.size(), batchReference.destinationType(), exportCycle,
                elapsedMillis(startedNanos));

        return ExportPackageDocumentsResult.of(
                command.packageId(), documents.size(), batchReference.destinationType(), exportCycle);
    }
    private long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000L;
    }

}

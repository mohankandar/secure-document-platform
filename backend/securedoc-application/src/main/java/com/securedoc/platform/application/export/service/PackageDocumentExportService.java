package com.securedoc.platform.application.export.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.export.command.ExportPackageDocumentsCommand;
import com.securedoc.platform.application.export.result.ExportPackageDocumentsResult;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.port.in.ExportPackageDocumentsUseCase;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.port.out.DeliveryPort.*;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.delivery.DeliveryAttempt;
import com.securedoc.platform.domain.delivery.DeliverySubmission;
import com.securedoc.platform.domain.delivery.DeliverySubmissionStatus;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.lifecycle.PackageExportRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;

/**
 * Delivers the complete redacted master package. A business export cycle is consumed only after
 * the active provider reaches its durable-success boundary (local atomic write or SDV COMPLETE ACK).
 * Delivery submission/document identities survive technical retries without rerunning redaction.
 */
public final class PackageDocumentExportService implements ExportPackageDocumentsUseCase {
    private static final Logger log = LoggerFactory.getLogger(PackageDocumentExportService.class);
    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    private static final String PDF_MIME_TYPE = "application/pdf";

    private final DocumentRepository documentRepository;
    private final PackageRepository packageRepository;
    private final BusinessRecordRepository businessRecordRepository;
    private final ArtifactRepository artifactRepository;
    private final StoragePort storagePort;
    private final DeliveryPort deliveryPort;
    private final DeliverySubmissionRepository deliverySubmissionRepository;
    private final PackageLifecycleService lifecycleService;
    private final AuditService auditService;

    public PackageDocumentExportService(DocumentRepository documentRepository,
                                        PackageRepository packageRepository,
                                        BusinessRecordRepository businessRecordRepository,
                                        ArtifactRepository artifactRepository,
                                        StoragePort storagePort,
                                        DeliveryPort deliveryPort,
                                        DeliverySubmissionRepository deliverySubmissionRepository,
                                        PackageLifecycleService lifecycleService,
                                        AuditService auditService) {
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.businessRecordRepository = Objects.requireNonNull(businessRecordRepository);
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.storagePort = Objects.requireNonNull(storagePort);
        this.deliveryPort = Objects.requireNonNull(deliveryPort);
        this.deliverySubmissionRepository = Objects.requireNonNull(deliverySubmissionRepository);
        this.lifecycleService = Objects.requireNonNull(lifecycleService);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public ExportPackageDocumentsResult execute(ExportPackageDocumentsCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        long startedNanos = System.nanoTime();
        DocumentPackage pkg = packageRepository.findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));

        List<Document> documents = documentRepository.findByPackageIdAndTenantId(command.packageId(), command.tenantId())
                .stream().sorted(Comparator.comparing(document -> document.id().toString())).toList();
        if (documents.isEmpty()) throw new ValidationException("Package has no documents to export");
        if (documents.stream().anyMatch(document -> document.status() != DocumentStatus.PROCESSED)) {
            throw new ValidationException("Every package document must be PROCESSED before batch delivery");
        }

        int exportCycle;
        try { exportCycle = lifecycleService.nextExportCycle(command.packageId(), command.tenantId()); }
        catch (IllegalStateException e) { throw new ValidationException(e.getMessage()); }

        Map<String, Artifact> artifactByDocumentId = new HashMap<>();
        List<DeliverySubmission.NewDocument> newDocs = new ArrayList<>();
        for (Document document : documents) {
            Artifact artifact = artifactRepository.findByDocumentIdAndTenantId(document.id(), command.tenantId()).stream()
                    .filter(a -> a.artifactType() == ArtifactType.REDACTED && a.isAvailable())
                    .max(Comparator.comparing(Artifact::createdAt))
                    .orElseThrow(() -> new NotFoundException("Redacted artifact for document", document.id().toString()));
            artifactByDocumentId.put(document.id().toString(), artifact);
            newDocs.add(new DeliverySubmission.NewDocument(document.id(), document.fileName().value(), artifact.sizeBytes(), artifact.checksum()));
        }

        DeliveryDescriptor descriptor = deliveryPort.describe(command.tenantId());
        DeliverySubmission submission = deliverySubmissionRepository
                .findByTenantIdAndPackageIdAndExportCycle(command.tenantId(), command.packageId(), exportCycle)
                .orElseGet(() -> {
                    DeliverySubmission created = DeliverySubmission.create(command.tenantId(), command.packageId(), exportCycle,
                            descriptor.destinationType(), newDocs, Instant.now());
                    deliverySubmissionRepository.save(created);
                    return created;
                });
        if (!submission.destinationType().equals(descriptor.destinationType())) {
            throw new ValidationException("Delivery provider cannot change within the same export cycle");
        }

        DeliveryAttempt attempt = null;
        if (submission.status() != DeliverySubmissionStatus.DELIVERED) {
            int attemptNumber = submission.startAttempt(Instant.now());
            deliverySubmissionRepository.save(submission);
            attempt = DeliveryAttempt.started(submission.id(), attemptNumber,
                    descriptor.connectionId(), descriptor.connectionConfigurationVersion(), Instant.now());
            deliverySubmissionRepository.saveAttempt(attempt);
        }
        final DeliveryAttempt activeAttempt = attempt;

        DeliveryProgressListener progress = new DeliveryProgressListener() {
            @Override public void submissionAccepted(String destinationSubmissionReference) {
                submission.recordDestinationSubmissionReference(destinationSubmissionReference, Instant.now());
                deliverySubmissionRepository.save(submission);
            }
            @Override public void documentAcknowledged(DeliveryReference reference) {
                submission.acknowledgeDocument(reference.documentId(), reference.destinationDocumentReference(), reference.location(), Instant.now());
                deliverySubmissionRepository.save(submission);
            }
        };

        List<DeliveryItem> pendingItems = new ArrayList<>();
        for (DeliverySubmission.DocumentState state : submission.pendingDocuments()) {
            Artifact artifact = artifactByDocumentId.get(state.documentId().toString());
            if (artifact == null) throw new IllegalStateException("Delivery submission refers to an unknown package document");
            pendingItems.add(new DeliveryItem(state.documentId(), state.sourceDocumentId(), state.fileName(),
                    () -> storagePort.retrieve(artifact.storageReference()), artifact.sizeBytes(), PDF_MIME_TYPE,
                    artifact.checksum().algorithm(), artifact.checksum().value()));
        }

        if (submission.status() != DeliverySubmissionStatus.DELIVERED) {
            BatchDeliveryReference batchReference;
            try {
                batchReference = deliveryPort.deliverBatch(new DeliveryBatch(command.tenantId(), command.packageId(), exportCycle,
                        submission.sourceSubmissionId(), submission.destinationSubmissionReference(), businessIdentifiers(pkg),
                        Map.of("sourceRevision", Integer.toString(exportCycle)), pendingItems, progress));
                if (batchReference.destinationSubmissionReference() != null) {
                    submission.recordDestinationSubmissionReference(batchReference.destinationSubmissionReference(), Instant.now());
                }
                for (DeliveryReference ref : batchReference.documents()) {
                    submission.acknowledgeDocument(ref.documentId(), ref.destinationDocumentReference(), ref.location(), Instant.now());
                }
                submission.markDelivered(batchReference.destinationSubmissionReference(), Instant.now());
                deliverySubmissionRepository.save(submission);
                deliverySubmissionRepository.saveAttempt(activeAttempt.completeSuccess(Instant.now()));
            } catch (TechnicalDependencyException e) {
                submission.markFailure(e.failureCode().name(), e.retryable(), Instant.now());
                deliverySubmissionRepository.save(submission);
                deliverySubmissionRepository.saveAttempt(activeAttempt.completeFailure(e.failureCode().name(), e.retryable(), Instant.now()));
            auditService.recordFailure(command.tenantId(), SYSTEM_ACTOR, AuditAction.DOCUMENT_EXPORT_FAILED,
                    "DocumentPackage", command.packageId().toString(), command.correlationId(),
                    "Package export failed: dependency=" + e.dependency() + ", code=" + e.failureCode()
                            + ", retryable=" + e.retryable() + ", attempts=" + e.attempts());
                throw e;
            } catch (Exception e) {
                submission.markFailure("DELIVERY_RUNTIME_ERROR", true, Instant.now());
                deliverySubmissionRepository.save(submission);
                deliverySubmissionRepository.saveAttempt(activeAttempt.completeFailure("DELIVERY_RUNTIME_ERROR", true, Instant.now()));
                auditService.recordFailure(command.tenantId(), SYSTEM_ACTOR, AuditAction.DOCUMENT_EXPORT_FAILED,
                        "DocumentPackage", command.packageId().toString(), command.correlationId(), "Package export failed");
                throw new RuntimeException("Failed to export redacted package", e);
            }
        }

        if (!submission.allAcknowledged()) throw new IllegalStateException("Delivery completed without acknowledgements for every package document");
        Map<String, DeliverySubmission.DocumentState> stateByDocument = new HashMap<>();
        submission.documents().forEach(d -> stateByDocument.put(d.documentId().toString(), d));
        List<PackageExportRecord.ExportedDocument> exportDocuments = new ArrayList<>(documents.size());
        for (Document document : documents) {
            Artifact artifact = artifactByDocumentId.get(document.id().toString());
            DeliverySubmission.DocumentState state = stateByDocument.get(document.id().toString());
            if (state == null || !state.acknowledged()) throw new IllegalStateException("Delivery result is missing a document acknowledgement");
            exportDocuments.add(new PackageExportRecord.ExportedDocument(document.id(), document.fileName().value(),
                    artifact.sizeBytes(), artifact.checksum(), state.destinationLocation()));
            auditService.recordSuccess(command.tenantId(), SYSTEM_ACTOR, AuditAction.DOCUMENT_EXPORTED,
                    "Document", document.id().toString(), command.correlationId(),
                    "Document acknowledged by master delivery destination=" + submission.destinationType()
                            + ", cycle=" + exportCycle + ", filename=" + document.fileName().value());
        }

        // This is the only point that starts the post-export retention clock.
        lifecycleService.recordSuccessfulExport(command.packageId(), command.tenantId(), exportCycle,
                submission.destinationType(), exportDocuments, command.correlationId());

        log.info("PACKAGE_DELIVERY_COMPLETED package={} documents={} destination={} cycle={} durationMs={}",
                command.packageId(), documents.size(), submission.destinationType(), exportCycle, elapsedMillis(startedNanos));
        return ExportPackageDocumentsResult.of(command.packageId(), documents.size(), submission.destinationType(), exportCycle);
    }

    private Map<String,String> businessIdentifiers(DocumentPackage pkg) {
        if (pkg.businessRecordId().isEmpty()) return Map.of();
        return businessRecordRepository.findByIdAndTenantId(pkg.businessRecordId().get(), pkg.tenantId())
                .map(record -> {
                    Map<String,String> values = new LinkedHashMap<>();
                    record.identifiers().forEach(i -> values.put(i.key(), i.value()));
                    return Map.copyOf(values);
                }).orElse(Map.of());
    }
    private long elapsedMillis(long startedNanos) { return (System.nanoTime() - startedNanos) / 1_000_000L; }
}

package com.securedoc.platform.application.export.service;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.application.casefields.CaseFieldService;
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
 * the active provider reaches its durable-success boundary (local atomic write or the document store's COMPLETE acknowledgement).
 * Delivery submission/document identities survive technical retries without rerunning redaction.
 */
public final class PackageDocumentExportService implements ExportPackageDocumentsUseCase,
        com.securedoc.platform.application.port.in.RebuildPackageExportUseCase {
    private static final Logger log = LoggerFactory.getLogger(PackageDocumentExportService.class);
    private static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));
    private static final String PDF_MIME_TYPE = "application/pdf";

    private final DocumentRepository documentRepository;
    private final PackageRepository packageRepository;
    private final CaseFieldService caseFields;
    private final ArtifactRepository artifactRepository;
    private final StoragePort storagePort;
    private final DeliveryPort deliveryPort;
    private final DeliverySubmissionRepository deliverySubmissionRepository;
    private final PackageLifecycleService lifecycleService;
    private final AuditService auditService;

    public PackageDocumentExportService(DocumentRepository documentRepository,
                                        PackageRepository packageRepository,
                                        CaseFieldService caseFields,
                                        ArtifactRepository artifactRepository,
                                        StoragePort storagePort,
                                        DeliveryPort deliveryPort,
                                        DeliverySubmissionRepository deliverySubmissionRepository,
                                        PackageLifecycleService lifecycleService,
                                        AuditService auditService) {
        this.documentRepository = Objects.requireNonNull(documentRepository);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.caseFields = Objects.requireNonNull(caseFields);
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
        if (documents.isEmpty()) throw new ValidationException(LvErrorCode.DLV_011, "Package has no documents to export");
        if (documents.stream().anyMatch(document -> document.status() != DocumentStatus.PROCESSED)) {
            throw new ValidationException(LvErrorCode.DLV_011, "Every package document must be PROCESSED before batch delivery");
        }

        int exportCycle;
        try { exportCycle = lifecycleService.nextExportCycle(command.packageId(), command.tenantId()); }
        catch (IllegalStateException e) { throw new ValidationException(LvErrorCode.REV_007, e.getMessage()); }

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
            throw new ValidationException(LvErrorCode.DLV_011, "Delivery provider cannot change within the same export cycle");
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
            @Override public void progressed(String note) {
                submission.recordDestinationProgress(note, Instant.now());
                deliverySubmissionRepository.save(submission);
            }
        };

        // Task 0021: the levels are read from the stored case on every attempt, so a retry carries the same levels.
        Map<String, com.securedoc.platform.domain.document.SensitivityLevel> levelByDocumentId = new HashMap<>();
        documents.forEach(d -> levelByDocumentId.put(d.id().toString(), d.sensitivityLevel()));
        // Task 0060: a stored copy that can't be opened is not the destination's failure, however the adapter reports it.
        DeliverySource source = new DeliverySource(storagePort);
        List<DeliveryItem> pendingItems = new ArrayList<>();
        List<DeliveryItem> acknowledgedItems = new ArrayList<>();
        for (DeliverySubmission.DocumentState state : submission.documents()) {
            Artifact artifact = artifactByDocumentId.get(state.documentId().toString());
            if (artifact == null) throw new IllegalStateException("Delivery submission refers to an unknown package document");
            var documentLevel = levelByDocumentId.get(state.documentId().toString());
            if (documentLevel == null) throw new IllegalStateException("Delivery submission refers to a document outside the package");
            (state.acknowledged() ? acknowledgedItems : pendingItems).add(new DeliveryItem(state.documentId(), state.sourceDocumentId(), state.fileName(),
                    () -> source.open(artifact.storageReference()), artifact.sizeBytes(), PDF_MIME_TYPE,
                    artifact.checksum().algorithm(), artifact.checksum().value(), documentLevel));
        }

        boolean alreadyReceived = false;
        if (submission.status() != DeliverySubmissionStatus.DELIVERED) {
            BatchDeliveryReference batchReference;
            try {
                CaseFieldService.Delivery fields = caseFields(pkg);
                batchReference = deliveryPort.deliverBatch(new DeliveryBatch(command.tenantId(), command.packageId(), exportCycle,
                        submission.sourceSubmissionId(), submission.destinationSubmissionReference(), fields.texts(),
                        Map.of("sourceRevision", Integer.toString(exportCycle)), pendingItems, progress,
                        pkg.sensitivityLevel(), fields.document(),
                        new DeliveryPort.DeliveryContext(submission.destinationProgress(), fields.fieldList(), acknowledgedItems)));
                if (batchReference.destinationSubmissionReference() != null) {
                    submission.recordDestinationSubmissionReference(batchReference.destinationSubmissionReference(), Instant.now());
                }
                for (DeliveryReference ref : batchReference.documents()) {
                    submission.acknowledgeDocument(ref.documentId(), ref.destinationDocumentReference(), ref.location(), Instant.now());
                }
                alreadyReceived = batchReference.alreadyReceived();
                submission.markDelivered(batchReference.destinationSubmissionReference(), Instant.now());
                deliverySubmissionRepository.save(submission);
                deliverySubmissionRepository.saveAttempt(activeAttempt.completeSuccess(Instant.now()));
            } catch (TechnicalDependencyException reported) {
                TechnicalDependencyException e = source.failureOr(reported);
                submission.markFailure(e.failureCode().name(), e.retryable(), Instant.now());
                deliverySubmissionRepository.save(submission);
                deliverySubmissionRepository.saveAttempt(activeAttempt.completeFailure(e.failureCode().name(), e.retryable(), Instant.now()));
            auditService.recordFailure(command.tenantId(), SYSTEM_ACTOR, AuditAction.DOCUMENT_EXPORT_FAILED,
                    "DocumentPackage", command.packageId().toString(), command.correlationId(),
                    "Package export failed: " + com.securedoc.platform.application.error.LvErrorCodes.auditDetail(com.securedoc.platform.application.error.LvErrorCodes.of(e), e.failureCode().name(), "dependency="
                            + e.dependency() + ", retryable=" + e.retryable() + ", attempts=" + e.attempts()));
                throw e;
            } catch (Exception e) {
                submission.markFailure("DELIVERY_RUNTIME_ERROR", true, Instant.now());
                deliverySubmissionRepository.save(submission);
                deliverySubmissionRepository.saveAttempt(activeAttempt.completeFailure("DELIVERY_RUNTIME_ERROR", true, Instant.now()));
                auditService.recordFailure(command.tenantId(), SYSTEM_ACTOR, AuditAction.DOCUMENT_EXPORT_FAILED,
                        "DocumentPackage", command.packageId().toString(), command.correlationId(), "Package export failed: "
                                + com.securedoc.platform.application.error.LvErrorCodes.auditDetail(com.securedoc.platform.application.error.LvErrorCodes.optional(e).orElse(com.securedoc.platform.domain.error.LvErrorCode.SYS_001), "DELIVERY_RUNTIME_ERROR",
                                "exceptionType=" + e.getClass().getSimpleName()));
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
            // Task 0023 (A1, B4): the export record keeps a document reference, never the original file name.
            exportDocuments.add(new PackageExportRecord.ExportedDocument(document.id(), exportRecordDocumentReference(document),
                    artifact.sizeBytes(), artifact.checksum(), state.destinationLocation()));
            auditService.recordSuccess(command.tenantId(), SYSTEM_ACTOR, AuditAction.DOCUMENT_EXPORTED,
                    "Document", document.id().toString(), command.packageId(), command.correlationId(),
                    "Document acknowledged by master delivery destination=" + submission.destinationType()
                            + ", cycle=" + exportCycle
                            // Task 0023 (A1): no file name here; the record names the document by its id.
                            // Task 0022 (A2): a destination that cannot receive a level (local folder) still records it here.
                            + ", sensitivityLevel=" + document.sensitivityLevel().name()
                            + ", caseSensitivityLevel=" + pkg.sensitivityLevel().name());
        }

        // This is the only point that starts the post-export retention clock.
        lifecycleService.recordSuccessfulExport(command.packageId(), command.tenantId(), exportCycle,
                submission.destinationType(), exportDocuments, command.correlationId());

        log.info("PACKAGE_DELIVERY_COMPLETED package={} documents={} destination={} cycle={} durationMs={}",
                command.packageId(), documents.size(), submission.destinationType(), exportCycle, elapsedMillis(startedNanos));
        return ExportPackageDocumentsResult.of(command.packageId(), documents.size(), submission.destinationType(), exportCycle,
                alreadyReceived);
    }

    /**
     * Task 0028: the case run's submission rebuilt from the stored redacted copies (see
     * {@link DeliverySubmission#rebuild}). Each copy is read back and must match its stored checksum, so a damaged or
     * missing copy is found here and not by the destination. The identities are kept: the next attempt sends the same
     * {@code Idempotency-Key}s, so the destination answers a repeat as a replay, not a second copy.
     */
    @Override
    public boolean rebuild(com.securedoc.platform.domain.identity.TenantId tenantId,
                           com.securedoc.platform.domain.identity.PackageId packageId,
                           com.securedoc.platform.domain.identity.CorrelationId correlationId) {
        int exportCycle;
        try { exportCycle = lifecycleService.nextExportCycle(packageId, tenantId); }
        catch (IllegalStateException e) { throw new ValidationException(LvErrorCode.REV_007, e.getMessage()); }
        DeliverySubmission submission = deliverySubmissionRepository
                .findByTenantIdAndPackageIdAndExportCycle(tenantId, packageId, exportCycle).orElse(null);
        if (submission == null || submission.status() == DeliverySubmissionStatus.DELIVERED) return false;
        List<DeliverySubmission.NewDocument> copies = new ArrayList<>();
        for (Document document : documentRepository.findByPackageIdAndTenantId(packageId, tenantId)) {
            Artifact artifact = artifactRepository.findByDocumentIdAndTenantId(document.id(), tenantId).stream()
                    .filter(a -> a.artifactType() == ArtifactType.REDACTED && a.isAvailable())
                    .max(Comparator.comparing(Artifact::createdAt))
                    .orElseThrow(() -> new NotFoundException("Redacted artifact for document", document.id().toString()));
            verifyStoredCopy(artifact);
            copies.add(new DeliverySubmission.NewDocument(document.id(), document.fileName().value(),
                    artifact.sizeBytes(), artifact.checksum()));
        }
        DeliverySubmission rebuilt;
        try {
            rebuilt = submission.rebuild(copies, Instant.now());
        } catch (IllegalStateException differs) {
            throw new ValidationException(LvErrorCode.DLV_011, "The stored copies are not the documents of this case run");
        }
        deliverySubmissionRepository.save(rebuilt);
        log.info("PACKAGE_DELIVERY_REBUILT package={} cycle={} documents={}", packageId, exportCycle, copies.size());
        return true;
    }

    /** Reads a stored redacted copy back and compares it with its checksum. Nothing of its content is kept. */
    private void verifyStoredCopy(Artifact artifact) {
        String algorithm = artifact.checksum().algorithm();
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance(algorithm);
        } catch (java.security.NoSuchAlgorithmException unknown) {
            throw new ValidationException(LvErrorCode.DLV_011, "The stored copy's checksum can't be checked");
        }
        try (java.io.InputStream in = storagePort.retrieve(artifact.storageReference())) {
            byte[] buffer = new byte[8192];
            for (int read; (read = in.read(buffer)) != -1; ) digest.update(buffer, 0, read);
        } catch (TechnicalDependencyException exact) {
            // Task 0060: the key service's or the file's own code.
            throw exact;
        } catch (java.io.IOException | RuntimeException unreadable) {
            throw new TechnicalDependencyException(com.securedoc.platform.application.exception.TechnicalDependency.STORAGE,
                    com.securedoc.platform.application.exception.TechnicalFailureCode.STORAGE_IO, true,
                    "Stored redacted copy could not be read", null);
        }
        String actual = java.util.HexFormat.of().formatHex(digest.digest());
        if (!actual.equalsIgnoreCase(artifact.checksum().value())) {
            throw new TechnicalDependencyException(com.securedoc.platform.application.exception.TechnicalDependency.STORAGE,
                    com.securedoc.platform.application.exception.TechnicalFailureCode.STORAGE_IO, false,
                    "Stored redacted copy does not match its checksum", null);
        }
    }

    private CaseFieldService.Delivery caseFields(DocumentPackage pkg) {
        return pkg.businessRecordId().map(id -> caseFields.forDelivery(id, pkg.tenantId()))
                .orElseGet(() -> new CaseFieldService.Delivery(Map.of(), null, null));
    }
    /** Kept in the export record in place of a file name (task 0023 A1/B4): the document id, never the name. */
    static String exportRecordDocumentReference(Document document) { return "document-" + document.id(); }
    private long elapsedMillis(long startedNanos) { return (System.nanoTime() - startedNanos) / 1_000_000L; }
}

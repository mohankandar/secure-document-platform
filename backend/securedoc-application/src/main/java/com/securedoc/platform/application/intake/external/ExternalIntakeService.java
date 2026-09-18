package com.securedoc.platform.application.intake.external;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.*;
import com.securedoc.platform.application.integration.IntegrationConnectionService;
import com.securedoc.platform.application.intake.command.*;
import com.securedoc.platform.application.port.in.*;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.integration.IntegrationConnection;
import com.securedoc.platform.domain.intake.*;
import com.securedoc.platform.domain.vo.Checksum;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Canonical manifest-first push API intake facade. */
public final class ExternalIntakeService {
    private final IntegrationConnectionService integrationConnectionService;
    private final ExternalIntakeRepository externalIntakeRepository;
    private final PackageRepository packageRepository;
    private final CreatePackageUseCase createPackageUseCase;
    private final UploadDocumentUseCase uploadDocumentUseCase;
    private final SubmitPackageUseCase submitPackageUseCase;
    private final PackageProcessingDispatcher processingDispatcher;
    private final ChecksumPort checksumPort;
    private final PdfIntakeValidationPort pdfValidationPort;
    private final AuditService auditService;
    private final ExternalIntakeMaintenanceService maintenanceService;
    private final Clock clock;

    public ExternalIntakeService(IntegrationConnectionService integrationConnectionService,
                                 ExternalIntakeRepository externalIntakeRepository,
                                 PackageRepository packageRepository,
                                 CreatePackageUseCase createPackageUseCase,
                                 UploadDocumentUseCase uploadDocumentUseCase,
                                 SubmitPackageUseCase submitPackageUseCase,
                                 PackageProcessingDispatcher processingDispatcher,
                                 ChecksumPort checksumPort,
                                 PdfIntakeValidationPort pdfValidationPort,
                                 AuditService auditService,
                                 ExternalIntakeMaintenanceService maintenanceService) {
        this(integrationConnectionService, externalIntakeRepository, packageRepository, createPackageUseCase,
                uploadDocumentUseCase, submitPackageUseCase, processingDispatcher, checksumPort, pdfValidationPort,
                auditService, maintenanceService, Clock.systemUTC());
    }

    ExternalIntakeService(IntegrationConnectionService integrationConnectionService,
                          ExternalIntakeRepository externalIntakeRepository,
                          PackageRepository packageRepository,
                          CreatePackageUseCase createPackageUseCase,
                          UploadDocumentUseCase uploadDocumentUseCase,
                          SubmitPackageUseCase submitPackageUseCase,
                          PackageProcessingDispatcher processingDispatcher,
                          ChecksumPort checksumPort,
                          PdfIntakeValidationPort pdfValidationPort,
                          AuditService auditService, ExternalIntakeMaintenanceService maintenanceService, Clock clock) {
        this.integrationConnectionService = Objects.requireNonNull(integrationConnectionService);
        this.externalIntakeRepository = Objects.requireNonNull(externalIntakeRepository);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.createPackageUseCase = Objects.requireNonNull(createPackageUseCase);
        this.uploadDocumentUseCase = Objects.requireNonNull(uploadDocumentUseCase);
        this.submitPackageUseCase = Objects.requireNonNull(submitPackageUseCase);
        this.processingDispatcher = Objects.requireNonNull(processingDispatcher);
        this.checksumPort = Objects.requireNonNull(checksumPort);
        this.pdfValidationPort = Objects.requireNonNull(pdfValidationPort);
        this.auditService = Objects.requireNonNull(auditService);
        this.maintenanceService = Objects.requireNonNull(maintenanceService);
        this.clock = Objects.requireNonNull(clock);
    }

    public ExternalIntakeSnapshot createPushIntake(PushIntakeCreateInput input, AuthenticatedPrincipal principal,
                                                   CorrelationId correlationId) {
        Objects.requireNonNull(principal, "Authenticated integration principal is required");
        IntegrationConnection source = integrationConnectionService.resolveEnabledPushSource(
                principal.tenantId(), principal.username());
        return createMachineIntake(input, source, ExternalIntakeChannel.PUSH_API, principal, correlationId);
    }

    public ExternalIntakeSnapshot createScannerIntake(PushIntakeCreateInput input, AuthenticatedPrincipal principal,
                                                       CorrelationId correlationId) {
        Objects.requireNonNull(principal, "Authenticated scanner principal is required");
        IntegrationConnection source = integrationConnectionService.resolveEnabledScannerSource(
                principal.tenantId(), principal.username());
        if (input == null || input.documents().size() != 1) {
            throw new ValidationException("Scanner intake requires exactly one consolidated PDF");
        }
        Map<String, String> metadata = new LinkedHashMap<>(input.metadata());
        PushIntakeCreateInput.ManifestDocumentInput scannerDocument = input.documents().get(0);
        String sourceSubmissionId = normalizeRequired(input.externalSourcePackageId(), "externalSourcePackageId");
        String sourceDocumentId = normalizeRequired(scannerDocument.externalSourceDocumentId(), "externalSourceDocumentId");
        final String sourceSha256;
        try {
            sourceSha256 = ExternalManifestDocument.normalizeSha256(scannerDocument.sha256(), true);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(ex.getMessage());
        }
        // These values already exist as authoritative source identity on the scanner intake request.
        // Persist server-derived copies for reporting instead of trusting duplicate metadata supplied by a client.
        metadata.put("intakeChannel", "SCANNER");
        metadata.put("sourceSubmissionId", sourceSubmissionId);
        metadata.put("sourceDocumentId", sourceDocumentId);
        metadata.put("sourceSha256", sourceSha256);
        validateScannerProvenance(metadata);
        PushIntakeCreateInput scannerInput = new PushIntakeCreateInput(
                input.externalSourcePackageId(), input.sourceRevision(), input.businessIdentifiers(), metadata,
                input.documents(), input.retry());
        return createMachineIntake(scannerInput, source, ExternalIntakeChannel.SCANNER, principal, correlationId);
    }

    /**
     * Scanner provenance is reporting/audit data, not business identity. Require a stable minimum
     * contract so downstream reporting never has to infer scanner source from filenames or logs.
     */
    private void validateScannerProvenance(Map<String, String> metadata) {
        String captureMode = requiredMetadata(metadata, "scannerCaptureMode");
        if (!Set.of("SIMULATOR", "PHYSICAL").contains(captureMode)) {
            throw new ValidationException("scannerCaptureMode must be SIMULATOR or PHYSICAL");
        }
        requiredMetadata(metadata, "bridgeId");
        requiredMetadata(metadata, "siteId");
        requiredMetadata(metadata, "scannerId");
        parseUuidMetadata(metadata, "scanSessionId");
        parseUuidMetadata(metadata, "sourceSubmissionId");
        parseUuidMetadata(metadata, "sourceDocumentId");
        String sourceSha256 = requiredMetadata(metadata, "sourceSha256");
        String size = requiredMetadata(metadata, "sourceFileSizeBytes");
        try {
            if (Long.parseLong(size) < 1) throw new NumberFormatException();
        } catch (NumberFormatException ex) {
            throw new ValidationException("sourceFileSizeBytes must be a positive integer");
        }
        Instant started;
        Instant completed;
        Instant submitted;
        try {
            started = Instant.parse(requiredMetadata(metadata, "scanStartedAt"));
            completed = Instant.parse(requiredMetadata(metadata, "scanCompletedAt"));
            submitted = Instant.parse(requiredMetadata(metadata, "scannerSubmittedAt"));
        } catch (RuntimeException ex) {
            throw new ValidationException("Scanner timestamps must be ISO-8601 instants");
        }
        if (completed.isBefore(started)) {
            throw new ValidationException("scanCompletedAt cannot be before scanStartedAt");
        }
        if (submitted.isBefore(completed)) {
            throw new ValidationException("scannerSubmittedAt cannot be before scanCompletedAt");
        }

        String reportedPages = metadata.get("scannerReportedPageCount");
        if (reportedPages != null && !reportedPages.isBlank()) {
            try {
                int pages = Integer.parseInt(reportedPages.trim());
                if (pages < 1 || pages > IntegrationConnection.MAX_PDF_PAGES) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                throw new ValidationException("scannerReportedPageCount must be between 1 and " + IntegrationConnection.MAX_PDF_PAGES);
            }
        }

        try { ExternalManifestDocument.normalizeSha256(sourceSha256, true); }
        catch (IllegalArgumentException ex) { throw new ValidationException(ex.getMessage()); }
    }

    private String requiredMetadata(Map<String, String> metadata, String key) {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) throw new ValidationException("Scanner metadata is missing " + key);
        return value.trim();
    }

    private String parseUuidMetadata(Map<String, String> metadata, String key) {
        String value = requiredMetadata(metadata, key);
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(key + " must be a UUID");
        }
    }

    public ExternalIntakeSnapshot createPollingIntake(PushIntakeCreateInput input, IntegrationConnection source,
                                                      AuthenticatedPrincipal principal, CorrelationId correlationId) {
        if (source == null || !source.enabled() || !source.pollingSource() || !source.tenantId().equals(principal.tenantId())) {
            throw new AccessDeniedException("Polling source is not enabled for this tenant");
        }
        return createMachineIntake(input, source, ExternalIntakeChannel.POLLING, principal, correlationId);
    }

    private ExternalIntakeSnapshot createMachineIntake(PushIntakeCreateInput input, IntegrationConnection source,
                                                        ExternalIntakeChannel channel, AuthenticatedPrincipal principal,
                                                        CorrelationId correlationId) {
        Objects.requireNonNull(input, "External intake request is required");
        validateCreateInput(input, source);

        List<ExternalManifestDocument> manifest;
        try {
            manifest = input.documents().stream().map(d -> ExternalManifestDocument.expected(
                    d.externalSourceDocumentId(), d.fileName(), d.sha256())).toList();
        } catch (IllegalArgumentException e) {
            throw new ValidationException(e.getMessage());
        }
        String requestFingerprint = requestFingerprint(input, manifest);
        Optional<ExternalIntake> existing = externalIntakeRepository.findBySourceIdentity(principal.tenantId(), source.id(),
                normalizeRequired(input.externalSourcePackageId(), "externalSourcePackageId"),
                normalizeRequired(input.sourceRevision(), "sourceRevision"));
        int attemptNumber = 1;
        if (existing.isPresent()) {
            ExternalIntake latest = existing.get();
            if (!latest.requestFingerprint().equals(requestFingerprint)) {
                throw new IntakeConflictException("SOURCE_REVISION_REUSED",
                        "The source package/revision already exists with a different manifest or business payload; use a new revision for resubmission");
            }
            if (latest.status() == ExternalIntakeStatus.INCOMPLETE_EXPIRED && input.retry()) {
                attemptNumber = latest.attemptNumber() + 1;
            } else {
                return snapshot(latest);
            }
        } else if (input.retry()) {
            throw new IntakeConflictException("RETRY_SOURCE_NOT_FOUND",
                    "Retry was requested but no prior source package/revision attempt exists");
        }

        String sourcePackageId = normalizeRequired(input.externalSourcePackageId(), "externalSourcePackageId");
        String sourceRevision = normalizeRequired(input.sourceRevision(), "sourceRevision");
        String intakeFingerprint = sha256Hex(source.id() + "|" + sourcePackageId + "|" + sourceRevision + "|attempt=" + attemptNumber);
        PackageType packageType = channel == ExternalIntakeChannel.SCANNER
                ? PackageType.SCANNER_INTAKE : PackageType.API_SUBMISSION;
        var createResult = createPackageUseCase.execute(new CreatePackageCommand(packageType,
                input.businessIdentifiers(), input.metadata(), intakeFingerprint, sourcePackageId, correlationId), principal);

        Instant createdAt = Instant.now(clock);
        ExternalIntake intake;
        try {
            intake = ExternalIntake.createAttempt(createResult.packageId(), principal.tenantId(), source.id(), channel,
                    sourcePackageId, sourceRevision, attemptNumber, requestFingerprint, createdAt,
                    createdAt.plus(source.incompleteWindowHours(), ChronoUnit.HOURS), manifest);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(e.getMessage());
        }
        externalIntakeRepository.save(intake);
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.EXTERNAL_INTAKE_CREATED,
                "ExternalIntake", intake.packageId().toString(), correlationId,
                channel + " intake created: sourceConnection=" + source.id() + ",attempt=" + intake.attemptNumber()
                        + ",expectedDocuments=" + intake.expectedCount() + ",expiresAt=" + intake.expiresAt());
        return snapshot(intake);
    }

    public ExternalDocumentReceipt receivePushDocument(PackageId packageId, String externalSourceDocumentId,
                                                        String idempotencyKey, String sourceSha256,
                                                        String mimeType, UploadContent content,
                                                        AuthenticatedPrincipal principal, CorrelationId correlationId) {
        Objects.requireNonNull(principal);
        IntegrationConnection source = integrationConnectionService.resolveEnabledPushSource(
                principal.tenantId(), principal.username());
        return receiveMachineDocument(packageId, externalSourceDocumentId, idempotencyKey, sourceSha256, mimeType, content,
                source, ExternalIntakeChannel.PUSH_API, principal, correlationId);
    }

    public ExternalDocumentReceipt receiveScannerDocument(PackageId packageId, String externalSourceDocumentId,
                                                           String idempotencyKey, String sourceSha256,
                                                           String mimeType, UploadContent content,
                                                           AuthenticatedPrincipal principal, CorrelationId correlationId) {
        Objects.requireNonNull(principal);
        IntegrationConnection source = integrationConnectionService.resolveEnabledScannerSource(
                principal.tenantId(), principal.username());
        return receiveMachineDocument(packageId, externalSourceDocumentId, idempotencyKey, sourceSha256, mimeType, content,
                source, ExternalIntakeChannel.SCANNER, principal, correlationId);
    }

    public ExternalDocumentReceipt receivePollingDocument(PackageId packageId, String externalSourceDocumentId,
                                                           String idempotencyKey, String sourceSha256, String mimeType,
                                                           UploadContent content, IntegrationConnection source,
                                                           AuthenticatedPrincipal principal, CorrelationId correlationId) {
        if (source == null || !source.enabled() || !source.pollingSource() || !source.tenantId().equals(principal.tenantId())) {
            throw new AccessDeniedException("Polling source is not enabled for this tenant");
        }
        return receiveMachineDocument(packageId, externalSourceDocumentId, idempotencyKey, sourceSha256, mimeType, content,
                source, ExternalIntakeChannel.POLLING, principal, correlationId);
    }

    private ExternalDocumentReceipt receiveMachineDocument(PackageId packageId, String externalSourceDocumentId,
                                                            String idempotencyKey, String sourceSha256, String mimeType,
                                                            UploadContent content, IntegrationConnection source,
                                                            ExternalIntakeChannel channel, AuthenticatedPrincipal principal,
                                                            CorrelationId correlationId) {
        Objects.requireNonNull(packageId); Objects.requireNonNull(content); Objects.requireNonNull(principal);
        ExternalIntake intake = authorizedIntake(principal, source, packageId, channel, true);
        expireForRequestIfDue(intake, principal.actorId(), correlationId);
        if (intake.status() == ExternalIntakeStatus.INCOMPLETE_EXPIRED) {
            throw new IntakeExpiredException("The intake completion window has expired; retry the same immutable source package/revision with retry=true, or use a new revision when content changes");
        }

        String normalizedExternalId = normalizeRequired(externalSourceDocumentId, "externalSourceDocumentId");
        String normalizedIdempotency = normalizeRequired(idempotencyKey, "Idempotency-Key");
        final String normalizedSourceSha;
        try { normalizedSourceSha = ExternalManifestDocument.normalizeSha256(sourceSha256, true); }
        catch (IllegalArgumentException e) { throw new ValidationException(e.getMessage()); }
        ExternalManifestDocument manifestDocument;
        try { manifestDocument = intake.document(normalizedExternalId); }
        catch (IllegalArgumentException e) { throw new ValidationException(e.getMessage()); }

        if (manifestDocument.received()) {
            if (!manifestDocument.matchesReceivedContent(normalizedSourceSha)) {
                throw new IntakeConflictException("SOURCE_DOCUMENT_ID_REUSED",
                        "The external source document id is already acknowledged with different content");
            }
            return receipt(intake, manifestDocument, true);
        }
        if (intake.status() != ExternalIntakeStatus.AWAITING_DOCUMENTS) {
            throw new IntakeConflictException("INTAKE_ALREADY_COMPLETE", "The intake is already accepted for processing");
        }
        try {
            intake.ensureDocumentReceiptAllowed(normalizedExternalId, normalizedIdempotency, Instant.now(clock));
        } catch (IllegalArgumentException e) {
            throw new IntakeConflictException("IDEMPOTENCY_CONFLICT", e.getMessage());
        } catch (IllegalStateException e) {
            throw new IntakeConflictException("INVALID_INTAKE_STATE", e.getMessage());
        }
        if (content.contentLength() < 1 || content.contentLength() > source.maxPdfSizeBytes()) {
            throw new ValidationException("PDF size exceeds the configured source limit");
        }
        if (channel == ExternalIntakeChannel.SCANNER) {
            validateScannerDeclaredSize(packageId, principal.tenantId(), content.contentLength());
        }
        if (!"application/pdf".equalsIgnoreCase(normalizeRequired(mimeType, "mimeType"))) {
            throw new ValidationException("Machine intake supports application/pdf only");
        }
        if (manifestDocument.declaredSha256() != null
                && !manifestDocument.declaredSha256().equalsIgnoreCase(normalizedSourceSha)) {
            throw new IntakeConflictException("MANIFEST_CHECKSUM_MISMATCH",
                    "Upload checksum does not match the checksum declared in the manifest");
        }

        Checksum actual;
        try (InputStream checksumStream = content.openStream()) {
            actual = checksumPort.compute(checksumStream);
        } catch (IOException e) {
            throw new ValidationException("Unable to validate uploaded document");
        }
        if (!actual.value().equalsIgnoreCase(normalizedSourceSha)) {
            throw new IntakeConflictException("CONTENT_CHECKSUM_MISMATCH",
                    "Uploaded PDF content does not match X-Content-SHA256");
        }
        final int pageCount;
        try (InputStream pdfStream = content.openStream()) {
            pageCount = pdfValidationPort.inspect(pdfStream).pageCount();
            if (pageCount > source.maxPdfPages()) {
                throw new ValidationException("PDF page count exceeds the configured source limit");
            }
        } catch (IOException e) {
            throw new ValidationException("Unable to validate uploaded PDF");
        }

        var uploadResult = uploadDocumentUseCase.execute(UploadDocumentCommand.of(packageId,
                manifestDocument.originalFileName(), "application/pdf", content, correlationId), principal);
        try {
            intake.recordReceived(normalizedExternalId, uploadResult.documentId(), actual.value(), normalizedIdempotency,
                    Instant.now(clock));
        } catch (IllegalArgumentException e) {
            throw new IntakeConflictException("IDEMPOTENCY_CONFLICT", e.getMessage());
        } catch (IllegalStateException e) {
            throw new IntakeConflictException("INVALID_INTAKE_STATE", e.getMessage());
        }
        externalIntakeRepository.save(intake);
        if (channel == ExternalIntakeChannel.SCANNER) {
            recordScannerReceiptProvenance(packageId, principal.tenantId(), content.contentLength(),
                    pageCount, actual.value());
        }
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.EXTERNAL_DOCUMENT_RECEIVED,
                "Document", uploadResult.documentId().toString(), correlationId,
                "External document durably received: sourceConnection=" + source.id() + ",received="
                        + intake.receivedCount() + "/" + intake.expectedCount());

        if (intake.status() == ExternalIntakeStatus.ACCEPTED_FOR_PROCESSING) {
            submitPackageUseCase.execute(SubmitPackageCommand.of(packageId, correlationId), principal);
            auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.EXTERNAL_INTAKE_ACCEPTED,
                    "ExternalIntake", packageId.toString(), correlationId,
                    "All expected documents received; package accepted for asynchronous processing");
            processingDispatcher.dispatch(TriggerPackageProcessingCommand.of(packageId, principal.tenantId(),
                    principal.actorId(), correlationId));
        }
        return receipt(intake, intake.document(normalizedExternalId), false);
    }

    private void validateScannerDeclaredSize(PackageId packageId, TenantId tenantId, long actualSizeBytes) {
        var documentPackage = packageRepository.findByIdAndTenantId(packageId, tenantId)
                .orElseThrow(() -> new NotFoundException("DocumentPackage", packageId.toString()));
        String declared = documentPackage.metadata().get("sourceFileSizeBytes")
                .orElseThrow(() -> new ValidationException("Scanner metadata is missing sourceFileSizeBytes"));
        final long declaredSize;
        try {
            declaredSize = Long.parseLong(declared);
        } catch (NumberFormatException ex) {
            throw new ValidationException("sourceFileSizeBytes must be a positive integer");
        }
        if (declaredSize != actualSizeBytes) {
            throw new IntakeConflictException("SCANNER_SOURCE_SIZE_MISMATCH",
                    "Scanner PDF size does not match the size declared when the intake was created");
        }
    }

    private void recordScannerReceiptProvenance(PackageId packageId, TenantId tenantId, long actualSizeBytes,
                                                 int pageCount, String actualSha256) {
        var documentPackage = packageRepository.findByIdAndTenantId(packageId, tenantId)
                .orElseThrow(() -> new NotFoundException("DocumentPackage", packageId.toString()));
        PackageMetadata verified = documentPackage.metadata()
                .with("sourceFileSizeBytes", Long.toString(actualSizeBytes))
                .with("sourcePageCount", Integer.toString(pageCount))
                .with("sourceSha256", actualSha256);
        documentPackage.updateMetadata(verified);
        packageRepository.save(documentPackage);
    }

    public ExternalIntakeSnapshot status(PackageId packageId, AuthenticatedPrincipal principal, CorrelationId correlationId) {
        IntegrationConnection source = integrationConnectionService.resolveEnabledPushSource(
                principal.tenantId(), principal.username());
        ExternalIntake intake = authorizedIntake(principal, source, packageId, ExternalIntakeChannel.PUSH_API, true);
        expireForRequestIfDue(intake, principal.actorId(), correlationId);
        return snapshot(intake);
    }

    public ExternalIntakeSnapshot scannerStatus(PackageId packageId, AuthenticatedPrincipal principal, CorrelationId correlationId) {
        IntegrationConnection source = integrationConnectionService.resolveEnabledScannerSource(
                principal.tenantId(), principal.username());
        ExternalIntake intake = authorizedIntake(principal, source, packageId, ExternalIntakeChannel.SCANNER, true);
        expireForRequestIfDue(intake, principal.actorId(), correlationId);
        return snapshot(intake);
    }

    public ExternalIntakeSnapshot pollingStatus(PackageId packageId, IntegrationConnection source,
                                                AuthenticatedPrincipal principal, CorrelationId correlationId) {
        if (source == null || !source.enabled() || !source.pollingSource() || !source.tenantId().equals(principal.tenantId())) {
            throw new AccessDeniedException("Polling source is not enabled for this tenant");
        }
        ExternalIntake intake = authorizedIntake(principal, source, packageId, ExternalIntakeChannel.POLLING, true);
        expireForRequestIfDue(intake, principal.actorId(), correlationId);
        return snapshot(intake);
    }

    private ExternalIntake authorizedIntake(AuthenticatedPrincipal principal, IntegrationConnection source,
                                            PackageId packageId, ExternalIntakeChannel channel, boolean forUpdate) {
        Optional<ExternalIntake> found = forUpdate
                ? externalIntakeRepository.findByTenantIdAndPackageIdForUpdate(principal.tenantId(), packageId)
                : externalIntakeRepository.findByTenantIdAndPackageId(principal.tenantId(), packageId);
        ExternalIntake intake = found.orElseThrow(() -> new NotFoundException("ExternalIntake", packageId.toString()));
        if (!intake.integrationConnectionId().equals(source.id()) || intake.channel() != channel) {
            throw new AccessDeniedException("Authenticated integration source does not own this intake");
        }
        return intake;
    }

    private void expireForRequestIfDue(ExternalIntake intake, ActorId actorId, CorrelationId correlationId) {
        maintenanceService.expireIfDue(intake, actorId, correlationId);
    }

    private void validateCreateInput(PushIntakeCreateInput input, IntegrationConnection source) {
        normalizeRequired(input.externalSourcePackageId(), "externalSourcePackageId");
        normalizeRequired(input.sourceRevision(), "sourceRevision");
        if (input.documents().isEmpty()) throw new ValidationException("Document manifest must contain at least one PDF");
        if (input.documents().size() > source.maxDocumentsPerIntake()) {
            throw new ValidationException("Document manifest exceeds the configured source document limit");
        }
    }

    private ExternalIntakeSnapshot snapshot(ExternalIntake intake) {
        var pkg = packageRepository.findByIdAndTenantId(intake.packageId(), intake.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", intake.packageId().toString()));
        return new ExternalIntakeSnapshot(intake.packageId().value(), intake.externalSourcePackageId(), intake.sourceRevision(),
                intake.attemptNumber(), intake.status(), pkg.status(), intake.expectedCount(), intake.receivedCount(),
                intake.missingExternalSourceDocumentIds(), intake.createdAt(), intake.expiresAt(), intake.acceptedAt(), intake.expiredAt());
    }

    private ExternalDocumentReceipt receipt(ExternalIntake intake, ExternalManifestDocument document, boolean duplicate) {
        return new ExternalDocumentReceipt(intake.packageId().value(), document.receivedDocumentId().value(),
                document.externalSourceDocumentId(), document.receivedSha256(), duplicate, intake.status(),
                intake.expectedCount(), intake.receivedCount(), intake.missingExternalSourceDocumentIds(), intake.expiresAt());
    }

    private String requestFingerprint(PushIntakeCreateInput input, List<ExternalManifestDocument> manifest) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(canonical, "package", normalizeRequired(input.externalSourcePackageId(), "externalSourcePackageId"));
        appendCanonical(canonical, "revision", normalizeRequired(input.sourceRevision(), "sourceRevision"));
        new TreeMap<>(input.businessIdentifiers()).forEach((k, v) ->
                appendCanonical(canonical, "business:" + k, v));
        new TreeMap<>(input.metadata()).forEach((k, v) ->
                appendCanonical(canonical, "metadata:" + k, v));
        manifest.stream().sorted(Comparator.comparing(ExternalManifestDocument::externalSourceDocumentId)).forEach(d -> {
            appendCanonical(canonical, "document:id", d.externalSourceDocumentId());
            appendCanonical(canonical, "document:file", d.originalFileName());
            appendCanonical(canonical, "document:sha256", d.declaredSha256() == null ? "" : d.declaredSha256());
        });
        return sha256Hex(canonical.toString());
    }

    private static void appendCanonical(StringBuilder target, String key, String value) {
        String safeKey = Objects.requireNonNull(key);
        String safeValue = Objects.requireNonNull(value);
        target.append(safeKey.length()).append(':').append(safeKey)
                .append(safeValue.length()).append(':').append(safeValue);
    }

    private static String sha256Hex(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static String normalizeRequired(String value, String label) {
        if (value == null || value.isBlank()) throw new ValidationException(label + " is required");
        return value.trim();
    }
}

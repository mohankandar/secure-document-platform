package com.securedoc.platform.application.intake.external;

import com.securedoc.platform.domain.document.CaseLimits;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.audit.RequestActorDisplayName;
import com.securedoc.platform.application.audit.RequestActorDisplayName.MachineClientKind;
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
import com.securedoc.platform.domain.document.SensitivityLevel;
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
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ExternalIntakeService.class);
    /**
     * Task 0039: the sanity bound for the page count a scanner reports in its metadata (LV-SCN-003). It was the old
     * source page limit and stays as it was; a scan over the case limit is refused when its pages are counted (LV-INT-028).
     */
    private static final int SCANNER_REPORTED_PAGES_MAX = 10000;
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
    /** Task 0063; null where no password is taken. */
    private PdfDecryptionPort pdfDecryptionPort;
    /** Task 0071; null where no case is rejected. */
    private PackageRejectionRepository rejections;
    private final ExternalIntakeMaintenanceService maintenanceService;
    private final Clock clock;
    private final RequestActorDisplayName actorDisplayName;

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
                auditService, maintenanceService, new RequestActorDisplayName(), Clock.systemUTC());
    }

    /**
     * Records the push-API or scanner client's configured connection name as the display name of its audit
     * records (task 0017), through the same request-bound holder {@code AuditService} reads.
     */
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
                                 ExternalIntakeMaintenanceService maintenanceService,
                                 RequestActorDisplayName actorDisplayName) {
        this(integrationConnectionService, externalIntakeRepository, packageRepository, createPackageUseCase,
                uploadDocumentUseCase, submitPackageUseCase, processingDispatcher, checksumPort, pdfValidationPort,
                auditService, maintenanceService, actorDisplayName, Clock.systemUTC());
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
        this(integrationConnectionService, externalIntakeRepository, packageRepository, createPackageUseCase,
                uploadDocumentUseCase, submitPackageUseCase, processingDispatcher, checksumPort, pdfValidationPort,
                auditService, maintenanceService, new RequestActorDisplayName(), clock);
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
                          AuditService auditService, ExternalIntakeMaintenanceService maintenanceService,
                          RequestActorDisplayName actorDisplayName, Clock clock) {
        this.actorDisplayName = Objects.requireNonNull(actorDisplayName);
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

    private com.securedoc.platform.application.port.out.IntakeRefusalPort refusals =
            com.securedoc.platform.application.port.out.IntakeRefusalPort.NONE;

    /** Task 0081: counts the cases sent again. */
    public ExternalIntakeService withRefusals(com.securedoc.platform.application.port.out.IntakeRefusalPort port) {
        this.refusals = Objects.requireNonNull(port);
        return this;
    }

    /** The caller's enabled push source; its configured name becomes the display name of this request's records. */
    private IntegrationConnection pushSource(AuthenticatedPrincipal principal) {
        IntegrationConnection source = integrationConnectionService.resolveEnabledPushSource(
                principal.tenantId(), principal.username());
        actorDisplayName.bindMachineClient(principal.actorId(), MachineClientKind.PUSH_API, source.name());
        return source;
    }

    /** The caller's enabled scanner source; its configured name becomes the display name of this request's records. */
    private IntegrationConnection scannerSource(AuthenticatedPrincipal principal) {
        IntegrationConnection source = integrationConnectionService.resolveEnabledScannerSource(
                principal.tenantId(), principal.username());
        actorDisplayName.bindMachineClient(principal.actorId(), MachineClientKind.SCANNER, source.name());
        return source;
    }

    public ExternalIntakeSnapshot createPushIntake(PushIntakeCreateInput input, AuthenticatedPrincipal principal,
                                                   CorrelationId correlationId) {
        Objects.requireNonNull(principal, "Authenticated integration principal is required");
        IntegrationConnection source = pushSource(principal);
        return createMachineIntake(input, source, ExternalIntakeChannel.PUSH_API, principal, correlationId);
    }

    public ExternalIntakeSnapshot createScannerIntake(PushIntakeCreateInput input, AuthenticatedPrincipal principal,
                                                       CorrelationId correlationId) {
        Objects.requireNonNull(principal, "Authenticated scanner principal is required");
        IntegrationConnection source = scannerSource(principal);
        if (input == null || input.documents().size() != 1) {
            throw new ValidationException(LvErrorCode.SCN_001, "Scanner intake requires exactly one consolidated PDF");
        }
        Map<String, String> metadata = new LinkedHashMap<>(input.metadata());
        PushIntakeCreateInput.ManifestDocumentInput scannerDocument = input.documents().get(0);
        String sourceSubmissionId = normalizeRequired(input.externalSourcePackageId(), "externalSourcePackageId");
        String sourceDocumentId = normalizeRequired(scannerDocument.externalSourceDocumentId(), "externalSourceDocumentId");
        final String sourceSha256;
        try {
            sourceSha256 = ExternalManifestDocument.normalizeSha256(scannerDocument.sha256(), true);
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(LvErrorCode.INT_003, ex.getMessage());
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
                input.documents(), input.retry(), input.caseSensitivityLevel(), input.priority());
        return createMachineIntake(scannerInput, source, ExternalIntakeChannel.SCANNER, principal, correlationId);
    }

    /**
     * Scanner provenance is reporting/audit data, not business identity. Require a stable minimum
     * contract so downstream reporting never has to infer scanner source from filenames or logs.
     */
    private void validateScannerProvenance(Map<String, String> metadata) {
        String captureMode = requiredMetadata(metadata, "scannerCaptureMode");
        if (!Set.of("SIMULATOR", "PHYSICAL").contains(captureMode)) {
            throw new ValidationException(LvErrorCode.SCN_003, "scannerCaptureMode must be SIMULATOR or PHYSICAL");
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
            throw new ValidationException(LvErrorCode.SCN_003, "sourceFileSizeBytes must be a positive integer");
        }
        Instant started;
        Instant completed;
        Instant submitted;
        try {
            started = Instant.parse(requiredMetadata(metadata, "scanStartedAt"));
            completed = Instant.parse(requiredMetadata(metadata, "scanCompletedAt"));
            submitted = Instant.parse(requiredMetadata(metadata, "scannerSubmittedAt"));
        } catch (RuntimeException ex) {
            throw new ValidationException(LvErrorCode.SCN_003, "Scanner timestamps must be ISO-8601 instants");
        }
        if (completed.isBefore(started)) {
            throw new ValidationException(LvErrorCode.SCN_003, "scanCompletedAt cannot be before scanStartedAt");
        }
        if (submitted.isBefore(completed)) {
            throw new ValidationException(LvErrorCode.SCN_003, "scannerSubmittedAt cannot be before scanCompletedAt");
        }

        String reportedPages = metadata.get("scannerReportedPageCount");
        if (reportedPages != null && !reportedPages.isBlank()) {
            try {
                int pages = Integer.parseInt(reportedPages.trim());
                if (pages < 1 || pages > SCANNER_REPORTED_PAGES_MAX) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                throw new ValidationException(LvErrorCode.SCN_003, "scannerReportedPageCount must be between 1 and " + SCANNER_REPORTED_PAGES_MAX);
            }
        }

        try { ExternalManifestDocument.normalizeSha256(sourceSha256, true); }
        catch (IllegalArgumentException ex) { throw new ValidationException(LvErrorCode.SCN_003, ex.getMessage()); }
    }

    private String requiredMetadata(Map<String, String> metadata, String key) {
        String value = metadata.get(key);
        if (value == null || value.isBlank()) throw new ValidationException(LvErrorCode.SCN_003, "Scanner metadata is missing " + key);
        return value.trim();
    }

    private String parseUuidMetadata(Map<String, String> metadata, String key) {
        String value = requiredMetadata(metadata, key);
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException ex) {
            throw new ValidationException(LvErrorCode.SCN_003, key + " must be a UUID");
        }
    }

    public ExternalIntakeSnapshot createPollingIntake(PushIntakeCreateInput input, IntegrationConnection source,
                                                      AuthenticatedPrincipal principal, CorrelationId correlationId) {
        if (source == null || !source.enabled() || !source.pollingSource() || !source.tenantId().equals(principal.tenantId())) {
            throw new AccessDeniedException(LvErrorCode.CFG_010, "Polling source is not enabled for this tenant");
        }
        return createMachineIntake(input, source, ExternalIntakeChannel.POLLING, principal, correlationId);
    }

    private ExternalIntakeSnapshot createMachineIntake(PushIntakeCreateInput input, IntegrationConnection source,
                                                        ExternalIntakeChannel channel, AuthenticatedPrincipal principal,
                                                        CorrelationId correlationId) {
        Objects.requireNonNull(input, "External intake request is required");
        validateCreateInput(input, source);
        var priority = com.securedoc.platform.application.intake.CasePriorityService.read(input.priority());

        // Task 0021: levels are read, never refused. An unknown value is taken as Standard with LV-INT-005.
        SensitivityLevel.Received caseSensitivity = SensitivityLevel.fromSource(input.caseSensitivityLevel());
        List<IntakeWarning> warnings = new ArrayList<>();
        if (caseSensitivity.unknown()) {
            warnings.add(new IntakeWarning(SensitivityLevel.UNKNOWN_LEVEL_WARNING,
                    SensitivityLevel.UNKNOWN_LEVEL_WARNING_TEXT, "CASE", null));
        }
        List<ExternalManifestDocument> manifest;
        try {
            manifest = input.documents().stream().map(d -> {
                SensitivityLevel.Received documentSensitivity = SensitivityLevel.fromSource(d.sensitivityLevel());
                ExternalManifestDocument expected = ExternalManifestDocument.expected(
                        d.externalSourceDocumentId(), d.fileName(), d.sha256(), documentSensitivity.level());
                if (documentSensitivity.unknown()) {
                    warnings.add(new IntakeWarning(SensitivityLevel.UNKNOWN_LEVEL_WARNING,
                            SensitivityLevel.UNKNOWN_LEVEL_WARNING_TEXT, "DOCUMENT", expected.externalSourceDocumentId()));
                }
                return expected;
            }).toList();
        } catch (IllegalArgumentException e) {
            throw new ValidationException(LvErrorCode.INT_003, e.getMessage());
        }
        validateManifest(manifest);
        // Task 0061: a sensitive value takes part only as its keyed fingerprint; after a key rotation also by the older key.
        List<String> requestFingerprints = fieldsForDigest.apply(principal.tenantId(), input.businessIdentifiers()).stream()
                .map(fields -> requestFingerprint(input, fields, manifest)).toList();
        String requestFingerprint = requestFingerprints.getFirst();
        Optional<ExternalIntake> existing = externalIntakeRepository.findBySourceIdentity(principal.tenantId(), source.id(),
                normalizeRequired(input.externalSourcePackageId(), "externalSourcePackageId"),
                normalizeRequired(input.sourceRevision(), "sourceRevision"));
        int attemptNumber = 1;
        if (existing.isPresent()) {
            ExternalIntake latest = existing.get();
            if (!requestFingerprints.contains(latest.requestFingerprint())) {
                throw new IntakeConflictException("SOURCE_REVISION_REUSED",
                        "The source package/revision already exists with a different manifest or business payload; use a new revision for resubmission");
            }
            if (latest.status() == ExternalIntakeStatus.INCOMPLETE_EXPIRED && input.retry()) {
                attemptNumber = latest.attemptNumber() + 1;
            } else {
                // A replay of the same request: the same warnings, not recorded again.
                // Task 0081: one already taken in is a duplicate. A polled source lists its cases until it is told, so it is not.
                if (latest.status() == ExternalIntakeStatus.ACCEPTED_FOR_PROCESSING && channel != ExternalIntakeChannel.POLLING) {
                    refusals.record(principal.tenantId(), source.id().value().toString(), LvErrorCode.INT_017);
                }
                return snapshot(latest).withWarnings(warnings);
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
                input.businessIdentifiers(), input.metadata(), intakeFingerprint, sourcePackageId, correlationId,
                caseSensitivity).withPriority(priority, source.id().value().toString()), principal);

        Instant createdAt = Instant.now(clock);
        ExternalIntake intake;
        try {
            intake = ExternalIntake.createAttempt(createResult.packageId(), principal.tenantId(), source.id(), channel,
                    sourcePackageId, sourceRevision, attemptNumber, requestFingerprint, createdAt,
                    createdAt.plus(source.incompleteWindowHours(), ChronoUnit.HOURS), manifest);
        } catch (IllegalArgumentException e) {
            throw new ValidationException(LvErrorCode.INT_020, e.getMessage());
        }
        externalIntakeRepository.save(intake);
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.EXTERNAL_INTAKE_CREATED,
                "ExternalIntake", intake.packageId().toString(), correlationId,
                channel + " intake created: sourceConnection=" + source.id() + ",attempt=" + intake.attemptNumber()
                        + ",expectedDocuments=" + intake.expectedCount() + ",expiresAt=" + intake.expiresAt());
        // The case warning is recorded by CreatePackageService; each unknown document level is recorded here.
        for (IntakeWarning warning : warnings) {
            if ("DOCUMENT".equals(warning.scope())) {
                auditService.recordUnknownSensitivityLevel(principal.tenantId(), principal.actorId(),
                        intake.packageId(), AuditService.SensitivityScope.DOCUMENT, correlationId);
            }
        }
        return snapshot(intake).withWarnings(warnings);
    }

    public ExternalDocumentReceipt receivePushDocument(PackageId packageId, String externalSourceDocumentId,
                                                        String idempotencyKey, String sourceSha256,
                                                        String mimeType, UploadContent content,
                                                        AuthenticatedPrincipal principal, CorrelationId correlationId) {
        Objects.requireNonNull(principal);
        IntegrationConnection source = pushSource(principal);
        return receiveMachineDocument(packageId, externalSourceDocumentId, idempotencyKey, sourceSha256, mimeType, content,
                null, source, ExternalIntakeChannel.PUSH_API, principal, correlationId);
    }

    /**
     * Task 0063 (R3): as above, for a password-protected PDF. The push API is the only way in for one. The password is
     * used once, in memory, and never stored, logged or audited; the encrypted file is not kept: the decrypted PDF is
     * taken in as any other.
     */
    public ExternalDocumentReceipt receivePushDocument(PackageId packageId, String externalSourceDocumentId,
                                                        String idempotencyKey, String sourceSha256,
                                                        String mimeType, UploadContent content, char[] password,
                                                        AuthenticatedPrincipal principal, CorrelationId correlationId) {
        Objects.requireNonNull(principal);
        IntegrationConnection source = pushSource(principal);
        return receiveMachineDocument(packageId, externalSourceDocumentId, idempotencyKey, sourceSha256, mimeType, content,
                password, source, ExternalIntakeChannel.PUSH_API, principal, correlationId);
    }

    /** Task 0063: with the decryption of password-protected PDFs; without it they are refused. */
    public ExternalIntakeService withPdfDecryption(PdfDecryptionPort port) {
        this.pdfDecryptionPort = Objects.requireNonNull(port);
        return this;
    }

    public ExternalDocumentReceipt receiveScannerDocument(PackageId packageId, String externalSourceDocumentId,
                                                           String idempotencyKey, String sourceSha256,
                                                           String mimeType, UploadContent content,
                                                           AuthenticatedPrincipal principal, CorrelationId correlationId) {
        Objects.requireNonNull(principal);
        IntegrationConnection source = scannerSource(principal);
        return receiveMachineDocument(packageId, externalSourceDocumentId, idempotencyKey, sourceSha256, mimeType, content,
                null, source, ExternalIntakeChannel.SCANNER, principal, correlationId);
    }

    public ExternalDocumentReceipt receivePollingDocument(PackageId packageId, String externalSourceDocumentId,
                                                           String idempotencyKey, String sourceSha256, String mimeType,
                                                           UploadContent content, IntegrationConnection source,
                                                           AuthenticatedPrincipal principal, CorrelationId correlationId) {
        if (source == null || !source.enabled() || !source.pollingSource() || !source.tenantId().equals(principal.tenantId())) {
            throw new AccessDeniedException(LvErrorCode.CFG_010, "Polling source is not enabled for this tenant");
        }
        return receiveMachineDocument(packageId, externalSourceDocumentId, idempotencyKey, sourceSha256, mimeType, content,
                null, source, ExternalIntakeChannel.POLLING, principal, correlationId);
    }

    private ExternalDocumentReceipt receiveMachineDocument(PackageId packageId, String externalSourceDocumentId,
                                                            String idempotencyKey, String sourceSha256, String mimeType,
                                                            UploadContent content, char[] password,
                                                            IntegrationConnection source,
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
        catch (IllegalArgumentException e) { throw new ValidationException(LvErrorCode.INT_002, e.getMessage()); }
        ExternalManifestDocument manifestDocument;
        try { manifestDocument = intake.document(normalizedExternalId); }
        catch (IllegalArgumentException e) { throw new ValidationException(LvErrorCode.INT_020, e.getMessage()); }

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
        if (content.contentLength() < 1) {
            throw new ValidationException(LvErrorCode.INT_006, "The file is empty");
        }
        if (content.contentLength() > source.maxPdfSizeBytes()) {
            throw new ValidationException(LvErrorCode.INT_004, "PDF size exceeds the configured source limit");
        }
        if (channel == ExternalIntakeChannel.SCANNER) {
            validateScannerDeclaredSize(packageId, principal.tenantId(), content.contentLength());
        }
        if (!"application/pdf".equalsIgnoreCase(normalizeRequired(mimeType, "mimeType"))) {
            throw new ValidationException(LvErrorCode.INT_007, "Machine intake supports application/pdf only");
        }
        if (manifestDocument.declaredSha256() != null
                && !manifestDocument.declaredSha256().equalsIgnoreCase(normalizedSourceSha)) {
            throw new IntakeConflictException("MANIFEST_CHECKSUM_MISMATCH",
                    "Upload checksum does not match the checksum declared in the manifest");
        }

        Checksum actual;
        try (InputStream checksumStream = content.openStream()) {
            actual = checksumPort.compute(checksumStream);
        } catch (IOException | java.io.UncheckedIOException e) {
            throw receivedCopyUnreadable(packageId, e);
        }
        if (!actual.value().equalsIgnoreCase(normalizedSourceSha)) {
            throw new IntakeConflictException("CONTENT_CHECKSUM_MISMATCH",
                    "Uploaded PDF content does not match X-Content-SHA256");
        }
        if (password != null) {
            try (InputStream encrypted = content.openStream();
                 PdfDecryptionPort.DecryptedPdf decrypted = decrypt(encrypted, password)) {
                return takeIn(intake, manifestDocument, normalizedIdempotency, actual, content,
                        decrypted == null ? content : decrypted, decrypted != null, source, channel, principal, correlationId);
            } catch (IOException | java.io.UncheckedIOException e) {
                throw receivedCopyUnreadable(packageId, e);
            }
        }
        return takeIn(intake, manifestDocument, normalizedIdempotency, actual, content, content, false, source, channel,
                principal, correlationId);
    }

    /** Null when the PDF needs no password. */
    private PdfDecryptionPort.DecryptedPdf decrypt(InputStream encrypted, char[] password) {
        if (pdfDecryptionPort == null) {
            throw new ValidationException(LvErrorCode.INT_008, "Password-protected files are not taken in here");
        }
        return pdfDecryptionPort.decrypt(encrypted, password).orElse(null);
    }

    /**
     * {@code received} is what the source sent (its size and checksum identify the document for a retry); {@code content}
     * is what is checked, stored and processed: the same, or (task 0063) its decrypted copy.
     */
    private ExternalDocumentReceipt takeIn(ExternalIntake intake, ExternalManifestDocument manifestDocument,
                                           String normalizedIdempotency, Checksum actual, UploadContent received,
                                           UploadContent content, boolean decrypted, IntegrationConnection source,
                                           ExternalIntakeChannel channel, AuthenticatedPrincipal principal,
                                           CorrelationId correlationId) {
        PackageId packageId = intake.packageId();
        String normalizedExternalId = manifestDocument.externalSourceDocumentId();
        final int pageCount;
        try (InputStream pdfStream = content.openStream()) {
            pageCount = pdfValidationPort.inspect(pdfStream).pageCount();
            if (pageCount > CaseLimits.MAX_PAGES_PER_FILE) {
                // Task 0038: the case limit holds even when the source allows more. Task 0039: checked first, so a
                // source stored earlier above the limit (now read as the limit) still gets this code, not LV-INT-009.
                throw new ValidationException(LvErrorCode.INT_028, "The PDF has more than "
                        + CaseLimits.MAX_PAGES_PER_FILE + " pages");
            }
            if (pageCount > source.maxPdfPages()) {
                throw new ValidationException(LvErrorCode.INT_009, "PDF page count exceeds the configured source limit");
            }
        } catch (IOException | java.io.UncheckedIOException e) {
            throw receivedCopyUnreadable(packageId, e);
        }

        var uploadResult = uploadDocumentUseCase.execute(UploadDocumentCommand.of(packageId,
                manifestDocument.originalFileName(), "application/pdf", content, correlationId,
                manifestDocument.sensitivityLevel()), principal);
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
            recordScannerReceiptProvenance(packageId, principal.tenantId(), received.contentLength(),
                    pageCount, actual.value());
        }
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.EXTERNAL_DOCUMENT_RECEIVED,
                "Document", uploadResult.documentId().toString(), packageId, correlationId,
                "External document durably received: sourceConnection=" + source.id() + ",received="
                        + intake.receivedCount() + "/" + intake.expectedCount()
                        + (decrypted ? ", decrypted with a supplied password" : ""));

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
                .orElseThrow(() -> new ValidationException(LvErrorCode.SCN_002, "Scanner metadata is missing sourceFileSizeBytes"));
        final long declaredSize;
        try {
            declaredSize = Long.parseLong(declared);
        } catch (NumberFormatException ex) {
            throw new ValidationException(LvErrorCode.SCN_002, "sourceFileSizeBytes must be a positive integer");
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
        IntegrationConnection source = pushSource(principal);
        ExternalIntake intake = authorizedIntake(principal, source, packageId, ExternalIntakeChannel.PUSH_API, true);
        expireForRequestIfDue(intake, principal.actorId(), correlationId);
        return snapshot(intake);
    }

    public ExternalIntakeSnapshot scannerStatus(PackageId packageId, AuthenticatedPrincipal principal, CorrelationId correlationId) {
        IntegrationConnection source = scannerSource(principal);
        ExternalIntake intake = authorizedIntake(principal, source, packageId, ExternalIntakeChannel.SCANNER, true);
        expireForRequestIfDue(intake, principal.actorId(), correlationId);
        return snapshot(intake);
    }

    public ExternalIntakeSnapshot pollingStatus(PackageId packageId, IntegrationConnection source,
                                                AuthenticatedPrincipal principal, CorrelationId correlationId) {
        if (source == null || !source.enabled() || !source.pollingSource() || !source.tenantId().equals(principal.tenantId())) {
            throw new AccessDeniedException(LvErrorCode.CFG_010, "Polling source is not enabled for this tenant");
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
            throw new AccessDeniedException(LvErrorCode.AUT_008, "Authenticated integration source does not own this intake");
        }
        return intake;
    }

    private void expireForRequestIfDue(ExternalIntake intake, ActorId actorId, CorrelationId correlationId) {
        maintenanceService.expireIfDue(intake, actorId, correlationId);
    }

    /**
     * Task 0023 #357: the server could not read its own spooled copy of a received file. That is a server-side storage
     * fault (503, retryable), not the client's mistake; logged with the type and a code, never the message.
     */
    private static TechnicalDependencyException receivedCopyUnreadable(PackageId packageId, Exception cause) {
        log.warn("EXTERNAL_DOCUMENT_COPY_UNREADABLE packageId={} reasonCode=STORAGE_IO exceptionType={}",
                packageId, cause.getClass().getSimpleName());
        return new TechnicalDependencyException(TechnicalDependency.STORAGE, TechnicalFailureCode.STORAGE_IO, true,
                "The received document could not be read on the server; send it again", cause);
    }

    /**
     * Task 0023 #49, #55: refuse a manifest that repeats a document id or names a file that could never be stored,
     * before the case is created, and say what is wrong without repeating the value.
     */
    private static void validateManifest(List<ExternalManifestDocument> manifest) {
        Set<String> ids = new HashSet<>();
        for (ExternalManifestDocument document : manifest) {
            if (!ids.add(document.externalSourceDocumentId())) {
                throw new ValidationException(LvErrorCode.INT_020, "Document manifest lists the same externalSourceDocumentId more than once");
            }
            try {
                com.securedoc.platform.domain.vo.FileName.of(document.originalFileName());
            } catch (IllegalArgumentException e) {
                throw new ValidationException(LvErrorCode.INT_003, "Document manifest fileName is not valid: " + e.getMessage());
            }
        }
    }

    private void validateCreateInput(PushIntakeCreateInput input, IntegrationConnection source) {
        normalizeRequired(input.externalSourcePackageId(), "externalSourcePackageId");
        normalizeRequired(input.sourceRevision(), "sourceRevision");
        if (input.documents().isEmpty()) throw new ValidationException(LvErrorCode.INT_003, "Document manifest must contain at least one PDF");
        if (input.documents().size() > CaseLimits.MAX_FILES_PER_CASE) {
            // Task 0038: the case limit holds even when the source allows more. Task 0039: checked first, so a source
            // stored earlier above the limit (now read as the limit) still gets this code, not LV-INT-010.
            throw new ValidationException(LvErrorCode.INT_029, "Document manifest lists more than "
                    + CaseLimits.MAX_FILES_PER_CASE + " files");
        }
        if (input.documents().size() > source.maxDocumentsPerIntake()) {
            throw new ValidationException(LvErrorCode.INT_010, "Document manifest exceeds the configured source document limit");
        }
    }

    private ExternalIntakeSnapshot snapshot(ExternalIntake intake) {
        var pkg = packageRepository.findByIdAndTenantId(intake.packageId(), intake.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", intake.packageId().toString()));
        return new ExternalIntakeSnapshot(intake.packageId().value(), intake.externalSourcePackageId(), intake.sourceRevision(),
                intake.attemptNumber(), intake.status(), pkg.status(), intake.expectedCount(), intake.receivedCount(),
                intake.missingExternalSourceDocumentIds(), intake.createdAt(), intake.expiresAt(), intake.acceptedAt(), intake.expiredAt())
                .withRejection(rejections == null || pkg.status() != com.securedoc.platform.domain.document.PackageStatus.REJECTED ? null
                        : rejections.find(intake.tenantId(), intake.packageId()).map(r -> r.code().code()).orElse(null));
    }

    private java.util.function.BiFunction<com.securedoc.platform.domain.identity.TenantId, Map<String, String>, List<Map<String, String>>>
            fieldsForDigest = (tenant, fields) -> List.of(fields);

    /** Task 0061: the case fields as they may take part in a plain digest. */
    public ExternalIntakeService withCaseFields(com.securedoc.platform.application.casefields.CaseFieldService caseFields) {
        this.fieldsForDigest = caseFields::forDigest;
        return this;
    }

    /** Task 0071: with the reasons of rejected cases. */
    public ExternalIntakeService withRejections(PackageRejectionRepository value) {
        this.rejections = Objects.requireNonNull(value);
        return this;
    }

    private ExternalDocumentReceipt receipt(ExternalIntake intake, ExternalManifestDocument document, boolean duplicate) {
        return new ExternalDocumentReceipt(intake.packageId().value(), document.receivedDocumentId().value(),
                document.externalSourceDocumentId(), document.receivedSha256(), duplicate, intake.status(),
                intake.expectedCount(), intake.receivedCount(), intake.missingExternalSourceDocumentIds(), intake.expiresAt());
    }

    private String requestFingerprint(PushIntakeCreateInput input, Map<String, String> fields, List<ExternalManifestDocument> manifest) {
        StringBuilder canonical = new StringBuilder();
        appendCanonical(canonical, "package", normalizeRequired(input.externalSourcePackageId(), "externalSourcePackageId"));
        appendCanonical(canonical, "revision", normalizeRequired(input.sourceRevision(), "sourceRevision"));
        new TreeMap<>(fields).forEach((k, v) ->
                appendCanonical(canonical, "business:" + k, v));
        new TreeMap<>(input.metadata()).forEach((k, v) ->
                appendCanonical(canonical, "metadata:" + k, v));
        // Task 0021: a level takes part only when one was sent, so requests without levels keep the fingerprint
        // they had before levels existed (a retry of an older intake still matches).
        if (input.caseSensitivityLevel() != null) {
            appendCanonical(canonical, "case:sensitivity", input.caseSensitivityLevel());
        }
        // Task 0073: likewise.
        if (input.priority() != null) appendCanonical(canonical, "case:priority", input.priority());
        Map<String, String> sentDocumentLevels = new HashMap<>();
        input.documents().forEach(d -> {
            if (d.sensitivityLevel() != null && d.externalSourceDocumentId() != null) {
                sentDocumentLevels.put(d.externalSourceDocumentId().trim(), d.sensitivityLevel());
            }
        });
        manifest.stream().sorted(Comparator.comparing(ExternalManifestDocument::externalSourceDocumentId)).forEach(d -> {
            appendCanonical(canonical, "document:id", d.externalSourceDocumentId());
            appendCanonical(canonical, "document:file", d.originalFileName());
            appendCanonical(canonical, "document:sha256", d.declaredSha256() == null ? "" : d.declaredSha256());
            String sentLevel = sentDocumentLevels.get(d.externalSourceDocumentId());
            if (sentLevel != null) appendCanonical(canonical, "document:sensitivity", sentLevel);
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
        if (value == null || value.isBlank()) {
            // #27: a blank Idempotency-Key header is LV-INT-002; a blank field (#31) is LV-INT-003.
            throw new ValidationException("Idempotency-Key".equals(label) ? LvErrorCode.INT_002 : LvErrorCode.INT_003,
                    label + " is required");
        }
        return value.trim();
    }
}

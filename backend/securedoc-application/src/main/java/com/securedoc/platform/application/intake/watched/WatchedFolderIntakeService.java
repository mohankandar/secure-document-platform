package com.securedoc.platform.application.intake.watched;

import com.securedoc.platform.domain.document.CaseLimits;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.command.*;
import com.securedoc.platform.application.intake.model.IntakeFailure;
import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import com.securedoc.platform.application.port.in.*;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import com.securedoc.platform.application.licensing.LicenseEnforcementException;

import java.util.*;

/**
 * One watched-folder intake scan cycle. The active scanner lease is owned outside this use case;
 * per-candidate filesystem claims provide a second fencing layer across lease transitions.
 *
 * R7 makes the create/upload/submit sequence restartable. A recovered source with the same
 * sourceReference + intake fingerprint resumes its existing package instead of being falsely
 * archived as a duplicate. Candidate/persisted documents are reconciled by safe structural
 * metadata plus SHA-256 before a CREATED package is submitted.
 */
public final class WatchedFolderIntakeService implements ScanWatchedFolderUseCase, ListIntakeObservationsQuery {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WatchedFolderIntakeService.class);
    private final WatchedFolderSourcePort sourcePort;
    private final WatchedFolderLeasePort leasePort;
    private final IntakeObservationRepository observationRepository;
    private final PackageRepository packageRepository;
    private final CreatePackageUseCase createPackageUseCase;
    private final UploadDocumentUseCase uploadDocumentUseCase;
    private final SubmitPackageUseCase submitPackageUseCase;
    private final PackageProcessingDispatcher processingDispatcher;
    private final AuditService auditService;
    private final Duration incompleteVisibility;
    private final Duration staleClaimAfter;
    /** Task 0021: the level Tenant IT set for the folder; every file picked up from it gets that level. */
    private com.securedoc.platform.application.licensing.IntakeLicence licence;
    private com.securedoc.platform.application.intake.IntakeGate gate = com.securedoc.platform.application.intake.IntakeGate.OPEN;
    /** Task 0067: drops one check takes while the queue is long, before its share is taken. */
    static final int DROPS_PER_CHECK = 50;
    private final java.util.function.Function<com.securedoc.platform.domain.identity.TenantId, com.securedoc.platform.domain.document.SensitivityLevel> folderSensitivityLevel;

    public WatchedFolderIntakeService(WatchedFolderSourcePort sourcePort,
                                      WatchedFolderLeasePort leasePort,
                                      IntakeObservationRepository observationRepository,
                                      PackageRepository packageRepository,
                                      CreatePackageUseCase createPackageUseCase,
                                      UploadDocumentUseCase uploadDocumentUseCase,
                                      SubmitPackageUseCase submitPackageUseCase,
                                      PackageProcessingDispatcher processingDispatcher,
                                      AuditService auditService,
                                      Duration incompleteVisibility,
                                      Duration staleClaimAfter) {
        this(sourcePort, leasePort, observationRepository, packageRepository, createPackageUseCase, uploadDocumentUseCase,
                submitPackageUseCase, processingDispatcher, auditService, incompleteVisibility, staleClaimAfter,
                tenant -> com.securedoc.platform.domain.document.SensitivityLevel.STANDARD);
    }

    public WatchedFolderIntakeService(WatchedFolderSourcePort sourcePort,
                                      WatchedFolderLeasePort leasePort,
                                      IntakeObservationRepository observationRepository,
                                      PackageRepository packageRepository,
                                      CreatePackageUseCase createPackageUseCase,
                                      UploadDocumentUseCase uploadDocumentUseCase,
                                      SubmitPackageUseCase submitPackageUseCase,
                                      PackageProcessingDispatcher processingDispatcher,
                                      AuditService auditService,
                                      Duration incompleteVisibility,
                                      Duration staleClaimAfter,
                                      java.util.function.Function<com.securedoc.platform.domain.identity.TenantId, com.securedoc.platform.domain.document.SensitivityLevel> folderSensitivityLevel) {
        this.folderSensitivityLevel = Objects.requireNonNull(folderSensitivityLevel);
        this.sourcePort=Objects.requireNonNull(sourcePort); this.leasePort=Objects.requireNonNull(leasePort);
        this.observationRepository=Objects.requireNonNull(observationRepository);
        this.packageRepository=Objects.requireNonNull(packageRepository); this.createPackageUseCase=Objects.requireNonNull(createPackageUseCase);
        this.uploadDocumentUseCase=Objects.requireNonNull(uploadDocumentUseCase); this.submitPackageUseCase=Objects.requireNonNull(submitPackageUseCase);
        this.processingDispatcher=Objects.requireNonNull(processingDispatcher); this.auditService=Objects.requireNonNull(auditService);
        this.incompleteVisibility=incompleteVisibility==null?Duration.ofMinutes(15):incompleteVisibility;
        this.staleClaimAfter=staleClaimAfter==null?Duration.ofMinutes(5):staleClaimAfter;
    }

    /** Task 0057: the same service on the word of the licence. Without it (tests of the folder itself) nothing is refused. */
    public WatchedFolderIntakeService withLicence(com.securedoc.platform.application.licensing.IntakeLicence licence) {
        this.licence = Objects.requireNonNull(licence);
        return this;
    }

    /** Task 0067: the same service, taking less while the queue is long and nothing while space is short. */
    public WatchedFolderIntakeService withGate(com.securedoc.platform.application.intake.IntakeGate gate) {
        this.gate = Objects.requireNonNull(gate);
        return this;
    }

    public com.securedoc.platform.application.intake.IntakeGate gate() {
        return gate;
    }

    /** Task 0057: why the folder is not read now; empty when it is. */
    public Optional<LicenseEnforcementException> licenceRefusal(com.securedoc.platform.domain.identity.TenantId tenantId) {
        return licence == null ? Optional.empty() : licence.refusal(tenantId);
    }

    /** Task 0057: as {@link #licenceRefusal}, recorded and thrown (a scan a person asked for). */
    public void requireLicence(AuthenticatedPrincipal principal, CorrelationId correlationId) {
        if (licence != null) licence.requireIntake(principal.tenantId(), principal.actorId(), correlationId);
    }

    @Override
    public ScanWatchedFolderResult scan(AuthenticatedPrincipal principal, CorrelationId correlationId) {
        return scan(principal, correlationId, WatchedFolderScanLease.singleNode());
    }

    public ScanWatchedFolderResult scan(AuthenticatedPrincipal principal,
                                        CorrelationId correlationId,
                                        WatchedFolderScanLease scanLease) {
        Objects.requireNonNull(scanLease, "scanLease");
        // Task 0057: with intake stopped by the licence no drop is claimed, moved or recorded; each waits where it is.
        Optional<LicenseEnforcementException> stopped = licenceRefusal(principal.tenantId());
        if (stopped.isPresent()) throw stopped.get();
        if (!leaseIsCurrent(scanLease)) return new ScanWatchedFolderResult(0, 0, 0, 0, 0);
        sourcePort.recoverStaleClaims(scanLease, staleClaimAfter, authoritativeNow(scanLease));
        List<WatchedFolderCandidate> candidates = sourcePort.discover();
        int waiting=0, accepted=0, rejected=0, duplicates=0;
        // Task 0067: while the queue is long, a check takes a few drops; the others stay where they are.
        int left = gate.slowdown() == 1 ? Integer.MAX_VALUE : gate.share(DROPS_PER_CHECK);
        for (WatchedFolderCandidate discovered : candidates) {
            if (!discovered.triggerPresent()) {
                waiting++;
                recordWaiting(principal, discovered, correlationId);
                continue;
            }

            if (!leaseIsCurrent(scanLease) || licenceRefusal(principal.tenantId()).isPresent()) break;
            if (left-- <= 0 || gate.full()) break;

            Optional<WatchedFolderCandidate> claimed = sourcePort.claim(
                    discovered.sourceReference(), scanLease, authoritativeNow(scanLease));
            if (claimed.isEmpty()) continue;
            WatchedFolderCandidate candidate = claimed.get();

            if (!candidate.manifestValid()) {
                rejected++;
                reject(principal,candidate,
                        candidate.rejectionCode()==null?IntakeRejectionCode.MALFORMED_TRIGGER:candidate.rejectionCode(),
                        null,correlationId,scanLease,null);
                continue;
            }

            String fingerprint;
            try {
                fingerprint = fingerprint(principal, candidate);
            } catch (Exception e) {
                // The drop could not be read while hashing: a real I/O fault. The type is kept, never the message.
                rejected++;
                reject(principal,candidate,IntakeRejectionCode.SOURCE_IO_ERROR,null,correlationId,scanLease,
                        new IntakeFailure("SOURCE_IO_ERROR", e.getClass().getSimpleName()));
                continue;
            }

            Optional<IntakeObservation> prior = priorObservation(principal, candidate);
            if (prior.isPresent() && fingerprint.equals(prior.get().fingerprint())
                    && (prior.get().status() == IntakeObservationStatus.ACCEPTED
                        || prior.get().status() == IntakeObservationStatus.DUPLICATE)) {
                if (!leaseStillCurrent(scanLease)) continue;
                duplicates++;
                // Task 0023 C2/#41: the same content was taken in before. Record it once (a drop left in place is
                // seen again on every scan, and is not recorded again while its latest record is this duplicate).
                if (prior.get().status() == IntakeObservationStatus.ACCEPTED) {
                    record(principal,candidate,IntakeObservationStatus.DUPLICATE,IntakeRejectionCode.DUPLICATE_INTAKE,
                            fingerprint,prior.get().packageId(),correlationId,null);
                }
                applyDispositionBestEffort(candidate.sourceReference(), IntakeDisposition.DUPLICATE, scanLease);
                continue;
            }

            PackageId packageId;
            try {
                packageId = resolveCreateOrResume(principal, candidate, fingerprint, correlationId, scanLease);
                requireCurrentLease(scanLease);
            } catch (DuplicateCandidateException duplicate) {
                if (!leaseStillCurrent(scanLease)) continue;
                duplicates++;
                record(principal,candidate,IntakeObservationStatus.DUPLICATE,IntakeRejectionCode.DUPLICATE_INTAKE,
                        fingerprint,null,correlationId,null);
                applyDispositionBestEffort(candidate.sourceReference(), IntakeDisposition.DUPLICATE, scanLease);
                continue;
            } catch (LicenseEnforcementException stoppedMeanwhile) {
                // Task 0057: the licence stopped intake while this drop was being taken in. It is not refused: the claim
                // is left, so a later scan recovers it and goes on with the case it made, if it made one.
                log.warn("WATCHED_FOLDER_DROP_LEFT_FOR_LATER reasonCode={}", stoppedMeanwhile.errorCode());
                break;
            } catch (LostScanLeaseException lostLease) {
                // Do not terminally dispose a candidate after losing the distributed generation.
                // The next owner will recover the claim and resume the persisted package.
                continue;
            } catch (RuntimeException e) {
                if (!leaseStillCurrent(scanLease)) continue;
                rejected++;
                // Task 0023 C2: the real reason (identifier, licence, capability, inactive tenant, storage, file, ...).
                boolean recoveryConflict = e instanceof RecoveryConflictException;
                IntakeRejectionCode reason = recoveryConflict
                        ? IntakeRejectionCode.RECOVERY_CONFLICT : IntakeFailure.rejectionCodeOf(e);
                IntakeFailure cause = recoveryConflict
                        ? new IntakeFailure(IntakeRejectionCode.RECOVERY_CONFLICT.name(), e.getClass().getSimpleName())
                        : IntakeFailure.of(e);
                reject(principal,candidate,reason,fingerprint,correlationId,scanLease,cause);
                continue;
            }

            record(principal,candidate,IntakeObservationStatus.ACCEPTED,null,fingerprint,packageId,correlationId,null);
            accepted++;
            applyDispositionBestEffort(candidate.sourceReference(), IntakeDisposition.ACCEPTED, scanLease);
            triggerProcessingIfSubmitted(packageId, principal, correlationId);
        }
        return new ScanWatchedFolderResult(candidates.size(),waiting,accepted,rejected,duplicates);
    }

    private PackageId resolveCreateOrResume(AuthenticatedPrincipal principal,
                                            WatchedFolderCandidate candidate,
                                            String fingerprint,
                                            CorrelationId correlationId,
                                            WatchedFolderScanLease scanLease) {
        Optional<DocumentPackage> existing = packageRepository.findByTenantIdAndIntakeFingerprint(
                principal.tenantId(), fingerprint);
        if (existing.isPresent()) {
            return resumeExisting(principal, candidate, existing.get(), correlationId, scanLease);
        }

        // Task 0038: a drop with more files than a case holds is refused before a case is made.
        if (candidate.documents().size() > CaseLimits.MAX_FILES_PER_CASE) {
            throw new com.securedoc.platform.application.exception.ValidationException(
                    com.securedoc.platform.domain.error.LvErrorCode.INT_029, "The folder drop has more than "
                    + CaseLimits.MAX_FILES_PER_CASE + " files");
        }
        requireCurrentLease(scanLease);
        PackageId createdPackageId;
        try {
            createdPackageId = createPackageUseCase.execute(
                    CreatePackageCommand.watchedFolder(candidate.businessIdentifiers(), candidate.metadata(), fingerprint,
                            dropReference(candidate), correlationId,
                            com.securedoc.platform.domain.document.SensitivityLevel.Received.of(
                                    folderSensitivityLevel.apply(principal.tenantId())))
                            .withPriority(candidate.priority(), com.securedoc.platform.application.intake.CasePriorityService.folder(
                                    WatchedFolderSettingsService.INSTALLED_FOLDER)), principal).packageId();
        } catch (RuntimeException createFailure) {
            // A competing transaction may have won the unique fingerprint insert after our pre-check.
            // Re-read and classify the winner rather than translating the race to SOURCE_IO_ERROR.
            Optional<DocumentPackage> winner = packageRepository.findByTenantIdAndIntakeFingerprint(
                    principal.tenantId(), fingerprint);
            if (winner.isPresent()) {
                return resumeExisting(principal, candidate, winner.get(), correlationId, scanLease);
            }
            throw createFailure;
        }

        DocumentPackage created = packageRepository.findByIdAndTenantId(createdPackageId, principal.tenantId())
                .orElseThrow(() -> new IllegalStateException("Created watched-folder package is not readable"));
        return resumeExisting(principal, candidate, created, correlationId, scanLease);
    }

    private PackageId resumeExisting(AuthenticatedPrincipal principal,
                                     WatchedFolderCandidate candidate,
                                     DocumentPackage existing,
                                     CorrelationId correlationId,
                                     WatchedFolderScanLease scanLease) {
        if (!sameWatchedSource(existing, candidate)) {
            throw new DuplicateCandidateException();
        }

        List<CandidateDocument> expected = candidateDocuments(candidate);
        DocumentPackage current = existing;

        if (current.status() == PackageStatus.CREATED) {
            List<WatchedFolderDocument> missing = missingDocumentsOrThrow(current, expected);
            for (WatchedFolderDocument document : missing) {
                requireCurrentLease(scanLease);
                uploadDocumentUseCase.execute(UploadDocumentCommand.of(current.id(), document.fileName(),
                        document.mimeType(), document.content(), correlationId,
                        // Every file gets the folder's level, which the case already holds (so a resumed drop never mixes levels).
                        current.sensitivityLevel()), principal);
            }

            current = packageRepository.findByIdAndTenantId(current.id(), principal.tenantId())
                    .orElseThrow(() -> new IllegalStateException("Recovered watched-folder package disappeared"));
            assertExactDocumentSet(current, expected);

            if (current.status() == PackageStatus.CREATED) {
                requireCurrentLease(scanLease);
                try {
                    submitPackageUseCase.execute(SubmitPackageCommand.of(current.id(), correlationId), principal);
                } catch (RuntimeException submitFailure) {
                    DocumentPackage after = packageRepository.findByIdAndTenantId(current.id(), principal.tenantId())
                            .orElseThrow(() -> submitFailure);
                    if (after.status() == PackageStatus.CREATED) throw submitFailure;
                    assertExactDocumentSet(after, expected);
                }
            }
        } else {
            // Recovery after submit but before observation/disposition is an accepted original intake,
            // not a duplicate. Verify it is complete before finalizing the source.
            assertExactDocumentSet(current, expected);
        }
        return current.id();
    }

    private boolean sameWatchedSource(DocumentPackage existing, WatchedFolderCandidate candidate) {
        // A case made before task 0023 holds the drop folder's own name; later cases hold the drop reference.
        return existing.packageType() == PackageType.FOLDER_INTAKE
                && existing.sourceReference()
                        .filter(ref -> ref.equals(dropReference(candidate)) || ref.equals(candidate.sourceReference()))
                        .isPresent();
    }

    /** Task 0023 B3: the folder key plus a short hash of the drop folder name; the name itself is never stored. */
    private static String dropReference(WatchedFolderCandidate candidate) {
        return WatchedFolderDropReference.of(candidate.sourceReference());
    }

    /** The drop's latest observation; one recorded before task 0023 is found under the drop folder's own name. */
    private Optional<IntakeObservation> priorObservation(AuthenticatedPrincipal principal, WatchedFolderCandidate candidate) {
        Optional<IntakeObservation> current = observationRepository.findByTenantAndSource(
                principal.tenantId(), dropReference(candidate));
        return current.isPresent() ? current
                : observationRepository.findByTenantAndSource(principal.tenantId(), candidate.sourceReference());
    }

    private List<WatchedFolderDocument> missingDocumentsOrThrow(DocumentPackage existing,
                                                                 List<CandidateDocument> expected) {
        Map<DocumentSignature,Integer> remaining = signatureCounts(expected.stream()
                .map(CandidateDocument::signature).toList());
        for (Document document : existing.documents()) {
            DocumentSignature persisted = persistedSignature(document);
            Integer count = remaining.get(persisted);
            if (count == null || count == 0) {
                throw new RecoveryConflictException("Persisted watched-folder document does not match recovered source");
            }
            if (count == 1) remaining.remove(persisted); else remaining.put(persisted, count - 1);
        }

        List<WatchedFolderDocument> missing = new ArrayList<>();
        Map<DocumentSignature,Integer> availablePersisted = signatureCounts(existing.documents().stream()
                .map(this::persistedSignature).toList());
        for (CandidateDocument candidate : expected) {
            int count = availablePersisted.getOrDefault(candidate.signature(), 0);
            if (count > 0) {
                if (count == 1) availablePersisted.remove(candidate.signature());
                else availablePersisted.put(candidate.signature(), count - 1);
            } else {
                missing.add(candidate.document());
            }
        }
        return missing;
    }

    private void assertExactDocumentSet(DocumentPackage existing, List<CandidateDocument> expected) {
        Map<DocumentSignature,Integer> expectedCounts = signatureCounts(expected.stream()
                .map(CandidateDocument::signature).toList());
        Map<DocumentSignature,Integer> actualCounts = signatureCounts(existing.documents().stream()
                .map(this::persistedSignature).toList());
        if (!expectedCounts.equals(actualCounts)) {
            throw new RecoveryConflictException("Recovered watched-folder package document set is incomplete or conflicting");
        }
    }

    private Map<DocumentSignature,Integer> signatureCounts(List<DocumentSignature> signatures) {
        Map<DocumentSignature,Integer> counts = new HashMap<>();
        for (DocumentSignature signature : signatures) counts.merge(signature, 1, Integer::sum);
        return counts;
    }

    private List<CandidateDocument> candidateDocuments(WatchedFolderCandidate candidate) {
        List<CandidateDocument> result = new ArrayList<>();
        for (WatchedFolderDocument document : candidate.documents()) {
            result.add(new CandidateDocument(document, candidateSignature(document)));
        }
        return result;
    }

    private DocumentSignature candidateSignature(WatchedFolderDocument document) {
        return new DocumentSignature(document.fileName(), document.mimeType(), document.content().contentLength(),
                sha256(document.content()));
    }

    private DocumentSignature persistedSignature(Document document) {
        if (!"SHA-256".equalsIgnoreCase(document.checksum().algorithm())) {
            throw new RecoveryConflictException("Recovered watched-folder document uses an unsupported checksum algorithm");
        }
        return new DocumentSignature(document.fileName().value(), document.mimeType().value(), document.sizeBytes(),
                document.checksum().value().toLowerCase(Locale.ROOT));
    }

    private String sha256(UploadContent content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = content.openStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) >= 0) if (n > 0) digest.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (Exception e) {
            throw new RecoveryConflictException("Unable to verify recovered watched-folder document", e);
        }
    }

    private void triggerProcessingIfSubmitted(PackageId packageId,
                                              AuthenticatedPrincipal principal,
                                              CorrelationId correlationId) {
        try {
            Optional<DocumentPackage> current = packageRepository.findByIdAndTenantId(packageId, principal.tenantId());
            if (current.isEmpty() || current.get().status() != PackageStatus.SUBMITTED) return;
            processingDispatcher.dispatch(TriggerPackageProcessingCommand.of(packageId, principal.tenantId(),
                    principal.actorId(), correlationId));
        } catch (RuntimeException dispatchFailure) {
            // Processing owns its technical failure state; do not rewrite an accepted intake as rejected.
            log.warn("WATCHED_FOLDER_DISPATCH_FAILED packageId={} reasonCode=DISPATCH_FAILED exceptionType={}",
                    packageId, dispatchFailure.getClass().getSimpleName());
        }
    }

    private boolean leaseIsCurrent(WatchedFolderScanLease scanLease) {
        if (scanLease.fencingToken() == 0L) return true;
        return leasePort.isCurrent(scanLease.leaseName(), scanLease.ownerId(), scanLease.fencingToken());
    }

    private boolean leaseStillCurrent(WatchedFolderScanLease scanLease) {
        if (scanLease.fencingToken() == 0L) return true;
        try {
            return leasePort.isCurrent(scanLease.leaseName(), scanLease.ownerId(), scanLease.fencingToken());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private void requireCurrentLease(WatchedFolderScanLease scanLease) {
        if (scanLease.fencingToken() == 0L) return;
        try {
            if (!leasePort.isCurrent(scanLease.leaseName(), scanLease.ownerId(), scanLease.fencingToken())) {
                throw new LostScanLeaseException();
            }
        } catch (LostScanLeaseException e) {
            throw e;
        } catch (RuntimeException unavailable) {
            throw new LostScanLeaseException();
        }
    }

    private Instant authoritativeNow(WatchedFolderScanLease scanLease) {
        if (scanLease.fencingToken() == 0L) return Instant.now();
        try {
            return leasePort.currentTime();
        } catch (RuntimeException unavailable) {
            throw new LostScanLeaseException();
        }
    }

    @Override
    public List<IntakeObservation> listVisible(AuthenticatedPrincipal principal, int limit) {
        Instant now=Instant.now();
        return observationRepository.findRecentByTenant(principal.tenantId(),limit).stream()
                .filter(o -> o.status()!=IntakeObservationStatus.WAITING_FOR_TRIGGER || !now.isBefore(o.visibleAfter()))
                .toList();
    }

    private void recordWaiting(AuthenticatedPrincipal principal, WatchedFolderCandidate candidate, CorrelationId cid) {
        Optional<IntakeObservation> existing=priorObservation(principal,candidate);
        if(existing.isPresent() && existing.get().status()==IntakeObservationStatus.WAITING_FOR_TRIGGER) return;
        Instant now=Instant.now();
        UUID id=UUID.randomUUID();
        Instant firstSeen=now;
        IntakeObservation observation=new IntakeObservation(id,principal.tenantId(),dropReference(candidate),
                IntakeObservationStatus.WAITING_FOR_TRIGGER,null,null,null,firstSeen,firstSeen.plus(incompleteVisibility),now);
        observationRepository.save(observation);
        auditService.recordSuccess(principal.tenantId(),principal.actorId(),AuditAction.INTAKE_WAITING_FOR_TRIGGER,
                "WatchedFolderIntake",observation.id().toString(),cid,"Watched folder observed without trigger");
    }

    private void reject(AuthenticatedPrincipal principal,
                        WatchedFolderCandidate candidate,
                        IntakeRejectionCode code,
                        String fingerprint,
                        CorrelationId cid,
                        WatchedFolderScanLease scanLease,
                        IntakeFailure cause) {
        // Logged with the drop reference, the code and the exception type only (task 0023 C2, B3).
        log.warn("WATCHED_FOLDER_DROP_REJECTED drop={} reasonCode={}{}", dropReference(candidate), code,
                cause == null ? "" : " causeCode=" + cause.code() + " exceptionType=" + cause.exceptionType());
        record(principal,candidate,IntakeObservationStatus.REJECTED,code,fingerprint,null,cid,cause);
        applyDispositionBestEffort(candidate.sourceReference(), IntakeDisposition.REJECTED, scanLease);
    }

    private void record(AuthenticatedPrincipal principal, WatchedFolderCandidate candidate, IntakeObservationStatus status,
                        IntakeRejectionCode code, String fingerprint, PackageId packageId, CorrelationId cid,
                        IntakeFailure cause) {
        Instant now=Instant.now();
        Optional<IntakeObservation> latest=priorObservation(principal,candidate);
        Optional<IntakeObservation> waiting=latest.filter(o -> o.status() == IntakeObservationStatus.WAITING_FOR_TRIGGER);
        Instant first=waiting.map(IntakeObservation::firstSeenAt).orElse(now);
        Instant visible=waiting.map(IntakeObservation::visibleAfter).orElse(now);
        UUID id=waiting.map(IntakeObservation::id).orElse(UUID.randomUUID());
        observationRepository.save(new IntakeObservation(id,principal.tenantId(),dropReference(candidate),
                status,code,fingerprint,packageId,first,visible,now));
        AuditAction action=switch(status){
            case ACCEPTED->AuditAction.INTAKE_ACCEPTED;
            case REJECTED->AuditAction.INTAKE_REJECTED;
            case DUPLICATE->AuditAction.INTAKE_DUPLICATE;
            default->AuditAction.INTAKE_WAITING_FOR_TRIGGER;
        };
        // Task 0025: a refusal, duplicate or wait records its LexaVault code first (the cause's own when known).
        com.securedoc.platform.domain.error.LvErrorCode lvCode = status == IntakeObservationStatus.ACCEPTED ? null
                : status == IntakeObservationStatus.WAITING_FOR_TRIGGER ? com.securedoc.platform.domain.error.LvErrorCode.INT_030
                : cause != null && cause.lvCode() != com.securedoc.platform.domain.error.LvErrorCode.SYS_001 ? cause.lvCode()
                : com.securedoc.platform.application.error.LvErrorCodes.forIntakeRejection(code);
        String detail=(lvCode==null?"":"code="+lvCode.code()+", detail="+(code==null?status:code)+", ")
                +"Intake status="+status+(code==null?"":", reasonCode="+code)
                +(cause==null?"":", causeCode="+cause.code()+", exceptionType="+cause.exceptionType())
                +", drop="+dropReference(candidate);
        auditService.recordSuccess(principal.tenantId(),principal.actorId(),action,
                "WatchedFolderIntake",id.toString(),cid,detail);
    }

    private void applyDispositionBestEffort(String sourceReference,
                                            IntakeDisposition disposition,
                                            WatchedFolderScanLease scanLease) {
        try {
            sourcePort.applyDisposition(sourceReference, disposition, scanLease);
        } catch (RuntimeException dispositionFailure) {
            // Acceptance/rejection/duplicate is already durably recorded. Source post-processing failure must not
            // rewrite the business intake outcome; operational health exposes source/storage failures separately.
            log.warn("WATCHED_FOLDER_DISPOSITION_FAILED drop={} disposition={} reasonCode=DISPOSITION_FAILED exceptionType={}",
                    WatchedFolderDropReference.of(sourceReference), disposition, dispositionFailure.getClass().getSimpleName());
        }
    }

    private java.util.function.BiFunction<com.securedoc.platform.domain.identity.TenantId, Map<String, String>, List<Map<String, String>>>
            fieldsForDigest = (tenant, fields) -> List.of(fields);

    /** Task 0061: the case fields as they may take part in a plain digest. */
    public WatchedFolderIntakeService withCaseFields(com.securedoc.platform.application.casefields.CaseFieldService caseFields) {
        this.fieldsForDigest = caseFields::forDigest;
        return this;
    }

    /** The drop's fingerprint; after a key rotation the one by an older key when a case with it exists. */
    private String fingerprint(AuthenticatedPrincipal principal, WatchedFolderCandidate candidate) throws Exception {
        List<MessageDigest> digests = new java.util.ArrayList<>();
        for (Map<String, String> fields : fieldsForDigest.apply(principal.tenantId(), candidate.businessIdentifiers())) {
            MessageDigest digest=MessageDigest.getInstance("SHA-256");
            update(digest, principal.tenantId().toString());
            fields.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e->{update(digest,e.getKey().trim().toLowerCase(Locale.ROOT));update(digest,e.getValue().trim().toUpperCase(Locale.ROOT));});
            candidate.metadata().entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .forEach(e->{update(digest,e.getKey().trim());update(digest,e.getValue().trim());});
            digests.add(digest);
        }
        for(WatchedFolderDocument doc:candidate.documents().stream().sorted(Comparator.comparing(WatchedFolderDocument::fileName)).toList()){
            for (MessageDigest digest : digests) {
                update(digest,doc.fileName()); update(digest,Long.toString(doc.content().contentLength()));
            }
            try(InputStream in=doc.content().openStream()){
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))>=0){if(n>0)for (MessageDigest digest : digests) digest.update(buf,0,n);}
            }
        }
        List<String> fingerprints = digests.stream().map(d -> HexFormat.of().formatHex(d.digest())).toList();
        return fingerprints.stream().skip(1).filter(f -> packageRepository
                        .findByTenantIdAndIntakeFingerprint(principal.tenantId(), f).isPresent())
                .findFirst().orElse(fingerprints.getFirst());
    }

    private static void update(MessageDigest digest,String value){
        digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte)0);
    }

    private record CandidateDocument(WatchedFolderDocument document, DocumentSignature signature) {}
    private record DocumentSignature(String fileName, String mimeType, long sizeBytes, String sha256) {}

    private static final class DuplicateCandidateException extends RuntimeException {}
    private static final class LostScanLeaseException extends RuntimeException {}
    private static final class RecoveryConflictException extends RuntimeException {
        private RecoveryConflictException(String message) { super(message); }
        private RecoveryConflictException(String message, Throwable cause) { super(message, cause); }
    }
}

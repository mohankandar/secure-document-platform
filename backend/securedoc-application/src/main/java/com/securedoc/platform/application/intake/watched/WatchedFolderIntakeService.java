package com.securedoc.platform.application.intake.watched;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.command.*;
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
        this.sourcePort=Objects.requireNonNull(sourcePort); this.leasePort=Objects.requireNonNull(leasePort);
        this.observationRepository=Objects.requireNonNull(observationRepository);
        this.packageRepository=Objects.requireNonNull(packageRepository); this.createPackageUseCase=Objects.requireNonNull(createPackageUseCase);
        this.uploadDocumentUseCase=Objects.requireNonNull(uploadDocumentUseCase); this.submitPackageUseCase=Objects.requireNonNull(submitPackageUseCase);
        this.processingDispatcher=Objects.requireNonNull(processingDispatcher); this.auditService=Objects.requireNonNull(auditService);
        this.incompleteVisibility=incompleteVisibility==null?Duration.ofMinutes(15):incompleteVisibility;
        this.staleClaimAfter=staleClaimAfter==null?Duration.ofMinutes(5):staleClaimAfter;
    }

    @Override
    public ScanWatchedFolderResult scan(AuthenticatedPrincipal principal, CorrelationId correlationId) {
        return scan(principal, correlationId, WatchedFolderScanLease.singleNode());
    }

    public ScanWatchedFolderResult scan(AuthenticatedPrincipal principal,
                                        CorrelationId correlationId,
                                        WatchedFolderScanLease scanLease) {
        Objects.requireNonNull(scanLease, "scanLease");
        if (!leaseIsCurrent(scanLease)) return new ScanWatchedFolderResult(0, 0, 0, 0, 0);
        sourcePort.recoverStaleClaims(scanLease, staleClaimAfter, authoritativeNow(scanLease));
        List<WatchedFolderCandidate> candidates = sourcePort.discover();
        int waiting=0, accepted=0, rejected=0, duplicates=0;
        for (WatchedFolderCandidate discovered : candidates) {
            if (!discovered.triggerPresent()) {
                waiting++;
                recordWaiting(principal, discovered, correlationId);
                continue;
            }

            if (!leaseIsCurrent(scanLease)) break;

            Optional<WatchedFolderCandidate> claimed = sourcePort.claim(
                    discovered.sourceReference(), scanLease, authoritativeNow(scanLease));
            if (claimed.isEmpty()) continue;
            WatchedFolderCandidate candidate = claimed.get();

            if (!candidate.manifestValid()) {
                rejected++;
                reject(principal,candidate,
                        candidate.rejectionCode()==null?IntakeRejectionCode.MALFORMED_TRIGGER:candidate.rejectionCode(),
                        null,correlationId,scanLease);
                continue;
            }

            String fingerprint;
            try {
                fingerprint = fingerprint(principal, candidate);
            } catch (Exception e) {
                rejected++;
                reject(principal,candidate,IntakeRejectionCode.SOURCE_IO_ERROR,null,correlationId,scanLease);
                continue;
            }

            Optional<IntakeObservation> prior = observationRepository.findByTenantAndSource(
                    principal.tenantId(), candidate.sourceReference());
            if (prior.isPresent() && prior.get().status() == IntakeObservationStatus.ACCEPTED
                    && fingerprint.equals(prior.get().fingerprint())) {
                if (!leaseStillCurrent(scanLease)) continue;
                duplicates++;
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
                        fingerprint,null,correlationId);
                applyDispositionBestEffort(candidate.sourceReference(), IntakeDisposition.DUPLICATE, scanLease);
                continue;
            } catch (LostScanLeaseException lostLease) {
                // Do not terminally dispose a candidate after losing the distributed generation.
                // The next owner will recover the claim and resume the persisted package.
                continue;
            } catch (RuntimeException e) {
                if (!leaseStillCurrent(scanLease)) continue;
                rejected++;
                reject(principal,candidate,IntakeRejectionCode.SOURCE_IO_ERROR,fingerprint,correlationId,scanLease);
                continue;
            }

            record(principal,candidate,IntakeObservationStatus.ACCEPTED,null,fingerprint,packageId,correlationId);
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

        requireCurrentLease(scanLease);
        PackageId createdPackageId;
        try {
            createdPackageId = createPackageUseCase.execute(
                    CreatePackageCommand.watchedFolder(candidate.businessIdentifiers(), candidate.metadata(), fingerprint,
                            candidate.sourceReference(), correlationId), principal).packageId();
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
                        document.mimeType(), document.content(), correlationId), principal);
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
        return existing.packageType() == PackageType.FOLDER_INTAKE
                && existing.sourceReference().filter(candidate.sourceReference()::equals).isPresent();
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
        } catch (RuntimeException ignored) {
            // Processing owns its technical failure state; do not rewrite an accepted intake as rejected.
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
        Optional<IntakeObservation> existing=observationRepository.findByTenantAndSource(
                principal.tenantId(),candidate.sourceReference());
        if(existing.isPresent() && existing.get().status()==IntakeObservationStatus.WAITING_FOR_TRIGGER) return;
        Instant now=Instant.now();
        UUID id=UUID.randomUUID();
        Instant firstSeen=now;
        IntakeObservation observation=new IntakeObservation(id,principal.tenantId(),candidate.sourceReference(),
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
                        WatchedFolderScanLease scanLease) {
        record(principal,candidate,IntakeObservationStatus.REJECTED,code,fingerprint,null,cid);
        applyDispositionBestEffort(candidate.sourceReference(), IntakeDisposition.REJECTED, scanLease);
    }

    private void record(AuthenticatedPrincipal principal, WatchedFolderCandidate candidate, IntakeObservationStatus status,
                        IntakeRejectionCode code, String fingerprint, PackageId packageId, CorrelationId cid) {
        Instant now=Instant.now();
        Optional<IntakeObservation> latest=observationRepository.findByTenantAndSource(
                principal.tenantId(),candidate.sourceReference());
        Optional<IntakeObservation> waiting=latest.filter(o -> o.status() == IntakeObservationStatus.WAITING_FOR_TRIGGER);
        Instant first=waiting.map(IntakeObservation::firstSeenAt).orElse(now);
        Instant visible=waiting.map(IntakeObservation::visibleAfter).orElse(now);
        UUID id=waiting.map(IntakeObservation::id).orElse(UUID.randomUUID());
        observationRepository.save(new IntakeObservation(id,principal.tenantId(),candidate.sourceReference(),
                status,code,fingerprint,packageId,first,visible,now));
        AuditAction action=switch(status){
            case ACCEPTED->AuditAction.INTAKE_ACCEPTED;
            case REJECTED->AuditAction.INTAKE_REJECTED;
            case DUPLICATE->AuditAction.INTAKE_DUPLICATE;
            default->AuditAction.INTAKE_WAITING_FOR_TRIGGER;
        };
        String detail="Intake status="+status+(code==null?"":", reasonCode="+code);
        auditService.recordSuccess(principal.tenantId(),principal.actorId(),action,
                "WatchedFolderIntake",id.toString(),cid,detail);
    }

    private void applyDispositionBestEffort(String sourceReference,
                                            IntakeDisposition disposition,
                                            WatchedFolderScanLease scanLease) {
        try {
            sourcePort.applyDisposition(sourceReference, disposition, scanLease);
        } catch (RuntimeException ignored) {
            // Acceptance/rejection/duplicate is already durably recorded. Source post-processing failure must not
            // rewrite the business intake outcome; operational health exposes source/storage failures separately.
        }
    }

    private String fingerprint(AuthenticatedPrincipal principal, WatchedFolderCandidate candidate) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        update(digest, principal.tenantId().toString());
        candidate.businessIdentifiers().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e->{update(digest,e.getKey().trim().toLowerCase(Locale.ROOT));update(digest,e.getValue().trim().toUpperCase(Locale.ROOT));});
        candidate.metadata().entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(e->{update(digest,e.getKey().trim());update(digest,e.getValue().trim());});
        for(WatchedFolderDocument doc:candidate.documents().stream().sorted(Comparator.comparing(WatchedFolderDocument::fileName)).toList()){
            update(digest,doc.fileName()); update(digest,Long.toString(doc.content().contentLength()));
            try(InputStream in=doc.content().openStream()){
                byte[] buf=new byte[8192];int n;
                while((n=in.read(buf))>=0){if(n>0)digest.update(buf,0,n);}
            }
        }
        return HexFormat.of().formatHex(digest.digest());
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

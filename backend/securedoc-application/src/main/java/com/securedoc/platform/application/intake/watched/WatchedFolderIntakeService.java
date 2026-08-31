package com.securedoc.platform.application.intake.watched;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.IntakeValidationException;
import com.securedoc.platform.application.intake.command.*;
import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import com.securedoc.platform.application.port.in.*;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;

import java.io.InputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** One scan cycle for local watched-folder intake. Scheduling/connection administration is Unit 24. */
public final class WatchedFolderIntakeService implements ScanWatchedFolderUseCase, ListIntakeObservationsQuery {
    private final WatchedFolderSourcePort sourcePort;
    private final IntakeObservationRepository observationRepository;
    private final PackageRepository packageRepository;
    private final CreatePackageUseCase createPackageUseCase;
    private final UploadDocumentUseCase uploadDocumentUseCase;
    private final SubmitPackageUseCase submitPackageUseCase;
    private final TriggerPackageProcessingUseCase triggerProcessingUseCase;
    private final AuditService auditService;
    private final Duration incompleteVisibility;

    public WatchedFolderIntakeService(WatchedFolderSourcePort sourcePort,
                                      IntakeObservationRepository observationRepository,
                                      PackageRepository packageRepository,
                                      CreatePackageUseCase createPackageUseCase,
                                      UploadDocumentUseCase uploadDocumentUseCase,
                                      SubmitPackageUseCase submitPackageUseCase,
                                      TriggerPackageProcessingUseCase triggerProcessingUseCase,
                                      AuditService auditService,
                                      Duration incompleteVisibility) {
        this.sourcePort=Objects.requireNonNull(sourcePort); this.observationRepository=Objects.requireNonNull(observationRepository);
        this.packageRepository=Objects.requireNonNull(packageRepository); this.createPackageUseCase=Objects.requireNonNull(createPackageUseCase);
        this.uploadDocumentUseCase=Objects.requireNonNull(uploadDocumentUseCase); this.submitPackageUseCase=Objects.requireNonNull(submitPackageUseCase);
        this.triggerProcessingUseCase=Objects.requireNonNull(triggerProcessingUseCase); this.auditService=Objects.requireNonNull(auditService);
        this.incompleteVisibility=incompleteVisibility==null?Duration.ofMinutes(15):incompleteVisibility;
    }

    @Override
    public ScanWatchedFolderResult scan(AuthenticatedPrincipal principal, CorrelationId correlationId) {
        List<WatchedFolderCandidate> candidates = sourcePort.discover();
        int waiting=0, accepted=0, rejected=0, duplicates=0;
        for (WatchedFolderCandidate candidate : candidates) {
            if (!candidate.triggerPresent()) {
                waiting++; recordWaiting(principal, candidate, correlationId); continue;
            }
            if (!candidate.manifestValid()) {
                rejected++; reject(principal,candidate,candidate.rejectionCode()==null?IntakeRejectionCode.MALFORMED_TRIGGER:candidate.rejectionCode(),null,correlationId); continue;
            }
            String fingerprint;
            try { fingerprint = fingerprint(principal, candidate); }
            catch (Exception e) { rejected++; reject(principal,candidate,IntakeRejectionCode.SOURCE_IO_ERROR,null,correlationId); continue; }

            Optional<IntakeObservation> prior = observationRepository.findByTenantAndSource(principal.tenantId(), candidate.sourceReference());
            if (prior.isPresent() && prior.get().status() == IntakeObservationStatus.ACCEPTED
                    && fingerprint.equals(prior.get().fingerprint())) {
                // LEAVE-in-place mode uses the persisted fingerprint as the consumed marker.
                duplicates++;
                continue;
            }
            if (packageRepository.findByTenantIdAndIntakeFingerprint(principal.tenantId(), fingerprint).isPresent()) {
                duplicates++; record(principal,candidate,IntakeObservationStatus.DUPLICATE,IntakeRejectionCode.DUPLICATE_INTAKE,fingerprint,null,correlationId);
                applyDispositionBestEffort(candidate.sourceReference(), IntakeDisposition.DUPLICATE);
                continue;
            }

            CreatePackageResult created;
            try {
                created = createPackageUseCase.execute(
                        CreatePackageCommand.watchedFolder(candidate.businessIdentifiers(), candidate.metadata(), fingerprint,
                                candidate.sourceReference(), correlationId), principal);
                for (WatchedFolderDocument document : candidate.documents()) {
                    uploadDocumentUseCase.execute(UploadDocumentCommand.of(created.packageId(), document.fileName(),
                            document.mimeType(), document.content(), correlationId), principal);
                }
                submitPackageUseCase.execute(SubmitPackageCommand.of(created.packageId(), correlationId), principal);
            } catch (IntakeValidationException e) {
                if (e.rejectionCode() == IntakeRejectionCode.DUPLICATE_INTAKE) {
                    duplicates++;
                    record(principal,candidate,IntakeObservationStatus.DUPLICATE,IntakeRejectionCode.DUPLICATE_INTAKE,
                            fingerprint,null,correlationId);
                    applyDispositionBestEffort(candidate.sourceReference(), IntakeDisposition.DUPLICATE);
                } else {
                    rejected++; reject(principal,candidate,e.rejectionCode(),fingerprint,correlationId);
                }
                continue;
            } catch (RuntimeException e) {
                rejected++; reject(principal,candidate,IntakeRejectionCode.SOURCE_IO_ERROR,fingerprint,correlationId);
                continue;
            }

            // Intake is accepted once the complete triggered batch is validated, persisted and submitted.
            // Downstream processing and optional source post-processing are separate operational concerns.
            record(principal,candidate,IntakeObservationStatus.ACCEPTED,null,fingerprint,created.packageId(),correlationId);
            accepted++;
            applyDispositionBestEffort(candidate.sourceReference(), IntakeDisposition.ACCEPTED);
            try {
                triggerProcessingUseCase.execute(TriggerPackageProcessingCommand.of(created.packageId(), principal.tenantId(),
                        principal.actorId(), correlationId));
            } catch (RuntimeException ignored) {
                // Processing owns its technical failure state; do not rewrite an accepted intake as rejected.
            }
        }
        return new ScanWatchedFolderResult(candidates.size(),waiting,accepted,rejected,duplicates);
    }

    @Override
    public List<IntakeObservation> listVisible(AuthenticatedPrincipal principal, int limit) {
        Instant now=Instant.now();
        return observationRepository.findRecentByTenant(principal.tenantId(),limit).stream()
                .filter(o -> o.status()!=IntakeObservationStatus.WAITING_FOR_TRIGGER || !now.isBefore(o.visibleAfter()))
                .toList();
    }

    private void recordWaiting(AuthenticatedPrincipal principal, WatchedFolderCandidate candidate, CorrelationId cid) {
        Optional<IntakeObservation> existing=observationRepository.findByTenantAndSource(principal.tenantId(),candidate.sourceReference());
        if(existing.isPresent() && existing.get().status()==IntakeObservationStatus.WAITING_FOR_TRIGGER) return;
        Instant now=Instant.now();
        // A new no-trigger episode after a terminal observation must be a new history row.
        // Existing WAITING rows returned above remain untouched so firstSeen/visibleAfter stay stable.
        UUID id=UUID.randomUUID();
        Instant firstSeen=now;
        IntakeObservation observation=new IntakeObservation(id,principal.tenantId(),candidate.sourceReference(),
                IntakeObservationStatus.WAITING_FOR_TRIGGER,null,null,null,firstSeen,firstSeen.plus(incompleteVisibility),now);
        observationRepository.save(observation);
        auditService.recordSuccess(principal.tenantId(),principal.actorId(),AuditAction.INTAKE_WAITING_FOR_TRIGGER,
                "WatchedFolderIntake",observation.id().toString(),cid,"Watched folder observed without trigger");
    }

    private void reject(AuthenticatedPrincipal principal, WatchedFolderCandidate candidate, IntakeRejectionCode code,
                        String fingerprint, CorrelationId cid) {
        record(principal,candidate,IntakeObservationStatus.REJECTED,code,fingerprint,null,cid);
        applyDispositionBestEffort(candidate.sourceReference(), IntakeDisposition.REJECTED);
    }

    private void record(AuthenticatedPrincipal principal, WatchedFolderCandidate candidate, IntakeObservationStatus status,
                        IntakeRejectionCode code, String fingerprint, PackageId packageId, CorrelationId cid) {
        Instant now=Instant.now();
        Optional<IntakeObservation> latest=observationRepository.findByTenantAndSource(principal.tenantId(),candidate.sourceReference());
        Optional<IntakeObservation> waiting=latest.filter(o -> o.status() == IntakeObservationStatus.WAITING_FOR_TRIGGER);
        Instant first=waiting.map(IntakeObservation::firstSeenAt).orElse(now);
        Instant visible=waiting.map(IntakeObservation::visibleAfter).orElse(now);
        UUID id=waiting.map(IntakeObservation::id).orElse(UUID.randomUUID());
        observationRepository.save(new IntakeObservation(id,principal.tenantId(),candidate.sourceReference(),status,code,fingerprint,packageId,first,visible,now));
        AuditAction action=switch(status){case ACCEPTED->AuditAction.INTAKE_ACCEPTED;case REJECTED->AuditAction.INTAKE_REJECTED;case DUPLICATE->AuditAction.INTAKE_DUPLICATE;default->AuditAction.INTAKE_WAITING_FOR_TRIGGER;};
        String detail="Intake status="+status+(code==null?"":", reasonCode="+code);
        auditService.recordSuccess(principal.tenantId(),principal.actorId(),action,"WatchedFolderIntake",id.toString(),cid,detail);
    }

    private void applyDispositionBestEffort(String sourceReference, IntakeDisposition disposition) {
        try {
            sourcePort.applyDisposition(sourceReference, disposition);
        } catch (RuntimeException ignored) {
            // Acceptance/rejection/duplicate is already durably recorded. Unit 24 owns source-post-processing
            // configuration and operational health; a move/copy permission problem must not abort the scan cycle.
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
            try(InputStream in=doc.content().openStream()){byte[] buf=new byte[8192];int n;while((n=in.read(buf))>=0){if(n>0)digest.update(buf,0,n);}}
        }
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void update(MessageDigest digest,String value){digest.update(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));digest.update((byte)0);}
}

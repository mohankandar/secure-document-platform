package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.export.command.ExportPackageDocumentsCommand;
import com.securedoc.platform.application.export.result.ExportPackageDocumentsResult;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.port.in.ExportPackageDocumentsUseCase;
import com.securedoc.platform.application.port.out.ExportDestinationProbePort;
import com.securedoc.platform.application.port.out.ExportDestinationStateRepository;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.PipelineJobRepository;
import com.securedoc.platform.application.routing.service.DerivedDeliveryService;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.technical.service.ProcessingIncidentService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.IntegrationConnection;
import com.securedoc.platform.domain.integration.IntegrationPurpose;
import com.securedoc.platform.domain.pipeline.DestinationState;
import com.securedoc.platform.domain.pipeline.ExportDestinationStatus;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import com.securedoc.platform.domain.processing.ProcessingStage;
import com.securedoc.platform.domain.routing.DerivedDelivery;
import com.securedoc.platform.domain.routing.DerivedDeliveryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Runs export jobs (task 0027; design sections 4 and 5). One attempt per run: the job does the spacing between tries,
 * not the adapter. What the export does is unchanged: an EXPORT job runs {@link ExportPackageDocumentsUseCase} and
 * then {@link MasterExportCompletion}, exactly as the inline path; a SPLIT_EXPORT job sends one derived delivery.
 *
 * <p>Retries by code:
 * <ul>
 *   <li>LV-DLV-001/002: try again after the retry delay, up to the maximum tries; failures in a row across the
 *       destination's jobs pause the destination (its jobs wait, using no attempts) until a probe answers.</li>
 *   <li>LV-DLV-009 (already received): DONE.</li>
 *   <li>LV-DLV-003/004: WAITING_FOR_YOU, no retry.</li>
 *   <li>LV-DLV-005: the destination and the job go to NEEDS_IT.</li>
 *   <li>LV-DLV-007/008: up to the maximum tries, then NEEDS_IT.</li>
 *   <li>Anything else: NEEDS_IT with its code (LV-SYS-001 if none).</li>
 * </ul>
 * Every status change, pause and resume is audited with its code. Codes and ids only.
 */
public final class ExportJobService {
    private static final Logger log = LoggerFactory.getLogger(ExportJobService.class);
    public static final Set<PipelineStage> EXPORT_STAGES = EnumSet.of(PipelineStage.EXPORT, PipelineStage.SPLIT_EXPORT);
    private static final int LIST_LIMIT = 500;
    private static final int MAX_IDS_PER_REQUEST = 200;

    private final PipelineJobRepository jobs;
    private final ExportDestinationStateRepository destinations;
    private final ExportPackageDocumentsUseCase exportUseCase;
    private final DerivedDeliveryService derivedDeliveries;
    private final PackageRepository packages;
    private final PackageLifecycleService lifecycle;
    private final MasterExportCompletion completion;
    private final ProcessingIncidentService incidents;
    private final AuditService audit;
    private final ExportDestinationProbePort probe;
    private final IntegrationConnectionRepository connections;
    private final ExportJobSettings settings;

    public ExportJobService(PipelineJobRepository jobs, ExportDestinationStateRepository destinations,
                            ExportPackageDocumentsUseCase exportUseCase, DerivedDeliveryService derivedDeliveries,
                            PackageRepository packages, PackageLifecycleService lifecycle,
                            MasterExportCompletion completion, ProcessingIncidentService incidents, AuditService audit,
                            ExportDestinationProbePort probe, IntegrationConnectionRepository connections,
                            ExportJobSettings settings) {
        this.jobs = Objects.requireNonNull(jobs);
        this.destinations = Objects.requireNonNull(destinations);
        this.exportUseCase = Objects.requireNonNull(exportUseCase);
        this.derivedDeliveries = Objects.requireNonNull(derivedDeliveries);
        this.packages = Objects.requireNonNull(packages);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.completion = Objects.requireNonNull(completion);
        this.incidents = Objects.requireNonNull(incidents);
        this.audit = Objects.requireNonNull(audit);
        this.probe = Objects.requireNonNull(probe);
        this.connections = Objects.requireNonNull(connections);
        this.settings = Objects.requireNonNull(settings);
    }

    public ExportJobSettings settings() {
        return settings;
    }

    // ---------------------------------------------------------------- workers

    /** Claims the next due export job for this worker, if there is one. */
    public Optional<PipelineJob> claimNext(String owner) {
        return jobs.claimNext(EXPORT_STAGES, owner, settings.claimLease(), settings.workersPerDestination());
    }

    /** Renews a running job's claim. False when the claim is no longer this worker's. */
    public boolean renew(PipelineJob claimed) {
        return jobs.renew(claimed.id(), claimed.claimToken(), settings.claimLease());
    }

    /** Runs one attempt of a claimed job and saves the result, if the claim is still current. */
    public JobResult run(PipelineJob claimed) {
        // A claim that already ran out sends nothing; another worker has (or will have) the job.
        if (!jobs.claimIsCurrent(claimed.id(), claimed.claimToken())) return discarded(claimed);
        CorrelationId correlationId = CorrelationId.generate();
        return apply(claimed, attempt(claimed, correlationId), false, ExportJobQueue.SYSTEM_ACTOR, correlationId);
    }

    /**
     * Jobs whose claim ran out (a server stopped mid-export) go back to READY; that counts as an attempt. A job that
     * has used every attempt goes to NEEDS_IT with LV-PRC-008.
     */
    public int releaseExpiredClaims() {
        List<PipelineJob> released = jobs.releaseExpiredClaims(settings.maxAttempts(), LvErrorCode.PRC_008.code(), 100);
        for (PipelineJob job : released) {
            CorrelationId correlationId = CorrelationId.generate();
            auditStatus(job, PipelineJobStatus.RUNNING, job.status(), job.attempts(), job.lastLvCode(),
                    "CLAIM_EXPIRED", ExportJobQueue.SYSTEM_ACTOR, correlationId);
            if (job.status() == PipelineJobStatus.NEEDS_IT) {
                toItQueue(job, LvErrorCode.PRC_008, null, correlationId);
            }
            log.warn("EXPORT_JOB_CLAIM_EXPIRED job={} package={} status={} attempts={}", job.id(), job.packageId(),
                    job.status(), job.attempts());
        }
        return released.size();
    }

    /** Probes the PAUSED destinations that are due; a destination that answers is WORKING again. */
    public int probePausedDestinations() {
        int resumed = 0;
        for (ExportDestinationStatus paused : destinations.findPausedDueForProbe(settings.probeInterval(), 50)) {
            destinations.recordProbe(paused.tenantId(), paused.connectionId());
            try {
                probe.probe(paused.tenantId(), paused.connectionId());
            } catch (RuntimeException stillDown) {
                log.info("EXPORT_DESTINATION_PROBE_FAILED destination={} code={}", paused.connectionId().value(),
                        LvErrorCodes.optional(stillDown).map(LvErrorCode::code).orElse(LvErrorCode.DLV_001.code()));
                continue;
            }
            if (resume(paused.tenantId(), paused.connectionId(), EnumSet.of(DestinationState.PAUSED), "probe",
                    ExportJobQueue.SYSTEM_ACTOR, CorrelationId.generate())) {
                resumed++;
            }
        }
        return resumed;
    }

    // ---------------------------------------------------------------- people

    /** "Waiting for you": the tenant's jobs a Processor or Supervisor can resend. */
    public List<JobView> waitingForYou(TenantId tenantId) {
        return views(tenantId, jobs.findByTenantIdAndStatus(tenantId, PipelineJobStatus.WAITING_FOR_YOU, LIST_LIMIT));
    }

    /** The IT error queue. */
    public List<JobView> needsIt(TenantId tenantId) {
        return views(tenantId, jobs.findByTenantIdAndStatus(tenantId, PipelineJobStatus.NEEDS_IT, LIST_LIMIT));
    }

    public List<JobView> jobsOfCase(TenantId tenantId, PackageId packageId) {
        return views(tenantId, jobs.findByTenantIdAndPackageId(tenantId, packageId));
    }

    /**
     * A resend by a person: one attempt per job. Success is DONE; any failure sends the job to the IT error queue
     * with LV-DLV-015, the failure's own code kept as the detail. A job that is not waiting for a person is refused
     * (LV-CFG-011) and left as it is.
     */
    public List<JobResult> resend(TenantId tenantId, List<UUID> jobIds, ActorId actorId, CorrelationId correlationId) {
        List<JobResult> results = new ArrayList<>();
        for (UUID id : distinctIds(jobIds)) {
            Optional<PipelineJob> claimed = jobs.claim(tenantId, id, EnumSet.of(PipelineJobStatus.WAITING_FOR_YOU),
                    "resend/" + UUID.randomUUID(), settings.claimLease());
            if (claimed.isEmpty()) {
                results.add(JobResult.refused(id));
                continue;
            }
            PipelineJob job = claimed.get();
            audit.recordSuccess(tenantId, actorId, AuditAction.EXPORT_JOB_RESEND_REQUESTED, "DocumentPackage",
                    job.packageId().toString(), job.packageId(), correlationId,
                    "Export job resend requested: job=" + job.id() + ", stage=" + job.stage());
            results.add(apply(job, attempt(job, correlationId), true, actorId, correlationId));
        }
        return results;
    }

    /**
     * An IT retry: a NEEDS_IT job goes back to READY with its attempts reset. With {@code resumeDestination}, its
     * destination, if PAUSED or NEEDS_IT, is set back to WORKING too. Other jobs are refused (LV-CFG-011).
     */
    public List<JobResult> itRetry(TenantId tenantId, List<UUID> jobIds, boolean resumeDestination, ActorId actorId,
                                   CorrelationId correlationId) {
        List<JobResult> results = new ArrayList<>();
        for (UUID id : distinctIds(jobIds)) {
            PipelineJob job = jobs.findByTenantIdAndId(tenantId, id).orElse(null);
            if (job == null || job.status() != PipelineJobStatus.NEEDS_IT) {
                results.add(JobResult.refused(id));
                continue;
            }
            if (resumeDestination && job.destinationId() != null) {
                resume(tenantId, job.destinationId(), EnumSet.of(DestinationState.PAUSED, DestinationState.NEEDS_IT),
                        "it", actorId, correlationId);
            }
            if (!jobs.changeStatus(tenantId, id, PipelineJobStatus.NEEDS_IT, PipelineJobStatus.READY, 0,
                    job.lastLvCode(), job.lastDetail())) {
                results.add(JobResult.refused(id));
                continue;
            }
            audit.recordSuccess(tenantId, actorId, AuditAction.EXPORT_JOB_IT_RETRY_REQUESTED, "DocumentPackage",
                    job.packageId().toString(), job.packageId(), correlationId,
                    "Export job retry by IT: job=" + job.id() + ", stage=" + job.stage() + ", lastCode="
                            + job.lastLvCode());
            auditStatus(job, PipelineJobStatus.NEEDS_IT, PipelineJobStatus.READY, 0, job.lastLvCode(), "IT_RETRY",
                    actorId, correlationId);
            results.add(new JobResult(id, PipelineJobStatus.READY.name(), job.lastLvCode(), true));
        }
        return results;
    }

    /** IT sets a PAUSED or NEEDS_IT destination back to WORKING; its waiting jobs go out in order. */
    public boolean setDestinationWorking(TenantId tenantId, IntegrationConnectionId destinationId, ActorId actorId,
                                         CorrelationId correlationId) {
        return resume(tenantId, destinationId, EnumSet.of(DestinationState.PAUSED, DestinationState.NEEDS_IT), "it",
                actorId, correlationId);
    }

    /** Every export destination of the tenant with its state (no row is WORKING). */
    public List<DestinationView> destinationStates(TenantId tenantId) {
        Map<UUID, ExportDestinationStatus> stored = new java.util.HashMap<>();
        destinations.findByTenantId(tenantId).forEach(s -> stored.put(s.connectionId().value(), s));
        List<DestinationView> views = new ArrayList<>();
        for (IntegrationConnection c : connections.findByTenantId(tenantId)) {
            if (c.purpose() != IntegrationPurpose.SDV_DELIVERY
                    && c.purpose() != IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY) continue;
            ExportDestinationStatus s = stored.getOrDefault(c.id().value(),
                    ExportDestinationStatus.working(tenantId, c.id()));
            views.add(new DestinationView(c.id().value(), c.name(), c.purpose().name(), c.enabled(), s.state().name(),
                    s.pausedAt(), s.consecutiveFailures(), s.lastProbeAt(), s.lastLvCode()));
        }
        return views;
    }

    /**
     * How the case's export looks from its EXPORT jobs: "Ready for export" while an export job is not DONE, more
     * precise when one waits (task 0027; derived, no package status added).
     */
    public CaseExportState caseExportState(TenantId tenantId, PackageId packageId) {
        List<PipelineJob> caseJobs = jobs.findByTenantIdAndPackageId(tenantId, packageId).stream()
                .filter(j -> j.stage() == PipelineStage.EXPORT).toList();
        return caseExportState(caseJobs, destination -> destination == null ? DestinationState.WORKING
                : destinations.find(tenantId, destination).map(ExportDestinationStatus::state)
                        .orElse(DestinationState.WORKING));
    }

    static CaseExportState caseExportState(List<PipelineJob> exportJobs,
                                           java.util.function.Function<IntegrationConnectionId, DestinationState> stateOf) {
        List<PipelineJob> open = exportJobs.stream().filter(j -> j.status().open()).toList();
        if (open.isEmpty()) {
            return exportJobs.stream().anyMatch(j -> j.status() == PipelineJobStatus.DONE)
                    ? CaseExportState.EXPORTED : CaseExportState.NONE;
        }
        if (open.stream().anyMatch(j -> j.status() == PipelineJobStatus.NEEDS_IT)) return CaseExportState.NEEDS_IT;
        if (open.stream().anyMatch(j -> j.status() == PipelineJobStatus.WAITING_FOR_YOU)) {
            return CaseExportState.WAITING_FOR_YOU;
        }
        if (open.stream().anyMatch(j -> stateOf.apply(j.destinationId()) != DestinationState.WORKING)) {
            return CaseExportState.DESTINATION_PAUSED;
        }
        if (open.stream().anyMatch(j -> j.status() == PipelineJobStatus.WAITING_RETRY)) {
            return CaseExportState.WAITING_TO_RETRY;
        }
        return CaseExportState.READY_FOR_EXPORT;
    }

    // ---------------------------------------------------------------- one attempt

    private Outcome attempt(PipelineJob job, CorrelationId correlationId) {
        try {
            return switch (job.stage()) {
                case EXPORT -> exportCase(job, correlationId);
                case SPLIT_EXPORT -> exportSplit(job, correlationId);
                default -> Outcome.of(Kind.OTHER, LvErrorCode.SYS_001, "STAGE_NOT_EXPORT", null);
            };
        } catch (Exception failure) {
            return classify(failure);
        }
    }

    private Outcome exportCase(PipelineJob job, CorrelationId correlationId) {
        DocumentPackage pkg = packages.findByIdAndTenantId(job.packageId(), job.tenantId()).orElse(null);
        if (pkg == null) return Outcome.of(Kind.STOPPED, LvErrorCode.INT_025, "CASE_NOT_FOUND", null);
        int nextCycle = lifecycle.nextExportCycle(job.packageId(), job.tenantId());
        if (nextCycle > job.exportCycle()) {
            // This run was exported already (its result was lost): nothing is sent again.
            if (waitingForExport(pkg.status())) {
                completion.complete(pkg, job.tenantId(), job.packageId(),
                        ExportPackageDocumentsResult.of(job.packageId(), pkg.documents().size(), "unchanged",
                                job.exportCycle()), pkg.documents().size(), correlationId);
            }
            return Outcome.done(false);
        }
        if (nextCycle != job.exportCycle()) {
            return Outcome.of(Kind.OTHER, LvErrorCode.SYS_001, "EXPORT_CYCLE_MISMATCH", null);
        }
        if (!waitingForExport(pkg.status())) {
            return Outcome.of(Kind.STOPPED, LvErrorCode.CFG_011, "CASE_NOT_WAITING_FOR_EXPORT", null);
        }
        ExportPackageDocumentsResult result = exportUseCase.execute(
                ExportPackageDocumentsCommand.of(job.packageId(), job.tenantId(), correlationId));
        completion.complete(pkg, job.tenantId(), job.packageId(), result, result.exportedDocumentCount(),
                correlationId);
        return Outcome.done(result.alreadyReceived());
    }

    /**
     * The statuses a case is handed to export in (the same ones processing completes from): PROCESSING, or
     * PENDING_REVIEW / IN_REVIEW when the last review decision finished the case.
     */
    static boolean waitingForExport(PackageStatus status) {
        return status == PackageStatus.PROCESSING || status == PackageStatus.PENDING_REVIEW
                || status == PackageStatus.IN_REVIEW;
    }

    private Outcome exportSplit(PipelineJob job, CorrelationId correlationId) {
        DerivedDelivery delivery = derivedDeliveries.get(job.tenantId(), job.itemId());
        if (delivery.status() != DerivedDeliveryStatus.DELIVERED) {
            derivedDeliveries.autoSend(job.tenantId(), delivery.id(), correlationId);
        }
        return Outcome.done(false);
    }

    static Outcome classify(Throwable failure) {
        LvErrorCode code = LvErrorCodes.of(failure);
        String detail = failure instanceof TechnicalDependencyException technical
                ? technical.failureCode().name() : failure.getClass().getSimpleName();
        Kind kind = switch (code) {
            case DLV_001, DLV_002 -> Kind.TRANSIENT;
            case DLV_009 -> Kind.DONE;
            case DLV_003, DLV_004 -> Kind.REFUSED;
            case DLV_005 -> Kind.CREDENTIAL;
            case DLV_007, DLV_008 -> Kind.ANSWER_UNCLEAR;
            default -> Kind.OTHER;
        };
        return Outcome.of(kind, code, detail, failure);
    }

    // ---------------------------------------------------------------- saving the result

    private JobResult apply(PipelineJob job, Outcome outcome, boolean resend, ActorId actorId,
                            CorrelationId correlationId) {
        if (!jobs.claimIsCurrent(job.id(), job.claimToken())) return discarded(job);
        int attempts = job.attempts() + 1;
        IntegrationConnectionId destination = job.destinationId();
        PipelineJobStatus to;
        Duration delay = null;
        String code = outcome.code() == null ? null : outcome.code().code();
        String detail = outcome.detail();
        switch (outcome.kind()) {
            case DONE -> {
                resetFailures(job);
                to = PipelineJobStatus.DONE;
                code = outcome.alreadyReceived() || outcome.code() == LvErrorCode.DLV_009
                        ? LvErrorCode.DLV_009.code() : null;
                detail = code == null ? null : "ALREADY_RECEIVED";
            }
            case STOPPED -> to = PipelineJobStatus.STOPPED;
            case TRANSIENT -> {
                DestinationState state = destination == null ? DestinationState.WORKING
                        : recordTransientFailure(job, code, correlationId);
                if (state == DestinationState.WORKING && attempts >= settings.maxAttempts()) {
                    to = PipelineJobStatus.NEEDS_IT;
                } else {
                    // A paused destination's jobs wait for it; they never go to IT because of the pause.
                    to = PipelineJobStatus.WAITING_RETRY;
                    delay = settings.retryDelay();
                }
            }
            case ANSWER_UNCLEAR -> {
                resetFailures(job);
                if (attempts >= settings.maxAttempts()) {
                    to = PipelineJobStatus.NEEDS_IT;
                } else {
                    to = PipelineJobStatus.WAITING_RETRY;
                    delay = settings.retryDelay();
                }
            }
            case REFUSED -> {
                resetFailures(job);
                to = PipelineJobStatus.WAITING_FOR_YOU;
            }
            case CREDENTIAL -> {
                if (destination != null) destinationNeedsIt(job, code, correlationId);
                to = PipelineJobStatus.NEEDS_IT;
            }
            default -> to = PipelineJobStatus.NEEDS_IT;
        }
        LvErrorCode itCode = outcome.code();
        if (resend && to != PipelineJobStatus.DONE && to != PipelineJobStatus.STOPPED) {
            // A resend is one attempt: a failure goes to IT as LV-DLV-015, with the failure's own code as the detail.
            detail = code;
            code = LvErrorCode.DLV_015.code();
            itCode = LvErrorCode.DLV_015;
            to = PipelineJobStatus.NEEDS_IT;
            delay = null;
        }
        if (!jobs.finish(job.id(), job.claimToken(), to, attempts, delay, code, detail)) return discarded(job);
        auditStatus(job, PipelineJobStatus.RUNNING, to, attempts, code, detail, actorId, correlationId);
        if (to == PipelineJobStatus.NEEDS_IT) toItQueue(job, itCode, outcome.failure(), correlationId);
        log.info("EXPORT_JOB_FINISHED job={} package={} stage={} status={} attempts={} code={}", job.id(),
                job.packageId(), job.stage(), to, attempts, code);
        return new JobResult(job.id(), to.name(), code, true);
    }

    private JobResult discarded(PipelineJob job) {
        log.warn("EXPORT_JOB_RESULT_DISCARDED job={} package={} claimToken={} reasonCode=CLAIM_NOT_CURRENT",
                job.id(), job.packageId(), job.claimToken());
        return new JobResult(job.id(), null, null, false);
    }

    private DestinationState recordTransientFailure(PipelineJob job, String code, CorrelationId correlationId) {
        var result = destinations.recordTransientFailure(job.tenantId(), job.destinationId(),
                settings.pauseAfterFailures(), code);
        if (result.pausedNow()) {
            audit.recordFailure(job.tenantId(), ExportJobQueue.SYSTEM_ACTOR, AuditAction.EXPORT_DESTINATION_PAUSED,
                    "IntegrationConnection", job.destinationId().value().toString(), correlationId,
                    "Export destination paused: code=" + code + ", failuresInARow=" + result.consecutiveFailures());
            log.warn("EXPORT_DESTINATION_PAUSED destination={} code={} failuresInARow={}",
                    job.destinationId().value(), code, result.consecutiveFailures());
        }
        return result.state();
    }

    private void resetFailures(PipelineJob job) {
        if (job.destinationId() != null) destinations.resetFailures(job.tenantId(), job.destinationId());
    }

    private void destinationNeedsIt(PipelineJob job, String code, CorrelationId correlationId) {
        if (destinations.transition(job.tenantId(), job.destinationId(),
                EnumSet.of(DestinationState.WORKING, DestinationState.PAUSED), DestinationState.NEEDS_IT, code)) {
            audit.recordFailure(job.tenantId(), ExportJobQueue.SYSTEM_ACTOR, AuditAction.EXPORT_DESTINATION_NEEDS_IT,
                    "IntegrationConnection", job.destinationId().value().toString(), correlationId,
                    "Export destination needs IT: code=" + code);
            log.warn("EXPORT_DESTINATION_NEEDS_IT destination={} code={}", job.destinationId().value(), code);
        }
    }

    private boolean resume(TenantId tenantId, IntegrationConnectionId destinationId, Set<DestinationState> from,
                           String by, ActorId actorId, CorrelationId correlationId) {
        if (!destinations.transition(tenantId, destinationId, from, DestinationState.WORKING, null)) return false;
        int due = jobs.makeDueNow(tenantId, destinationId);
        audit.recordSuccess(tenantId, actorId, AuditAction.EXPORT_DESTINATION_RESUMED, "IntegrationConnection",
                destinationId.value().toString(), correlationId,
                "Export destination working again: by=" + by + ", jobsMadeDue=" + due);
        log.info("EXPORT_DESTINATION_RESUMED destination={} by={} jobsMadeDue={}", destinationId.value(), by, due);
        return true;
    }

    /**
     * The IT error queue: an incident (so the job counts in Support's "needs attention" numbers) and an audit row
     * the notifications module can pick up later.
     */
    private void toItQueue(PipelineJob job, LvErrorCode code, Throwable failure, CorrelationId correlationId) {
        LvErrorCode lvCode = code == null ? LvErrorCode.SYS_001 : code;
        try {
            TechnicalFailureCode reason = failure instanceof TechnicalDependencyException technical
                    ? technical.failureCode() : TechnicalFailureCode.UNKNOWN_TECHNICAL_FAILURE;
            TechnicalDependencyException coded = new TechnicalDependencyException(TechnicalDependency.DELIVERY,
                    reason, false, "Export job needs IT", failure).withLvCode(lvCode);
            incidents.recordFailure(job.tenantId(), job.packageId(), job.documentId(), ProcessingStage.DELIVERY, coded,
                    correlationId, "Export job needs IT", Map.of("exportJobId", job.id().toString(),
                            "exportStage", job.stage().name()));
        } catch (RuntimeException incidentFailure) {
            log.warn("EXPORT_JOB_INCIDENT_NOT_RECORDED job={} type={}", job.id(),
                    incidentFailure.getClass().getSimpleName());
        }
        audit.recordFailure(job.tenantId(), ExportJobQueue.SYSTEM_ACTOR, AuditAction.EXPORT_JOB_NEEDS_IT_NOTIFICATION,
                "DocumentPackage", job.packageId().toString(), job.packageId(), correlationId,
                "Export job needs IT: job=" + job.id() + ", stage=" + job.stage() + ", code=" + lvCode.code());
    }

    private void auditStatus(PipelineJob job, PipelineJobStatus from, PipelineJobStatus to, int attempts, String code,
                             String detail, ActorId actorId, CorrelationId correlationId) {
        String text = "Export job status: job=" + job.id() + ", stage=" + job.stage() + ", from=" + from + ", to=" + to
                + ", attempts=" + attempts + ", destination=" + ExportJobQueue.destinationText(job.destinationId())
                + (code == null ? "" : ", code=" + code) + (detail == null ? "" : ", detail=" + detail);
        if (to == PipelineJobStatus.DONE || to == PipelineJobStatus.READY) {
            audit.recordSuccess(job.tenantId(), actorId, AuditAction.EXPORT_JOB_STATUS_CHANGED, "DocumentPackage",
                    job.packageId().toString(), job.packageId(), correlationId, text);
        } else {
            audit.recordFailure(job.tenantId(), actorId, AuditAction.EXPORT_JOB_STATUS_CHANGED, "DocumentPackage",
                    job.packageId().toString(), job.packageId(), correlationId, text);
        }
    }

    private List<JobView> views(TenantId tenantId, List<PipelineJob> list) {
        Map<UUID, String> names = new java.util.HashMap<>();
        List<JobView> views = new ArrayList<>(list.size());
        for (PipelineJob job : list) {
            String name = null;
            if (job.destinationId() != null) {
                name = names.computeIfAbsent(job.destinationId().value(), id -> connections
                        .findByTenantIdAndId(tenantId, job.destinationId()).map(IntegrationConnection::name)
                        .orElse(null));
            }
            views.add(new JobView(job.id(), job.packageId().value(), job.stage().name(),
                    job.documentId() == null ? null : job.documentId().value(),
                    job.destinationId() == null ? null : job.destinationId().value(), name, job.status().name(),
                    job.attempts(), job.lastLvCode(), job.lastDetail(), job.updatedAt(), job.nextAttemptAt()));
        }
        return views;
    }

    private static Set<UUID> distinctIds(List<UUID> ids) {
        Set<UUID> distinct = new LinkedHashSet<>();
        if (ids != null) ids.stream().filter(Objects::nonNull).forEach(distinct::add);
        if (distinct.size() > MAX_IDS_PER_REQUEST) {
            throw new com.securedoc.platform.application.exception.ValidationException(LvErrorCode.CFG_001,
                    "At most " + MAX_IDS_PER_REQUEST + " jobs at once");
        }
        return distinct;
    }

    // ---------------------------------------------------------------- types

    enum Kind { DONE, STOPPED, TRANSIENT, ANSWER_UNCLEAR, REFUSED, CREDENTIAL, OTHER }

    record Outcome(Kind kind, LvErrorCode code, String detail, Throwable failure, boolean alreadyReceived) {
        static Outcome done(boolean alreadyReceived) {
            return new Outcome(Kind.DONE, null, null, null, alreadyReceived);
        }

        static Outcome of(Kind kind, LvErrorCode code, String detail, Throwable failure) {
            return new Outcome(kind, code, detail, failure, false);
        }
    }

    /** One job's result. {@code saved} is false when the job was refused or its claim was no longer current. */
    public record JobResult(UUID jobId, String status, String lvCode, boolean saved) {
        static JobResult refused(UUID jobId) {
            return new JobResult(jobId, null, LvErrorCode.CFG_011.code(), false);
        }
    }

    /** A job as the API shows it: codes, ids and times only. */
    public record JobView(UUID jobId, UUID packageId, String stage, UUID documentId, UUID destinationId,
                          String destinationName, String status, int attempts, String lvCode, String detail,
                          Instant since, Instant nextAttemptAt) { }

    public record DestinationView(UUID destinationId, String name, String purpose, boolean enabled, String state,
                                  Instant pausedAt, int consecutiveFailures, Instant lastProbeAt, String lastLvCode) { }

    /** How a case's export looks (design section 9, "Not exported yet"). */
    public enum CaseExportState {
        /** No export job (inline mode, or not finished processing). */
        NONE,
        READY_FOR_EXPORT,
        WAITING_TO_RETRY,
        DESTINATION_PAUSED,
        WAITING_FOR_YOU,
        NEEDS_IT,
        EXPORTED
    }
}

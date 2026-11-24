package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.export.command.ExportPackageDocumentsCommand;
import com.securedoc.platform.application.export.result.ExportPackageDocumentsResult;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.port.in.ExportPackageDocumentsUseCase;
import com.securedoc.platform.application.port.in.RebuildPackageExportUseCase;
import com.securedoc.platform.application.port.out.CasePrimaryIdentifierLookup;
import com.securedoc.platform.application.port.out.ExportDestinationProbePort;
import com.securedoc.platform.application.port.out.ExportDestinationStateRepository;
import com.securedoc.platform.application.port.out.ExportReachabilityAlertRepository;
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
import com.securedoc.platform.domain.pipeline.ExportDestinations;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import com.securedoc.platform.domain.processing.ProcessingStage;
import com.securedoc.platform.domain.routing.DerivedDelivery;
import com.securedoc.platform.domain.routing.DerivedDeliveryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

/**
 * Runs export jobs (task 0027; task 0028 pauses destinations by evidence and gives a single failing case a long retry
 * schedule). One attempt per run: the job does the spacing between tries, not the adapter. What the export does is
 * unchanged: an EXPORT job runs {@link ExportPackageDocumentsUseCase} and then {@link MasterExportCompletion}, exactly
 * as the inline path; a SPLIT_EXPORT job sends one derived delivery.
 *
 * <p>On a failure, by code:
 * <ul>
 *   <li>LV-DLV-001/002 (can't connect, timeout, 500/502/503): the destination's health check runs at once.
 *       <ul>
 *         <li>Check fails: the destination is PAUSED (its jobs wait, using no attempts) and checked every probe
 *             interval; it resumes on its own, in order. If every destination (at least 2) failed its check within
 *             the same minutes, it is LexaVault's own network: one alert, nothing paused, no attempt used.</li>
 *         <li>Check passes: it is this case. Its own retry schedule (5 min, 15 min, 1 h, 4 h, 12 h), rebuilt once
 *             before the 3rd retry, then NEEDS_IT. Backup rule: failures from 3 different cases within 10 minutes
 *             pause the destination though its check passes ("answering but failing").</li>
 *       </ul></li>
 *   <li>429: not a failure. Waits for Retry-After (60 s if none), one worker less for that destination (never below
 *       1, back one after 10 quiet minutes). No attempt used, no pause.</li>
 *   <li>LV-DLV-009 (already received): DONE.</li>
 *   <li>LV-DLV-003/004: WAITING_FOR_YOU, no retry.</li>
 *   <li>LV-DLV-005: the destination and the job go to NEEDS_IT.</li>
 *   <li>LV-DLV-007/008: the case's retry schedule, as above (the destination answered, so no check).</li>
 *   <li>Anything else: NEEDS_IT with its code (LV-SYS-001 if none).</li>
 * </ul>
 * NEEDS_IT jobs of one destination with one code form one group: one incident and one notification, one IT retry.
 * Every status change, pause and resume is audited with its code. Codes and ids only.
 */
public final class ExportJobService {
    private static final Logger log = LoggerFactory.getLogger(ExportJobService.class);
    public static final Set<PipelineStage> EXPORT_STAGES = EnumSet.of(PipelineStage.EXPORT, PipelineStage.SPLIT_EXPORT);
    /** The codes a single failing case is retried on, by its schedule. */
    static final Set<String> SCHEDULED_CODES = Set.of(LvErrorCode.DLV_001.code(), LvErrorCode.DLV_002.code(),
            LvErrorCode.DLV_007.code(), LvErrorCode.DLV_008.code());
    public static final String ALL_DESTINATIONS_UNREACHABLE = "ALL_DESTINATIONS_UNREACHABLE";
    public static final String RATE_LIMITED = "RATE_LIMITED";
    private static final int LIST_LIMIT = 500;
    private static final int MAX_IDS_PER_REQUEST = 200;

    private final PipelineJobRepository jobs;
    private final ExportDestinationStateRepository destinations;
    private final ExportReachabilityAlertRepository alerts;
    private final ExportPackageDocumentsUseCase exportUseCase;
    private final RebuildPackageExportUseCase rebuildUseCase;
    private final DerivedDeliveryService derivedDeliveries;
    private final PackageRepository packages;
    private final PackageLifecycleService lifecycle;
    private final MasterExportCompletion completion;
    private final ProcessingIncidentService incidents;
    private final AuditService audit;
    private final ExportDestinationProbePort probe;
    private final IntegrationConnectionRepository connections;
    private final ExportJobQueue queue;
    private final ExportJobSettings settings;
    private final Clock clock;
    private final CasePrimaryIdentifierLookup caseNumbers;

    public ExportJobService(PipelineJobRepository jobs, ExportDestinationStateRepository destinations,
                            ExportReachabilityAlertRepository alerts, ExportPackageDocumentsUseCase exportUseCase,
                            RebuildPackageExportUseCase rebuildUseCase, DerivedDeliveryService derivedDeliveries,
                            PackageRepository packages, PackageLifecycleService lifecycle,
                            MasterExportCompletion completion, ProcessingIncidentService incidents, AuditService audit,
                            ExportDestinationProbePort probe, IntegrationConnectionRepository connections,
                            ExportJobQueue queue, ExportJobSettings settings, Clock clock,
                            CasePrimaryIdentifierLookup caseNumbers) {
        this.jobs = Objects.requireNonNull(jobs);
        this.destinations = Objects.requireNonNull(destinations);
        this.alerts = Objects.requireNonNull(alerts);
        this.exportUseCase = Objects.requireNonNull(exportUseCase);
        this.rebuildUseCase = Objects.requireNonNull(rebuildUseCase);
        this.derivedDeliveries = Objects.requireNonNull(derivedDeliveries);
        this.packages = Objects.requireNonNull(packages);
        this.lifecycle = Objects.requireNonNull(lifecycle);
        this.completion = Objects.requireNonNull(completion);
        this.incidents = Objects.requireNonNull(incidents);
        this.audit = Objects.requireNonNull(audit);
        this.probe = Objects.requireNonNull(probe);
        this.connections = Objects.requireNonNull(connections);
        this.queue = Objects.requireNonNull(queue);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
        this.caseNumbers = Objects.requireNonNull(caseNumbers);
    }

    public ExportJobSettings settings() {
        return settings;
    }

    // ---------------------------------------------------------------- workers

    /** Task 0052: true while any export job is still to be done (after a switch back to inline it is finished). */
    public boolean hasOpenJobs() {
        return jobs.hasOpenExportJobs();
    }

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
        boolean rebuiltNow = false;
        Outcome outcome = null;
        if (rebuildDue(claimed)) {
            rebuiltNow = true;
            outcome = rebuild(claimed, correlationId);
        }
        if (outcome == null) outcome = attempt(claimed, correlationId);
        return apply(claimed, outcome, claimed.resend(), rebuiltNow, ExportJobQueue.SYSTEM_ACTOR, correlationId);
    }

    /**
     * Jobs whose claim ran out (a server stopped mid-export) go back to READY; that counts as an attempt. A job whose
     * claim has run out the maximum number of times goes to NEEDS_IT with LV-PRC-008.
     */
    public int releaseExpiredClaims() {
        // Task 0048: export jobs only; Text/Find/Redact jobs are released by their own workers.
        List<PipelineJob> released = jobs.releaseExpiredClaims(EXPORT_STAGES, settings.maxAttempts(),
                LvErrorCode.PRC_008.code(), 100);
        for (PipelineJob job : released) {
            CorrelationId correlationId = CorrelationId.generate();
            auditStatus(job, PipelineJobStatus.RUNNING, job.status(), job.attempts(), job.lastLvCode(),
                    "CLAIM_EXPIRED", ExportJobQueue.SYSTEM_ACTOR, correlationId);
            if (job.status() == PipelineJobStatus.NEEDS_IT) {
                toItQueue(job, LvErrorCode.PRC_008.code(), LvErrorCode.PRC_008, null, correlationId);
            }
            log.warn("EXPORT_JOB_CLAIM_EXPIRED job={} package={} status={} attempts={}", job.id(), job.packageId(),
                    job.status(), job.attempts());
        }
        return released.size();
    }

    /** Probes the PAUSED destinations that are due; a destination whose check passes is WORKING again. */
    public int probePausedDestinations() {
        int resumed = 0;
        for (ExportDestinationStatus paused : destinations.findPausedDueForProbe(settings.probeInterval(), 50)) {
            destinations.recordProbe(paused.tenantId(), paused.connectionId());
            if (!check(paused.tenantId(), paused.connectionId())) {
                destinations.recordCheckFailed(paused.tenantId(), paused.connectionId());
                continue;
            }
            if (resume(paused.tenantId(), paused.connectionId(), EnumSet.of(DestinationState.PAUSED), "probe",
                    ExportJobQueue.SYSTEM_ACTOR, CorrelationId.generate())) {
                resumed++;
            }
        }
        return resumed;
    }

    /**
     * While "LexaVault can't reach its destinations" is raised, checks every destination each probe interval; the
     * alert clears when any check passes, and the jobs that waited for the network are due again, in order.
     */
    public int checkUnreachableAlerts() {
        int cleared = 0;
        for (ExportReachabilityAlertRepository.Alert alert : alerts.findRaisedDueForCheck(settings.probeInterval(), 50)) {
            TenantId tenantId = alert.tenantId();
            alerts.recordCheck(tenantId);
            IntegrationConnectionId passed = null;
            for (IntegrationConnectionId destination : knownDestinations(tenantId)) {
                if (check(tenantId, destination)) {
                    passed = destination;
                    break;
                }
                destinations.recordCheckFailed(tenantId, destination);
            }
            if (passed == null) continue;
            // Task 0036: a destination paused for its failed check (the only destination) resumes with the alert;
            // resume clears it. Otherwise (two or more destinations, none paused) the alert clears here.
            boolean resumed = resume(tenantId, passed, EnumSet.of(DestinationState.PAUSED), "probe",
                    ExportJobQueue.SYSTEM_ACTOR, CorrelationId.generate());
            if (resumed || clearNetworkAlert(tenantId)) cleared++;
        }
        return cleared;
    }

    /** A destination slowed down by a 429 gets a worker back after the quiet time. */
    public int restoreWorkers() {
        return destinations.restoreWorkers(settings.rateLimitRecovery(), 100);
    }

    // ---------------------------------------------------------------- people

    /** "Waiting for you", grouped by destination, with counts per code. */
    public List<WaitingGroup> waitingForYou(TenantId tenantId) {
        List<JobView> waiting = views(tenantId,
                jobs.findByTenantIdAndStatus(tenantId, PipelineJobStatus.WAITING_FOR_YOU, LIST_LIMIT));
        Map<String, List<JobView>> byDestination = new LinkedHashMap<>();
        waiting.stream().sorted(Comparator.comparing(JobView::createdAt, Comparator.nullsLast(Comparator.naturalOrder())))
                .forEach(v -> byDestination.computeIfAbsent(String.valueOf(v.destinationId()), k -> new ArrayList<>())
                        .add(v));
        List<WaitingGroup> groups = new ArrayList<>();
        for (List<JobView> group : byDestination.values()) {
            Map<String, Integer> counts = new TreeMap<>();
            group.forEach(v -> counts.merge(v.lvCode() == null ? "NONE" : v.lvCode(), 1, Integer::sum));
            JobView first = group.get(0);
            groups.add(new WaitingGroup(first.destinationId(), first.destinationName(), group.size(), counts,
                    List.copyOf(group)));
        }
        return groups;
    }

    /** The IT error queue, as jobs. */
    public List<JobView> needsIt(TenantId tenantId) {
        return views(tenantId, jobs.findByTenantIdAndStatus(tenantId, PipelineJobStatus.NEEDS_IT, LIST_LIMIT));
    }

    /** The IT error queue grouped: one group per destination and code, with a count, first failed at and the cases. */
    public List<ItGroup> needsItGroups(TenantId tenantId) {
        Map<String, List<JobView>> byKey = new LinkedHashMap<>();
        for (JobView v : needsIt(tenantId)) {
            byKey.computeIfAbsent(v.destinationId() + "|" + v.lvCode(), k -> new ArrayList<>()).add(v);
        }
        // Task 0036: each case by its case number (the main ID the Cases list shows), one lookup for all groups.
        Map<PackageId, CasePrimaryIdentifierLookup.CasePrimaryIdentifier> numbers = byKey.isEmpty() ? Map.of()
                : caseNumbers.findByPackageIds(tenantId, byKey.values().stream().flatMap(List::stream)
                        .map(v -> PackageId.of(v.packageId())).distinct().toList());
        List<ItGroup> groups = new ArrayList<>();
        for (List<JobView> group : byKey.values()) {
            JobView first = group.get(0);
            Instant firstFailedAt = group.stream().map(v -> v.firstFailedAt() != null ? v.firstFailedAt() : v.since())
                    .filter(Objects::nonNull).min(Comparator.naturalOrder()).orElse(null);
            List<UUID> caseIds = group.stream().map(JobView::packageId).distinct().toList();
            List<CaseRef> cases = caseIds.stream().map(id -> {
                CasePrimaryIdentifierLookup.CasePrimaryIdentifier number = numbers.get(PackageId.of(id));
                return new CaseRef(id, number == null ? null : number.value());
            }).toList();
            groups.add(new ItGroup(first.destinationId(), first.destinationName(), first.lvCode(), group.size(),
                    firstFailedAt, caseIds, cases, List.copyOf(group)));
        }
        return groups;
    }

    public List<JobView> jobsOfCase(TenantId tenantId, PackageId packageId) {
        return views(tenantId, jobs.findByTenantIdAndPackageId(tenantId, packageId));
    }

    /**
     * A resend by a person: one attempt per job, now. Success is DONE; a failure sends the job to the IT error queue
     * with LV-DLV-015, the failure's own code kept as the detail. A 429, or a destination found down by its check,
     * is not the case's failure: the job waits for it and is still one resend. A job that is not waiting for a person
     * is refused (LV-CFG-011) and left as it is.
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
            results.add(apply(job, attempt(job, correlationId), true, false, actorId, correlationId));
        }
        return results;
    }

    /**
     * Resend all for one destination: every WAITING_FOR_YOU job of that destination goes back to READY, one attempt
     * each as a resend, in their original order (the workers take them oldest first).
     */
    public List<UUID> resendAll(TenantId tenantId, IntegrationConnectionId destinationId, ActorId actorId,
                                CorrelationId correlationId) {
        Objects.requireNonNull(destinationId, "destinationId");
        List<UUID> ids = jobs.resendAll(tenantId, destinationId);
        audit.recordSuccess(tenantId, actorId, AuditAction.EXPORT_JOB_RESEND_ALL_REQUESTED, "IntegrationConnection",
                destinationId.value().toString(), correlationId,
                "Resend all requested: destination=" + destinationId.value() + ", jobs=" + ids.size());
        for (UUID id : ids) {
            jobs.findByTenantIdAndId(tenantId, id).ifPresent(job -> {
                audit.recordSuccess(tenantId, actorId, AuditAction.EXPORT_JOB_RESEND_REQUESTED, "DocumentPackage",
                        job.packageId().toString(), job.packageId(), correlationId,
                        "Export job resend requested: job=" + job.id() + ", stage=" + job.stage() + ", all=true");
                auditStatus(job, PipelineJobStatus.WAITING_FOR_YOU, PipelineJobStatus.READY, job.attempts(),
                        job.lastLvCode(), "RESEND", actorId, correlationId);
            });
        }
        log.info("EXPORT_JOB_RESEND_ALL destination={} jobs={}", destinationId.value(), ids.size());
        return ids;
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

    /**
     * IT retries one group: every NEEDS_IT job of this destination with this code, and the destination back to
     * WORKING if it was paused or waiting for IT. Empty when there is no such group.
     */
    public List<JobResult> retryGroup(TenantId tenantId, IntegrationConnectionId destinationId, String lvCode,
                                      ActorId actorId, CorrelationId correlationId) {
        Objects.requireNonNull(lvCode, "lvCode");
        List<UUID> ids = jobs.findByTenantIdAndStatus(tenantId, PipelineJobStatus.NEEDS_IT, LIST_LIMIT).stream()
                .filter(j -> Objects.equals(j.destinationId(), destinationId) && lvCode.equals(j.lastLvCode()))
                .map(PipelineJob::id).toList();
        if (ids.isEmpty()) return List.of();
        audit.recordSuccess(tenantId, actorId, AuditAction.EXPORT_JOB_IT_GROUP_RETRY_REQUESTED, "IntegrationConnection",
                destinationId == null ? "none" : destinationId.value().toString(), correlationId,
                "IT group retry: destination=" + ExportJobQueue.destinationText(destinationId) + ", code=" + lvCode
                        + ", jobs=" + ids.size());
        List<JobResult> results = new ArrayList<>();
        for (int from = 0; from < ids.size(); from += MAX_IDS_PER_REQUEST) {
            results.addAll(itRetry(tenantId, ids.subList(from, Math.min(ids.size(), from + MAX_IDS_PER_REQUEST)), true,
                    actorId, correlationId));
        }
        return results;
    }

    /** IT sets a PAUSED or NEEDS_IT destination back to WORKING; its waiting jobs go out in order. */
    public boolean setDestinationWorking(TenantId tenantId, IntegrationConnectionId destinationId, ActorId actorId,
                                         CorrelationId correlationId) {
        return resume(tenantId, destinationId, EnumSet.of(DestinationState.PAUSED, DestinationState.NEEDS_IT), "it",
                actorId, correlationId);
    }

    /**
     * Every export destination of the tenant with its state (no row is WORKING), the local folder included. Task 0029:
     * with the time of its next check while paused, and the number of its export jobs still to be done.
     */
    public List<DestinationView> destinationStates(TenantId tenantId) {
        Map<UUID, ExportDestinationStatus> stored = new HashMap<>();
        destinations.findByTenantId(tenantId).forEach(s -> stored.put(s.connectionId().value(), s));
        Map<UUID, Map<PipelineJobStatus, Integer>> open = jobs.countOpenByDestinationAndStatus(tenantId);
        List<DestinationView> views = new ArrayList<>();
        IntegrationConnectionId master = queue.masterDestination(tenantId);
        if (ExportDestinations.isLocalFolder(tenantId, master)) {
            views.add(view(stored.getOrDefault(master.value(), ExportDestinationStatus.working(tenantId, master)),
                    LOCAL_FOLDER_NAME, "LOCAL_FOLDER", true, open));
        }
        for (IntegrationConnection c : connections.findByTenantId(tenantId)) {
            if (c.purpose() != IntegrationPurpose.DOCUMENT_STORE_DELIVERY
                    && c.purpose() != IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY) continue;
            views.add(view(stored.getOrDefault(c.id().value(), ExportDestinationStatus.working(tenantId, c.id())),
                    c.name(), c.purpose().name(), c.enabled(), open));
        }
        return views;
    }

    /**
     * Support's "Check now" (task 0029): the destination's health check, at once. A PAUSED destination whose check
     * passes is WORKING again and its waiting jobs go out in their order (audited as a resume, by "check-now"). A
     * failing check is recorded and the destination stays as it is. Empty when it is not one of the tenant's export
     * destinations.
     */
    public Optional<CheckNow> checkDestinationNow(TenantId tenantId, IntegrationConnectionId destinationId,
                                                  ActorId actorId, CorrelationId correlationId) {
        if (destinationId == null || !isExportDestination(tenantId, destinationId)) return Optional.empty();
        destinations.recordProbe(tenantId, destinationId);
        boolean passed = check(tenantId, destinationId);
        boolean resumed = false;
        if (passed) {
            resumed = resume(tenantId, destinationId, EnumSet.of(DestinationState.PAUSED), "check-now", actorId,
                    correlationId);
        } else {
            destinations.recordCheckFailed(tenantId, destinationId);
        }
        String state = destinations.find(tenantId, destinationId).map(s -> s.state().name())
                .orElse(DestinationState.WORKING.name());
        log.info("EXPORT_DESTINATION_CHECKED_NOW destination={} passed={} resumed={}", destinationId.value(), passed,
                resumed);
        return Optional.of(new CheckNow(destinationId.value(), passed, resumed, state));
    }

    /** The master destination (the local folder too) or one of the tenant's document store or split delivery connections. */
    private boolean isExportDestination(TenantId tenantId, IntegrationConnectionId destinationId) {
        if (destinationId.equals(queue.masterDestination(tenantId))) return true;
        return connections.findByTenantId(tenantId).stream().anyMatch(c -> c.id().equals(destinationId)
                && (c.purpose() == IntegrationPurpose.DOCUMENT_STORE_DELIVERY
                || c.purpose() == IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY));
    }

    /** "LexaVault can't reach its destinations", as Support shows it. */
    public AlertView reachabilityAlert(TenantId tenantId) {
        return alerts.find(tenantId)
                .map(a -> new AlertView(a.raised(), a.raised() ? LvErrorCode.DLV_001.code() : null,
                        a.raised() ? ALL_DESTINATIONS_UNREACHABLE : null, a.raisedAt(), a.lastCheckAt(), a.clearedAt()))
                .orElse(new AlertView(false, null, null, null, null, null));
    }

    private DestinationView view(ExportDestinationStatus s, String name, String purpose, boolean enabled,
                                 Map<UUID, Map<PipelineJobStatus, Integer>> open) {
        WaitingCounts waiting = WaitingCounts.of(open.getOrDefault(s.connectionId().value(), Map.of()));
        return new DestinationView(s.connectionId().value(), name, purpose, enabled, s.state().name(), s.pausedAt(),
                s.consecutiveFailures(), s.lastProbeAt(), s.lastLvCode(), s.lastCheckFailedAt(), s.rateLimitedUntil(),
                s.workerLimit(settings.workersPerDestination()), nextCheckAt(s),
                waiting.total(), waiting);
    }

    /** A paused destination is checked one probe interval after its last check (or after it paused); null otherwise. */
    private Instant nextCheckAt(ExportDestinationStatus s) {
        if (s.state() != DestinationState.PAUSED) return null;
        Instant last = s.lastProbeAt() != null ? s.lastProbeAt() : s.pausedAt();
        return last == null ? clock.instant() : last.plus(settings.probeInterval());
    }

    /**
     * How the case's export looks from all its export jobs (master and split): EXPORTED only when every one is done
     * (task 0028). Derived, no package status added.
     */
    public CaseExportState caseExportState(TenantId tenantId, PackageId packageId) {
        List<PipelineJob> caseJobs = jobs.findByTenantIdAndPackageId(tenantId, packageId).stream()
                .filter(j -> j.stage().isExport()).toList();
        return caseExportState(caseJobs, destination -> destination == null ? DestinationState.WORKING
                : destinations.find(tenantId, destination).map(ExportDestinationStatus::state)
                        .orElse(DestinationState.WORKING));
    }

    /**
     * The export state of many cases at once, for the Cases list: one query for their export jobs and one for the
     * tenant's destination states, whatever the number of cases.
     */
    public Map<PackageId, CaseExportState> caseExportStates(TenantId tenantId, Collection<PackageId> packageIds) {
        Map<PackageId, CaseExportState> states = new HashMap<>();
        caseExportRows(tenantId, packageIds).forEach((id, row) -> states.put(id, row.state()));
        return states;
    }

    /**
     * Task 0038: what the Cases list shows of a case's export: its state, the LV code of why it waits (Waiting for IT,
     * Waiting for you, destination paused, or a retry on its own schedule) and the name of its delivery destination once
     * one is chosen. Only codes and the destination's name: never a document value, a file name or a detail. The same
     * two queries as before, plus one name lookup per destination.
     */
    public Map<PackageId, CaseExportRow> caseExportRows(TenantId tenantId, Collection<PackageId> packageIds) {
        Map<PackageId, CaseExportRow> rows = new HashMap<>();
        if (packageIds == null || packageIds.isEmpty()) return rows;
        Map<PackageId, List<PipelineJob>> byCase = new HashMap<>();
        for (PipelineJob job : jobs.findExportJobsByPackageIds(tenantId, packageIds)) {
            byCase.computeIfAbsent(job.packageId(), k -> new ArrayList<>()).add(job);
        }
        Map<IntegrationConnectionId, ExportDestinationStatus> statusOf = new HashMap<>();
        destinations.findByTenantId(tenantId).forEach(s -> statusOf.put(s.connectionId(), s));
        Function<IntegrationConnectionId, DestinationState> lookup = id -> id == null || !statusOf.containsKey(id)
                ? DestinationState.WORKING : statusOf.get(id).state();
        Map<UUID, Optional<String>> names = new HashMap<>();
        for (PackageId id : packageIds) {
            List<PipelineJob> caseJobs = byCase.getOrDefault(id, List.of());
            CaseExportState state = caseExportState(caseJobs, lookup);
            rows.put(id, new CaseExportRow(state, reasonCode(state, caseJobs, statusOf, lookup),
                    destinationName(tenantId, caseJobs, names)));
        }
        return rows;
    }

    /** Task 0038: one case's row facts for the Cases list (see {@link #caseExportRows}). */
    public record CaseExportRow(CaseExportState state, String reasonCode, String destinationName) { }

    private static String reasonCode(CaseExportState state, List<PipelineJob> caseJobs,
                                     Map<IntegrationConnectionId, ExportDestinationStatus> statusOf,
                                     Function<IntegrationConnectionId, DestinationState> lookup) {
        List<PipelineJob> open = caseJobs.stream().filter(j -> j.status().open()).toList();
        java.util.stream.Stream<String> candidates = switch (state) {
            case NEEDS_IT -> open.stream().filter(j -> j.status() == PipelineJobStatus.NEEDS_IT)
                    .map(PipelineJob::lastLvCode);
            case WAITING_FOR_YOU -> open.stream().filter(j -> j.status() == PipelineJobStatus.WAITING_FOR_YOU)
                    .map(PipelineJob::lastLvCode);
            case DESTINATION_PAUSED -> open.stream()
                    .filter(j -> lookup.apply(j.destinationId()) != DestinationState.WORKING)
                    .flatMap(j -> java.util.stream.Stream.of(j.lastLvCode(), statusOf.containsKey(j.destinationId())
                            ? statusOf.get(j.destinationId()).lastLvCode() : null));
            case READY -> open.stream().filter(j -> j.status() == PipelineJobStatus.WAITING_RETRY)
                    .map(PipelineJob::lastLvCode);
            default -> java.util.stream.Stream.empty();
        };
        // Only a code LexaVault knows; anything else is left out (fail closed: no text of unknown origin).
        return candidates.map(c -> com.securedoc.platform.domain.error.LvErrorCode.fromCode(c).orElse(null))
                .filter(java.util.Objects::nonNull).map(com.securedoc.platform.domain.error.LvErrorCode::code)
                .findFirst().orElse(null);
    }

    /** The main export's destination (split exports go to their own), else the only destination there is. */
    private String destinationName(TenantId tenantId, List<PipelineJob> caseJobs, Map<UUID, Optional<String>> names) {
        PipelineJob main = caseJobs.stream().filter(j -> j.destinationId() != null && j.stage() == PipelineStage.EXPORT)
                .reduce((first, second) -> second).orElse(null);
        if (main == null) {
            List<IntegrationConnectionId> all = caseJobs.stream().map(PipelineJob::destinationId)
                    .filter(java.util.Objects::nonNull).distinct().toList();
            if (all.size() != 1) return null;
            main = caseJobs.stream().filter(j -> all.get(0).equals(j.destinationId())).findFirst().orElseThrow();
        }
        IntegrationConnectionId destination = main.destinationId();
        if (ExportDestinations.isLocalFolder(tenantId, destination)) return LOCAL_FOLDER_NAME;
        return names.computeIfAbsent(destination.value(), id -> connections.findByTenantIdAndId(tenantId, destination)
                .map(IntegrationConnection::name)).orElse(null);
    }

    static CaseExportState caseExportState(List<PipelineJob> exportJobs,
                                           Function<IntegrationConnectionId, DestinationState> stateOf) {
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
        return CaseExportState.READY;
    }

    // ---------------------------------------------------------------- one attempt

    private Outcome attempt(PipelineJob job, CorrelationId correlationId) {
        return ExportJobAttempt.run(() -> attemptOnce(job, correlationId));
    }

    private Outcome attemptOnce(PipelineJob job, CorrelationId correlationId) {
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

    /** The rebuild comes once, before the configured retry of a case failing on its own schedule. */
    private boolean rebuildDue(PipelineJob job) {
        return !job.rebuilt() && !job.resend() && job.attempts() >= settings.rebuildBeforeRetry()
                && job.lastLvCode() != null && SCHEDULED_CODES.contains(job.lastLvCode());
    }

    /** Rebuilds the export from the stored redacted copies. Null when that worked; otherwise the failure. */
    private Outcome rebuild(PipelineJob job, CorrelationId correlationId) {
        try {
            boolean rebuilt = switch (job.stage()) {
                case EXPORT -> rebuildUseCase.rebuild(job.tenantId(), job.packageId(), correlationId);
                case SPLIT_EXPORT -> {
                    derivedDeliveries.verifyStoredCopy(job.tenantId(), job.itemId());
                    yield true;
                }
                default -> false;
            };
            audit.recordSuccess(job.tenantId(), ExportJobQueue.SYSTEM_ACTOR, AuditAction.EXPORT_JOB_REBUILT,
                    "DocumentPackage", job.packageId().toString(), job.packageId(), correlationId,
                    "Export rebuilt from the stored redacted copies: job=" + job.id() + ", stage=" + job.stage()
                            + ", attempts=" + job.attempts() + ", submissionRebuilt=" + rebuilt);
            log.info("EXPORT_JOB_REBUILT job={} package={} stage={} submissionRebuilt={}", job.id(), job.packageId(),
                    job.stage(), rebuilt);
            return null;
        } catch (Exception failure) {
            Outcome outcome = classify(failure);
            audit.recordFailure(job.tenantId(), ExportJobQueue.SYSTEM_ACTOR, AuditAction.EXPORT_JOB_REBUILT,
                    "DocumentPackage", job.packageId().toString(), job.packageId(), correlationId,
                    "Export rebuild failed: job=" + job.id() + ", stage=" + job.stage() + ", code="
                            + outcome.code().code());
            // A copy that can't be rebuilt is not retried: it goes to IT with its code.
            return new Outcome(Kind.OTHER, outcome.code(), outcome.detail(), failure, false, null);
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

    /** Task 0030: what {@code last_detail} holds after the internal reason: "; destination=" and the destination's code. */
    public static final String DESTINATION_CODE_PREFIX = "; destination=";
    /** {@code pipeline_jobs.last_detail} is VARCHAR(80). */
    static final int LAST_DETAIL_MAX = 80;

    /**
     * Task 0030: LexaVault's internal reason, then the destination's own code (for example
     * {@code DELIVERY_SENSITIVITY_REFUSED; destination=DS-SEN-006}). The code is checked again against the safe
     * pattern; it is dropped (the reason alone is kept) when it is not safe or the whole would not fit the column.
     */
    static String withDestinationCode(String reason, String destinationCode) {
        String safe = TechnicalDependencyException.safeDestinationCode(destinationCode);
        if (safe == null) return reason;
        String detail = reason + DESTINATION_CODE_PREFIX + safe;
        return detail.length() <= LAST_DETAIL_MAX ? detail : reason;
    }

    static Outcome classify(Throwable failure) {
        LvErrorCode code = LvErrorCodes.of(failure);
        String detail = failure instanceof TechnicalDependencyException technical
                ? withDestinationCode(technical.failureCode().name(), technical.destinationCode())
                : failure.getClass().getSimpleName();
        if (failure instanceof TechnicalDependencyException technical && technical.rateLimited()) {
            return new Outcome(Kind.RATE_LIMITED, code, RATE_LIMITED, failure, false, technical.retryAfter());
        }
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

    private JobResult apply(PipelineJob job, Outcome outcome, boolean resend, boolean rebuiltNow, ActorId actorId,
                            CorrelationId correlationId) {
        if (!jobs.claimIsCurrent(job.id(), job.claimToken())) return discarded(job);
        int attempts = job.attempts() + 1;
        IntegrationConnectionId destination = job.destinationId();
        PipelineJobStatus to;
        Duration delay = null;
        String code = outcome.code() == null ? null : outcome.code().code();
        String detail = outcome.detail();
        boolean failed = false;
        boolean waitsAsResend = false;
        switch (outcome.kind()) {
            case DONE -> {
                to = PipelineJobStatus.DONE;
                code = outcome.alreadyReceived() || outcome.code() == LvErrorCode.DLV_009
                        ? LvErrorCode.DLV_009.code() : null;
                detail = code == null ? null : "ALREADY_RECEIVED";
            }
            case STOPPED -> to = PipelineJobStatus.STOPPED;
            case RATE_LIMITED -> {
                // Not a failure: wait as asked, with one worker less for this destination. No attempt used.
                attempts = job.attempts();
                delay = outcome.retryAfter() != null ? outcome.retryAfter() : settings.rateLimitDefaultWait();
                if (destination != null) slowDown(job, delay, correlationId);
                to = PipelineJobStatus.WAITING_RETRY;
                detail = RATE_LIMITED;
                waitsAsResend = resend;
            }
            case TRANSIENT -> {
                Evidence evidence = destination == null ? Evidence.CASE : evidence(job, code, correlationId);
                if (evidence == Evidence.CASE) {
                    failed = destination != null;
                    to = scheduled(attempts);
                    delay = scheduledDelay(attempts);
                } else {
                    // The destination (or the network) is down, not the case: it waits, using no attempt.
                    attempts = job.attempts();
                    to = PipelineJobStatus.WAITING_RETRY;
                    delay = settings.probeInterval();
                    detail = evidence == Evidence.NETWORK ? PipelineJobRepository.WAITING_FOR_NETWORK
                            : PipelineJobRepository.WAITING_FOR_DESTINATION;
                    waitsAsResend = resend;
                }
            }
            case ANSWER_UNCLEAR -> {
                failed = destination != null;
                to = scheduled(attempts);
                delay = scheduledDelay(attempts);
            }
            case REFUSED -> to = PipelineJobStatus.WAITING_FOR_YOU;
            case CREDENTIAL -> {
                if (destination != null) destinationNeedsIt(job, code, correlationId);
                to = PipelineJobStatus.NEEDS_IT;
            }
            default -> to = PipelineJobStatus.NEEDS_IT;
        }
        LvErrorCode itCode = outcome.code();
        if (resend && !waitsAsResend && to != PipelineJobStatus.DONE && to != PipelineJobStatus.STOPPED) {
            // A resend is one attempt: a failure goes to IT as LV-DLV-015, with the failure's own code as the detail.
            detail = code;
            code = LvErrorCode.DLV_015.code();
            itCode = LvErrorCode.DLV_015;
            to = PipelineJobStatus.NEEDS_IT;
            delay = null;
        }
        if (to != PipelineJobStatus.WAITING_RETRY) delay = null;
        if (!jobs.finish(job.id(), job.claimToken(), new PipelineJobRepository.Finish(to, attempts, delay, code, detail,
                waitsAsResend, rebuiltNow, failed))) {
            return discarded(job);
        }
        auditStatus(job, PipelineJobStatus.RUNNING, to, attempts, code, detail, actorId, correlationId);
        if (failed && to != PipelineJobStatus.NEEDS_IT) answeringButFailing(job, code, correlationId);
        if (to == PipelineJobStatus.NEEDS_IT) toItQueue(job, code, itCode, outcome.failure(), correlationId);
        log.info("EXPORT_JOB_FINISHED job={} package={} stage={} status={} attempts={} code={}", job.id(),
                job.packageId(), job.stage(), to, attempts, code);
        return new JobResult(job.id(), to.name(), code, true);
    }

    /** After {@code attempts} failed tries: the next delay of the schedule, or IT when the schedule is used up. */
    private PipelineJobStatus scheduled(int attempts) {
        return attempts <= settings.retrySchedule().size() ? PipelineJobStatus.WAITING_RETRY : PipelineJobStatus.NEEDS_IT;
    }

    private Duration scheduledDelay(int attempts) {
        return attempts <= settings.retrySchedule().size() ? settings.retrySchedule().get(attempts - 1) : null;
    }

    private JobResult discarded(PipelineJob job) {
        log.warn("EXPORT_JOB_RESULT_DISCARDED job={} package={} claimToken={} reasonCode=CLAIM_NOT_CURRENT",
                job.id(), job.packageId(), job.claimToken());
        return new JobResult(job.id(), null, null, false);
    }

    // ---------------------------------------------------------------- evidence

    private enum Evidence {
        /** The destination's check passes: it is this case. */
        CASE,
        /** The destination's check fails: the destination is paused. */
        DESTINATION,
        /** Every destination fails its check: LexaVault's own network. */
        NETWORK
    }

    /**
     * Can't connect, timeout or 5xx: the destination's health check runs at once. Fails: paused, unless every
     * destination (at least 2) failed its check within the window, which raises the network alert instead.
     */
    private Evidence evidence(PipelineJob job, String code, CorrelationId correlationId) {
        TenantId tenantId = job.tenantId();
        IntegrationConnectionId destination = job.destinationId();
        if (alerts.find(tenantId).map(ExportReachabilityAlertRepository.Alert::raised).orElse(false)) {
            return Evidence.NETWORK;
        }
        if (check(tenantId, destination)) return Evidence.CASE;
        destinations.recordCheckFailed(tenantId, destination);
        if (onlyDestination(tenantId, destination)) {
            // Task 0036 (owner's answer 1): the entity's only destination is down. It is paused as before, and IT
            // gets "can't reach its destinations" too; the alert clears when the destination resumes.
            pause(job, code, "CHECK_FAILED", correlationId);
            raiseNetworkAlert(job, correlationId);
            return Evidence.DESTINATION;
        }
        if (allDestinationsDown(tenantId, destination)) {
            raiseNetworkAlert(job, correlationId);
            return Evidence.NETWORK;
        }
        pause(job, code, "CHECK_FAILED", correlationId);
        return Evidence.DESTINATION;
    }

    private boolean check(TenantId tenantId, IntegrationConnectionId destination) {
        try {
            probe.probe(tenantId, destination);
            return true;
        } catch (RuntimeException down) {
            log.info("EXPORT_DESTINATION_CHECK_FAILED destination={} code={}", destination.value(),
                    LvErrorCodes.optional(down).map(LvErrorCode::code).orElse(LvErrorCode.DLV_001.code()));
            return false;
        }
    }

    /** The failed destination is the entity's only export destination. */
    private boolean onlyDestination(TenantId tenantId, IntegrationConnectionId failed) {
        Set<IntegrationConnectionId> all = knownDestinations(tenantId);
        all.add(failed);
        return all.size() == 1;
    }

    /**
     * Every known destination failed its check within the window: checked now where needed. With one destination
     * that is its own failed check ({@link #onlyDestination}); with two or more it raises the alert instead of
     * pausing.
     */
    private boolean allDestinationsDown(TenantId tenantId, IntegrationConnectionId failed) {
        Set<IntegrationConnectionId> all = knownDestinations(tenantId);
        all.add(failed);
        if (all.size() < 2) return true;
        Instant since = clock.instant().minus(settings.allUnreachableWindow());
        for (IntegrationConnectionId other : all) {
            if (other.equals(failed)) continue;
            Instant lastFailed = destinations.find(tenantId, other).map(ExportDestinationStatus::lastCheckFailedAt)
                    .orElse(null);
            if (lastFailed != null && !lastFailed.isBefore(since)) continue;
            if (check(tenantId, other)) return false;
            destinations.recordCheckFailed(tenantId, other);
        }
        return true;
    }

    /** The tenant's export destinations: the master (the local folder too) and every enabled split destination. */
    private Set<IntegrationConnectionId> knownDestinations(TenantId tenantId) {
        Set<IntegrationConnectionId> all = new LinkedHashSet<>();
        IntegrationConnectionId master = queue.masterDestination(tenantId);
        if (master != null) all.add(master);
        for (IntegrationConnection c : connections.findByTenantId(tenantId)) {
            if (!c.enabled()) continue;
            if (c.purpose() == IntegrationPurpose.DOCUMENT_STORE_DELIVERY
                    || c.purpose() == IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY) {
                all.add(c.id());
            }
        }
        return all;
    }

    private void raiseNetworkAlert(PipelineJob job, CorrelationId correlationId) {
        if (!alerts.raise(job.tenantId())) return;
        audit.recordFailure(job.tenantId(), ExportJobQueue.SYSTEM_ACTOR, AuditAction.EXPORT_DESTINATIONS_UNREACHABLE_ALERT,
                "Tenant", job.tenantId().toString(), correlationId,
                "LexaVault can't reach its destinations: code=" + LvErrorCode.DLV_001.code() + ", detail="
                        + ALL_DESTINATIONS_UNREACHABLE);
        try {
            TechnicalDependencyException coded = new TechnicalDependencyException(TechnicalDependency.DELIVERY,
                    TechnicalFailureCode.DELIVERY_IO, true, "LexaVault can't reach its destinations")
                    .withLvCode(LvErrorCode.DLV_001);
            incidents.recordFailure(job.tenantId(), job.packageId(), null, ProcessingStage.DELIVERY, coded,
                    correlationId, "LexaVault can't reach its destinations",
                    Map.of("detail", ALL_DESTINATIONS_UNREACHABLE, "exportJobId", job.id().toString()));
        } catch (RuntimeException incidentFailure) {
            log.warn("EXPORT_ALERT_INCIDENT_NOT_RECORDED tenant={} type={}", job.tenantId(),
                    incidentFailure.getClass().getSimpleName());
        }
        log.warn("EXPORT_DESTINATIONS_UNREACHABLE tenant={} code={} detail={}", job.tenantId(),
                LvErrorCode.DLV_001.code(), ALL_DESTINATIONS_UNREACHABLE);
    }

    /** Backup rule: failures from several different cases within minutes, while the check passes, pause it. */
    private void answeringButFailing(PipelineJob job, String code, CorrelationId correlationId) {
        if (job.destinationId() == null) return;
        int cases = jobs.countRecentlyFailingCases(job.tenantId(), job.destinationId(), settings.failingCasesWindow());
        if (cases >= settings.failingCasesToPause()) {
            pause(job, code, "FAILING_CASES", correlationId, ", cases=" + cases);
        }
    }

    private void pause(PipelineJob job, String code, String reason, CorrelationId correlationId) {
        pause(job, code, reason, correlationId, "");
    }

    private void pause(PipelineJob job, String code, String reason, CorrelationId correlationId, String extra) {
        if (!destinations.transition(job.tenantId(), job.destinationId(), EnumSet.of(DestinationState.WORKING),
                DestinationState.PAUSED, code)) {
            return;
        }
        audit.recordFailure(job.tenantId(), ExportJobQueue.SYSTEM_ACTOR, AuditAction.EXPORT_DESTINATION_PAUSED,
                "IntegrationConnection", job.destinationId().value().toString(), correlationId,
                "Export destination paused: code=" + code + ", reason=" + reason + extra);
        log.warn("EXPORT_DESTINATION_PAUSED destination={} code={} reason={}{}", job.destinationId().value(), code,
                reason, extra);
    }

    private void slowDown(PipelineJob job, Duration wait, CorrelationId correlationId) {
        destinations.recordRateLimited(job.tenantId(), job.destinationId(), wait,
                Math.max(0, settings.workersPerDestination() - 1));
        audit.recordSuccess(job.tenantId(), ExportJobQueue.SYSTEM_ACTOR, AuditAction.EXPORT_DESTINATION_RATE_LIMITED,
                "IntegrationConnection", job.destinationId().value().toString(), correlationId,
                "Export destination asked to slow down: waitSeconds=" + wait.toSeconds());
        log.info("EXPORT_DESTINATION_RATE_LIMITED destination={} waitSeconds={}", job.destinationId().value(),
                wait.toSeconds());
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
        // Task 0036: a destination that answers again clears "can't reach its destinations".
        clearNetworkAlert(tenantId);
        return true;
    }

    /** Clears a raised network alert; the jobs that waited for the network are due again. True when it was raised. */
    private boolean clearNetworkAlert(TenantId tenantId) {
        if (!alerts.clear(tenantId)) return false;
        int due = jobs.makeNetworkWaitersDueNow(tenantId);
        audit.recordSuccess(tenantId, ExportJobQueue.SYSTEM_ACTOR,
                AuditAction.EXPORT_DESTINATIONS_UNREACHABLE_ALERT_CLEARED, "Tenant", tenantId.toString(),
                CorrelationId.generate(), "Destinations reachable again: jobsMadeDue=" + due);
        log.info("EXPORT_DESTINATIONS_UNREACHABLE_CLEARED tenant={} jobsMadeDue={}", tenantId, due);
        return true;
    }

    /**
     * The IT error queue, grouped: the first NEEDS_IT job of a destination and code opens the group with an incident
     * (so it counts once in Support's "needs attention" numbers) and one notification audit row; later jobs with the
     * same destination and code join that group without another incident or notification.
     */
    private void toItQueue(PipelineJob job, String groupCode, LvErrorCode code, Throwable failure,
                           CorrelationId correlationId) {
        LvErrorCode lvCode = code == null ? LvErrorCode.SYS_001 : code;
        String key = groupCode == null ? lvCode.code() : groupCode;
        int others = jobs.countNeedsIt(job.tenantId(), job.destinationId(), key, job.id());
        if (others > 0) {
            log.info("EXPORT_JOB_JOINED_IT_GROUP job={} destination={} code={} groupSize={}", job.id(),
                    ExportJobQueue.destinationText(job.destinationId()), key, others + 1);
            return;
        }
        try {
            TechnicalFailureCode reason = failure instanceof TechnicalDependencyException technical
                    ? technical.failureCode() : TechnicalFailureCode.UNKNOWN_TECHNICAL_FAILURE;
            TechnicalDependencyException coded = new TechnicalDependencyException(TechnicalDependency.DELIVERY,
                    reason, false, "Export job needs IT", failure).withLvCode(lvCode);
            incidents.recordFailure(job.tenantId(), job.packageId(), job.documentId(), ProcessingStage.DELIVERY, coded,
                    correlationId, "Export job needs IT", Map.of("exportJobId", job.id().toString(),
                            "exportStage", job.stage().name(),
                            "exportGroup", ExportJobQueue.destinationText(job.destinationId()) + ":" + key));
        } catch (RuntimeException incidentFailure) {
            log.warn("EXPORT_JOB_INCIDENT_NOT_RECORDED job={} type={}", job.id(),
                    incidentFailure.getClass().getSimpleName());
        }
        audit.recordFailure(job.tenantId(), ExportJobQueue.SYSTEM_ACTOR, AuditAction.EXPORT_JOB_NEEDS_IT_NOTIFICATION,
                "DocumentPackage", job.packageId().toString(), job.packageId(), correlationId,
                "Export job needs IT: job=" + job.id() + ", stage=" + job.stage() + ", code=" + lvCode.code()
                        + ", destination=" + ExportJobQueue.destinationText(job.destinationId()) + ", group=new");
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

    private static final String LOCAL_FOLDER_NAME = "Local export folder";

    private List<JobView> views(TenantId tenantId, List<PipelineJob> list) {
        Map<UUID, String> names = new HashMap<>();
        List<JobView> views = new ArrayList<>(list.size());
        for (PipelineJob job : list) {
            String name = null;
            if (ExportDestinations.isLocalFolder(tenantId, job.destinationId())) {
                name = LOCAL_FOLDER_NAME;
            } else if (job.destinationId() != null) {
                name = names.computeIfAbsent(job.destinationId().value(), id -> connections
                        .findByTenantIdAndId(tenantId, job.destinationId()).map(IntegrationConnection::name)
                        .orElse(null));
            }
            views.add(new JobView(job.id(), job.packageId().value(), job.stage().name(),
                    job.documentId() == null ? null : job.documentId().value(),
                    job.destinationId() == null ? null : job.destinationId().value(), name, job.status().name(),
                    job.attempts(), job.lastLvCode(), job.lastDetail(), job.updatedAt(), job.nextAttemptAt(),
                    job.createdAt(), job.firstFailedAt(), job.resend()));
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

    enum Kind { DONE, STOPPED, TRANSIENT, RATE_LIMITED, ANSWER_UNCLEAR, REFUSED, CREDENTIAL, OTHER }

    record Outcome(Kind kind, LvErrorCode code, String detail, Throwable failure, boolean alreadyReceived,
                   Duration retryAfter) {
        static Outcome done(boolean alreadyReceived) {
            return new Outcome(Kind.DONE, null, null, null, alreadyReceived, null);
        }

        static Outcome of(Kind kind, LvErrorCode code, String detail, Throwable failure) {
            return new Outcome(kind, code, detail, failure, false, null);
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
                          Instant since, Instant nextAttemptAt, Instant createdAt, Instant firstFailedAt,
                          boolean resend) { }

    /** "Waiting for you" for one destination: the count, the count per code and the jobs, oldest first. */
    public record WaitingGroup(UUID destinationId, String destinationName, int total, Map<String, Integer> countsByCode,
                               List<JobView> jobs) { }

    /**
     * One IT alert: NEEDS_IT jobs of one destination with one code. Task 0036 added {@code cases}: each case with its
     * case number (null when the case has none).
     */
    public record ItGroup(UUID destinationId, String destinationName, String lvCode, int count, Instant firstFailedAt,
                          List<UUID> caseIds, List<CaseRef> cases, List<JobView> jobs) { }

    /** A case as the IT card lists it: its id (for links) and its case number. */
    public record CaseRef(UUID packageId, String caseNumber) { }

    /**
     * Task 0029 added {@code nextCheckAt} (while paused) and {@code openJobs} (its export jobs still to be done); task
     * 0036 added {@code waiting}: the same jobs counted per state, so Needs IT is never counted as ready.
     */
    public record DestinationView(UUID destinationId, String name, String purpose, boolean enabled, String state,
                                  Instant pausedAt, int consecutiveFailures, Instant lastProbeAt, String lastLvCode,
                                  Instant lastCheckFailedAt, Instant rateLimitedUntil, int workerLimit,
                                  Instant nextCheckAt, int openJobs, WaitingCounts waiting) { }

    /**
     * A destination's open export jobs per state: {@code queued} (READY, WAITING_RETRY, RUNNING: they go out on their
     * own), {@code waitingForYou} and {@code needsIt}.
     */
    public record WaitingCounts(int queued, int waitingForYou, int needsIt) {
        static WaitingCounts of(Map<PipelineJobStatus, Integer> byStatus) {
            int queued = 0;
            int you = 0;
            int it = 0;
            for (Map.Entry<PipelineJobStatus, Integer> e : byStatus.entrySet()) {
                int n = e.getValue() == null ? 0 : e.getValue();
                switch (e.getKey()) {
                    case WAITING_FOR_YOU -> you += n;
                    case NEEDS_IT -> it += n;
                    case DONE, STOPPED -> { }
                    default -> queued += n;
                }
            }
            return new WaitingCounts(queued, you, it);
        }

        public int total() {
            return queued + waitingForYou + needsIt;
        }
    }

    /**
     * Task 0036: the timings the screens name, from the settings, so no sentence says more than happens: how often a
     * paused destination is checked, how long a failing case is retried (the sum of the schedule), how many retries,
     * whether it is rebuilt once on the way, and how many interruptions send a job to IT.
     */
    public record ExportTimings(long probeIntervalSeconds, long retrySpanSeconds, int retries, boolean rebuiltOnce,
                                int maxInterruptions) { }

    public ExportTimings timings() {
        long span = settings.retrySchedule().stream().mapToLong(Duration::toSeconds).sum();
        return new ExportTimings(settings.probeInterval().toSeconds(), span, settings.retrySchedule().size(),
                settings.rebuildBeforeRetry() <= settings.retrySchedule().size(), settings.maxAttempts());
    }

    /** "Check now": whether the check passed, whether that brought the destination back, and its state now. */
    public record CheckNow(UUID destinationId, boolean passed, boolean resumed, String state) { }

    public record AlertView(boolean raised, String lvCode, String detail, Instant raisedAt, Instant lastCheckAt,
                            Instant clearedAt) { }

    /** How a case's export looks, for the "Exporting" status (task 0029): one value per case. */
    public enum CaseExportState {
        /** No export job (inline mode, or not finished processing). */
        NONE,
        /** An export job is queued or retrying on its own schedule. */
        READY,
        DESTINATION_PAUSED,
        WAITING_FOR_YOU,
        NEEDS_IT,
        /** Every export job of the case (master and split) is done. */
        EXPORTED
    }
}

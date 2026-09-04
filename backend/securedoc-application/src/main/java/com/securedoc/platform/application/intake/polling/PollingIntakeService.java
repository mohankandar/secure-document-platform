package com.securedoc.platform.application.intake.polling;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.intake.external.*;
import com.securedoc.platform.application.integration.IntegrationConnectionService;
import com.securedoc.platform.application.port.out.PollingRunRepository;
import com.securedoc.platform.application.port.out.PollingSourcePort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.integration.*;
import com.securedoc.platform.domain.intake.ExternalIntakeStatus;

import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Scheduled/manual coordinator for LexaVault-owned pull ingestion. */
public final class PollingIntakeService {
    private final IntegrationConnectionService connectionService;
    private final PollingRunRepository runRepository;
    private final PollingSourcePort sourcePort;
    private final ExternalIntakeService externalIntakeService;
    private final AuditService auditService;
    private final Clock clock;

    public PollingIntakeService(IntegrationConnectionService connectionService, PollingRunRepository runRepository,
                                PollingSourcePort sourcePort, ExternalIntakeService externalIntakeService,
                                AuditService auditService) {
        this(connectionService, runRepository, sourcePort, externalIntakeService, auditService, Clock.systemUTC());
    }

    PollingIntakeService(IntegrationConnectionService connectionService, PollingRunRepository runRepository,
                         PollingSourcePort sourcePort, ExternalIntakeService externalIntakeService,
                         AuditService auditService, Clock clock) {
        this.connectionService = Objects.requireNonNull(connectionService);
        this.runRepository = Objects.requireNonNull(runRepository);
        this.sourcePort = Objects.requireNonNull(sourcePort);
        this.externalIntakeService = Objects.requireNonNull(externalIntakeService);
        this.auditService = Objects.requireNonNull(auditService);
        this.clock = Objects.requireNonNull(clock);
    }

    /** Returns due sources only; the API scheduler dispatches each source to a bounded executor. */
    public List<PollingSourceRef> dueSources() {
        Instant now = Instant.now(clock);
        List<PollingSourceRef> result = new ArrayList<>();
        for (IntegrationConnection source : connectionService.listEnabledPollingSources()) {
            Instant latestStarted = runRepository.findLatest(source.id()).map(PollingRun::startedAt).orElse(null);
            if (PollingScheduleCalculator.isDue(source, latestStarted, now)) {
                result.add(new PollingSourceRef(source.tenantId(), source.id()));
            }
        }
        return List.copyOf(result);
    }

    public Optional<PollingRunSnapshot> runScheduled(TenantId tenantId, IntegrationConnectionId connectionId) {
        IntegrationConnection source = connectionService.get(tenantId, connectionId);
        if (!source.enabled() || !source.pollingSource()) return Optional.empty();
        return runInternal(source, PollingRunTrigger.SCHEDULED, systemActor(source));
    }

    public Optional<PollingRunSnapshot> runNow(TenantId tenantId, IntegrationConnectionId connectionId, ActorId actorId) {
        IntegrationConnection source = connectionService.get(tenantId, connectionId);
        if (!source.enabled() || !source.pollingSource()) {
            throw new AccessDeniedException("Integration connection is not an enabled polling ingestion source");
        }
        return runInternal(source, PollingRunTrigger.MANUAL, actorId);
    }

    public PollingConnectionStatus testConnection(TenantId tenantId, IntegrationConnectionId connectionId, ActorId actorId) {
        IntegrationConnection source = connectionService.get(tenantId, connectionId);
        if (!source.enabled() || !source.pollingSource()) throw new AccessDeniedException("Integration connection is not an enabled polling source");
        CorrelationId correlationId = CorrelationId.generate();
        try {
            sourcePort.discoverAvailable(source, 1);
            connectionService.recordOperationalSuccess(source);
            auditService.recordSuccess(tenantId, actorId, AuditAction.INTEGRATION_CONNECTION_TESTED,
                    "IntegrationConnection", source.id().toString(), correlationId, "Polling connection test succeeded without document retrieval");
        } catch (RuntimeException e) {
            String category = categorize(e);
            connectionService.recordOperationalFailure(source, category);
            auditService.recordFailure(tenantId, actorId, AuditAction.INTEGRATION_CONNECTION_TESTED,
                    "IntegrationConnection", source.id().toString(), correlationId, "Polling connection test failed: category=" + category);
            throw e;
        }
        return status(tenantId, connectionId);
    }

    public PollingConnectionStatus status(TenantId tenantId, IntegrationConnectionId connectionId) {
        IntegrationConnection source = connectionService.get(tenantId, connectionId);
        if (!source.pollingSource()) throw new AccessDeniedException("Integration connection is not a polling source");
        List<PollingRun> runs = runRepository.findRecent(tenantId, connectionId, 20);
        Instant latest = runs.isEmpty() ? null : runs.get(0).startedAt();
        Instant next = source.enabled() ? PollingScheduleCalculator.nextRunAt(source, latest, Instant.now(clock)) : null;
        return new PollingConnectionStatus(connectionId.value(), source.name(), source.enabled(), source.healthStatus().name(), next,
                runs.stream().map(this::snapshot).toList());
    }

    private Optional<PollingRunSnapshot> runInternal(IntegrationConnection source, PollingRunTrigger trigger, ActorId actorId) {
        Instant now = Instant.now(clock);
        int leaseSeconds = Math.max(source.timeoutSeconds() * Math.max(2, source.retryMaxAttempts() + 1), 300);
        Optional<PollingRun> started = runRepository.tryStart(source.tenantId(), source.id(), trigger, now, now.minusSeconds(leaseSeconds));
        if (started.isEmpty()) return Optional.empty();
        PollingRun run = started.get();
        CorrelationId correlationId = CorrelationId.generate();
        AuthenticatedPrincipal principal = systemPrincipal(source, actorId);
        auditService.recordSuccess(source.tenantId(), actorId, AuditAction.POLLING_RUN_STARTED,
                "IntegrationConnection", source.id().toString(), correlationId, "Polling run started: trigger=" + trigger);

        int discovered = 0, expected = 0, retrieved = 0, acknowledged = 0, packageFailures = 0;
        String lastFailureCategory = null, lastFailureDetail = null;
        try {
            List<PollingSourcePackage> packages = sourcePort.discoverAvailable(source, effectiveBatchLimit(source));
            discovered = packages.size();
            for (PollingSourcePackage sourcePackage : packages) {
                expected += sourcePackage.documents().size();
                try {
                    PackageOutcome outcome = processPackage(source, sourcePackage, principal, correlationId);
                    retrieved += outcome.retrieved();
                    if (outcome.acknowledged()) acknowledged++;
                } catch (RuntimeException packageFailure) {
                    packageFailures++;
                    if (packageFailure instanceof PackageProcessingFailure processingFailure) {
                        retrieved += processingFailure.retrieved();
                        packageFailure = processingFailure.original();
                    }
                    lastFailureCategory = categorize(packageFailure);
                    lastFailureDetail = safeDetail(packageFailure);
                }
            }
            PollingRunStatus finalStatus = packageFailures == 0 ? PollingRunStatus.SUCCESS : PollingRunStatus.PARTIAL;
            PollingRun completed = run.complete(finalStatus, discovered, expected, retrieved, acknowledged,
                    lastFailureCategory, lastFailureDetail, Instant.now(clock));
            runRepository.save(completed);
            if (packageFailures == 0) connectionService.recordOperationalSuccess(source);
            else connectionService.recordOperationalFailure(source, lastFailureCategory);
            auditService.recordSuccess(source.tenantId(), actorId, AuditAction.POLLING_RUN_COMPLETED,
                    "IntegrationConnection", source.id().toString(), correlationId,
                    "Polling run completed: status=" + finalStatus + ",discovered=" + discovered + ",retrieved=" + retrieved + ",acknowledged=" + acknowledged);
            return Optional.of(snapshot(completed));
        } catch (RuntimeException failure) {
            String category = categorize(failure);
            PollingRun failed = run.complete(PollingRunStatus.FAILED, discovered, expected, retrieved, acknowledged,
                    category, safeDetail(failure), Instant.now(clock));
            runRepository.save(failed);
            connectionService.recordOperationalFailure(source, category);
            auditService.recordFailure(source.tenantId(), actorId, AuditAction.POLLING_RUN_FAILED,
                    "IntegrationConnection", source.id().toString(), correlationId,
                    "category=" + category + ",detail=" + safeDetail(failure));
            return Optional.of(snapshot(failed));
        }
    }

    private PackageOutcome processPackage(IntegrationConnection source, PollingSourcePackage sourcePackage,
                                          AuthenticatedPrincipal principal, CorrelationId correlationId) {
        int retrieved = 0;
        try {
            PushIntakeCreateInput input = new PushIntakeCreateInput(sourcePackage.externalSourcePackageId(), sourcePackage.sourceRevision(),
                    sourcePackage.businessIdentifiers(), sourcePackage.metadata(), sourcePackage.documents().stream()
                    .map(d -> new PushIntakeCreateInput.ManifestDocumentInput(d.externalSourceDocumentId(), d.fileName(), d.sha256())).toList(),
                    sourcePackage.retry());
            ExternalIntakeSnapshot intake = externalIntakeService.createPollingIntake(input, source, principal, correlationId);
            if (intake.intakeStatus() == ExternalIntakeStatus.INCOMPLETE_EXPIRED) return new PackageOutcome(0, false);
            Set<String> missing = new HashSet<>(intake.missingExternalSourceDocumentIds());
            if (intake.intakeStatus() != ExternalIntakeStatus.ACCEPTED_FOR_PROCESSING) {
                for (PollingSourceDocument document : sourcePackage.documents()) {
                    if (!missing.contains(document.externalSourceDocumentId())) continue;
                    try (RetrievedPollingDocument retrievedDocument = retrieveWithRetry(source, sourcePackage, document)) {
                        String idempotencyKey = idempotencyKey(source, sourcePackage, document);
                        ExternalDocumentReceipt receipt = externalIntakeService.receivePollingDocument(PackageId.of(intake.packageId()),
                                document.externalSourceDocumentId(), idempotencyKey, retrievedDocument.sha256(), retrievedDocument.mimeType(),
                                retrievedDocument.content(), source, principal, correlationId);
                        if (!receipt.duplicateAcknowledgement()) retrieved++;
                    }
                }
            }
            ExternalIntakeSnapshot finalSnapshot = externalIntakeService.pollingStatus(PackageId.of(intake.packageId()), source, principal, correlationId);
            if (finalSnapshot.intakeStatus() == ExternalIntakeStatus.ACCEPTED_FOR_PROCESSING) {
                sourcePort.acknowledgeReceived(source, sourcePackage, finalSnapshot.attemptNumber());
                auditService.recordSuccess(source.tenantId(), principal.actorId(), AuditAction.POLLING_SOURCE_ACKNOWLEDGED,
                        "ExternalIntake", finalSnapshot.packageId().toString(), correlationId,
                        "Source pickup acknowledged after durable receipt: revision=" + sourcePackage.sourceRevision());
                return new PackageOutcome(retrieved, true);
            }
            return new PackageOutcome(retrieved, false);
        } catch (RuntimeException failure) {
            throw new PackageProcessingFailure(retrieved, failure);
        }
    }

    private RetrievedPollingDocument retrieveWithRetry(IntegrationConnection source, PollingSourcePackage pkg,
                                                        PollingSourceDocument document) {
        RuntimeException last = null;
        int attempts = Math.max(1, source.retryMaxAttempts() + 1);
        for (int i = 1; i <= attempts; i++) {
            try { return sourcePort.retrieveDocument(source, pkg, document); }
            catch (RuntimeException e) { last = e; }
        }
        throw Objects.requireNonNull(last);
    }

    private AuthenticatedPrincipal systemPrincipal(IntegrationConnection source, ActorId actorId) {
        return AuthenticatedPrincipal.of(actorId, source.tenantId(), "polling:" + source.id(), Set.of());
    }
    private ActorId systemActor(IntegrationConnection source) {
        UUID value = UUID.nameUUIDFromBytes(("lexavault-polling:" + source.tenantId() + ":" + source.id()).getBytes(StandardCharsets.UTF_8));
        return ActorId.of(value);
    }
    private int effectiveBatchLimit(IntegrationConnection c){return c.pollingBatchLimit()==null?IntegrationConnection.DEFAULT_POLLING_BATCH_LIMIT:c.pollingBatchLimit();}
    private String idempotencyKey(IntegrationConnection source, PollingSourcePackage pkg, PollingSourceDocument doc) {
        return "poll:" + source.id() + ":" + pkg.externalSourcePackageId() + ":" + pkg.sourceRevision() + ":" + doc.externalSourceDocumentId();
    }
    private String categorize(RuntimeException e){
        if (e instanceof PollingSourceException sourceFailure) return sourceFailure.category();
        return e.getClass().getSimpleName().replaceAll("Exception$", "").toUpperCase(Locale.ROOT);
    }
    private String safeDetail(Throwable e){String m=e.getMessage(); return m==null?e.getClass().getSimpleName():(m.length()>500?m.substring(0,500):m);}
    private PollingRunSnapshot snapshot(PollingRun r){return new PollingRunSnapshot(r.id(), r.integrationConnectionId().value(), r.trigger(), r.status(), r.startedAt(), r.completedAt(), r.packagesDiscovered(), r.documentsExpected(), r.documentsRetrieved(), r.packagesAcknowledged(), r.failureCategory(), r.failureDetail());}
    public record PollingSourceRef(TenantId tenantId, IntegrationConnectionId connectionId) {}
    private record PackageOutcome(int retrieved, boolean acknowledged) {}
    private static final class PackageProcessingFailure extends RuntimeException {
        private final int retrieved;
        private final RuntimeException original;
        private PackageProcessingFailure(int retrieved, RuntimeException original) {
            super(original.getMessage(), original);
            this.retrieved = Math.max(0, retrieved);
            this.original = Objects.requireNonNull(original);
        }
        int retrieved() { return retrieved; }
        RuntimeException original() { return original; }
    }
}

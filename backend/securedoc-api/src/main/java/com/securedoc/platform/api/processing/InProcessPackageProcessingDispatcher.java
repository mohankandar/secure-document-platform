package com.securedoc.platform.api.processing;

import com.securedoc.platform.application.port.in.TriggerPackageProcessingUseCase;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.processing.service.PackageProcessingRecoveryService;
import com.securedoc.platform.application.observability.StructuredLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import com.securedoc.platform.domain.identity.TenantId;

/**
 * V1 in-process dispatcher for asynchronous package processing.
 *
 * <p>If dispatch is requested while a Spring transaction is active, the background task is
 * submitted only after that transaction commits. This prevents the worker from observing
 * pre-commit package state (for example CREATED immediately before SUBMITTED is committed).
 * A rollback therefore never starts processing for state that was not durably committed.
 */
public final class InProcessPackageProcessingDispatcher implements PackageProcessingDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InProcessPackageProcessingDispatcher.class);

    private final TaskExecutor taskExecutor;
    private final TriggerPackageProcessingUseCase triggerPackageProcessingUseCase;
    private final PackageProcessingDispatchLease processingLease;
    private final Predicate<TenantId> processingStorageReady;
    private final DispatchFailureStop dispatchFailureStop;
    private final int maxDispatchAttempts;
    /**
     * Task 0023 C1: failed dispatches per package in this JVM. In memory on purpose (no schema
     * change): a restart or another node starts its own count, so a case is stopped after at most
     * {@code maxDispatchAttempts} failures per process lifetime per node. Entries are removed on
     * success and when the case is stopped (or the stop does not apply).
     */
    private final Map<UUID, Integer> dispatchFailures = new ConcurrentHashMap<>();

    /** Task 0023 C1: stops a case after repeated dispatch failures. Returns true when it stopped the case. */
    @FunctionalInterface
    public interface DispatchFailureStop {
        boolean stop(TriggerPackageProcessingCommand command, RuntimeException failure,
                     int failedAttempts, int maxAttempts);

        static DispatchFailureStop none() {
            return (command, failure, failedAttempts, maxAttempts) -> false;
        }
    }

    public InProcessPackageProcessingDispatcher(TaskExecutor taskExecutor,
                                                TriggerPackageProcessingUseCase triggerPackageProcessingUseCase) {
        this(taskExecutor, triggerPackageProcessingUseCase, PackageProcessingDispatchLease.disabled(), tenantId -> true);
    }

    public InProcessPackageProcessingDispatcher(TaskExecutor taskExecutor,
                                                TriggerPackageProcessingUseCase triggerPackageProcessingUseCase,
                                                PackageProcessingDispatchLease processingLease) {
        this(taskExecutor, triggerPackageProcessingUseCase, processingLease, tenantId -> true);
    }

    public InProcessPackageProcessingDispatcher(TaskExecutor taskExecutor,
                                                TriggerPackageProcessingUseCase triggerPackageProcessingUseCase,
                                                PackageProcessingDispatchLease processingLease,
                                                Predicate<TenantId> processingStorageReady) {
        this(taskExecutor, triggerPackageProcessingUseCase, processingLease, processingStorageReady,
                DispatchFailureStop.none(), 3);
    }

    public InProcessPackageProcessingDispatcher(TaskExecutor taskExecutor,
                                                TriggerPackageProcessingUseCase triggerPackageProcessingUseCase,
                                                PackageProcessingDispatchLease processingLease,
                                                Predicate<TenantId> processingStorageReady,
                                                DispatchFailureStop dispatchFailureStop,
                                                int maxDispatchAttempts) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor);
        this.triggerPackageProcessingUseCase = Objects.requireNonNull(triggerPackageProcessingUseCase);
        this.processingLease = Objects.requireNonNull(processingLease);
        this.processingStorageReady = Objects.requireNonNull(processingStorageReady);
        this.dispatchFailureStop = Objects.requireNonNull(dispatchFailureStop);
        this.maxDispatchAttempts = Math.max(1, maxDispatchAttempts);
    }

    @Override
    public void dispatch(TriggerPackageProcessingCommand command) {
        Objects.requireNonNull(command, "Command must not be null");

        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    schedule(command);
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        log.warn("PACKAGE_PROCESSING_DISPATCH_CANCELLED package={} tenant={} correlation={} transactionStatus={}",
                                command.packageId(), command.tenantId(), command.correlationId(), status);
                    }
                }
            });
            log.info("PACKAGE_PROCESSING_DISPATCH_DEFERRED_UNTIL_COMMIT package={} tenant={} correlation={}",
                    command.packageId(), command.tenantId(), command.correlationId());
            return;
        }

        schedule(command);
    }

    private void schedule(TriggerPackageProcessingCommand command) {
        if (!processingStorageReady.test(command.tenantId())) {
            log.warn("PACKAGE_PROCESSING_DISPATCH_SKIPPED_STORAGE_NOT_READY package={} tenant={} correlation={}",
                    command.packageId(), command.tenantId(), command.correlationId());
            return;
        }
        long queuedAtNanos = System.nanoTime();
        StructuredLogContext.info(log, "PACKAGE_PROCESSING_DISPATCH_ENQUEUED", executorSnapshot(),
                "Package processing dispatch enqueued");

        var claim = processingLease.tryClaim(command);
        if (claim.isEmpty()) {
            Map<String, Object> skippedFields = new LinkedHashMap<>(executorSnapshot());
            skippedFields.put("recovery", command.recovery());
            StructuredLogContext.info(log, "PACKAGE_PROCESSING_DISPATCH_SKIPPED_ALREADY_OWNED", skippedFields,
                    "Package processing dispatch skipped because another node/process owns the package lease");
            return;
        }

        try {
            taskExecutor.execute(() -> {
                try {
                    try (var context = StructuredLogContext.open(java.util.Map.of(
                            "packageId", command.packageId(),
                            "tenantId", command.tenantId(),
                            "correlationId", command.correlationId()))) {
                        if (!processingLease.isCurrent(claim.get())) {
                            Map<String, Object> staleFields = new LinkedHashMap<>(executorSnapshot());
                            staleFields.put("recovery", command.recovery());
                            staleFields.put("reasonCode", "PACKAGE_PROCESSING_LEASE_NOT_CURRENT");
                            StructuredLogContext.warn(log, "PACKAGE_PROCESSING_DISPATCH_SKIPPED_STALE_LEASE", staleFields,
                                    "Package processing dispatch skipped because its lease is no longer current");
                            return;
                        }
                        Map<String, Object> startedFields = new LinkedHashMap<>(executorSnapshot());
                        startedFields.put("queueWaitMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - queuedAtNanos));
                        startedFields.put("recovery", command.recovery());
                        StructuredLogContext.info(log, "PACKAGE_PROCESSING_DISPATCH_STARTED", startedFields,
                                "Package processing dispatch started");
                        try {
                            triggerPackageProcessingUseCase.execute(command);
                            dispatchFailures.remove(command.packageId().value());
                            StructuredLogContext.info(log, "PACKAGE_PROCESSING_DISPATCH_COMPLETED", executorSnapshot(),
                                    "Package processing dispatch completed");
                        } catch (RuntimeException ex) {
                            // Keep document content and raw exception messages out of logs while making
                            // asynchronous processing failures operationally visible.
                            Map<String, Object> failedFields = new LinkedHashMap<>(executorSnapshot());
                            failedFields.put("reasonCode", ex.getClass().getSimpleName());
                            failedFields.put("recovery", command.recovery());
                            StructuredLogContext.error(log, "PACKAGE_PROCESSING_DISPATCH_FAILED", failedFields,
                                    "Package processing dispatch failed");
                            handleDispatchFailure(command, ex);
                        }
                    }
                } finally {
                    processingLease.release(claim.get());
                }
            });
        } catch (RuntimeException rejected) {
            processingLease.release(claim.get());
            throw rejected;
        }
        log.info("PACKAGE_PROCESSING_DISPATCH_SCHEDULED package={} tenant={} correlation={} recovery={}",
                command.packageId(), command.tenantId(), command.correlationId(), command.recovery());
    }

    /**
     * Task 0023 C1: counts the failure and, after {@code maxDispatchAttempts} failures of the same
     * case (or at once for a licence denial), asks the application to stop the case as FAILED with a
     * code and a Support incident. Runs while this node still holds the processing lease.
     */
    private void handleDispatchFailure(TriggerPackageProcessingCommand command, RuntimeException failure) {
        UUID key = command.packageId().value();
        boolean licenceDenial = PackageProcessingRecoveryService.isLicenceDenial(failure);
        int failures = dispatchFailures.merge(key, 1, Integer::sum);
        String failureType = failure.getClass().getSimpleName();
        if (!licenceDenial && failures < maxDispatchAttempts) {
            log.warn("PACKAGE_PROCESSING_DISPATCH_RETRY_PENDING package={} tenant={} failureType={} failedAttempts={} maxAttempts={}",
                    command.packageId(), command.tenantId(), failureType, failures, maxDispatchAttempts);
            return;
        }
        dispatchFailures.remove(key);
        try {
            boolean stopped = dispatchFailureStop.stop(command, failure, failures, maxDispatchAttempts);
            if (stopped) {
                log.error("PACKAGE_PROCESSING_DISPATCH_STOPPED package={} tenant={} reasonCode={} failureType={} failedAttempts={} maxAttempts={}",
                        command.packageId(), command.tenantId(),
                        licenceDenial ? "PROCESSING_LICENSE_DENIED" : "PROCESSING_DISPATCH_EXHAUSTED",
                        failureType, failures, maxDispatchAttempts);
            } else {
                log.warn("PACKAGE_PROCESSING_DISPATCH_STOP_NOT_APPLICABLE package={} tenant={} failureType={} failedAttempts={}",
                        command.packageId(), command.tenantId(), failureType, failures);
            }
        } catch (RuntimeException stopFailure) {
            log.error("PACKAGE_PROCESSING_DISPATCH_STOP_FAILED package={} tenant={} failureType={} stopFailureType={}",
                    command.packageId(), command.tenantId(), failureType, stopFailure.getClass().getSimpleName());
        }
    }

    /**
     * Returns privacy-safe executor capacity counters for performance baselining. No tenant, document,
     * prompt, OCR, or protected values are exposed. Non-thread-pool executors simply return no counters.
     */
    private Map<String, Object> executorSnapshot() {
        if (!(taskExecutor instanceof ThreadPoolTaskExecutor executor)) {
            return Map.of();
        }

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("workerCorePoolSize", executor.getCorePoolSize());
        fields.put("workerMaxPoolSize", executor.getMaxPoolSize());
        fields.put("workerPoolSize", executor.getPoolSize());
        fields.put("activeWorkers", executor.getActiveCount());

        try {
            ThreadPoolExecutor delegate = executor.getThreadPoolExecutor();
            fields.put("workerQueueDepth", delegate.getQueue().size());
            fields.put("workerQueueRemaining", delegate.getQueue().remainingCapacity());
        } catch (IllegalStateException ignored) {
            // Executor is not initialized yet. Capacity fields above remain useful and safe.
        }

        return fields;
    }
}

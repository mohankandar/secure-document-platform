package com.securedoc.platform.api.processing;

import com.securedoc.platform.application.port.in.TriggerPackageProcessingUseCase;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
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
        this.taskExecutor = Objects.requireNonNull(taskExecutor);
        this.triggerPackageProcessingUseCase = Objects.requireNonNull(triggerPackageProcessingUseCase);
        this.processingLease = Objects.requireNonNull(processingLease);
        this.processingStorageReady = Objects.requireNonNull(processingStorageReady);
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

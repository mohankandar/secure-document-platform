package com.securedoc.platform.api.processing;

import com.securedoc.platform.application.port.in.TriggerPackageProcessingUseCase;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.observability.StructuredLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

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

    public InProcessPackageProcessingDispatcher(TaskExecutor taskExecutor,
                                                TriggerPackageProcessingUseCase triggerPackageProcessingUseCase) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor);
        this.triggerPackageProcessingUseCase = Objects.requireNonNull(triggerPackageProcessingUseCase);
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
        taskExecutor.execute(() -> {
            try (var context = StructuredLogContext.open(java.util.Map.of(
                    "packageId", command.packageId(),
                    "tenantId", command.tenantId(),
                    "correlationId", command.correlationId()))) {
                StructuredLogContext.info(log, "PACKAGE_PROCESSING_DISPATCH_STARTED", java.util.Map.of(),
                        "Package processing dispatch started");
                try {
                    triggerPackageProcessingUseCase.execute(command);
                    StructuredLogContext.info(log, "PACKAGE_PROCESSING_DISPATCH_COMPLETED", java.util.Map.of(),
                            "Package processing dispatch completed");
                } catch (RuntimeException ex) {
                    // Keep document content and raw exception messages out of logs while making
                    // asynchronous processing failures operationally visible.
                    StructuredLogContext.error(log, "PACKAGE_PROCESSING_DISPATCH_FAILED",
                            java.util.Map.of("reasonCode", ex.getClass().getSimpleName()),
                            "Package processing dispatch failed");
                }
            }
        });
        log.info("PACKAGE_PROCESSING_DISPATCH_SCHEDULED package={} tenant={} correlation={}",
                command.packageId(), command.tenantId(), command.correlationId());
    }
}

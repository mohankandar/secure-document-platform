package com.securedoc.platform.api.processing;

import com.securedoc.platform.application.port.in.TriggerPackageProcessingUseCase;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InProcessPackageProcessingDispatcherTest {

    @Test
    void dispatchReturnsWithoutWaitingForPackageProcessingToFinish() throws Exception {
        CountDownLatch processingStarted = new CountDownLatch(1);
        CountDownLatch allowProcessingToFinish = new CountDownLatch(1);
        CountDownLatch processingFinished = new CountDownLatch(1);

        TriggerPackageProcessingUseCase slowProcessing = command -> {
            processingStarted.countDown();
            try {
                if (!allowProcessingToFinish.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Test processing was not released");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted", ex);
            } finally {
                processingFinished.countDown();
            }
        };

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(4);
        executor.setThreadNamePrefix("package-processing-test-");
        executor.initialize();

        try {
            InProcessPackageProcessingDispatcher dispatcher =
                    new InProcessPackageProcessingDispatcher(executor, slowProcessing);

            assertTimeoutPreemptively(Duration.ofMillis(500), () -> dispatcher.dispatch(command()));
            assertTrue(processingStarted.await(2, TimeUnit.SECONDS));
            assertFalse(processingFinished.await(100, TimeUnit.MILLISECONDS),
                    "Background processing must still be running after dispatch returns");

            allowProcessingToFinish.countDown();
            assertTrue(processingFinished.await(2, TimeUnit.SECONDS));
        } finally {
            allowProcessingToFinish.countDown();
            executor.shutdown();
        }
    }

    @Test
    void dispatchInsideTransactionStartsOnlyAfterCommit() {
        AtomicInteger executions = new AtomicInteger();
        InProcessPackageProcessingDispatcher dispatcher = new InProcessPackageProcessingDispatcher(
                new SyncTaskExecutor(), command -> executions.incrementAndGet());

        beginSynchronization();
        try {
            dispatcher.dispatch(command());
            assertEquals(0, executions.get(), "Processing must not see uncommitted package state");

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCommit();
            }
            assertEquals(1, executions.get(), "Processing must start after commit");

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED);
            }
        } finally {
            endSynchronization();
        }
    }

    @Test
    void rolledBackTransactionDoesNotDispatchProcessing() {
        AtomicInteger executions = new AtomicInteger();
        InProcessPackageProcessingDispatcher dispatcher = new InProcessPackageProcessingDispatcher(
                new SyncTaskExecutor(), command -> executions.incrementAndGet());

        beginSynchronization();
        try {
            dispatcher.dispatch(command());
            assertEquals(0, executions.get());

            for (TransactionSynchronization synchronization : TransactionSynchronizationManager.getSynchronizations()) {
                synchronization.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            assertEquals(0, executions.get(), "Rolled-back intake must never start package processing");
        } finally {
            endSynchronization();
        }
    }

    private static void beginSynchronization() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
    }

    private static void endSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.setActualTransactionActive(false);
    }

    private static TriggerPackageProcessingCommand command() {
        return TriggerPackageProcessingCommand.of(
                PackageId.of(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                TenantId.of(UUID.fromString("22222222-2222-2222-2222-222222222222")),
                ActorId.of(UUID.fromString("33333333-3333-3333-3333-333333333333")),
                CorrelationId.of(UUID.fromString("44444444-4444-4444-4444-444444444444")));
    }
}

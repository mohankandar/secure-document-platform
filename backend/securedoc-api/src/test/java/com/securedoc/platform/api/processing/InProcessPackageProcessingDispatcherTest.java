package com.securedoc.platform.api.processing;

import com.securedoc.platform.application.port.in.TriggerPackageProcessingUseCase;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
        executor.setThreadNamePrefix("review-resume-test-");
        executor.initialize();

        try {
            InProcessPackageProcessingDispatcher dispatcher =
                    new InProcessPackageProcessingDispatcher(executor, slowProcessing);
            TriggerPackageProcessingCommand command = TriggerPackageProcessingCommand.of(
                    PackageId.of(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                    TenantId.of(UUID.fromString("22222222-2222-2222-2222-222222222222")),
                    ActorId.of(UUID.fromString("33333333-3333-3333-3333-333333333333")),
                    CorrelationId.of(UUID.fromString("44444444-4444-4444-4444-444444444444")));

            assertTimeoutPreemptively(Duration.ofMillis(500), () -> dispatcher.dispatch(command));
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
}

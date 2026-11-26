package com.securedoc.platform.api.lifecycle;

import com.securedoc.platform.application.lifecycle.command.PurgeDueRetentionCommand;
import com.securedoc.platform.application.lifecycle.result.RetentionPurgeResult;
import com.securedoc.platform.application.pipeline.FinishedJobCleanupService;
import com.securedoc.platform.application.port.in.PurgeDueRetentionUseCase;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Task 0034: the clean-up runs inside the retention purge's own run; one failing never stops the other. */
class RetentionPurgeSchedulerCleanupTest {

    @Test
    void theCleanUpRunsAfterThePurgeWithTheSameTimeAndCorrelation() {
        PurgeDueRetentionUseCase purge = mock(PurgeDueRetentionUseCase.class);
        FinishedJobCleanupService cleanup = mock(FinishedJobCleanupService.class);
        when(purge.execute(any())).thenReturn(new RetentionPurgeResult(0, 0));
        when(cleanup.run(any(), any(), any())).thenReturn(new FinishedJobCleanupService.Result(0, 0));

        new RetentionPurgeScheduler(purge, cleanup,
                mock(com.securedoc.platform.application.lifecycle.service.UnrecordedFileSweepService.class),
                mock(com.securedoc.platform.application.lifecycle.service.DeliveryLeftoverService.class)).purgeDueArtifacts();

        var order = inOrder(purge, cleanup);
        var command = org.mockito.ArgumentCaptor.forClass(PurgeDueRetentionCommand.class);
        order.verify(purge).execute(command.capture());
        order.verify(cleanup).run(eq(command.getValue().now()), eq(command.getValue().actorId()),
                eq(command.getValue().correlationId()));
    }

    @Test
    void aFailingPurgeDoesNotStopTheCleanUpAndAFailingCleanUpIsContained() {
        PurgeDueRetentionUseCase purge = mock(PurgeDueRetentionUseCase.class);
        FinishedJobCleanupService cleanup = mock(FinishedJobCleanupService.class);
        when(purge.execute(any())).thenThrow(new IllegalStateException("purge"));
        when(cleanup.run(any(), any(), any())).thenThrow(new IllegalStateException("cleanup"));

        new RetentionPurgeScheduler(purge, cleanup,
                mock(com.securedoc.platform.application.lifecycle.service.UnrecordedFileSweepService.class),
                mock(com.securedoc.platform.application.lifecycle.service.DeliveryLeftoverService.class)).purgeDueArtifacts();

        verify(cleanup).run(any(), any(), any());
    }
}

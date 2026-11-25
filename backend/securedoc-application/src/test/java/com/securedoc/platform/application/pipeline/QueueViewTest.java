package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.pipeline.QueueView.Work;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Task 0078: the pace, the time of each stage and of the queue. */
class QueueViewTest {

    @Test
    void theSlowestStageSaysTheQueuesTimeAndNoStageEndsBeforeTheOneBeforeIt() {
        var view = QueueView.view(Map.of(
                PipelineStage.TEXT, work(10, 2, 0, 60, 60),       // 12 left at 1 a second
                PipelineStage.FIND, work(50, 10, 60, 30, 60),     // 120 left at 0.5 a second
                PipelineStage.REDACT, work(0, 0, 100, 100, 50),   // 100 left at 2 a second, but after Find
                PipelineStage.EXPORT, work(0, 0, 0, 9, 10)));
        assertEquals(12L, eta(view, PipelineStage.TEXT));
        assertEquals(240L, eta(view, PipelineStage.FIND));
        assertEquals(240L, eta(view, PipelineStage.REDACT));
        assertEquals(0L, eta(view, PipelineStage.EXPORT));
        assertEquals(0L, eta(view, PipelineStage.VISION));
        assertEquals(240L, view.etaSeconds());
        assertEquals(30.0, view.stages().get(1).perMinute());
    }

    @Test
    void noTimeIsSaidWhileAStageWithWorkHasNoKnownPace() {
        var view = QueueView.view(Map.of(PipelineStage.TEXT, work(10, 0, 0, 5, 10),
                PipelineStage.FIND, work(1, 0, 0, 4, 10), PipelineStage.REDACT, work(1, 0, 0, 50, 10)));
        assertEquals(20L, eta(view, PipelineStage.TEXT));
        assertNull(eta(view, PipelineStage.FIND), "four finished jobs are too few");
        assertNull(eta(view, PipelineStage.REDACT), "it comes after a stage whose time is not known");
        assertNull(view.etaSeconds());
    }

    @Test
    void anEmptyQueueIsDoneAndHeldJobsAreNotInTheTime() {
        assertEquals(0L, QueueView.view(Map.of()).etaSeconds());
        assertEquals(0L, QueueView.view(Map.of(PipelineStage.EXPORT, new Work(0, 0, 7, null, 0, 0, 0))).etaSeconds());
    }

    private static Work work(int waiting, int running, int toCome, int done, double busy) {
        return new Work(waiting, running, 0, null, toCome, done, busy);
    }

    private static Long eta(QueueView.View view, PipelineStage stage) {
        return view.stages().stream().filter(s -> s.stage() == stage).findFirst().orElseThrow().etaSeconds();
    }
}

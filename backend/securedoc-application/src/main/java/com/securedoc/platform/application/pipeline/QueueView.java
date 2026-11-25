package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.port.out.QueueWorkPort;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineStage;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Task 0078 (P6): the work of each stage and the time until the queue is empty. Numbers only, never content.
 *
 * <p>A stage's pace: its jobs finished in the last {@link #WINDOW} (the last minute, if busy enough), divided by the time in it in which the stage had any
 * work. Its time: everything still to do there (waiting, running, and what the stages before it will still bring)
 * divided by that pace, and never less than the stage before it. The queue's time is the last one's. With fewer than
 * {@link #LEAST_FINISHED} finished jobs the pace is not known, and no time is said.
 */
public final class QueueView {
    public static final Duration WINDOW = Duration.ofMinutes(10);
    /** The last minute alone says the pace of a stage that finished this many jobs in it: workers come and go. */
    public static final Duration LATELY = Duration.ofMinutes(1);
    public static final int ENOUGH_LATELY = 20;
    static final int LEAST_FINISHED = 5;
    /** In working order. EXPORT stands for both export stages. */
    static final List<PipelineStage> ORDER = List.of(PipelineStage.TEXT, PipelineStage.FIND, PipelineStage.VISION,
            PipelineStage.REDACT, PipelineStage.EXPORT);

    /**
     * @param held    not due: waiting for a retry's time, a paused destination, a Bulk source's window, or a person; not in the time
     * @param toCome  jobs the stages before will still bring
     * @param done    finished in the time the pace is taken from
     * @param busySeconds the part of that time in which the stage had work
     */
    public record Work(int waiting, int running, int held, Long oldestWaitingSeconds, int toCome, int done,
                       double busySeconds) {
        public static final Work NONE = new Work(0, 0, 0, null, 0, 0, 0);

        int left() {
            return waiting + running + toCome;
        }
    }

    /** @param etaSeconds 0 with nothing left; null while the pace is not known */
    public record Stage(PipelineStage stage, Work work, Double perMinute, Long etaSeconds) { }

    public record View(List<Stage> stages, Long etaSeconds) { }

    private final QueueWorkPort port;

    public QueueView(QueueWorkPort port) {
        this.port = port;
    }

    /** @param tenantId null for every entity together (the numbers are added up, nothing else) */
    public View of(TenantId tenantId, Set<String> openBulkSources) {
        return view(port.work(tenantId, WINDOW, openBulkSources));
    }

    static View view(Map<PipelineStage, Work> work) {
        Map<PipelineStage, Work> known = new EnumMap<>(PipelineStage.class);
        known.putAll(work);
        List<Stage> out = new ArrayList<>();
        Double longest = 0.0;
        for (PipelineStage stage : ORDER) {
            Work w = known.getOrDefault(stage, Work.NONE);
            Double perSecond = w.done() >= LEAST_FINISHED && w.busySeconds() > 0 ? w.done() / w.busySeconds() : null;
            if (w.left() > 0) {
                if (perSecond == null) longest = null;
                else if (longest != null) longest = Math.max(longest, w.left() / perSecond);
            }
            Long eta = w.left() == 0 ? Long.valueOf(0) : longest == null ? null : Long.valueOf(Math.round(longest));
            out.add(new Stage(stage, w, perSecond == null ? null : Math.round(perSecond * 600) / 10.0, eta));
        }
        return new View(out, longest == null ? null : Long.valueOf(Math.round(longest)));
    }
}

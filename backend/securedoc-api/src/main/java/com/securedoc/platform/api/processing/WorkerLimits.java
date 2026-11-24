package com.securedoc.platform.api.processing;

import com.securedoc.platform.domain.pipeline.PipelineStage;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Task 0067: how many workers a stage may have, and how many it gets now. No state. */
final class WorkerLimits {
    static final List<PipelineStage> STAGES = List.of(PipelineStage.TEXT, PipelineStage.FIND, PipelineStage.VISION,
            PipelineStage.REDACT, PipelineStage.EXPORT);
    /** Database connections left for requests and for the app's own checks. */
    static final int CONNECTIONS_KEPT_FREE = 10;

    private static final int FIND_FIRST = 6;
    private static final int WAITING_STAGES_FIRST = 4;

    /** @param freeMemoryMb memory free when the app started */
    record Machine(int cores, long freeMemoryMb, int databaseConnections) { }

    private WorkerLimits() {
    }

    /**
     * The highest number per stage on this machine: Text one per core leaving one free, Redact half the cores, Text and
     * Redact together no more than the free memory allows, Find and Vision what the AI servers may take, Export what is
     * asked, and all together no more than the database connections (a running job holds one). Never below 1. When
     * connections are short, Find (usually the slowest) first gets 6, Vision and Export 4 each (they mostly wait), then
     * Text and Redact theirs (two to one), then the others the rest.
     */
    static Map<PipelineStage, Integer> measured(Machine machine, int memoryPerWorkerMb, int aiRequests, int visionRequests,
                                                int exports) {
        Map<PipelineStage, Integer> wanted = new EnumMap<>(PipelineStage.class);
        wanted.put(PipelineStage.TEXT, machine.cores() - 1);
        wanted.put(PipelineStage.REDACT, machine.cores() / 2);
        wanted.put(PipelineStage.FIND, aiRequests);
        wanted.put(PipelineStage.VISION, visionRequests);
        wanted.put(PipelineStage.EXPORT, exports);
        long memory = machine.freeMemoryMb() / Math.max(1, memoryPerWorkerMb) - 2;
        int connections = machine.databaseConnections() - CONNECTIONS_KEPT_FREE - STAGES.size();
        Map<PipelineStage, Integer> out = new EnumMap<>(PipelineStage.class);
        STAGES.forEach(stage -> out.put(stage, 1));
        List<PipelineStage> waiting = List.of(PipelineStage.FIND, PipelineStage.VISION, PipelineStage.EXPORT);
        List<PipelineStage> working = List.of(PipelineStage.TEXT, PipelineStage.TEXT, PipelineStage.REDACT);
        connections -= share(out, wanted, waiting, WAITING_STAGES_FIRST, connections);
        connections -= share(out, wanted, List.of(PipelineStage.FIND), FIND_FIRST, connections);
        connections -= share(out, wanted, working, MANY, (int) Math.min(connections, memory));
        share(out, wanted, waiting, MANY, connections);
        return out;
    }

    private static final int MANY = 1_000;

    /** One by one in this order, up to what each wants and {@code upTo}; the number given. */
    private static int share(Map<PipelineStage, Integer> out, Map<PipelineStage, Integer> wanted,
                             List<PipelineStage> stages, int upTo, int available) {
        int left = Math.max(0, available);
        boolean given = true;
        while (given && left > 0) {
            given = false;
            for (PipelineStage stage : stages) {
                if (left < 1 || out.get(stage) >= Math.min(upTo, wanted.get(stage))) continue;
                out.merge(stage, 1, Integer::sum);
                left--;
                given = true;
            }
        }
        return Math.max(0, available) - left;
    }

    /** Up at once, down by half of what is too many, so a short gap in the queue does not stop and start workers. */
    static int next(int current, int due, int busy, int lowest, int highest) {
        int wanted = Math.max(lowest, (int) Math.min(highest, (long) due + busy));
        if (wanted >= current) return wanted;
        // Over the highest (IT lowered it, or the AI server takes less): at once.
        int from = Math.min(current, highest);
        return from <= wanted ? wanted : from - Math.max(1, (from - wanted) / 2);
    }
}

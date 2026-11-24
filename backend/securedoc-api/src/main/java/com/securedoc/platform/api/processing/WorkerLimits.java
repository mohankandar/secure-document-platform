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

    /** @param freeMemoryMb memory free when the app started */
    record Machine(int cores, long freeMemoryMb, int databaseConnections) { }

    private WorkerLimits() {
    }

    /**
     * The highest number per stage on this machine: Text one per core leaving one free, Redact half the cores, Text and
     * Redact together no more than the free memory allows, Find and Vision what the AI servers may take, Export what is
     * asked, and all together no more than the database connections (each may hold one). Never below 1; what is short is
     * shared out one by one.
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
        boolean given = true;
        while (given && connections > 0) {
            given = false;
            for (PipelineStage stage : STAGES) {
                boolean usesMemory = stage == PipelineStage.TEXT || stage == PipelineStage.REDACT;
                if (connections < 1 || out.get(stage) >= wanted.get(stage) || usesMemory && memory < 1) continue;
                out.merge(stage, 1, Integer::sum);
                connections--;
                if (usesMemory) memory--;
                given = true;
            }
        }
        return out;
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

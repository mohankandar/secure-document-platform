package com.securedoc.platform.api.processing;

import com.securedoc.platform.ai.analysis.AiServerRequestLimit;
import com.securedoc.platform.api.config.AiRuntimeProperties;
import com.securedoc.platform.application.pipeline.PipelineMode;
import com.securedoc.platform.application.pipeline.StagedProcessingSettings;
import com.securedoc.platform.application.port.out.CapacityRepository;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/** Task 0067: the workers of a stage follow its queue between its lowest and its highest. */
class WorkersThatGrowTest {
    private final Map<String, Integer> maximums = new HashMap<>();
    private final Map<PipelineStage, Integer> due = new HashMap<>();
    private final Map<String, Integer> learned = new HashMap<>();
    private boolean databaseDown;
    private final CapacityRepository repository = new CapacityRepository() {
        @Override
        public OptionalInt learnedLimit(String server) {
            return learned.containsKey(server) ? OptionalInt.of(learned.get(server)) : OptionalInt.empty();
        }

        @Override
        public void saveLearnedLimit(String server, int limit) {
            learned.put(server, limit);
        }

        @Override
        public Map<String, Integer> maximums() {
            if (databaseDown) throw new IllegalStateException("down");
            return Map.copyOf(maximums);
        }

        @Override
        public void setMaximum(String name, Integer maximum) {
            if (maximum == null) maximums.remove(name);
            else maximums.put(name, maximum);
        }

        @Override
        public Map<PipelineStage, Integer> dueJobs(int most) {
            return Map.copyOf(due);
        }

        @Override
        public int waitingCases(int most) {
            return 3;
        }

        @Override
        public long databaseBytes() {
            return 0;
        }
    };

    @AfterEach
    void noLimit() {
        AiServerRequestLimit.set(0);
    }

    @Test
    void theHighestComesFromTheMachine() {
        var eightCores = WorkerLimits.measured(new WorkerLimits.Machine(8, 32_000, 100), 512, 4, 4, 16);
        assertEquals(Map.of(PipelineStage.TEXT, 7, PipelineStage.FIND, 4, PipelineStage.VISION, 4,
                PipelineStage.REDACT, 4, PipelineStage.EXPORT, 16), eightCores);
        var littleMemory = WorkerLimits.measured(new WorkerLimits.Machine(8, 2_100, 100), 512, 4, 4, 16);
        assertEquals(4, littleMemory.get(PipelineStage.TEXT) + littleMemory.get(PipelineStage.REDACT));
        var nothingFree = WorkerLimits.measured(new WorkerLimits.Machine(1, 0, 5), 512, 4, 4, 16);
        nothingFree.values().forEach(n -> assertEquals(1, n));
        var fewConnections = WorkerLimits.measured(new WorkerLimits.Machine(64, 256_000, 40), 512, 64, 64, 16);
        assertEquals(Map.of(PipelineStage.TEXT, 11, PipelineStage.REDACT, 5, PipelineStage.FIND, 6,
                PipelineStage.VISION, 4, PipelineStage.EXPORT, 4), fewConnections, "30 in all");
        assertEquals(Map.of(PipelineStage.TEXT, 7, PipelineStage.REDACT, 4, PipelineStage.FIND, 8,
                PipelineStage.VISION, 6, PipelineStage.EXPORT, 5),
                WorkerLimits.measured(new WorkerLimits.Machine(8, 32_000, 40), 512, 64, 64, 16));
    }

    @Test
    void upAtOnceAndDownGradually() {
        assertEquals(7, WorkerLimits.next(1, 400, 1, 1, 7));
        assertEquals(3, WorkerLimits.next(1, 2, 1, 1, 7));
        assertEquals(4, WorkerLimits.next(7, 0, 0, 1, 7));
        assertEquals(3, WorkerLimits.next(4, 0, 0, 1, 7));
        assertEquals(2, WorkerLimits.next(3, 0, 0, 1, 7));
        assertEquals(1, WorkerLimits.next(2, 0, 0, 1, 7));
        assertEquals(1, WorkerLimits.next(1, 0, 0, 1, 7));
        assertEquals(2, WorkerLimits.next(7, 400, 7, 1, 2), "a lower highest holds at once");
        assertEquals(1, WorkerLimits.next(1, Integer.MAX_VALUE, 64, 1, 1));
    }

    @Test
    void workersRiseToTheHighestAndDropBackWithoutStoppingAJob() throws Exception {
        AtomicInteger jobs = new AtomicInteger(200);
        AtomicInteger at = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        AtomicInteger interrupted = new AtomicInteger();
        ElasticWorkers workers = new ElasticWorkers("test", "node/test", "x", 5, (owner, started) -> {
            if (jobs.getAndUpdate(n -> Math.max(0, n - 1)) == 0) return false;
            started.run();
            most.accumulateAndGet(at.incrementAndGet(), Math::max);
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
            }
            at.decrementAndGet();
            return true;
        });
        workers.set(5);
        waitFor(() -> most.get() == 5);
        workers.set(2);
        waitFor(() -> workers.busy() <= 2);
        most.set(0);
        waitFor(() -> jobs.get() == 0 && workers.busy() == 0);
        assertTrue(most.get() <= 2, "no more than two after the number came down: " + most.get());
        assertEquals(0, interrupted.get());
        workers.stop();
    }

    @Test
    void aFailingTurnDoesNotEndTheWorkerAndStopEndsAll() throws Exception {
        AtomicInteger turns = new AtomicInteger();
        CountDownLatch blocked = new CountDownLatch(1);
        AtomicInteger interrupted = new AtomicInteger();
        ElasticWorkers workers = new ElasticWorkers("test", "node/test", "x", 1, (owner, started) -> {
            if (turns.incrementAndGet() < 5) return false;
            started.run();
            blocked.countDown();
            try {
                Thread.sleep(60_000);
            } catch (InterruptedException e) {
                interrupted.incrementAndGet();
                Thread.currentThread().interrupt();
            }
            return true;
        });
        workers.set(1);
        blocked.await();
        assertEquals(1, workers.busy());
        workers.stop();
        waitFor(() -> interrupted.get() == 1 && workers.busy() == 0);
        workers.set(3);
        Thread.sleep(50);
        assertEquals(5, turns.get());
    }

    @Test
    void theNumberFollowsTheQueueAndItsOwnMaximumWins() {
        ProcessingCapacity capacity = capacity(new MockEnvironment()
                .withProperty("securedoc.processing.stage-jobs.text-workers", "3"), PipelineMode.STAGED);
        ElasticWorkers text = idle();
        capacity.drive(PipelineStage.TEXT, text);
        assertEquals(1, text.number());
        due.put(PipelineStage.TEXT, 100);
        capacity.tick();
        assertEquals(3, text.number(), "this server's setting");
        assertEquals(103, capacity.queueLength(), "jobs and cases not started");
        capacity.setMaximums(Map.of("TEXT", 6));
        capacity.tick();
        assertEquals(6, text.number(), "Admin's maximum holds for every server");
        databaseDown = true;
        due.clear();
        capacity.tick();
        assertEquals(6, text.number(), "stays as it is while the queue cannot be read");
        databaseDown = false;
        for (int i = 0; i < 5; i++) capacity.tick();
        assertEquals(1, text.number());
        Map<String, Integer> none = new HashMap<>();
        none.put("TEXT", null);
        capacity.setMaximums(none);
        assertEquals(3, capacity.highest(PipelineStage.TEXT));
        text.stop();
    }

    @Test
    void findAndVisionGetWhatTheAiServerTakesAndOneThatWaits() {
        learned.put("http://localhost:11434", 4);
        ProcessingCapacity capacity = capacity(new MockEnvironment()
                .withProperty("securedoc.ai.requests-at-once", "8"), PipelineMode.STAGED);
        ElasticWorkers find = idle();
        ElasticWorkers vision = idle();
        capacity.drive(PipelineStage.FIND, find);
        capacity.drive(PipelineStage.VISION, vision);
        capacity.setMaximums(Map.of("FIND", 10, "VISION", 10));
        due.put(PipelineStage.FIND, 100);
        due.put(PipelineStage.VISION, 100);
        assertEquals(4, AiServerRequestLimit.limitOf(URI.create("http://localhost:11434/api/chat")), "as learned before");
        capacity.tick();
        assertEquals(5, find.number());
        assertEquals(5, vision.number());
        capacity.setMaximums(Map.of("AI", 2));
        capacity.tick();
        assertEquals(2, AiServerRequestLimit.states().get(0).limit(), "IT's own maximum");
        assertEquals(2, find.number());
        find.stop();
        vision.stop();
    }

    @Test
    void aNumberOutsideTheBoundsStopsTheStart() {
        assertThrows(IllegalStateException.class, () -> capacity(new MockEnvironment()
                .withProperty("securedoc.ai.requests-at-once", "0"), PipelineMode.STAGED));
        assertThrows(IllegalStateException.class, () -> capacity(new MockEnvironment()
                .withProperty("securedoc.processing.stage-jobs.redact-workers", "many"), PipelineMode.STAGED));
    }

    private ProcessingCapacity capacity(MockEnvironment environment, PipelineMode mode) {
        return new ProcessingCapacity(repository, mock(DataSource.class), StagedProcessingSettings.defaults(mode),
                new AiRuntimeProperties(), environment, "http://localhost:11434", 5000, 512, 120_000, 3_600_000);
    }

    private static ElasticWorkers idle() {
        return new ElasticWorkers("idle", "node/idle", "x", 60_000, (owner, started) -> false);
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < until, "waited too long");
            Thread.sleep(2);
        }
    }
}

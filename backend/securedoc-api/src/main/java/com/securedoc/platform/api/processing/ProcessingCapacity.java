package com.securedoc.platform.api.processing;

import com.securedoc.platform.ai.analysis.AiServerRequestLimit;
import com.securedoc.platform.api.config.AiRuntimeProperties;
import com.securedoc.platform.api.config.OllamaInferencePoolConfiguration;
import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.pipeline.StagedProcessingSettings;
import com.securedoc.platform.application.port.out.CapacityRepository;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Task 0067: how many workers each stage has on this server, set every few seconds from the stage's queue between its
 * lowest (1) and its highest. The highest is measured when the app starts, unless IT set one: in Admin (for every server)
 * or else in this server's settings. The requests at once per AI server are learned; IT's own maximum holds for them too.
 */
@Component
public class ProcessingCapacity {
    private static final Logger log = LoggerFactory.getLogger(ProcessingCapacity.class);
    public static final String AI = "AI";
    public static final int LOWEST = 1;
    public static final int MOST = 64;
    /** Counted up to this many per stage: more waiting work makes no difference. */
    private static final int MOST_COUNTED = 10_000;

    public record StageState(PipelineStage stage, int current, int busy, int lowest, int highest, int measured,
                             Integer maximum, int waiting) { }

    private final CapacityRepository repository;
    private final long everyMs;
    private final Map<PipelineStage, Integer> measured;
    /** This server's own settings. */
    private final Map<String, Integer> settings = new HashMap<>();
    private final WorkerLimits.Machine machine;
    private final boolean staged;
    private final int aiServers;
    /** The most the inference pool sends at once; 0 without a pool. */
    private final int poolPlaces;
    private final URI visionServer;
    private final Map<PipelineStage, ElasticWorkers> pools = new ConcurrentHashMap<>();
    private volatile Map<String, Integer> maximums = Map.of();
    private volatile Map<PipelineStage, Integer> due = Map.of();
    private volatile int waitingCases;
    private ScheduledExecutorService timer;

    public ProcessingCapacity(CapacityRepository repository, DataSource dataSource, StagedProcessingSettings staged,
                              AiRuntimeProperties ai, Environment environment,
                              @Value("${securedoc.ai.vision.base-url:http://localhost:11434}") String visionBaseUrl,
                              @Value("${securedoc.processing.capacity.scale-every-ms:5000}") long everyMs,
                              @Value("${securedoc.processing.capacity.memory-per-worker-mb:512}") int memoryPerWorkerMb,
                              @Value("${securedoc.ai.learn.up-every-ms:120000}") long aiUpEveryMs,
                              @Value("${securedoc.ai.learn.recheck-ms:3600000}") long aiRecheckMs) {
        this.repository = repository;
        this.everyMs = Math.max(500, everyMs);
        this.staged = staged.staged();
        this.visionServer = URI.create(visionBaseUrl.trim());
        setting(environment, PipelineStage.TEXT.name(), "securedoc.processing.stage-jobs.text-workers");
        setting(environment, PipelineStage.FIND.name(), "securedoc.processing.stage-jobs.find-workers");
        setting(environment, PipelineStage.VISION.name(), "securedoc.processing.stage-jobs.vision-workers");
        setting(environment, PipelineStage.REDACT.name(), "securedoc.processing.stage-jobs.redact-workers");
        setting(environment, PipelineStage.EXPORT.name(), "securedoc.processing.export-jobs.workers-total");
        setting(environment, AI, "securedoc.ai.requests-at-once");
        int perNode = Math.max(1, ai.getPoolMaxConcurrentPerNode());
        aiServers = ai.isPoolEnabled() ? OllamaInferencePoolConfiguration.parse(ai.getPoolNodeUrls()).size() : 1;
        poolPlaces = ai.isPoolEnabled() ? aiServers * perNode : 0;
        machine = new WorkerLimits.Machine(Runtime.getRuntime().availableProcessors(), freeMemoryMb(),
                connections(dataSource));
        int aiMost = settings.getOrDefault(AI, MOST);
        measured = WorkerLimits.measured(machine, memoryPerWorkerMb,
                poolPlaces > 0 ? Math.min(poolPlaces, aiServers * aiMost) : aiMost, aiMost,
                environment.getProperty("securedoc.processing.export-jobs.workers-per-destination", Integer.class, 4) * 4);
        if (this.staged) {
            AiServerRequestLimit.learn(new AiServerRequestLimit.Learning(aiMost, Duration.ofMillis(aiUpEveryMs),
                    Duration.ofMillis(aiRecheckMs)), new AiServerRequestLimit.Store() {
                @Override
                public void saved(String server, int limit) {
                    log.info("AI_SERVER_LIMIT_LEARNED requestsAtOnce={}", limit);
                    repository.saveLearnedLimit(server, limit);
                }

                @Override
                public OptionalInt start(String server) {
                    try {
                        return repository.learnedLimit(server);
                    } catch (RuntimeException notRemembered) {
                        return OptionalInt.empty();
                    }
                }
            });
        } else {
            AiServerRequestLimit.set(0);
        }
    }

    @PostConstruct
    public void start() {
        log.info("PROCESSING_CAPACITY_MEASURED cores={} freeMemoryMb={} databaseConnections={} text={} find={} vision={}"
                        + " redact={} export={}", machine.cores(), machine.freeMemoryMb(), machine.databaseConnections(),
                measured.get(PipelineStage.TEXT), measured.get(PipelineStage.FIND), measured.get(PipelineStage.VISION),
                measured.get(PipelineStage.REDACT), measured.get(PipelineStage.EXPORT));
        timer = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "processing-capacity");
            thread.setDaemon(true);
            return thread;
        });
        timer.scheduleWithFixedDelay(this::tick, 0, everyMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void stop() {
        if (timer != null) timer.shutdownNow();
    }

    /** These workers are set from the stage's queue from now on. EXPORT stands for both export stages. */
    void drive(PipelineStage stage, ElasticWorkers workers) {
        workers.set(LOWEST);
        pools.put(stage, workers);
    }

    void tick() {
        try {
            maximums = repository.maximums();
            due = repository.dueJobs(MOST_COUNTED);
            waitingCases = repository.waitingCases(MOST_COUNTED);
        } catch (RuntimeException failure) {
            // The numbers stay as they are.
            log.warn("PROCESSING_CAPACITY_CHECK_FAILED type={} code={}", failure.getClass().getSimpleName(),
                    LvErrorCodes.forPlatformFailure(failure).code());
            return;
        }
        if (staged) AiServerRequestLimit.maximum(aiMaximum());
        pools.forEach((stage, workers) -> {
            int before = workers.number();
            int now = WorkerLimits.next(before, waiting(stage), workers.busy(), LOWEST, Math.min(highest(stage), places(stage)));
            if (now == before) return;
            workers.set(now);
            log.info("PROCESSING_WORKERS_CHANGED stage={} from={} to={} highest={} waiting={}", stage, before, now,
                    highest(stage), waiting(stage));
        });
    }

    /** IT's own number (Admin, else this server's settings), else the measured one. */
    public int highest(PipelineStage stage) {
        Integer own = maximums.getOrDefault(stage.name(), settings.get(stage.name()));
        return own == null ? measured.get(stage) : own;
    }

    public int aiMaximum() {
        return maximums.getOrDefault(AI, settings.getOrDefault(AI, MOST));
    }

    /** Null when IT set none for the installation. */
    public Integer aiMaximumSet() {
        return maximums.get(AI);
    }

    /** The jobs of every stage that wait, and the cases not yet started (each counted up to a bound). */
    public int queueLength() {
        return due.entrySet().stream().filter(e -> !e.getKey().isExport()).mapToInt(Map.Entry::getValue).sum()
                + waitingCases;
    }

    public boolean staged() {
        return staged;
    }

    public List<StageState> stages() {
        List<StageState> out = new ArrayList<>();
        for (PipelineStage stage : WorkerLimits.STAGES) {
            ElasticWorkers workers = pools.get(stage);
            out.add(new StageState(stage, workers == null ? 0 : workers.number(), workers == null ? 0 : workers.busy(),
                    LOWEST, highest(stage), measured.get(stage), maximums.get(stage.name()), waiting(stage)));
        }
        return out;
    }

    /** IT's own maximums for the installation; a null value removes one. Takes effect here at once. */
    public void setMaximums(Map<String, Integer> changes) {
        changes.forEach(repository::setMaximum);
        maximums = repository.maximums();
    }

    private int waiting(PipelineStage stage) {
        return due.getOrDefault(stage, 0)
                + (stage == PipelineStage.EXPORT ? due.getOrDefault(PipelineStage.SPLIT_EXPORT, 0) : 0);
    }

    /** Find and Vision: what the AI servers take now, and one waiting per server (else one more is never tried). */
    private int places(PipelineStage stage) {
        if (!staged) return MOST;
        if (stage == PipelineStage.VISION) return Math.min(aiMaximum(), AiServerRequestLimit.limitOf(visionServer) + 1);
        if (stage != PipelineStage.FIND) return MOST;
        var servers = AiServerRequestLimit.states();
        int places = servers.stream().mapToInt(s -> Math.min(s.max(), s.limit() + 1)).sum()
                + 2 * Math.max(0, aiServers - servers.size());
        return poolPlaces > 0 ? Math.min(places, poolPlaces) : places;
    }

    private void setting(Environment environment, String name, String key) {
        String value = environment.getProperty(key, "").trim();
        if (value.isEmpty()) return;
        int number;
        try {
            number = Integer.parseInt(value);
        } catch (NumberFormatException notANumber) {
            number = 0;
        }
        if (number < 1 || number > MOST) throw new IllegalStateException(key + " must be between 1 and " + MOST);
        settings.put(name, number);
    }

    private static int connections(DataSource dataSource) {
        try {
            if (dataSource.isWrapperFor(HikariDataSource.class)) {
                return dataSource.unwrap(HikariDataSource.class).getMaximumPoolSize();
            }
        } catch (java.sql.SQLException notKnown) {
            // As below.
        }
        return 10;
    }

    private static long freeMemoryMb() {
        return ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os
                ? os.getFreeMemorySize() / (1024 * 1024) : 0;
    }
}

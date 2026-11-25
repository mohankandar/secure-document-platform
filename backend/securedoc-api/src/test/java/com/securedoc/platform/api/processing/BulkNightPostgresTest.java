package com.securedoc.platform.api.processing;

import com.securedoc.platform.api.config.AiRuntimeProperties;
import com.securedoc.platform.application.intake.BulkSourceService;
import com.securedoc.platform.application.pipeline.PipelineMode;
import com.securedoc.platform.application.pipeline.StagedProcessingSettings;
import com.securedoc.platform.application.port.out.BulkSourceRepository.BulkSource;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.BulkWindow;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelinePriority;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import com.securedoc.platform.infrastructure.pipeline.JdbcBulkSourceRepository;
import com.securedoc.platform.infrastructure.pipeline.JdbcCapacityRepository;
import com.securedoc.platform.infrastructure.pipeline.JdbcCasePriorityRepository;
import com.securedoc.platform.infrastructure.pipeline.JdbcPipelineJobRepository;
import com.zaxxer.hikari.HikariDataSource;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0079: a migration night with a simulated clock, the real queue (a throwaway PostgreSQL) and the real workers'
 * numbers; only the jobs themselves are short waits.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class BulkNightPostgresTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    private static final int JOB_MS = 60;
    private static final int WORKERS = 6;

    private String name;
    private HikariDataSource source;
    private NamedParameterJdbcTemplate jdbc;
    private JdbcPipelineJobRepository jobs;
    private JdbcCasePriorityRepository priorities;
    private JdbcBulkSourceRepository bulkSources;
    private BulkSourceService bulk;
    private ProcessingCapacity capacity;
    private ElasticWorkers text;
    private TenantId tenant;
    private volatile Instant now;

    private final AtomicInteger bulkRunning = new AtomicInteger();
    private final AtomicInteger mostBulk = new AtomicInteger();
    private final Map<PackageId, Long> finishedAt = new ConcurrentHashMap<>();
    private final AtomicInteger bulkDone = new AtomicInteger();

    @BeforeEach
    void start() throws Exception {
        name = "lv_t_bulk_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        onServer("CREATE DATABASE " + name);
        source = new HikariDataSource();
        source.setJdbcUrl(System.getenv("LEXAVAULT_TEST_PG_URL").replaceFirst("/[^/?]+(\\?|$)", "/" + name + "$1"));
        source.setUsername(System.getenv("LEXAVAULT_TEST_PG_USER"));
        source.setPassword(System.getenv("LEXAVAULT_TEST_PG_PASSWORD"));
        source.setMaximumPoolSize(20);
        try (Connection connection = source.getConnection()) {
            new Liquibase("db/changelog/db.changelog-master.xml", new ClassLoaderResourceAccessor(), DatabaseFactory
                    .getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection))).update(new Contexts());
        }
        jdbc = new NamedParameterJdbcTemplate(source);
        jobs = new JdbcPipelineJobRepository(jdbc);
        priorities = new JdbcCasePriorityRepository(jdbc);
        bulkSources = new JdbcBulkSourceRepository(jdbc);
        tenant = TenantId.generate();
        jdbc.update("INSERT INTO tenants (id, name, slug, status, created_at, updated_at) VALUES (:id, 't', :slug, 'ACTIVE',"
                + " now(), now())", new MapSqlParameterSource("id", tenant.value()).addValue("slug", tenant.toString()));
        at("2026-11-24T20:00"); // a Tuesday evening, window open
        Clock clock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return now;
            }
        };
        bulk = new BulkSourceService(bulkSources, entity -> Optional.of(NEW_YORK), new AuditService(r -> { }), clock);
        bulkSources.save(tenant, "archive", BulkWindow.DEFAULT);
        capacity = new ProcessingCapacity(new JdbcCapacityRepository(jdbc), source,
                StagedProcessingSettings.defaults(PipelineMode.INLINE), new AiRuntimeProperties(), new MockEnvironment()
                .withProperty("securedoc.processing.stage-jobs.text-workers", "" + WORKERS)
                .withProperty("securedoc.processing.stage-jobs.find-workers", "2"),
                "http://localhost:1", 5000, 512, 120_000, 3_600_000);
        capacity.setBulkSources(bulk);
        text = new ElasticWorkers("text", "node/text", "x", 5, this::turn);
    }

    @AfterEach
    void stop() throws Exception {
        if (text != null) text.stop();
        if (source != null) source.close();
        if (name != null) onServer("DROP DATABASE IF EXISTS " + name + " WITH (FORCE)");
    }

    private static void onServer(String sql) throws Exception {
        try (Connection admin = DriverManager.getConnection(System.getenv("LEXAVAULT_TEST_PG_URL"),
                System.getenv("LEXAVAULT_TEST_PG_USER"), System.getenv("LEXAVAULT_TEST_PG_PASSWORD"));
             var statement = admin.createStatement()) {
            statement.execute(sql);
        }
    }

    /** As StageJobWorkers: asks for a Bulk place, claims, runs, gives it back. */
    private boolean turn(String owner, Runnable started) throws InterruptedException {
        Set<String> places = capacity.bulkTurn(PipelineStage.TEXT);
        try {
            Optional<PipelineJob> job = jobs.claimNext(Set.of(PipelineStage.TEXT), owner, Duration.ofMinutes(5), 1, places);
            if (job.isEmpty()) return false;
            boolean isBulk = job.get().priority() == PipelinePriority.BULK;
            if (!isBulk) {
                capacity.bulkDone(PipelineStage.TEXT, places);
                places = Set.of();
            }
            started.run();
            if (isBulk) capacity.bulkStarted(PipelineStage.TEXT);
            if (isBulk) mostBulk.accumulateAndGet(bulkRunning.incrementAndGet(), Math::max);
            try {
                Thread.sleep(JOB_MS);
            } finally {
                if (isBulk) {
                    capacity.bulkEnded(PipelineStage.TEXT);
                    bulkRunning.decrementAndGet();
                    bulkDone.incrementAndGet();
                }
                finishedAt.put(job.get().packageId(), System.nanoTime());
                jdbc.update("UPDATE pipeline_jobs SET status = 'DONE', claim_owner = NULL, claim_until = NULL WHERE id = :id",
                        new MapSqlParameterSource("id", job.get().id()));
            }
            return true;
        } finally {
            capacity.bulkDone(PipelineStage.TEXT, places);
        }
    }

    @Test
    void aMigrationNight() throws Exception {
        List<PackageId> migration = new ArrayList<>();
        for (int i = 0; i < 400; i++) migration.add(queue(PipelinePriority.BULK));
        capacity.drive(PipelineStage.TEXT, text);
        capacity.tick();
        assertEquals(WORKERS / 2, text.number(), "for Bulk alone: half of the workers");
        waitFor(() -> bulkDone.get() >= 20);

        // 2 AM: a live case of 6 documents. All 6 workers may take it; Bulk never had more than 3.
        at("2026-11-25T02:00");
        capacity.tick();
        long sent = System.nanoTime();
        List<PackageId> live = new ArrayList<>();
        for (int i = 0; i < 6; i++) live.add(queue(PipelinePriority.NORMAL));
        capacity.tick();
        waitFor(() -> finishedAt.keySet().containsAll(live));
        long took = live.stream().mapToLong(finishedAt::get).max().orElseThrow() - sent;
        // Alone it takes one to two turns (6 documents, 6 workers, up to 3 of them busy with one Bulk job each).
        assertTrue(took < 20L * JOB_MS * 1_000_000L, "a live case at 2 AM is not stuck behind the migration: " + took / 1_000_000
                + " ms");
        assertTrue(bulkDone.get() < 300, "the migration was still going on");
        capacity.tick();
        assertEquals(0, capacity.queueLength(), "a migration does not slow live intake down");

        // A reviewer makes one migration case Urgent: it is next.
        PackageId wanted = migration.get(399);
        new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource
                .DataSourceTransactionManager(source)).executeWithoutResult(
                s -> priorities.change(tenant, wanted, PipelinePriority.URGENT));
        waitFor(() -> finishedAt.containsKey(wanted));

        // 6 AM: nothing new of Bulk starts; what runs finishes; live work goes on.
        at("2026-11-25T06:00");
        capacity.tick();
        waitFor(() -> bulkRunning.get() == 0);
        int doneAtSix = bulkDone.get();
        PackageId morning = queue(PipelinePriority.LOW);
        capacity.tick();
        waitFor(() -> finishedAt.containsKey(morning));
        Thread.sleep(600);
        assertEquals(doneAtSix, bulkDone.get(), "Bulk stopped at 6 AM");
        assertTrue(doneAtSix < 400);
        for (int i = 0; i < 6; i++) capacity.tick();
        assertEquals(1, text.number(), "a waiting migration keeps no workers outside its window");
        assertFalse(bulk.mayTakeIn(tenant, "archive"), "its files wait at the source");

        // Saturday noon: on again.
        at("2026-11-28T12:00");
        capacity.tick();
        waitFor(() -> bulkDone.get() > doneAtSix);
        assertTrue(mostBulk.get() <= WORKERS / 2, "never more than half: " + mostBulk.get());
        assertEquals(WORKERS / 2, mostBulk.get());
    }

    @Test
    void neverTheLastAiWorkerAndNoBulkWithOneWorker() {
        assertEquals(1, capacity.bulkPlaces(PipelineStage.FIND), "two AI workers: one");
        capacity.setMaximums(Map.of("FIND", 1));
        assertEquals(0, capacity.bulkPlaces(PipelineStage.FIND), "the last AI worker is never Bulk's");
        capacity.setMaximums(Map.of("FIND", 5));
        assertEquals(2, capacity.bulkPlaces(PipelineStage.FIND));
    }

    @Test
    void progressAndUnmarking() {
        PackageId done = queue(PipelinePriority.BULK);
        PackageId failed = queue(PipelinePriority.BULK);
        PackageId waiting = queue(PipelinePriority.BULK);
        status(done, "COMPLETED");
        status(failed, "FAILED");
        var counts = bulkSources.counts(tenant, "archive");
        assertEquals(List.of(1L, 1L, 1L), List.of(counts.done(), counts.failed(), counts.waiting()));
        assertEquals(1, bulkSources.doneSince(tenant, "archive", Instant.now().minusSeconds(3600)));
        assertEquals(Set.of(BulkSource.reference(tenant, "archive")), bulk.openSources());
        at("2026-11-25T10:00");
        assertEquals(Set.of(), bulk.openSources());
        assertTrue(jobs.claimNext(Set.of(PipelineStage.TEXT), "w", Duration.ofMinutes(1), 1, Set.of()).isEmpty());
        bulk.unmark(tenant, com.securedoc.platform.domain.identity.ActorId.generate(), "archive",
                com.securedoc.platform.domain.identity.CorrelationId.generate());
        assertEquals(PipelinePriority.LOW, priorities.find(tenant, List.of(waiting)).get(waiting));
        assertEquals(PipelinePriority.LOW, jobs.claimNext(Set.of(PipelineStage.TEXT), "w", Duration.ofMinutes(1), 1, Set.of())
                .orElseThrow().priority(), "not left waiting for a window that is gone");
    }

    private void at(String newYorkTime) {
        now = ZonedDateTime.parse(newYorkTime + ":00-05:00").toInstant();
    }

    private PackageId queue(PipelinePriority priority) {
        PackageId pkg = PackageId.generate();
        jdbc.update("INSERT INTO document_packages (id, tenant_id, package_type, status, created_by, created_at, updated_at)"
                + " VALUES (:id, :t, 'API_SUBMISSION', 'PROCESSING', :id, now(), now())",
                new MapSqlParameterSource("id", pkg.value()).addValue("t", tenant.value()));
        priorities.saveNew(tenant, pkg, priority, "archive");
        assertTrue(jobs.enqueue(PipelineJob.ready(tenant, PipelineStage.TEXT, pkg, DocumentId.generate(), null, null, 1,
                Instant.now())));
        return pkg;
    }

    private void status(PackageId pkg, String status) {
        jdbc.update("UPDATE document_packages SET status = :s, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("s", status).addValue("id", pkg.value()));
    }

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long until = System.nanoTime() + 30_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < until, "waited too long");
            Thread.sleep(5);
        }
    }
}

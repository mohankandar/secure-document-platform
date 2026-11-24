package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0067: the capacity SQL and migration 081 against a real, throwaway PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class JdbcCapacityRepositoryPostgresTest {
    private static PostgresTestDatabase db;
    private static NamedParameterJdbcTemplate jdbc;
    private static JdbcCapacityRepository capacity;

    @BeforeAll
    static void migrate() throws Exception {
        db = PostgresTestDatabase.migrated("CapacityRepository");
        jdbc = db.jdbc();
        capacity = new JdbcCapacityRepository(jdbc);
    }

    @AfterAll
    static void dropDatabase() throws Exception {
        if (db != null) db.close();
    }

    @Test
    void learnedLimitsAndMaximumsAreKeptAndBounded() {
        assertTrue(capacity.learnedLimit("http://ai:1").isEmpty());
        capacity.saveLearnedLimit("http://ai:1", 5);
        capacity.saveLearnedLimit("http://ai:1", 4);
        assertEquals(4, capacity.learnedLimit("http://ai:1").getAsInt());
        capacity.setMaximum("TEXT", 3);
        capacity.setMaximum("AI", 9);
        capacity.setMaximum("TEXT", 6);
        assertEquals(Map.of("TEXT", 6, "AI", 9), capacity.maximums());
        capacity.setMaximum("TEXT", null);
        assertEquals(Map.of("AI", 9), capacity.maximums());
        assertThrows(DataIntegrityViolationException.class, () -> capacity.setMaximum("FIND", 0));
        assertThrows(DataIntegrityViolationException.class, () -> capacity.saveLearnedLimit("http://ai:1", 65));
    }

    @Test
    void dueJobsAreCountedPerStageUpToTheBound() {
        TenantId tenant = TenantId.generate();
        IntegrationConnectionId destination = IntegrationConnectionId.generate();
        TestRows.tenant(jdbc, tenant);
        TestRows.connection(jdbc, tenant, destination);
        JdbcPipelineJobRepository jobs = new JdbcPipelineJobRepository(jdbc);
        for (int i = 0; i < 5; i++) {
            PackageId pkg = PackageId.generate();
            TestRows.packageRow(jdbc, tenant, pkg);
            jobs.enqueue(PipelineJob.ready(tenant, PipelineStage.EXPORT, pkg, null, destination, null, 1, Instant.now()));
        }
        assertEquals(Map.of(PipelineStage.EXPORT, 5), capacity.dueJobs(100));
        assertEquals(Map.of(PipelineStage.EXPORT, 3), capacity.dueJobs(3));
        jobs.claimNext(EnumSet.of(PipelineStage.EXPORT), "w", Duration.ofSeconds(30), 4).orElseThrow();
        assertEquals(4, capacity.dueJobs(100).get(PipelineStage.EXPORT), "a running job does not wait");
        jdbc.getJdbcOperations().update("UPDATE pipeline_jobs SET status = 'WAITING_RETRY',"
                + " next_attempt_at = clock_timestamp() + interval '1 hour' WHERE status = 'READY'");
        assertEquals(Map.of(), capacity.dueJobs(100), "not due yet");
    }

    @Test
    void submittedCasesAndTheDatabaseSizeAreRead() {
        int before = capacity.waitingCases(1000);
        TenantId tenant = TenantId.generate();
        TestRows.tenant(jdbc, tenant);
        PackageId pkg = PackageId.generate();
        TestRows.packageRow(jdbc, tenant, pkg);
        jdbc.getJdbcOperations().update("UPDATE document_packages SET status = 'SUBMITTED' WHERE id = ?", pkg.value());
        assertEquals(before + 1, capacity.waitingCases(1000));
        assertEquals(1, capacity.waitingCases(1));
        assertTrue(capacity.databaseBytes() > 1_000_000);
    }
}

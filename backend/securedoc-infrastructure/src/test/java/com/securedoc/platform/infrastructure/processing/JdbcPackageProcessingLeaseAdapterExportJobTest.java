package com.securedoc.platform.infrastructure.processing;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0027: both processing-recovery queries leave alone a case that waits for its EXPORT job ("Ready for export"),
 * so a paused destination never makes recovery re-run or stop the case. Proven against PostgreSQL in
 * JdbcPipelineJobRepositoryPostgresTest; this keeps the condition in the normal build.
 */
class JdbcPackageProcessingLeaseAdapterExportJobTest {

    @Test
    void bothRecoveryQueriesSkipCasesWithAnOpenExportJob() throws Exception {
        for (String name : new String[] {"EXPIRED_SQL", "PROCESSING_WITHOUT_LEASE_SQL"}) {
            String sql = constant(name);
            assertTrue(sql.contains("NOT EXISTS (SELECT 1 FROM pipeline_jobs j"), name);
            assertTrue(sql.contains("j.stage = 'EXPORT'"), name);
            assertTrue(sql.contains("j.status NOT IN ('DONE', 'STOPPED')"), name);
            assertTrue(sql.indexOf("NOT EXISTS") < sql.indexOf("ORDER BY"), name + ": in the WHERE clause");
        }
    }

    private static String constant(String name) throws Exception {
        Field field = JdbcPackageProcessingLeaseAdapter.class.getDeclaredField(name);
        field.setAccessible(true);
        return (String) field.get(null);
    }
}

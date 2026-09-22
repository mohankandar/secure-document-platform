package com.securedoc.platform.infrastructure.processing;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JdbcPackageProcessingLeaseAdapterAttemptBudgetTest {

    @Test
    void continuationPreservesAttemptCountWhileRecoveryIncrementsAndFreshCycleCanReset() throws Exception {
        Field field = JdbcPackageProcessingLeaseAdapter.class.getDeclaredField("ACQUIRE_PROCESSING_ATTEMPT_SQL");
        field.setAccessible(true);
        String sql = (String) field.get(null);

        assertTrue(sql.contains("WHEN :recovery THEN package_processing_leases.attempt_count + 1"));
        assertTrue(sql.contains("WHEN :resetAttemptBudget THEN 1"));
        assertTrue(sql.contains("ELSE package_processing_leases.attempt_count"),
                "ordinary continuation must preserve the durable attempt count");
        assertFalse(sql.contains("WHEN :recovery THEN package_processing_leases.attempt_count + 1\n                    ELSE 1"),
                "the pre-FIX2 reset-after-review branch must not return");
    }
}

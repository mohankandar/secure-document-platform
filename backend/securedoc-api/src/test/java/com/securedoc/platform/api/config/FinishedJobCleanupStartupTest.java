package com.securedoc.platform.api.config;

import com.securedoc.platform.api.testsupport.LogCapture;
import com.securedoc.platform.application.pipeline.FinishedJobCleanupSettings;
import com.securedoc.platform.domain.error.LvErrorCode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationFailedEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0034: {@code securedoc.processing.export-jobs.keep-finished-days} is read by the real bean method, defaults to
 * 90, and a value outside 7 to 3650 stops start-up with LV-SYS-005 (the log names neither the value nor the message).
 */
class FinishedJobCleanupStartupTest {

    /** Only the settings bean is created (everything else in the configuration stays lazy and unused). */
    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(ctx -> ctx.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .withUserConfiguration(ApplicationServiceConfig.class);
    }

    @Test
    void theDefaultIs90DaysAnd1000RowsPerBatch() {
        runner().run(ctx -> {
            FinishedJobCleanupSettings s = ctx.getBean(FinishedJobCleanupSettings.class);
            assertEquals(90, s.keepFinishedDays());
            assertEquals(1000, s.batchSize());
        });
        runner().withPropertyValues("securedoc.processing.export-jobs.keep-finished-days=7",
                "securedoc.processing.export-jobs.cleanup-batch-size=250").run(ctx -> {
            FinishedJobCleanupSettings s = ctx.getBean(FinishedJobCleanupSettings.class);
            assertEquals(7, s.keepFinishedDays());
            assertEquals(250, s.batchSize());
        });
    }

    @Test
    void theShippedSettingFileHasTheSameDefaults() throws Exception {
        String yml = new ClassPathResource("application.yml").getContentAsString(StandardCharsets.UTF_8);
        assertTrue(yml.contains("keep-finished-days: ${SECUREDOC_EXPORT_JOBS_KEEP_FINISHED_DAYS:90}"));
        assertTrue(yml.contains("cleanup-batch-size: ${SECUREDOC_EXPORT_JOBS_CLEANUP_BATCH_SIZE:1000}"));
    }

    @Test
    void aValueOutsideItsRangeStopsStartUpWithLvSys005() {
        for (String bad : new String[]{"6", "3651", "0"}) {
            runner().withPropertyValues("securedoc.processing.export-jobs.keep-finished-days=" + bad).run(ctx -> {
                Throwable failure = assertThrows(BeanCreationException.class,
                        () -> ctx.getBean(FinishedJobCleanupSettings.class));
                Throwable root = failure;
                while (root.getCause() != null) root = root.getCause();
                assertInstanceOf(IllegalArgumentException.class, root);
                assertEquals("securedoc.processing.export-jobs.keep-finished-days must be between 7 and 3650 days",
                        root.getMessage());
                assertEquals(LvErrorCode.SYS_005, StartupFailureLogger.codeFor(failure));
                try (LogCapture logs = LogCapture.of(StartupFailureLogger.class)) {
                    new StartupFailureLogger().onApplicationEvent(new ApplicationFailedEvent(new SpringApplication(),
                            new String[0], null, failure));
                    String logged = logs.text();
                    assertTrue(logged.contains("LV_STARTUP_FAILED code=LV-SYS-005 type=IllegalArgumentException"),
                            logged);
                    assertFalse(logged.contains("keep-finished-days"), "the message is never logged: " + logged);
                }
            });
        }
        runner().withPropertyValues("securedoc.processing.export-jobs.cleanup-batch-size=0").run(ctx ->
                assertThrows(BeanCreationException.class, () -> ctx.getBean(FinishedJobCleanupSettings.class)));
    }
}

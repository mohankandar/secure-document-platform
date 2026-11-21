package com.securedoc.platform.api.platform;

import com.securedoc.platform.api.config.AiRuntimeProperties;
import com.securedoc.platform.api.testsupport.LogCapture;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.storage.ProcessingStorageConfigurationService;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.delivery.LocalDeliveryProperties;
import com.securedoc.platform.infrastructure.intake.WatchedFolderProperties;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import com.securedoc.platform.infrastructure.resilience.TechnicalResilienceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Task 0023 C7 (#336-#338): a failed health probe is logged with component, code and type, never its message. */
class PlatformHealthFailureLoggingTest {

    @Test
    void failedProbesAreLoggedByCodeAndType() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("password authentication failed for user lexa"));
        TesseractOcrProperties ocr = new TesseractOcrProperties();
        ocr.setExecutable("C:/definitely/missing/tesseract-0023.exe");
        ProcessingStorageConfigurationService storage = mock(ProcessingStorageConfigurationService.class);
        when(storage.overview()).thenThrow(new IllegalStateException("s3 secret key rejected"));
        WatchedFolderScheduler scheduler = mock(WatchedFolderScheduler.class);
        MockEnvironment environment = new MockEnvironment().withProperty("securedoc.delivery.type", "sdv");
        PlatformHealthService service = new PlatformHealthService(dataSource, new AiRuntimeProperties(), ocr, storage,
                new LocalDeliveryProperties(), mock(IntegrationConnectionRepository.class),
                mock(WatchedFolderProperties.class), new TechnicalResilienceProperties(), environment,
                mock(PackageRepository.class), mock(PackageLifecycleRepository.class), mock(AuditQueryPort.class),
                mock(ProcessingIncidentRepository.class), scheduler);

        try (LogCapture logs = LogCapture.of(PlatformHealthService.class)) {
            var overview = service.overview(TenantId.generate(), true);
            String logged = logs.text();
            // Task 0026: a failed check logs and shows LV-SYS-006; a check that did not fail shows no code.
            assertTrue(logged.contains("component=database code=CONNECTION_UNAVAILABLE type=SQLException lvCode=LV-SYS-006"), logged);
            var database = overview.providers().stream().filter(p -> p.key().equals("database")).findFirst().orElseThrow();
            assertEquals("UNHEALTHY", database.status());
            assertEquals("LV-SYS-006", database.lvCode());
            for (var item : overview.providers()) {
                assertEquals("UNHEALTHY".equals(item.status()) ? "LV-SYS-006" : null, item.lvCode(), item.key());
            }
            assertTrue(logged.contains("component=database code=CONNECTION_UNAVAILABLE type=SQLException"), logged);
            assertTrue(logged.contains("component=ocr code=EXECUTABLE_UNAVAILABLE type=IOException"), logged);
            assertTrue(logged.contains("component=storage code=CONFIGURATION_UNAVAILABLE type=IllegalStateException"), logged);
            assertFalse(logged.contains("password"));
            assertFalse(logged.contains("secret"));
        }
    }
}

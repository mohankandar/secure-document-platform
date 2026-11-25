package com.securedoc.platform.api.intake;

import com.securedoc.platform.api.processing.ProcessingCapacity;
import com.securedoc.platform.application.intake.IntakeGate;
import com.securedoc.platform.application.port.out.CapacityRepository;
import com.securedoc.platform.application.port.out.ProcessingStorageConfigurationRepository;
import com.securedoc.platform.domain.storage.ProcessingStorageAssignment;
import com.securedoc.platform.domain.storage.ProcessingStorageProvider;
import com.securedoc.platform.domain.storage.ProcessingStorageProviderStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.stream.Stream;

/** Task 0067: the gate every intake asks. */
@Configuration
public class IntakeGateConfig {

    @Bean
    IntakeGate intakeGate(ProcessingStorageConfigurationRepository storage, CapacityRepository capacity,
                          ProcessingCapacity processing, com.securedoc.platform.application.intake.BulkSourceService bulkSourceService,
                          @Value("${securedoc.intake.space.stop-below-percent:10}") int stopBelow,
                          @Value("${securedoc.intake.space.resume-above-percent:15}") int resumeAbove,
                          @Value("${securedoc.intake.space.retry-after-seconds:300}") int retryAfterSeconds,
                          @Value("${securedoc.intake.space.database-size-gb:0}") long databaseSizeGb,
                          @Value("${securedoc.intake.pacing.long-queue:200}") int longQueue) {
        return new IntakeSpaceGate(new IntakeSpaceGate.Settings(stopBelow, resumeAbove, retryAfterSeconds,
                databaseSizeGb * 1024 * 1024 * 1024, longQueue), () -> folders(storage), capacity::databaseBytes,
                processing::queueLength).withBulk(bulkSourceService);
    }

    /** The document storage in use now. */
    public static List<String> folders(ProcessingStorageConfigurationRepository storage) {
        return Stream.concat(storage.findDeploymentDefault().stream(), storage.findTenantOverrides().stream())
                .map(ProcessingStorageAssignment::providerId).distinct()
                .flatMap(id -> storage.findProvider(id).stream())
                .filter(p -> p.status() != ProcessingStorageProviderStatus.RETIRED)
                .map(ProcessingStorageProvider::rootPath).toList();
    }
}

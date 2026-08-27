package com.securedoc.platform.worker.processing;

import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Background worker that polls for submitted packages and coordinates their processing.
 *
 * <p>Disabled by default. When enabled via configuration, it polls for packages
 * in SUBMITTED status and delegates to the ProcessingWorkCoordinator.
 *
 * <p>Rules:
 * <ul>
 *   <li>Calls application services through use case ports — never bypasses the application layer</li>
 *   <li>Does not directly manipulate JPA entities</li>
 *   <li>No OCR/AI/redaction logic</li>
 *   <li>Uses PackageRepository only to discover submitted packages (read-only query)</li>
 * </ul>
 *
 * <p>In future units, this will be triggered by scheduled polling or event subscription.
 * For now it exposes a {@link #pollAndProcess()} method that can be called manually
 * or by a minimal scheduler.
 */
public class ProcessingWorker {

    private static final Logger log = LoggerFactory.getLogger(ProcessingWorker.class);

    private final PackageRepository packageRepository;
    private final ProcessingWorkCoordinator coordinator;
    private final ProcessingWorkerConfig config;

    public ProcessingWorker(PackageRepository packageRepository,
                            ProcessingWorkCoordinator coordinator,
                            ProcessingWorkerConfig config) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.config = Objects.requireNonNull(config);
    }

    /**
     * Polls for submitted packages and starts processing for each.
     * Returns the number of packages picked up in this cycle.
     */
    public int pollAndProcess() {
        if (!config.isEnabled()) {
            log.trace("Processing worker is disabled, skipping poll cycle");
            return 0;
        }

        log.debug("Polling for submitted packages (batchSize={})", config.getBatchSize());

        List<DocumentPackage> submittedPackages = packageRepository.findByStatus(
                PackageStatus.SUBMITTED, config.getBatchSize());

        if (submittedPackages.isEmpty()) {
            log.debug("No submitted packages found");
            return 0;
        }

        log.info("Found {} submitted package(s) to process", submittedPackages.size());

        int processed = 0;
        for (DocumentPackage pkg : submittedPackages) {
            try {
                TenantId tenantId = pkg.tenantId();
                coordinator.processPackage(pkg.id(), tenantId);
                processed++;
            } catch (Exception e) {
                log.error("Failed to process package={}. See audit/outbox for sanitized failure reason.", pkg.id());
                // Continue with other packages
            }
        }

        log.info("Poll cycle complete: {}/{} packages processed successfully",
                processed, submittedPackages.size());
        return processed;
    }
}

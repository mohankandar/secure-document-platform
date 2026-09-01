package com.securedoc.platform.api.processing;

import com.securedoc.platform.application.port.in.TriggerPackageProcessingUseCase;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.task.TaskExecutor;

import java.util.Objects;

/**
 * V1 in-process dispatcher used to resume package processing after the final review decision.
 * The review HTTP request returns once the work has been accepted by the bounded executor;
 * redaction, verification and delivery continue on a background thread.
 */
public final class InProcessPackageProcessingDispatcher implements PackageProcessingDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InProcessPackageProcessingDispatcher.class);

    private final TaskExecutor taskExecutor;
    private final TriggerPackageProcessingUseCase triggerPackageProcessingUseCase;

    public InProcessPackageProcessingDispatcher(TaskExecutor taskExecutor,
                                                TriggerPackageProcessingUseCase triggerPackageProcessingUseCase) {
        this.taskExecutor = Objects.requireNonNull(taskExecutor);
        this.triggerPackageProcessingUseCase = Objects.requireNonNull(triggerPackageProcessingUseCase);
    }

    @Override
    public void dispatch(TriggerPackageProcessingCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        taskExecutor.execute(() -> {
            log.info("REVIEW_PROCESSING_RESUME_STARTED package={} tenant={} correlation={}",
                    command.packageId(), command.tenantId(), command.correlationId());
            try {
                triggerPackageProcessingUseCase.execute(command);
                log.info("REVIEW_PROCESSING_RESUME_COMPLETED package={} tenant={} correlation={}",
                        command.packageId(), command.tenantId(), command.correlationId());
            } catch (RuntimeException ex) {
                // Do not expose document content or raw exception messages. The processing service
                // records authoritative package/document failure state where applicable.
                log.error("REVIEW_PROCESSING_RESUME_FAILED package={} tenant={} correlation={} errorType={}",
                        command.packageId(), command.tenantId(), command.correlationId(),
                        ex.getClass().getSimpleName());
            }
        });
        log.info("REVIEW_PROCESSING_RESUME_SCHEDULED package={} tenant={} correlation={}",
                command.packageId(), command.tenantId(), command.correlationId());
    }
}

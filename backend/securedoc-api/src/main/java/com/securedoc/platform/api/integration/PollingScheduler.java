package com.securedoc.platform.api.integration;

import com.securedoc.platform.application.intake.polling.PollingIntakeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name="securedoc.intake.polling.scheduler-enabled", havingValue="true", matchIfMissing=true)
public class PollingScheduler {
    private static final Logger log= LoggerFactory.getLogger(PollingScheduler.class);
    private final PollingIntakeService service;
    private final TaskExecutor executor;
    public PollingScheduler(PollingIntakeService service, @Qualifier("pollingTaskExecutor") TaskExecutor executor){this.service=service;this.executor=executor;}

    @Scheduled(fixedDelayString="${securedoc.intake.polling.scheduler-scan-interval-ms:60000}")
    public void runDue(){
        try {
            var due=service.dueSources();
            for (var source : due) {
                executor.execute(() -> {
                    try { service.runScheduled(source.tenantId(), source.connectionId()); }
                    catch (RuntimeException e) {
                        // Task 0023 #333: the type and a code only. A failure before the run started leaves no run record.
                        log.warn("POLLING_SOURCE_RUN_FAILED connectionId={} reasonCode=POLLING_RUN_ERROR exceptionType={}",
                                source.connectionId(), e.getClass().getSimpleName());
                    }
                });
            }
            if(!due.isEmpty()) log.info("Polling scheduler dispatched {} due source(s)", due.size());
        } catch(RuntimeException e){
            // Task 0023 #334.
            log.warn("POLLING_DISCOVERY_FAILED reasonCode=POLLING_DISCOVERY_ERROR exceptionType={}; sources will be reconsidered on the next cycle",
                    e.getClass().getSimpleName());
        }
    }
}

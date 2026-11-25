package com.securedoc.platform.api.protection;

import com.securedoc.platform.application.port.out.WorkGate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;

/**
 * Task 0060: no scheduled task runs while no work may be taken, also one added later, except the few that take no work:
 * this server's own signs of life and the messages that tell IT about it. A skipped run is only skipped: the task keeps its
 * schedule.
 */
@Configuration
public class ScheduledWorkGate {
    private static final Logger log = LoggerFactory.getLogger(ScheduledWorkGate.class);

    /** Class and method, as Spring names a scheduled task. */
    static final Set<String> ALWAYS = Set.of(
            "com.securedoc.platform.api.storage.ProcessingStorageNodeMonitor.heartbeatAndValidate",
            "com.securedoc.platform.api.processing.DatabasePackageProcessingDispatchLease.heartbeat",
            // Tells IT about the stop itself, and nothing else while it lasts.
            "com.securedoc.platform.api.notifications.OperationalNotices.check");

    /** In place of Spring Boot's own scheduler, with its settings. */
    @Bean(name = "taskScheduler")
    ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder, WorkGate gate) {
        return builder.configure(new Gated(gate));
    }

    static Runnable gated(Runnable task, WorkGate gate) {
        String name = task.toString();
        if (ALWAYS.contains(name)) return task;
        return () -> {
            if (gate.mayTakeWork()) {
                task.run();
            } else {
                log.debug("SCHEDULED_TASK_SKIPPED_NO_WORK task={}", name);
            }
        };
    }

    static final class Gated extends ThreadPoolTaskScheduler {
        private final transient WorkGate gate;

        Gated(WorkGate gate) {
            this.gate = gate;
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Trigger trigger) {
            return super.schedule(gated(task, gate), trigger);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Instant startTime) {
            return super.schedule(gated(task, gate), startTime);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Instant startTime, Duration period) {
            return super.scheduleAtFixedRate(gated(task, gate), startTime, period);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable task, Duration period) {
            return super.scheduleAtFixedRate(gated(task, gate), period);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Instant startTime, Duration delay) {
            return super.scheduleWithFixedDelay(gated(task, gate), startTime, delay);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, Duration delay) {
            return super.scheduleWithFixedDelay(gated(task, gate), delay);
        }
    }
}

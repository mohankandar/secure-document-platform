package com.securedoc.platform.api.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduled jobs (retention purge, polling, recovery, monitors) run in the server only. Command mode
 * ({@code --lexavault.command}) sets {@code lexavault.command-mode=true}, so no job runs during a command.
 * (Moved here from {@code SecureDocumentPlatformApplication}; the server behaves as before.)
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "lexavault.command-mode", havingValue = "false", matchIfMissing = true)
public class SchedulingConfiguration {
}

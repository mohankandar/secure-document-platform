package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;

/**
 * Inbound port for manually triggering processing on a submitted package.
 * Intended for local/demo use when the worker is disabled.
 */
public interface TriggerPackageProcessingUseCase {
    void execute(TriggerPackageProcessingCommand command);
}

package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.processing.command.StartPackageProcessingCommand;
import com.securedoc.platform.application.processing.result.StartPackageProcessingResult;

/**
 * Inbound port for starting processing on a submitted package.
 * Transitions the package to PROCESSING and identifies documents ready for work.
 */
public interface StartPackageProcessingUseCase {

    StartPackageProcessingResult execute(StartPackageProcessingCommand command);
}

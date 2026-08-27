package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.processing.command.StartDocumentProcessingCommand;
import com.securedoc.platform.application.processing.result.StartDocumentProcessingResult;

/**
 * Inbound port for starting extraction processing on a single document.
 * Acquires a processing lock, transitions the document to EXTRACTING,
 * and publishes the appropriate domain event.
 *
 * @return result containing lock key/token for later release
 */
public interface StartDocumentProcessingUseCase {

    StartDocumentProcessingResult execute(StartDocumentProcessingCommand command);
}

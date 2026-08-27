package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.processing.command.FailDocumentProcessingCommand;

/**
 * Inbound port for marking a document's processing as failed.
 * Transitions the document to FAILED and publishes failure event.
 * May also fail the parent package if appropriate.
 */
public interface FailDocumentProcessingUseCase {

    void execute(FailDocumentProcessingCommand command);
}

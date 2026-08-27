package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.extraction.command.ExtractDocumentTextCommand;
import com.securedoc.platform.application.extraction.result.ExtractDocumentTextResult;

/**
 * Inbound port for performing text extraction on a document in EXTRACTING status.
 * Reads the original artifact, extracts text via a provider port, stores the
 * extracted text as a derived artifact, and completes the extraction lifecycle.
 */
public interface ExtractDocumentTextUseCase {

    ExtractDocumentTextResult execute(ExtractDocumentTextCommand command);
}

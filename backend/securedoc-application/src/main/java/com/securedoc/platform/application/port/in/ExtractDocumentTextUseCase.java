package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.extraction.command.ExtractDocumentTextCommand;
import com.securedoc.platform.application.extraction.result.ExtractDocumentTextResult;
import com.securedoc.platform.application.pipeline.PageBatch;
import com.securedoc.platform.application.pipeline.PageBatchCommand;

import java.util.List;

/**
 * Inbound port for performing text extraction on a document in EXTRACTING status.
 * Reads the original artifact, extracts text via a provider port, stores the
 * extracted text as a derived artifact, and completes the extraction lifecycle.
 */
public interface ExtractDocumentTextUseCase {

    ExtractDocumentTextResult execute(ExtractDocumentTextCommand command);

    /** Task 0049: reads one page batch of an UPLOADED document and keeps its text for {@link #completeFromBatches}. */
    default void extractBatch(PageBatchCommand command) {
        throw new UnsupportedOperationException("Page batches are not supported");
    }

    /**
     * Task 0049: completes the extraction of a document in EXTRACTING status from its read page batches, exactly as
     * {@link #execute} does from the whole document. Refused when a page is missing.
     */
    default ExtractDocumentTextResult completeFromBatches(ExtractDocumentTextCommand command,
                                                          List<PageBatch> batches) {
        throw new UnsupportedOperationException("Page batches are not supported");
    }
}

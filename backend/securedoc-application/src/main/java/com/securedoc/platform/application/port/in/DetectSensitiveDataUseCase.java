package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.detection.command.DetectSensitiveDataCommand;
import com.securedoc.platform.application.detection.result.DetectSensitiveDataResult;
import com.securedoc.platform.application.pipeline.PageBatch;
import com.securedoc.platform.application.pipeline.PageBatchCommand;

import java.util.List;

/**
 * Inbound port for running sensitive-data detection on a document.
 * Reads the EXTRACTED_TEXT artifact, runs deterministic detection rules,
 * creates Finding records, evaluates policy, and transitions the document
 * to its next state.
 */
public interface DetectSensitiveDataUseCase {

    DetectSensitiveDataResult execute(DetectSensitiveDataCommand command);

    /** Task 0049: searches one page batch of a DETECTING document and keeps the places for {@link #completeFromBatches}. */
    default void detectBatch(PageBatchCommand command) {
        throw new UnsupportedOperationException("Page batches are not supported");
    }

    /**
     * Task 0049: merges what the document's page batches found (pages two batches share give one finding per place)
     * and completes the detection exactly as {@link #execute} does for the whole document.
     */
    default DetectSensitiveDataResult completeFromBatches(DetectSensitiveDataCommand command,
                                                          List<PageBatch> batches) {
        throw new UnsupportedOperationException("Page batches are not supported");
    }
}

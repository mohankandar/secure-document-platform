package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.detection.command.DetectSensitiveDataCommand;
import com.securedoc.platform.application.detection.result.DetectSensitiveDataResult;
import com.securedoc.platform.application.pipeline.PageBatch;
import com.securedoc.platform.application.pipeline.PageBatchCommand;

import com.securedoc.platform.domain.vision.VisionPageResult;

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

    /** Task 0064: the pages of a read document that the vision model is to read, each as its own job. */
    default List<Integer> pagesForVision(DetectSensitiveDataCommand command) {
        return List.of();
    }

    /** Task 0064: reads one page with the vision model. Changes nothing, so it may run outside a transaction. */
    default VisionPageResult readVisionPage(DetectSensitiveDataCommand command, int page) {
        throw new UnsupportedOperationException("Vision is not supported");
    }

    /** Task 0064: keeps one page's vision result for {@link #completeFromBatches}. */
    default void saveVisionPage(PageBatchCommand command, VisionPageResult result) {
        throw new UnsupportedOperationException("Vision is not supported");
    }

    /** Task 0064: as above, with the results of the document's vision jobs. */
    default DetectSensitiveDataResult completeFromBatches(DetectSensitiveDataCommand command,
                                                          List<PageBatch> batches, List<PageBatch> visionPages) {
        if (!visionPages.isEmpty()) throw new UnsupportedOperationException("Vision is not supported");
        return completeFromBatches(command, batches);
    }
}

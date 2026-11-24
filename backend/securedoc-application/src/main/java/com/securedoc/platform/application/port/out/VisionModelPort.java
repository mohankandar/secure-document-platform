package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.vision.VisionPageResult;

/**
 * Task 0064: the local vision model. It answers with kinds and boxes; what it wrote is never kept. A page it cannot
 * read for any reason (no model, the wrong model, an unusable answer) is a "not read" result, never an exception: the
 * page then goes to a person.
 */
public interface VisionModelPort {

    /** The longer side of the page image the model is asked with. */
    int IMAGE_LONG_SIDE = 1400;

    VisionPageResult read(PageImagePort.PageImage image, int page);
}

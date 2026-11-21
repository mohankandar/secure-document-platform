package com.securedoc.platform.infrastructure.redaction;

import com.securedoc.platform.domain.error.LvCoded;
import com.securedoc.platform.domain.error.LvErrorCode;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Task 0025 (#141): the PDF engine could not apply the redactions, {@code LV-RED-003}. It is an
 * {@link UncheckedIOException}, so code that catches those behaves exactly as before.
 */
public class RedactionEngineException extends UncheckedIOException implements LvCoded {

    public RedactionEngineException(String message, IOException cause) {
        super(message, cause);
    }

    @Override
    public LvErrorCode lvCode() {
        return LvErrorCode.RED_003;
    }
}

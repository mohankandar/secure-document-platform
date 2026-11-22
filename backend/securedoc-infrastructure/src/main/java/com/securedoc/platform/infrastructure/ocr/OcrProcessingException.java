package com.securedoc.platform.infrastructure.ocr;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;

/** Typed runtime failure raised when a page that requires OCR cannot be safely recognized. */
public class OcrProcessingException extends TechnicalDependencyException {
    public OcrProcessingException(TechnicalFailureCode code, boolean retryable, String safeMessage) {
        super(TechnicalDependency.OCR, code, retryable, safeMessage);
    }

    public OcrProcessingException(TechnicalFailureCode code, boolean retryable,
                                  String safeMessage, Throwable cause) {
        super(TechnicalDependency.OCR, code, retryable, safeMessage, cause);
    }

    /** Task 0025: a page-level cause with its own LexaVault code (for example a page too poor to read, LV-PRC-003). */
    public OcrProcessingException(TechnicalFailureCode code, boolean retryable, String safeMessage,
                                  com.securedoc.platform.domain.error.LvErrorCode lvCode) {
        super(TechnicalDependency.OCR, code, retryable, safeMessage, null, lvCode);
    }

    /**
     * Task 0039: a failure on one page, with its 1-based page number (only the number; {@code lvCode} may be null,
     * then the code follows from {@code code}).
     */
    public OcrProcessingException(TechnicalFailureCode code, boolean retryable, String safeMessage, Throwable cause,
                                  com.securedoc.platform.domain.error.LvErrorCode lvCode, int pageNumber) {
        super(TechnicalDependency.OCR, code, retryable, safeMessage, cause, lvCode, pageNumber);
    }
}

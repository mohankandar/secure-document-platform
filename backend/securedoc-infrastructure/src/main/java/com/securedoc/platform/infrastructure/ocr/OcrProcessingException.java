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
}

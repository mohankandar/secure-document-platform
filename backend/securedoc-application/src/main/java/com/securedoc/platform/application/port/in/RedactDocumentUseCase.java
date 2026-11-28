package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.redaction.command.RedactDocumentCommand;
import com.securedoc.platform.application.redaction.result.RedactDocumentResult;

/**
 * Inbound port for performing redaction on a document in REDACTING status.
 * Reads the original PDF, applies burn-flat redaction based on findings,
 * stores the redacted artifact, and completes the document processing lifecycle.
 */
public interface RedactDocumentUseCase {

    RedactDocumentResult execute(RedactDocumentCommand command);

    /**
     * Task 0119: before the case stops for a person, the checks of the finished file run on a trial file (the findings
     * waiting for a person covered as proposed) and add what they find to the same review. Nothing is kept. True when
     * pages were added. This default checks nothing.
     */
    default boolean checkBeforeReview(RedactDocumentCommand command) {
        return false;
    }
}

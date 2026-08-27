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
}

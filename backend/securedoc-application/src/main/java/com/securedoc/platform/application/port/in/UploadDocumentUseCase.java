package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.command.UploadDocumentCommand;
import com.securedoc.platform.application.intake.command.UploadDocumentResult;

/**
 * Inbound port for uploading a document to an existing package.
 */
public interface UploadDocumentUseCase {

    UploadDocumentResult execute(UploadDocumentCommand command, AuthenticatedPrincipal principal);
}

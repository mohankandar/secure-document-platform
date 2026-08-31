package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.export.command.ExportRedactedDocumentCommand;
import com.securedoc.platform.application.export.result.ExportRedactedDocumentResult;

/**
 * @deprecated The active V1 processing path exports complete packages atomically via
 * {@code ExportPackageDocumentsUseCase}. Retained temporarily for compatibility only.
 *
 * Inbound port for exporting a redacted document to the configured delivery destination.
 */
@Deprecated
public interface ExportRedactedDocumentUseCase {
    ExportRedactedDocumentResult execute(ExportRedactedDocumentCommand command);
}

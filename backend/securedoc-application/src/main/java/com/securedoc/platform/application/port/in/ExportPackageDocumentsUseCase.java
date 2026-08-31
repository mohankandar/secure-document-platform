package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.export.command.ExportPackageDocumentsCommand;
import com.securedoc.platform.application.export.result.ExportPackageDocumentsResult;

/** Atomically exports every finished document in one package/batch. */
public interface ExportPackageDocumentsUseCase {
    ExportPackageDocumentsResult execute(ExportPackageDocumentsCommand command);
}

package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.processing.command.FinalizePackageCommand;
import com.securedoc.platform.application.processing.result.FinalizePackageResult;

/**
 * Inbound port for safely finalizing/approving a package for vault registration.
 * Validates all documents are processed and all findings are resolved before approval.
 */
public interface FinalizePackageUseCase {
    FinalizePackageResult execute(FinalizePackageCommand command);
}

package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.command.CreatePackageCommand;
import com.securedoc.platform.application.intake.command.CreatePackageResult;

/**
 * Inbound port for creating a new document package.
 */
public interface CreatePackageUseCase {

    CreatePackageResult execute(CreatePackageCommand command, AuthenticatedPrincipal principal);
}

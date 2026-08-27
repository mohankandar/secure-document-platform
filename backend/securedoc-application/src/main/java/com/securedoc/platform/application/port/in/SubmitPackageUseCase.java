package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.command.SubmitPackageCommand;

/**
 * Inbound port for submitting a package for processing.
 */
public interface SubmitPackageUseCase {

    void execute(SubmitPackageCommand command, AuthenticatedPrincipal principal);
}

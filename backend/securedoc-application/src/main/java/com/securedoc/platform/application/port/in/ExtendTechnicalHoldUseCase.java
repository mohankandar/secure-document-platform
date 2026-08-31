package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.lifecycle.command.ExtendTechnicalHoldCommand;
import com.securedoc.platform.application.lifecycle.result.PackageLifecycleSummary;

public interface ExtendTechnicalHoldUseCase {
    PackageLifecycleSummary execute(ExtendTechnicalHoldCommand command);
}

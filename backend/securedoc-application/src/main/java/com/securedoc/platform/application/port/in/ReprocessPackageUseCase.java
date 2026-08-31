package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.lifecycle.command.ReprocessPackageCommand;
import com.securedoc.platform.application.lifecycle.result.ReprocessPackageResult;

public interface ReprocessPackageUseCase {
    ReprocessPackageResult execute(ReprocessPackageCommand command);
}

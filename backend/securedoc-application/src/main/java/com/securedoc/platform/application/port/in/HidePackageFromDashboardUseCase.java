package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.operations.command.HidePackageFromDashboardCommand;
import com.securedoc.platform.application.operations.result.HidePackageFromDashboardResult;

public interface HidePackageFromDashboardUseCase {
    HidePackageFromDashboardResult execute(HidePackageFromDashboardCommand command);
}

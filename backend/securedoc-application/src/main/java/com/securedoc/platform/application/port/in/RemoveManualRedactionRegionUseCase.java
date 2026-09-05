package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.manual.command.RemoveManualRedactionRegionCommand;

public interface RemoveManualRedactionRegionUseCase {
    void execute(RemoveManualRedactionRegionCommand command);
}

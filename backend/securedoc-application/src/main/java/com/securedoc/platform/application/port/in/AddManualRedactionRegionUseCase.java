package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.manual.command.AddManualRedactionRegionCommand;
import com.securedoc.platform.application.manual.result.ManualRedactionRegionSummary;

public interface AddManualRedactionRegionUseCase {
    ManualRedactionRegionSummary execute(AddManualRedactionRegionCommand command);
}

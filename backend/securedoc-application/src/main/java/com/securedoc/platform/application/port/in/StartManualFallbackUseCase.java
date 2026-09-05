package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.manual.command.ManualFallbackCommand;
import com.securedoc.platform.application.manual.result.ManualFallbackDetail;

public interface StartManualFallbackUseCase {
    ManualFallbackDetail execute(ManualFallbackCommand command);
}

package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.manual.command.SubmitManualFallbackCommand;

public interface SubmitManualFallbackUseCase {
    void execute(SubmitManualFallbackCommand command);
}

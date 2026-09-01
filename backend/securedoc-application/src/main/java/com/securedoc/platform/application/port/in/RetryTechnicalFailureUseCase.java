package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.technical.command.RetryTechnicalFailureCommand;

public interface RetryTechnicalFailureUseCase {
    void execute(RetryTechnicalFailureCommand command);
}

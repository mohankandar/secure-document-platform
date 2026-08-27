package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.review.command.ConfirmFindingCommand;

/**
 * Inbound port for confirming a finding as a true positive during review.
 */
public interface ConfirmFindingUseCase {
    void execute(ConfirmFindingCommand command);
}

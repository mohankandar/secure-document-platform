package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.review.command.DismissFindingCommand;

/**
 * Inbound port for dismissing a finding as a false positive during review.
 */
public interface DismissFindingUseCase {
    void execute(DismissFindingCommand command);
}

package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.lifecycle.command.ApproveFinalDeliveryCommand;

public interface ApproveFinalDeliveryUseCase {
    void execute(ApproveFinalDeliveryCommand command);
}

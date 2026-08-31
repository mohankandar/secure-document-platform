package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.lifecycle.command.PurgeDueRetentionCommand;
import com.securedoc.platform.application.lifecycle.result.RetentionPurgeResult;

public interface PurgeDueRetentionUseCase {
    RetentionPurgeResult execute(PurgeDueRetentionCommand command);
}

package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.vault.command.DecideArtifactAccessRequestCommand;
import com.securedoc.platform.application.vault.result.ArtifactAccessRequestResult;

/**
 * Inbound port for approving or denying an artifact access request.
 */
public interface DecideArtifactAccessRequestUseCase {
    ArtifactAccessRequestResult execute(DecideArtifactAccessRequestCommand command);
}

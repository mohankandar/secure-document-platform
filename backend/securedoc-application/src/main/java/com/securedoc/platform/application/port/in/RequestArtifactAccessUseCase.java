package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.vault.command.RequestArtifactAccessCommand;
import com.securedoc.platform.application.vault.result.ArtifactAccessRequestResult;

/**
 * Inbound port for requesting access to a restricted (original) artifact.
 */
public interface RequestArtifactAccessUseCase {
    ArtifactAccessRequestResult execute(RequestArtifactAccessCommand command);
}

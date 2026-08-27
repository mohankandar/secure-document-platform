package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.vault.command.DownloadArtifactCommand;
import com.securedoc.platform.application.vault.result.DownloadArtifactResult;

/**
 * Inbound port for downloading an artifact from the vault.
 */
public interface DownloadArtifactUseCase {
    DownloadArtifactResult execute(DownloadArtifactCommand command);
}

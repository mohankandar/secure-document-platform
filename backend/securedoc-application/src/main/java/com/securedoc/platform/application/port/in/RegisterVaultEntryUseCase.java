package com.securedoc.platform.application.port.in;

import com.securedoc.platform.application.vault.command.RegisterVaultEntryCommand;
import com.securedoc.platform.application.vault.result.RegisterVaultEntryResult;

/**
 * Inbound port for registering a processed artifact in the secure vault.
 */
public interface RegisterVaultEntryUseCase {
    RegisterVaultEntryResult execute(RegisterVaultEntryCommand command);
}

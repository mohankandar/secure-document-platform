package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;

/**
 * Dispatches package processing without tying application services to a specific
 * execution mechanism. V1 uses an in-process bounded executor; a durable queue/outbox
 * consumer can replace that adapter later without changing application behavior.
 *
 * <p>When dispatch is requested from inside a transaction, the infrastructure adapter is
 * responsible for ensuring the worker cannot observe uncommitted package state.
 */
public interface PackageProcessingDispatcher {

    void dispatch(TriggerPackageProcessingCommand command);
}

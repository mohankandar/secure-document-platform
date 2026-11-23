package com.securedoc.platform.application.processing.service;

import com.securedoc.platform.application.port.in.TriggerPackageProcessingUseCase;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;

import java.util.Objects;

/**
 * Task 0048: where a processing trigger goes. A case of the staged pipeline (it has stage jobs, or it starts now
 * with pipeline-mode staged) is planned as jobs; every other case runs inline, exactly as before. So a case started
 * inline before the switch finishes inline, and a staged case is never re-run inline.
 */
public final class PipelineModeRouter implements TriggerPackageProcessingUseCase {

    private final TriggerPackageProcessingService inline;
    private final StagedProcessingService staged;

    public PipelineModeRouter(TriggerPackageProcessingService inline, StagedProcessingService staged) {
        this.inline = Objects.requireNonNull(inline);
        this.staged = Objects.requireNonNull(staged);
    }

    @Override
    public void execute(TriggerPackageProcessingCommand command) {
        Objects.requireNonNull(command, "Command must not be null");
        if (staged.handles(command)) {
            staged.plan(command);
        } else {
            inline.execute(command);
        }
    }
}

package com.securedoc.platform.application.resilience;

import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;

import java.util.Objects;

/**
 * Provider-neutral AI resilience decorator: selective retry of transient failures followed by
 * optional secondary-provider failover. The secondary is never a silent heuristic substitute.
 */
public final class ResilientSensitiveContentAnalysisPort implements SensitiveContentAnalysisPort {

    private final SensitiveContentAnalysisPort primary;
    private final SensitiveContentAnalysisPort secondary;
    private final SelectiveRetryExecutor retryExecutor;

    public ResilientSensitiveContentAnalysisPort(SensitiveContentAnalysisPort primary,
                                                 SensitiveContentAnalysisPort secondary,
                                                 SelectiveRetryExecutor retryExecutor) {
        this.primary = Objects.requireNonNull(primary);
        this.secondary = secondary;
        this.retryExecutor = Objects.requireNonNull(retryExecutor);
    }

    @Override
    public AnalysisOutput analyze(AnalysisRequest request) {
        try {
            return retryExecutor.execute(() -> primary.analyze(request));
        } catch (TechnicalDependencyException primaryFailure) {
            if (secondary == null) {
                throw primaryFailure;
            }
            return retryExecutor.execute(() -> secondary.analyze(request));
        }
    }
}

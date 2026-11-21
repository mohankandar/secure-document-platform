package com.securedoc.platform.application.resilience;

import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Provider-neutral AI resilience decorator: selective retry of transient failures followed by
 * optional secondary-provider failover. The secondary is never a silent heuristic substitute.
 */
public final class ResilientSensitiveContentAnalysisPort implements SensitiveContentAnalysisPort {

    private static final Logger log = LoggerFactory.getLogger(ResilientSensitiveContentAnalysisPort.class);

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
            // Task 0023 C7 (#134): a failover is no longer silent. IT sees that the primary is
            // broken even when the secondary succeeds. Codes only, never provider text.
            log.warn("AI_PRIMARY_FAILED_FAILOVER tenant={} reasonCode={} dependency={} retryable={} attempts={}",
                    request.tenantId(), primaryFailure.failureCode(), primaryFailure.dependency(),
                    primaryFailure.retryable(), primaryFailure.attempts());
            try {
                AnalysisOutput output = retryExecutor.execute(() -> secondary.analyze(request));
                log.info("AI_SECONDARY_SUCCEEDED tenant={} primaryReasonCode={}",
                        request.tenantId(), primaryFailure.failureCode());
                return output;
            } catch (TechnicalDependencyException secondaryFailure) {
                // The incident carries the secondary's code; the log keeps the primary's.
                log.warn("AI_SECONDARY_FAILED tenant={} reasonCode={} primaryReasonCode={}",
                        request.tenantId(), secondaryFailure.failureCode(), primaryFailure.failureCode());
                secondaryFailure.addSuppressed(primaryFailure);
                throw secondaryFailure;
            }
        }
    }
}

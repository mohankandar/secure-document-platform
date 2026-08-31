package com.securedoc.platform.application.resilience;

import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.port.out.OcrPort;

import java.util.Objects;

/**
 * Provider-neutral OCR decorator that selectively retries transient failures and can fail over to
 * an optional secondary OCR provider when one is configured later.
 */
public final class ResilientOcrPort implements OcrPort {
    private final OcrPort primary;
    private final OcrPort secondary;
    private final SelectiveRetryExecutor retryExecutor;

    public ResilientOcrPort(OcrPort primary, SelectiveRetryExecutor retryExecutor) {
        this(primary, null, retryExecutor);
    }

    public ResilientOcrPort(OcrPort primary, OcrPort secondary, SelectiveRetryExecutor retryExecutor) {
        this.primary = Objects.requireNonNull(primary);
        this.secondary = secondary;
        this.retryExecutor = Objects.requireNonNull(retryExecutor);
    }

    @Override
    public OcrPageOutput recognize(OcrPageInput input) {
        try {
            return retryExecutor.execute(() -> primary.recognize(input));
        } catch (TechnicalDependencyException primaryFailure) {
            if (secondary == null) {
                throw primaryFailure;
            }
            return retryExecutor.execute(() -> secondary.recognize(input));
        }
    }
}

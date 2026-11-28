package com.securedoc.platform.application.intake.command;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;

import java.util.Objects;

/**
 * Command to submit a package for processing.
 *
 * @param nextBatch task 0104: submits a delivered case's next batch (only its new documents are processed), not a case
 *                  being filled for the first time
 */
public record SubmitPackageCommand(PackageId packageId, CorrelationId correlationId, boolean nextBatch) {

    public SubmitPackageCommand {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(correlationId, "CorrelationId must not be null");
    }

    public SubmitPackageCommand(PackageId packageId, CorrelationId correlationId) {
        this(packageId, correlationId, false);
    }

    public static SubmitPackageCommand of(PackageId packageId, CorrelationId correlationId) {
        return new SubmitPackageCommand(packageId, correlationId, false);
    }

    /** Task 0104: the next batch of a delivered case. */
    public static SubmitPackageCommand nextBatch(PackageId packageId, CorrelationId correlationId) {
        return new SubmitPackageCommand(packageId, correlationId, true);
    }

    /**
     * Convenience factory for non-API callers that do not already have a correlation id.
     */
    public static SubmitPackageCommand of(PackageId packageId) {
        return new SubmitPackageCommand(packageId, CorrelationId.generate(), false);
    }
}

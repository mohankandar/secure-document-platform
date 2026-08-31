package com.securedoc.platform.application.intake.command;

import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.BusinessRecordId;
import com.securedoc.platform.domain.identity.PackageId;
import java.time.Instant;
import java.util.Objects;

public record CreatePackageResult(PackageId packageId, BusinessRecordId businessRecordId,
                                  PackageStatus status, Instant createdAt) {
    public CreatePackageResult {
        Objects.requireNonNull(packageId); Objects.requireNonNull(businessRecordId);
        Objects.requireNonNull(status); Objects.requireNonNull(createdAt);
    }
    public static CreatePackageResult of(PackageId packageId, BusinessRecordId businessRecordId,
                                         PackageStatus status, Instant createdAt) {
        return new CreatePackageResult(packageId, businessRecordId, status, createdAt);
    }
}

package com.securedoc.platform.application.processing.result;

import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.PackageId;

import java.util.List;
import java.util.Objects;

/**
 * Result returned after starting package processing.
 * Contains the list of documents that are ready for processing work.
 */
public record StartPackageProcessingResult(
        PackageId packageId,
        PackageStatus status,
        int documentCount,
        List<ProcessingWorkItem> workItems
) {

    public StartPackageProcessingResult {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(status, "Status must not be null");
        Objects.requireNonNull(workItems, "WorkItems must not be null");
        workItems = List.copyOf(workItems);
    }

    public static StartPackageProcessingResult of(PackageId packageId, PackageStatus status,
                                                   List<ProcessingWorkItem> workItems) {
        return new StartPackageProcessingResult(packageId, status, workItems.size(), workItems);
    }
}

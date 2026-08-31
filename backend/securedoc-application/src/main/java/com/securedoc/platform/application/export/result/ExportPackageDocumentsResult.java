package com.securedoc.platform.application.export.result;

import com.securedoc.platform.domain.identity.PackageId;

public record ExportPackageDocumentsResult(
        PackageId packageId,
        int exportedDocumentCount,
        String destinationType,
        int exportCycle
) {
    public static ExportPackageDocumentsResult of(PackageId packageId, int count, String destinationType, int exportCycle) {
        return new ExportPackageDocumentsResult(packageId, count, destinationType, exportCycle);
    }
}

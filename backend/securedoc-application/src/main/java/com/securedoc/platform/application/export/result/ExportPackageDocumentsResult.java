package com.securedoc.platform.application.export.result;

import com.securedoc.platform.domain.identity.PackageId;

/**
 * @param alreadyReceived task 0027: the destination said it already had the case or a document; counted as exported
 *                        and audited with LV-DLV-009
 */
public record ExportPackageDocumentsResult(
        PackageId packageId,
        int exportedDocumentCount,
        String destinationType,
        int exportCycle,
        boolean alreadyReceived
) {
    public ExportPackageDocumentsResult(PackageId packageId, int exportedDocumentCount, String destinationType,
                                        int exportCycle) {
        this(packageId, exportedDocumentCount, destinationType, exportCycle, false);
    }

    public static ExportPackageDocumentsResult of(PackageId packageId, int count, String destinationType, int exportCycle) {
        return new ExportPackageDocumentsResult(packageId, count, destinationType, exportCycle, false);
    }

    public static ExportPackageDocumentsResult of(PackageId packageId, int count, String destinationType, int exportCycle,
                                                  boolean alreadyReceived) {
        return new ExportPackageDocumentsResult(packageId, count, destinationType, exportCycle, alreadyReceived);
    }
}

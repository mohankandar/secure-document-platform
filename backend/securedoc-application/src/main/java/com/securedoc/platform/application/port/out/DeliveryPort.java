package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.io.InputStream;
import java.util.List;
import java.util.Objects;

/**
 * Outbound port for delivering finished redacted documents to a configured destination.
 *
 * <p>The customer filename MUST be preserved — no suffixes and no merges. Internal IDs/directories
 * provide collision isolation. The primary package method is atomic from the caller's perspective:
 * either the whole submitted package becomes visible at the destination or none of it does.
 */
public interface DeliveryPort {

    /**
     * Atomically delivers every document in one package/batch. Implementations may stage content
     * internally, but no partial package should be exposed as successfully delivered.
     */
    BatchDeliveryReference deliverBatch(TenantId tenantId, PackageId packageId, int exportCycle, List<DeliveryItem> items);

    /**
     * Legacy/single-document convenience. The active straight-through pipeline uses deliverBatch.
     */
    default DeliveryReference deliver(TenantId tenantId, PackageId packageId, DocumentId documentId,
                                      String originalFileName, InputStream content,
                                      long sizeBytes, String mimeType) {
        Objects.requireNonNull(content, "Content must not be null");
        DeliveryItem item = new DeliveryItem(documentId, originalFileName, () -> content, sizeBytes, mimeType);
        BatchDeliveryReference batch = deliverBatch(tenantId, packageId, 1, List.of(item));
        return batch.documents().get(0);
    }

    @FunctionalInterface
    interface ContentSource {
        InputStream open();
    }

    record DeliveryItem(DocumentId documentId, String originalFileName,
                        ContentSource contentSource, long sizeBytes, String mimeType) {
        public DeliveryItem {
            Objects.requireNonNull(documentId, "DocumentId must not be null");
            if (originalFileName == null || originalFileName.isBlank()) {
                throw new IllegalArgumentException("Original filename must not be blank");
            }
            Objects.requireNonNull(contentSource, "Content source must not be null");
            if (sizeBytes < 0) {
                throw new IllegalArgumentException("Size must be >= 0");
            }
            if (mimeType == null || mimeType.isBlank()) {
                throw new IllegalArgumentException("Mime type must not be blank");
            }
        }
    }

    record DeliveryReference(DocumentId documentId, String destinationType, String location) {
    }

    record BatchDeliveryReference(String destinationType, List<DeliveryReference> documents) {
        public BatchDeliveryReference {
            if (destinationType == null || destinationType.isBlank()) {
                throw new IllegalArgumentException("Destination type must not be blank");
            }
            documents = List.copyOf(documents == null ? List.of() : documents);
        }
    }
}

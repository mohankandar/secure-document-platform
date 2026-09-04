package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Outbound transport for one master redacted-package delivery cycle. */
public interface DeliveryPort {

    DeliveryDescriptor describe(TenantId tenantId);

    BatchDeliveryReference deliverBatch(DeliveryBatch batch);

    default DeliveryReference deliver(TenantId tenantId, PackageId packageId, DocumentId documentId,
                                      String originalFileName, InputStream content, long sizeBytes, String mimeType) {
        DeliveryDescriptor descriptor = describe(tenantId);
        DeliveryItem item = new DeliveryItem(documentId, "doc-legacy-" + documentId, originalFileName,
                () -> content, sizeBytes, mimeType, null, null);
        BatchDeliveryReference batch = deliverBatch(new DeliveryBatch(tenantId, packageId, 1,
                "sub-legacy-" + packageId, null, Map.of(), Map.of(), List.of(item), NOOP_PROGRESS));
        return batch.documents().get(0);
    }

    @FunctionalInterface interface ContentSource { InputStream open(); }

    record DeliveryDescriptor(String destinationType, IntegrationConnectionId connectionId,
                              Integer connectionConfigurationVersion) {
        public DeliveryDescriptor {
            if (destinationType == null || destinationType.isBlank()) throw new IllegalArgumentException("Destination type must not be blank");
        }
    }

    interface DeliveryProgressListener {
        default void submissionAccepted(String destinationSubmissionReference) { }
        default void documentAcknowledged(DeliveryReference reference) { }
    }

    DeliveryProgressListener NOOP_PROGRESS = new DeliveryProgressListener() { };

    record DeliveryBatch(TenantId tenantId, PackageId packageId, int exportCycle, String sourceSubmissionId,
                         String existingDestinationSubmissionReference,
                         Map<String,String> businessIdentifiers, Map<String,String> metadata,
                         List<DeliveryItem> items, DeliveryProgressListener progressListener) {
        public DeliveryBatch {
            Objects.requireNonNull(tenantId); Objects.requireNonNull(packageId);
            if (exportCycle < 1) throw new IllegalArgumentException("Export cycle must be >= 1");
            if (sourceSubmissionId == null || sourceSubmissionId.isBlank()) throw new IllegalArgumentException("sourceSubmissionId is required");
            existingDestinationSubmissionReference = existingDestinationSubmissionReference == null || existingDestinationSubmissionReference.isBlank()
                    ? null : existingDestinationSubmissionReference.trim();
            businessIdentifiers = Map.copyOf(businessIdentifiers == null ? Map.of() : businessIdentifiers);
            metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
            items = List.copyOf(items == null ? List.of() : items);
            progressListener = progressListener == null ? NOOP_PROGRESS : progressListener;
            if (items.isEmpty() && existingDestinationSubmissionReference == null) {
                throw new IllegalArgumentException("Delivery batch must contain documents when no destination submission exists");
            }
        }
    }

    record DeliveryItem(DocumentId documentId, String sourceDocumentId, String originalFileName,
                        ContentSource contentSource, long sizeBytes, String mimeType,
                        String checksumAlgorithm, String checksumValue) {
        public DeliveryItem {
            Objects.requireNonNull(documentId, "DocumentId must not be null");
            if (sourceDocumentId == null || sourceDocumentId.isBlank()) throw new IllegalArgumentException("Source document id must not be blank");
            if (originalFileName == null || originalFileName.isBlank()) throw new IllegalArgumentException("Original filename must not be blank");
            Objects.requireNonNull(contentSource, "Content source must not be null");
            if (sizeBytes < 0) throw new IllegalArgumentException("Size must be >= 0");
            if (mimeType == null || mimeType.isBlank()) throw new IllegalArgumentException("Mime type must not be blank");
        }
    }

    record DeliveryReference(DocumentId documentId, String sourceDocumentId, String destinationType,
                             String destinationDocumentReference, String location) { }

    record BatchDeliveryReference(String destinationType, String destinationSubmissionReference,
                                  List<DeliveryReference> documents) {
        public BatchDeliveryReference {
            if (destinationType == null || destinationType.isBlank()) throw new IllegalArgumentException("Destination type must not be blank");
            documents = List.copyOf(documents == null ? List.of() : documents);
        }
    }
}

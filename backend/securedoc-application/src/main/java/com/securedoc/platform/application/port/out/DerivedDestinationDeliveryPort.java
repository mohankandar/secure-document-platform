package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.integration.IntegrationConnection;

import java.io.InputStream;
import java.util.Map;

/** Dynamic transport for approved derived-document destinations (HTTP_API or FILE_SHARE). */
public interface DerivedDestinationDeliveryPort {
    /** Metadata key: the source document's own sensitivity level (task 0021). Every document cut from it carries it. */
    String SENSITIVITY_LEVEL = "sensitivityLevel";
    /** Metadata key: the case's sensitivity level (task 0021). */
    String CASE_SENSITIVITY_LEVEL = "caseSensitivityLevel";

    DeliveryReceipt deliver(IntegrationConnection connection, DeliveryRequest request);
    void testConnection(IntegrationConnection connection);

    @FunctionalInterface interface ContentSource { InputStream open(); }

    record DeliveryRequest(String sourceDerivedDocumentId, String fileName, String mimeType,
                           long sizeBytes, String sha256, String markerValue,
                           Map<String,String> metadata, ContentSource contentSource) {
        public DeliveryRequest {
            metadata = Map.copyOf(metadata == null ? Map.of() : metadata);
            if (sourceDerivedDocumentId == null || sourceDerivedDocumentId.isBlank()) throw new IllegalArgumentException("sourceDerivedDocumentId is required");
            if (fileName == null || fileName.isBlank()) throw new IllegalArgumentException("fileName is required");
            if (mimeType == null || mimeType.isBlank()) throw new IllegalArgumentException("mimeType is required");
            if (contentSource == null) throw new IllegalArgumentException("content source is required");
        }
    }

    record DeliveryReceipt(String destinationReference, String location) { }
}

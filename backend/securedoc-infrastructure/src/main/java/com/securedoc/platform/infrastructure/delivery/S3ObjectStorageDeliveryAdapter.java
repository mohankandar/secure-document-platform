package com.securedoc.platform.infrastructure.delivery;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * S3/object-storage delivery with a completion marker as the publication boundary.
 *
 * <p>Objects are first written under a private staging prefix. Once every staged upload succeeds,
 * they are copied into the cycle's final prefix. The {@code _COMPLETE} marker is written last.
 * Consumers must regard a cycle as published only when that marker exists. On failure, staging and
 * any copied final objects are cleaned up best-effort.</p>
 */
@Component
@ConditionalOnProperty(name = "securedoc.delivery.type", havingValue = "s3")
public class S3ObjectStorageDeliveryAdapter implements DeliveryPort, AutoCloseable {
    private static final String DESTINATION_TYPE = "s3";
    private static final String COMPLETE_MARKER = "_COMPLETE";

    private final S3DeliveryProperties properties;
    private final S3Client s3;

    public S3ObjectStorageDeliveryAdapter(S3DeliveryProperties properties) {
        this.properties = properties;
        S3ClientBuilder builder = S3Client.builder()
                .region(Region.of(nonBlankOr(properties.getRegion(), "us-east-1")))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(properties.isPathStyleAccess())
                        .build());
        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.getEndpoint().trim()));
        }
        this.s3 = builder.build();
    }

    @Override
    public BatchDeliveryReference deliverBatch(TenantId tenantId, PackageId packageId, int exportCycle,
                                               List<DeliveryItem> items) {
        validateConfiguration(exportCycle, items);
        String bucket = properties.getBucket().trim();
        String basePrefix = trimSlashes(properties.getPrefix());
        String cyclePrefix = join(basePrefix, tenantId.value().toString(), packageId.value().toString(),
                "cycle-" + exportCycle);
        String stagingPrefix = join(basePrefix, ".staging", tenantId.value().toString(),
                packageId.value().toString(), "cycle-" + exportCycle + "-" + UUID.randomUUID());

        List<String> stagedKeys = new ArrayList<>();
        List<String> finalKeys = new ArrayList<>();
        List<DeliveryReference> references = new ArrayList<>();
        try {
            for (DeliveryItem item : items) {
                String name = sanitizeFileName(item.originalFileName());
                String relative = join(item.documentId().value().toString(), name);
                String stagedKey = join(stagingPrefix, relative);
                String finalKey = join(cyclePrefix, relative);
                try (InputStream input = item.contentSource().open()) {
                    if (input == null) {
                        throw new IllegalStateException("Delivery content source returned null");
                    }
                    s3.putObject(PutObjectRequest.builder()
                                    .bucket(bucket)
                                    .key(stagedKey)
                                    .contentType(item.mimeType())
                                    .build(),
                            RequestBody.fromInputStream(input, item.sizeBytes()));
                }
                stagedKeys.add(stagedKey);
                finalKeys.add(finalKey);
                references.add(new DeliveryReference(item.documentId(), DESTINATION_TYPE,
                        "s3://" + bucket + "/" + finalKey));
            }

            for (int i = 0; i < stagedKeys.size(); i++) {
                s3.copyObject(CopyObjectRequest.builder()
                        .copySource(encodeCopySource(bucket + "/" + stagedKeys.get(i)))
                        .destinationBucket(bucket)
                        .destinationKey(finalKeys.get(i))
                        .build());
            }

            String markerKey = join(cyclePrefix, COMPLETE_MARKER);
            String markerBody = "packageId=" + packageId + "\nexportCycle=" + exportCycle + "\n";
            s3.putObject(PutObjectRequest.builder()
                            .bucket(bucket)
                            .key(markerKey)
                            .contentType("text/plain")
                            .build(),
                    RequestBody.fromBytes(markerBody.getBytes(StandardCharsets.UTF_8)));

            deleteKeysQuietly(bucket, stagedKeys);
            return new BatchDeliveryReference(DESTINATION_TYPE, references);
        } catch (TechnicalDependencyException e) {
            deleteKeysQuietly(bucket, stagedKeys);
            deleteKeysQuietly(bucket, finalKeys);
            throw e;
        } catch (Exception e) {
            deleteKeysQuietly(bucket, stagedKeys);
            deleteKeysQuietly(bucket, finalKeys);
            boolean retryable = isRetryable(e);
            TechnicalFailureCode code = e instanceof S3Exception s3e && s3e.statusCode() >= 400 && s3e.statusCode() < 500
                    && s3e.statusCode() != 429
                    ? TechnicalFailureCode.DELIVERY_DESTINATION_INVALID
                    : TechnicalFailureCode.DELIVERY_IO;
            throw new TechnicalDependencyException(TechnicalDependency.DELIVERY,
                    code, retryable, "S3 package delivery failed", e);
        }
    }


    private boolean isRetryable(Exception e) {
        if (e instanceof S3Exception s3e) {
            return s3e.statusCode() == 429 || s3e.statusCode() >= 500;
        }
        return e instanceof SdkException;
    }

    private String encodeCopySource(String source) {
        return URLEncoder.encode(source, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace("%2F", "/");
    }

    private void validateConfiguration(int exportCycle, List<DeliveryItem> items) {
        if (properties.getBucket() == null || properties.getBucket().isBlank()) {
            throw new TechnicalDependencyException(TechnicalDependency.DELIVERY,
                    TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false,
                    "S3 delivery bucket is not configured");
        }
        if (exportCycle < 1) {
            throw new IllegalArgumentException("Export cycle must be >= 1");
        }
        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException("Delivery batch must not be empty");
        }
    }

    private void deleteKeysQuietly(String bucket, List<String> keys) {
        for (String key : keys) {
            try {
                s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
            } catch (RuntimeException ignored) {
                // Best-effort cleanup of unpublished/staging objects only.
            }
        }
    }

    private String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Original filename must not be blank");
        }
        String name = fileName.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        if (name.isBlank() || name.contains("..")) {
            throw new IllegalArgumentException("Invalid original filename");
        }
        return name;
    }

    private String join(String... parts) {
        StringBuilder out = new StringBuilder();
        for (String part : parts) {
            String clean = trimSlashes(part);
            if (clean.isBlank()) continue;
            if (out.length() > 0) out.append('/');
            out.append(clean);
        }
        return out.toString();
    }

    private String trimSlashes(String value) {
        if (value == null) return "";
        String result = value.trim();
        while (result.startsWith("/")) result = result.substring(1);
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private String nonBlankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    @Override
    public void close() {
        s3.close();
    }
}

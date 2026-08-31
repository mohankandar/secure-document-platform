package com.securedoc.platform.infrastructure.delivery;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Package-atomic API/callback delivery: one multipart request contains the whole intake batch.
 * The remote endpoint acknowledges success only for the complete request.
 */
@Component
@ConditionalOnProperty(name = "securedoc.delivery.type", havingValue = "api")
public class ApiCallbackDeliveryAdapter implements DeliveryPort {
    private static final String DESTINATION_TYPE = "api-callback";
    private final ApiDeliveryProperties properties;
    private final HttpClient client;

    public ApiCallbackDeliveryAdapter(ApiDeliveryProperties properties) {
        this.properties = properties;
        if (properties.getEndpoint() == null || properties.getEndpoint().isBlank()) {
            throw new IllegalStateException("API delivery endpoint is required when API delivery is selected");
        }
        if (properties.isRequireAuthentication()
                && (properties.getBearerToken() == null || properties.getBearerToken().isBlank())) {
            throw new IllegalStateException("API delivery authentication is required when API delivery is selected");
        }
        validatedEndpoint();
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                .build();
    }

    @Override
    public BatchDeliveryReference deliverBatch(TenantId tenantId, PackageId packageId, int exportCycle,
                                               List<DeliveryItem> items) {
        if (properties.getEndpoint() == null || properties.getEndpoint().isBlank()) {
            throw failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, "API delivery endpoint is not configured", null);
        }
        URI endpoint = validatedEndpoint();
        if (properties.isRequireAuthentication() && (properties.getBearerToken() == null || properties.getBearerToken().isBlank())) {
            throw failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false,
                    "API delivery authentication is not configured", null);
        }
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("Delivery batch must not be empty");
        String boundary = "securedoc-" + UUID.randomUUID();
        try {
            byte[] body = multipartBody(boundary, tenantId, packageId, exportCycle, items);
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(Math.max(1, properties.getTimeoutSeconds())))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header("X-SecureDoc-Tenant", tenantId.toString())
                    .header("X-SecureDoc-Package", packageId.toString())
                    .header("X-SecureDoc-Export-Cycle", Integer.toString(exportCycle))
                    .header("Idempotency-Key", tenantId + ":" + packageId + ":" + exportCycle)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (properties.getBearerToken() != null && !properties.getBearerToken().isBlank()) {
                builder.header("Authorization", "Bearer " + properties.getBearerToken().trim());
            }
            HttpResponse<Void> response = client.send(builder.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                boolean retryable = response.statusCode() == 429 || response.statusCode() >= 500;
                throw failure(TechnicalFailureCode.DELIVERY_IO, retryable,
                        "API delivery endpoint rejected the package", null);
            }
            List<DeliveryReference> refs = new ArrayList<>();
            for (DeliveryItem item : items) {
                refs.add(new DeliveryReference(item.documentId(), DESTINATION_TYPE,
                        properties.getEndpoint() + "#" + sanitizeFileName(item.originalFileName())));
            }
            return new BatchDeliveryReference(DESTINATION_TYPE, refs);
        } catch (TechnicalDependencyException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure(TechnicalFailureCode.DELIVERY_IO, true, "API delivery was interrupted", e);
        } catch (Exception e) {
            throw failure(TechnicalFailureCode.DELIVERY_IO, true, "API delivery failed", e);
        }
    }


    private URI validatedEndpoint() {
        try {
            URI endpoint = URI.create(properties.getEndpoint().trim());
            String scheme = endpoint.getScheme();
            if (properties.isRequireHttps() && (scheme == null || !scheme.equalsIgnoreCase("https"))) {
                throw failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false,
                        "API delivery requires HTTPS", null);
            }
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
                throw failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false,
                        "API delivery endpoint scheme is invalid", null);
            }
            return endpoint;
        } catch (TechnicalDependencyException e) {
            throw e;
        } catch (IllegalArgumentException e) {
            throw failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false,
                    "API delivery endpoint is invalid", e);
        }
    }

    private byte[] multipartBody(String boundary, TenantId tenantId, PackageId packageId,
                                 int exportCycle, List<DeliveryItem> items) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, boundary, "tenantId", tenantId.toString());
        field(out, boundary, "packageId", packageId.toString());
        field(out, boundary, "exportCycle", Integer.toString(exportCycle));
        for (DeliveryItem item : items) {
            String fileName = sanitizeFileName(item.originalFileName());
            out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Disposition: form-data; name=\"documents\"; filename=\"" + fileName + "\"\r\n")
                    .getBytes(StandardCharsets.UTF_8));
            out.write(("Content-Type: " + item.mimeType() + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            try (InputStream input = item.contentSource().open()) {
                input.transferTo(out);
            }
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private void field(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
        out.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(("Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        out.write(value.getBytes(StandardCharsets.UTF_8));
        out.write("\r\n".getBytes(StandardCharsets.UTF_8));
    }

    private String sanitizeFileName(String value) {
        String name = value == null ? "document.pdf" : value.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        return name.replace("\"", "_").replace("\r", "_").replace("\n", "_");
    }

    private TechnicalDependencyException failure(TechnicalFailureCode code, boolean retryable,
                                                 String message, Throwable cause) {
        return new TechnicalDependencyException(TechnicalDependency.DELIVERY, code, retryable, message, cause);
    }
}

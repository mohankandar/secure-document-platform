package com.securedoc.platform.infrastructure.routing;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.port.out.DerivedDestinationDeliveryPort;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DerivedDestinationDeliveryAdapterTest {
    @Test
    void httpDeliveryRequiresDurableReceipt() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var adapter = new DerivedDestinationDeliveryAdapter(new ObjectMapper(), client);

        TechnicalDependencyException ex = assertThrows(TechnicalDependencyException.class,
                () -> adapter.deliver(httpConnection("https://example.test/derived"), request()));
        assertTrue(ex.retryable());
        assertEquals("DELIVERY_IO", ex.failureCode().name());
    }

    @Test
    void httpDeliveryAcceptsReceiptAndUsesStableIdempotencyHeader() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"receiptId\":\"receipt-42\"}");
        final HttpRequest[] captured = new HttpRequest[1];
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            captured[0] = invocation.getArgument(0);
            return response;
        });
        var adapter = new DerivedDestinationDeliveryAdapter(new ObjectMapper(), client);

        var receipt = adapter.deliver(httpConnection("https://example.test/derived"), request());
        assertEquals("receipt-42", receipt.destinationReference());
        assertEquals("derived-123", captured[0].headers().firstValue("Idempotency-Key").orElseThrow());
    }

    @Test
    void fileShareWritesOnlyBeneathConfiguredRoot() throws Exception {
        var root = Files.createTempDirectory("derived-destination-");
        var adapter = new DerivedDestinationDeliveryAdapter(new ObjectMapper(), mock(HttpClient.class));
        var receipt = adapter.deliver(fileConnection(root.toString()), request());

        var written = root.resolve(receipt.destinationReference()).normalize();
        assertTrue(written.startsWith(root.toAbsolutePath().normalize()));
        assertArrayEquals("PDF-CONTENT".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(written));
    }

    private DerivedDestinationDeliveryPort.DeliveryRequest request() {
        byte[] body = "PDF-CONTENT".getBytes(StandardCharsets.UTF_8);
        return new DerivedDestinationDeliveryPort.DeliveryRequest("derived-123", "routed.pdf", "application/pdf",
                body.length, "a".repeat(64), "77777777", Map.of("packageId", "pkg-1"),
                () -> new ByteArrayInputStream(body));
    }

    private IntegrationConnection httpConnection(String endpoint) {
        return IntegrationConnection.create(TenantId.generate(), "HTTP", null,
                IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.NONE, null,
                endpoint, null, null, 3, 0, 24, 10, 10_000_000L, 100,
                IntegrationAcknowledgementMode.DESTINATION_RECEIPT, true, Instant.now());
    }

    private IntegrationConnection fileConnection(String root) {
        return IntegrationConnection.create(TenantId.generate(), "FILES", null,
                IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.FILE_SHARE, IntegrationAuthenticationType.NONE, null,
                null, root, null, 3, 0, 24, 10, 10_000_000L, 100,
                IntegrationAcknowledgementMode.DURABLE_FILE_WRITE, true, Instant.now());
    }
}

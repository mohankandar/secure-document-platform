package com.securedoc.platform.infrastructure.routing;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.port.out.DerivedDestinationDeliveryPort;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DerivedDestinationDeliveryAdapterTest {
    /** Hardening: secret references limited to approved names; a saved one that is refused does not run and sends nothing. */
    @Test
    void aSavedDestinationWithAnUnapprovedSecretNameDoesNotRunAndSendsNothing() throws Exception {
        TenantId entity = TenantId.generate();
        String value = "value-that-must-never-show-0109";
        String approved = com.securedoc.platform.api.notifications.AppSecretResolver.entityPrefix(entity) + "DEST_KEY";
        java.util.List<String> looked = new java.util.ArrayList<>();
        var resolver = new com.securedoc.platform.api.notifications.AppSecretResolver(name -> {
            looked.add(name);
            return value;
        }, "");
        var secrets = new com.securedoc.platform.infrastructure.secrets.IntegrationSecrets(
                com.securedoc.platform.api.notifications.AppSecretResolver::entityRef, resolver);
        HttpClient client = mock(HttpClient.class);
        var adapter = new DerivedDestinationDeliveryAdapter(JsonMapper.builderWithJackson2Defaults().build(), client, true, secrets);
        for (String reference : new String[] {"env:PATH", "sys:java.home", "env:" + approved.replace(
                entity.value().toString().replace("-", "").toUpperCase(java.util.Locale.ROOT), "0".repeat(32))}) {
            TechnicalDependencyException refused = assertThrows(TechnicalDependencyException.class,
                    () -> adapter.deliver(securedConnection(entity, reference), request()), reference);
            assertEquals(com.securedoc.platform.domain.error.LvErrorCode.DLV_005, refused.lvCode());
            assertFalse(refused.retryable());
            assertFalse(refused.getMessage().contains(reference) || refused.getMessage().contains(value), refused.getMessage());
        }
        verify(client, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        assertTrue(looked.isEmpty(), "a refused name is never looked up");

        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"receiptId\":\"receipt-1\"}");
        HttpRequest[] sent = new HttpRequest[1];
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(call -> {
            sent[0] = call.getArgument(0);
            return response;
        });
        adapter.deliver(securedConnection(entity, "env:" + approved), request());
        assertEquals(java.util.Optional.of(value), sent[0].headers().firstValue("X-API-Key"), "an approved name still works");
    }

    private IntegrationConnection securedConnection(TenantId entity, String reference) {
        return IntegrationConnection.create(entity, "HTTP", null,
                IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.API_KEY, null,
                "https://example.test/derived", null, reference, 3, 0, 24, 10, 10_000_000L, 100,
                IntegrationAcknowledgementMode.DESTINATION_RECEIPT, true, Instant.now());
    }

    @Test
    void httpDeliveryRequiresDurableReceipt() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{}");
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        var adapter = new DerivedDestinationDeliveryAdapter(JsonMapper.builderWithJackson2Defaults().build(), client);

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
        var adapter = new DerivedDestinationDeliveryAdapter(JsonMapper.builderWithJackson2Defaults().build(), client);

        var receipt = adapter.deliver(httpConnection("https://example.test/derived"), request());
        assertEquals("receipt-42", receipt.destinationReference());
        assertEquals("derived-123", captured[0].headers().firstValue("Idempotency-Key").orElseThrow());
    }

    /** Task 0030: a refusal carries the destination's own code from its answer; an unsafe one is dropped. */
    @Test
    void aRefusalCarriesTheDestinationsOwnCodeOnlyWhenSafe() throws Exception {
        String[][] answers = {
                {"400", "{\"code\":\"TC-REFUSED_17\",\"message\":\"no\"}", "TC-REFUSED_17"},
                {"503", "{\"code\":\"TC-BUSY\"}", "TC-BUSY"},
                {"400", "{\"code\":\"<script>alert(1)</script>\"}", null},
                {"400", "{\"code\":\"" + "x".repeat(41) + "\"}", null},
                {"400", "{\"code\":{\"nested\":\"TC-1\"}}", null},
                {"400", "not json", null},
                {"400", "", null},
        };
        for (String[] answer : answers) {
            HttpClient client = mock(HttpClient.class);
            @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
            when(response.statusCode()).thenReturn(Integer.parseInt(answer[0]));
            when(response.body()).thenReturn(answer[1]);
            when(response.headers()).thenReturn(HttpHeaders.of(Map.of(), (a, b) -> true));
            when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
            var adapter = new DerivedDestinationDeliveryAdapter(JsonMapper.builderWithJackson2Defaults().build(), client, true);

            TechnicalDependencyException ex = assertThrows(TechnicalDependencyException.class,
                    () -> adapter.deliver(httpConnection("https://example.test/derived"), request()));
            assertEquals(answer[2], ex.destinationCode(), answer[1]);
        }
    }

    /** Task 0021: a split document carries its source document's level and the case level to an HTTP destination. */
    @Test
    void httpDeliveryCarriesTheSourceDocumentAndCaseLevels() throws Exception {
        HttpClient client = mock(HttpClient.class);
        @SuppressWarnings("unchecked") HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"receiptId\":\"receipt-42\"}");
        final HttpRequest[] captured = new HttpRequest[1];
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            captured[0] = invocation.getArgument(0);
            return response;
        });
        var adapter = new DerivedDestinationDeliveryAdapter(JsonMapper.builderWithJackson2Defaults().build(), client);
        byte[] body = "PDF-CONTENT".getBytes(StandardCharsets.UTF_8);
        var request = new DerivedDestinationDeliveryPort.DeliveryRequest("derived-123", "routed.pdf", "application/pdf",
                body.length, "a".repeat(64), "77777777", Map.of("packageId", "pkg-1",
                DerivedDestinationDeliveryPort.SENSITIVITY_LEVEL, "HIGHLY_CONFIDENTIAL",
                DerivedDestinationDeliveryPort.CASE_SENSITIVITY_LEVEL, "CONFIDENTIAL"),
                () -> new ByteArrayInputStream(body));

        adapter.deliver(httpConnection("https://example.test/derived"), request);

        assertEquals("HIGHLY_CONFIDENTIAL", captured[0].headers().firstValue("X-LexaVault-Sensitivity-Level").orElseThrow());
        assertEquals("CONFIDENTIAL", captured[0].headers().firstValue("X-LexaVault-Case-Sensitivity-Level").orElseThrow());
    }

    @Test
    void fileShareWritesOnlyBeneathConfiguredRoot() throws Exception {
        var root = Files.createTempDirectory("derived-destination-");
        var adapter = new DerivedDestinationDeliveryAdapter(JsonMapper.builderWithJackson2Defaults().build(), mock(HttpClient.class));
        adapter.deliver(fileConnection(root.toString()), request());

        // Task 0023 (B4): the receipt no longer carries the file name, so the written file is found on the share.
        // The file on the share is still named "<derived id>__<file name>".
        var written = root.resolve("derived-123__routed.pdf").normalize();
        assertTrue(written.startsWith(root.toAbsolutePath().normalize()));
        assertArrayEquals("PDF-CONTENT".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(written));
    }

    /** Task 0023 (B4, #222): the receipt kept in the delivery row and returned by the API names no file and no path. */
    @Test
    void fileShareReceiptCarriesTheDerivedIdOnly() throws Exception {
        var root = Files.createTempDirectory("derived-destination-");
        var adapter = new DerivedDestinationDeliveryAdapter(JsonMapper.builderWithJackson2Defaults().build(), mock(HttpClient.class));
        var receipt = adapter.deliver(fileConnection(root.toString()), request());

        assertEquals("derived-123", receipt.destinationReference());
        assertEquals("file-share:derived-123", receipt.location());
        assertFalse(receipt.location().contains(root.toString()));
        assertFalse(receipt.destinationReference().contains("routed"));
    }

    /** Task 0023 (#222): a failed write no longer leaves a temp file with redacted content on the share. */
    @Test
    void aFailedFileShareWriteLeavesNoTempFile() throws Exception {
        var root = Files.createTempDirectory("derived-destination-");
        var adapter = new DerivedDestinationDeliveryAdapter(JsonMapper.builderWithJackson2Defaults().build(), mock(HttpClient.class));
        var failing = new DerivedDestinationDeliveryPort.DeliveryRequest("derived-123", "routed.pdf", "application/pdf",
                3, "a".repeat(64), null, Map.of(), () -> new InputStream() {
                    @Override public int read() throws IOException { throw new IOException("disk"); }
                });

        TechnicalDependencyException ex = assertThrows(TechnicalDependencyException.class,
                () -> adapter.deliver(fileConnection(root.toString()), failing));
        assertEquals("DELIVERY_IO", ex.failureCode().name());
        try (var left = Files.list(root)) {
            assertEquals(0, left.count());
        }
    }

    /** Task 0052: with export-mode inline, an export job's attempt is still one try; anything else keeps its retries. */
    @Test
    void anExportJobsAttemptIsOneTryWhateverTheExportMode() throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("down"));
        var adapter = new DerivedDestinationDeliveryAdapter(JsonMapper.builderWithJackson2Defaults().build(), client);
        IntegrationConnection connection = IntegrationConnection.create(TenantId.generate(), "HTTP", null,
                IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.NONE, null,
                "https://destination.example/api", null, null, 3, 1, 24, 10, 10_000_000L, 100,
                IntegrationAcknowledgementMode.DESTINATION_RECEIPT, true, Instant.now());

        assertThrows(TechnicalDependencyException.class, () -> com.securedoc.platform.application.pipeline
                .ExportJobAttempt.run(() -> adapter.deliver(connection, request())));
        verify(client, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        assertThrows(TechnicalDependencyException.class, () -> adapter.deliver(connection, request()));
        verify(client, times(3)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
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

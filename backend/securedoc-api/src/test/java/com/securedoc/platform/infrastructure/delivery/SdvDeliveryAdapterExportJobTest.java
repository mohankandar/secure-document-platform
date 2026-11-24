package com.securedoc.platform.infrastructure.delivery;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.integration.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Task 0027: the SDV adapter with export jobs (one attempt per call) and Vero's "already received" answers. */
class SdvDeliveryAdapterExportJobTest {
    private final TenantId tenant = TenantId.generate();

    @Test
    void inJobModeABusyDestinationIsTriedOnceTheJobSpacesTheTries() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient client = client(requests, new ArrayDeque<>(List.of(response(503, "{}"), response(503, "{}"),
                response(503, "{}"))));
        SdvDeliveryAdapter adapter = new SdvDeliveryAdapter(new OneConnection(connection(2)), JsonMapper.builderWithJackson2Defaults().build(),
                client, true);

        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> adapter.deliverBatch(batch()));

        assertEquals(1, requests.size(), "no in-adapter retries in job mode");
        assertEquals(LvErrorCode.DLV_002, LvErrorCodes.of(failure));
    }

    /** Task 0052: after a switch back to inline, an export job's attempt is still one try. */
    @Test
    void inInlineModeAnExportJobsAttemptIsStillOneTry() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient client = client(requests, new ArrayDeque<>(List.of(response(503, "{}"), response(503, "{}"),
                response(503, "{}"))));
        SdvDeliveryAdapter adapter = new SdvDeliveryAdapter(new OneConnection(connection(2)), JsonMapper.builderWithJackson2Defaults().build(),
                client);

        assertThrows(TechnicalDependencyException.class, () -> com.securedoc.platform.application.pipeline
                .ExportJobAttempt.run(() -> adapter.deliverBatch(batch())));

        assertEquals(1, requests.size());
    }

    @Test
    void inlineKeepsTheInAdapterRetries() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient client = client(requests, new ArrayDeque<>(List.of(response(503, "{}"), response(503, "{}"),
                response(503, "{}"))));
        SdvDeliveryAdapter adapter = new SdvDeliveryAdapter(new OneConnection(connection(2)), JsonMapper.builderWithJackson2Defaults().build(),
                client);

        assertThrows(TechnicalDependencyException.class, () -> adapter.deliverBatch(batch()));

        assertEquals(3, requests.size(), "1 + retryMaxAttempts (2), as today");
    }

    @Test
    void veroSayingItAlreadyHasTheDocumentIsASuccessMarkedAlreadyReceived() throws Exception {
        for (String upload : List.of(
                "{\"documentId\":\"d1\",\"sourceDocumentId\":\"source-doc-1\",\"idempotentReplay\":true}",
                "{\"documentId\":\"d1\",\"sourceDocumentId\":\"source-doc-1\",\"receiptAcknowledgement\":\"DUPLICATE_ALREADY_STORED\",\"duplicate\":true}")) {
            HttpClient client = client(new ArrayList<>(), new ArrayDeque<>(List.of(
                    response(201, "{\"submissionId\":\"sdv-sub-1\"}"), response(200, upload),
                    response(200, "{\"status\":\"COMPLETE\"}"))));
            SdvDeliveryAdapter adapter = new SdvDeliveryAdapter(new OneConnection(connection(0)), JsonMapper.builderWithJackson2Defaults().build(),
                    client, true);

            var result = adapter.deliverBatch(batch());

            assertTrue(result.alreadyReceived(), upload);
            assertEquals(1, result.documents().size());
        }
    }

    @Test
    void aNormalReceiptIsNotAlreadyReceived() throws Exception {
        HttpClient client = client(new ArrayList<>(), new ArrayDeque<>(List.of(
                response(201, "{\"submissionId\":\"sdv-sub-1\"}"),
                response(201, "{\"documentId\":\"d1\",\"sourceDocumentId\":\"source-doc-1\",\"receiptAcknowledgement\":\"RECEIVED\"}"),
                response(200, "{\"status\":\"COMPLETE\"}"))));
        SdvDeliveryAdapter adapter = new SdvDeliveryAdapter(new OneConnection(connection(0)), JsonMapper.builderWithJackson2Defaults().build(),
                client, true);

        assertFalse(adapter.deliverBatch(batch()).alreadyReceived());
    }

    @Test
    void veroAnswering409IsSameKeyDifferentContentSoItStaysRefusedTheFile() throws Exception {
        HttpClient client = client(new ArrayList<>(), new ArrayDeque<>(List.of(response(409,
                "{\"code\":\"CONFLICT\",\"message\":\"sourceSubmissionId already exists with a different submission create request.\"}"))));
        SdvDeliveryAdapter adapter = new SdvDeliveryAdapter(new OneConnection(connection(0)), JsonMapper.builderWithJackson2Defaults().build(),
                client, true);

        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> adapter.deliverBatch(batch()));

        assertEquals(LvErrorCode.DLV_004, LvErrorCodes.of(failure));
    }

    @SuppressWarnings("unchecked")
    private static HttpClient client(List<HttpRequest> requests, Deque<HttpResponse<String>> responses) throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return responses.removeFirst();
        });
        return client;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private DeliveryPort.DeliveryBatch batch() {
        byte[] pdf = "%PDF-test".getBytes();
        var item = new DeliveryPort.DeliveryItem(DocumentId.generate(), "source-doc-1", "redacted.pdf",
                () -> new ByteArrayInputStream(pdf), pdf.length, "application/pdf", null, null);
        return new DeliveryPort.DeliveryBatch(tenant, PackageId.generate(), 1, "source-sub-1", null, Map.of(), Map.of(),
                List.of(item), DeliveryPort.NOOP_PROGRESS);
    }

    private IntegrationConnection connection(int retryMaxAttempts) {
        return IntegrationConnection.create(tenant, "SDV", null, IntegrationPurpose.DOCUMENT_STORE_DELIVERY,
                IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.NONE,
                null, "https://sdv.example.test", null, null, 5, retryMaxAttempts, 24, 10, 10_000_000L, 100,
                IntegrationAcknowledgementMode.SUBMISSION_ACK, true, Instant.now());
    }

    private static final class OneConnection implements IntegrationConnectionRepository {
        private IntegrationConnection current;
        private OneConnection(IntegrationConnection current) { this.current = current; }
        public List<IntegrationConnection> findByTenantId(TenantId tenantId) { return List.of(current); }
        public Optional<IntegrationConnection> findByTenantIdAndId(TenantId tenantId, IntegrationConnectionId id) { return Optional.of(current); }
        public Optional<IntegrationConnection> findEnabledPushSourceByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal) { return Optional.empty(); }
        public Optional<IntegrationConnection> findEnabledScannerSourceByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal) { return Optional.empty(); }
        public Optional<IntegrationConnection> findEnabledDocumentStoreByTenantId(TenantId tenantId) { return Optional.of(current); }
        public boolean existsByTenantIdAndNameIgnoreCase(TenantId tenantId, String name, IntegrationConnectionId excludeId) { return false; }
        public boolean existsByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal, IntegrationConnectionId excludeId) { return false; }
        public void save(IntegrationConnection connection) { current = connection; }
    }
}

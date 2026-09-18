package com.securedoc.platform.infrastructure.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.integration.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.net.http.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SdvDeliveryAdapterTest {
    @Test
    void createsOneSubmissionStreamsDocumentAndRequiresCompleteStatus() throws Exception {
        TenantId tenant = TenantId.generate();
        IntegrationConnection connection = sdvConnection(tenant);
        FakeConnections repository = new FakeConnections(connection);
        HttpClient client = mock(HttpClient.class);
        List<HttpRequest> requests = new ArrayList<>();
        Deque<HttpResponse<String>> responses = new ArrayDeque<>();
        responses.add(response(201, "{\"submissionId\":\"sdv-sub-1\"}"));
        responses.add(response(201, "{\"documentId\":\"sdv-doc-1\",\"sourceDocumentId\":\"source-doc-1\"}"));
        responses.add(response(200, "{\"status\":\"COMPLETE\"}"));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return responses.removeFirst();
        });

        SdvDeliveryAdapter adapter = new SdvDeliveryAdapter(repository, new ObjectMapper(), client);
        byte[] pdf = "%PDF-test".getBytes();
        List<String> progress = new ArrayList<>();
        DeliveryPort.DeliveryProgressListener listener = new DeliveryPort.DeliveryProgressListener() {
            public void submissionAccepted(String ref) { progress.add("SUB:" + ref); }
            public void documentAcknowledged(DeliveryPort.DeliveryReference ref) { progress.add("DOC:" + ref.sourceDocumentId()); }
        };
        var item = new DeliveryPort.DeliveryItem(DocumentId.generate(), "source-doc-1", "redacted.pdf",
                () -> new ByteArrayInputStream(pdf), pdf.length, "application/pdf", "SHA-256", "a".repeat(64));
        var batch = new DeliveryPort.DeliveryBatch(tenant, PackageId.generate(), 1, "source-sub-1", null,
                Map.of("contractNumber", "C-1"), Map.of(), List.of(item), listener);

        var result = adapter.deliverBatch(batch);

        assertEquals("sdv-sub-1", result.destinationSubmissionReference());
        assertEquals(1, result.documents().size());
        assertEquals(List.of("SUB:sdv-sub-1", "DOC:source-doc-1"), progress);
        assertEquals(3, requests.size());
        assertEquals("source-sub-1", requests.get(0).headers().firstValue("Idempotency-Key").orElseThrow());
        assertEquals("source-sub-1:source-doc-1", requests.get(1).headers().firstValue("Idempotency-Key").orElseThrow());
        assertTrue(requests.get(1).bodyPublisher().isPresent(), "document upload must use a body publisher");
        assertEquals(IntegrationHealthStatus.HEALTHY, repository.current.healthStatus());
    }

    @Test
    void incompleteSubmissionDoesNotReportSuccessfulDelivery() throws Exception {
        TenantId tenant = TenantId.generate();
        IntegrationConnection connection = sdvConnection(tenant);
        FakeConnections repository = new FakeConnections(connection);
        HttpClient client = mock(HttpClient.class);
        Deque<HttpResponse<String>> responses = new ArrayDeque<>();
        responses.add(response(201, "{\"submissionId\":\"sdv-sub-1\"}"));
        responses.add(response(201, "{\"documentId\":\"sdv-doc-1\",\"sourceDocumentId\":\"source-doc-1\"}"));
        responses.add(response(200, "{\"status\":\"RECEIVING\"}"));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> responses.removeFirst());
        SdvDeliveryAdapter adapter = new SdvDeliveryAdapter(repository, new ObjectMapper(), client);
        byte[] pdf = "%PDF-test".getBytes();
        var item = new DeliveryPort.DeliveryItem(DocumentId.generate(), "source-doc-1", "redacted.pdf",
                () -> new ByteArrayInputStream(pdf), pdf.length, "application/pdf", null, null);
        var batch = new DeliveryPort.DeliveryBatch(tenant, PackageId.generate(), 1, "source-sub-1", null,
                Map.of(), Map.of(), List.of(item), DeliveryPort.NOOP_PROGRESS);

        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class, () -> adapter.deliverBatch(batch));
        assertTrue(failure.retryable());
        assertEquals("DELIVERY_IO", failure.failureCode().name());
        assertEquals(IntegrationHealthStatus.DEGRADED, repository.current.healthStatus());
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    private IntegrationConnection sdvConnection(TenantId tenant) {
        return IntegrationConnection.create(tenant, "SDV", null,
                IntegrationPurpose.SDV_DELIVERY, IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API,
                IntegrationAuthenticationType.NONE, null, "https://sdv.example.test", null, null,
                5, 0, 24, 10, 10_000_000L, 100,
                IntegrationAcknowledgementMode.SDV_SUBMISSION_ACK, true, Instant.now());
    }

    private static final class FakeConnections implements IntegrationConnectionRepository {
        private IntegrationConnection current;
        private FakeConnections(IntegrationConnection current) { this.current = current; }
        public List<IntegrationConnection> findByTenantId(TenantId tenantId) { return List.of(current); }
        public Optional<IntegrationConnection> findByTenantIdAndId(TenantId tenantId, IntegrationConnectionId id) { return Optional.of(current); }
        public Optional<IntegrationConnection> findEnabledPushSourceByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal) { return Optional.empty(); }
        public Optional<IntegrationConnection> findEnabledScannerSourceByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal) { return Optional.empty(); }
        public Optional<IntegrationConnection> findEnabledSdvDeliveryByTenantId(TenantId tenantId) { return current.enabled() ? Optional.of(current) : Optional.empty(); }
        public boolean existsByTenantIdAndNameIgnoreCase(TenantId tenantId, String name, IntegrationConnectionId excludeId) { return false; }
        public boolean existsByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal, IntegrationConnectionId excludeId) { return false; }
        public void save(IntegrationConnection connection) { current = connection; }
    }
}

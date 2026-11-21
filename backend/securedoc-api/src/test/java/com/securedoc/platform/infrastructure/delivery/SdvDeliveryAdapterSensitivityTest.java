package com.securedoc.platform.infrastructure.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.domain.document.SensitivityLevel;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.integration.*;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Task 0021: the full-case export carries both levels, retries carry the same, and a refused level is LV-DLV-003. */
class SdvDeliveryAdapterSensitivityTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void submissionCarriesTheCaseLevelAndEachUploadItsDocumentLevel() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        SdvDeliveryAdapter adapter = adapter(requests, new ArrayDeque<>(List.of(
                response(201, "{\"submissionId\":\"sdv-sub-1\"}"),
                response(201, "{\"documentId\":\"d1\",\"sourceDocumentId\":\"src-1\"}"),
                response(201, "{\"documentId\":\"d2\",\"sourceDocumentId\":\"src-2\"}"),
                response(200, "{\"status\":\"COMPLETE\"}"))), 0);

        adapter.deliverBatch(batch(SensitivityLevel.CONFIDENTIAL,
                item("src-1", SensitivityLevel.HIGHLY_CONFIDENTIAL), item("src-2", SensitivityLevel.STANDARD)));

        JsonNode submission = mapper.readTree(body(requests.get(0)));
        assertEquals("CONFIDENTIAL", submission.get("recordSensitivityLevel").asText());
        assertFalse(submission.has("sensitivityLevel"), "Vero refuses a document level on the submission (SDV-SEN-006)");
        assertEquals("HIGHLY_CONFIDENTIAL", requests.get(1).headers().firstValue("X-SDV-Sensitivity-Level").orElseThrow());
        assertEquals("STANDARD", requests.get(2).headers().firstValue("X-SDV-Sensitivity-Level").orElseThrow());
        assertTrue(requests.get(1).headers().firstValue("X-SDV-Record-Sensitivity-Level").isEmpty());
    }

    @Test
    void nothingSentIsExportedAsStandard() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        SdvDeliveryAdapter adapter = adapter(requests, new ArrayDeque<>(List.of(
                response(201, "{\"submissionId\":\"sdv-sub-1\"}"),
                response(201, "{\"documentId\":\"d1\",\"sourceDocumentId\":\"src-1\"}"),
                response(200, "{\"status\":\"COMPLETE\"}"))), 0);
        byte[] pdf = "%PDF-test".getBytes();
        var legacyItem = new DeliveryPort.DeliveryItem(DocumentId.generate(), "src-1", "r.pdf",
                () -> new ByteArrayInputStream(pdf), pdf.length, "application/pdf", null, null);
        adapter.deliverBatch(new DeliveryPort.DeliveryBatch(TenantId.generate(), PackageId.generate(), 1, "sub-1", null,
                Map.of(), Map.of(), List.of(legacyItem), DeliveryPort.NOOP_PROGRESS));
        assertEquals("STANDARD", mapper.readTree(body(requests.get(0))).get("recordSensitivityLevel").asText());
        assertEquals("STANDARD", requests.get(1).headers().firstValue("X-SDV-Sensitivity-Level").orElseThrow());
    }

    @Test
    void aRetriedUploadCarriesTheSameLevel() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        SdvDeliveryAdapter adapter = adapter(requests, new ArrayDeque<>(List.of(
                response(201, "{\"submissionId\":\"sdv-sub-1\"}"),
                response(503, "{}"),
                response(201, "{\"documentId\":\"d1\",\"sourceDocumentId\":\"src-1\"}"),
                response(200, "{\"status\":\"COMPLETE\"}"))), 1);
        adapter.deliverBatch(batch(SensitivityLevel.HIGHLY_CONFIDENTIAL, item("src-1", SensitivityLevel.CONFIDENTIAL)));
        assertEquals(4, requests.size());
        assertEquals("CONFIDENTIAL", requests.get(1).headers().firstValue("X-SDV-Sensitivity-Level").orElseThrow());
        assertEquals("CONFIDENTIAL", requests.get(2).headers().firstValue("X-SDV-Sensitivity-Level").orElseThrow());
    }

    @Test
    void aRefusedCaseLevelIsLvDlv003AndNotRetried() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        SdvDeliveryAdapter adapter = adapter(requests, new ArrayDeque<>(List.of(
                response(400, "{\"code\":\"SDV-SEN-002\",\"message\":\"unknown\"}"))), 3);
        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> adapter.deliverBatch(batch(SensitivityLevel.CONFIDENTIAL, item("src-1", SensitivityLevel.STANDARD))));
        assertEquals(TechnicalFailureCode.DELIVERY_SENSITIVITY_REFUSED, failure.failureCode());
        assertEquals("LV-DLV-003", failure.failureCode().publicCode());
        assertFalse(failure.retryable());
        assertEquals(1, requests.size(), "never retried");
    }

    @Test
    void aRefusedDocumentLevelIsLvDlv003AndNotRetried() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        SdvDeliveryAdapter adapter = adapter(requests, new ArrayDeque<>(List.of(
                response(201, "{\"submissionId\":\"sdv-sub-1\"}"),
                response(400, "{\"code\":\"SDV-SEN-001\",\"message\":\"unknown\"}"))), 3);
        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> adapter.deliverBatch(batch(SensitivityLevel.STANDARD, item("src-1", SensitivityLevel.CONFIDENTIAL))));
        assertEquals(TechnicalFailureCode.DELIVERY_SENSITIVITY_REFUSED, failure.failureCode());
        assertFalse(failure.retryable());
        assertEquals(2, requests.size(), "the upload is not retried");
    }

    @Test
    void anotherBadRequestKeepsItsOldCode() throws Exception {
        SdvDeliveryAdapter adapter = adapter(new ArrayList<>(), new ArrayDeque<>(List.of(
                response(400, "{\"code\":\"BAD_REQUEST\",\"message\":\"x\"}"))), 0);
        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> adapter.deliverBatch(batch(SensitivityLevel.STANDARD, item("src-1", SensitivityLevel.STANDARD))));
        assertEquals(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, failure.failureCode());
        assertNull(failure.failureCode().publicCode());
    }

    private SdvDeliveryAdapter adapter(List<HttpRequest> requests, Deque<HttpResponse<String>> responses, int retries) throws Exception {
        HttpClient client = mock(HttpClient.class);
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            requests.add(invocation.getArgument(0));
            return responses.removeFirst();
        });
        return new SdvDeliveryAdapter(new FakeConnections(connection(retries)), mapper, client);
    }

    private DeliveryPort.DeliveryBatch batch(SensitivityLevel caseLevel, DeliveryPort.DeliveryItem... items) {
        return new DeliveryPort.DeliveryBatch(TenantId.generate(), PackageId.generate(), 1, "sub-1", null,
                Map.of(), Map.of(), List.of(items), DeliveryPort.NOOP_PROGRESS, caseLevel);
    }

    private DeliveryPort.DeliveryItem item(String sourceId, SensitivityLevel level) {
        byte[] pdf = "%PDF-test".getBytes();
        return new DeliveryPort.DeliveryItem(DocumentId.generate(), sourceId, "r.pdf",
                () -> new ByteArrayInputStream(pdf), pdf.length, "application/pdf", null, null, level);
    }

    @SuppressWarnings("unchecked")
    private HttpResponse<String> response(int status, String body) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        return response;
    }

    static String body(HttpRequest request) throws Exception {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        CountDownLatch done = new CountDownLatch(1);
        publisher.subscribe(new Flow.Subscriber<>() {
            public void onSubscribe(Flow.Subscription s) { s.request(Long.MAX_VALUE); }
            public void onNext(ByteBuffer b) { byte[] bytes = new byte[b.remaining()]; b.get(bytes); out.writeBytes(bytes); }
            public void onError(Throwable t) { done.countDown(); }
            public void onComplete() { done.countDown(); }
        });
        assertTrue(done.await(5, TimeUnit.SECONDS));
        return out.toString(java.nio.charset.StandardCharsets.UTF_8);
    }

    private IntegrationConnection connection(int retries) {
        return IntegrationConnection.create(TenantId.generate(), "SDV", null,
                IntegrationPurpose.SDV_DELIVERY, IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API,
                IntegrationAuthenticationType.NONE, null, "https://sdv.example.test", null, null,
                5, retries, 24, 10, 10_000_000L, 100,
                IntegrationAcknowledgementMode.SDV_SUBMISSION_ACK, true, Instant.now());
    }

    private static final class FakeConnections implements IntegrationConnectionRepository {
        private IntegrationConnection current;
        private FakeConnections(IntegrationConnection current) { this.current = current; }
        public List<IntegrationConnection> findByTenantId(TenantId tenantId) { return List.of(current); }
        public Optional<IntegrationConnection> findByTenantIdAndId(TenantId tenantId, IntegrationConnectionId id) { return Optional.of(current); }
        public Optional<IntegrationConnection> findEnabledPushSourceByTenantIdAndClientPrincipal(TenantId t, String p) { return Optional.empty(); }
        public Optional<IntegrationConnection> findEnabledScannerSourceByTenantIdAndClientPrincipal(TenantId t, String p) { return Optional.empty(); }
        public Optional<IntegrationConnection> findEnabledSdvDeliveryByTenantId(TenantId tenantId) { return Optional.of(current); }
        public boolean existsByTenantIdAndNameIgnoreCase(TenantId t, String n, IntegrationConnectionId e) { return false; }
        public boolean existsByTenantIdAndClientPrincipal(TenantId t, String p, IntegrationConnectionId e) { return false; }
        public void save(IntegrationConnection connection) { current = connection; }
    }
}

package com.securedoc.platform.infrastructure.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.integration.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Task 0028: a destination's 429 carries its Retry-After to the export job; the local folder has a health check. */
class ExportDestinationAdapterFollowupsTest {
    private final TenantId tenant = TenantId.generate();

    @Test
    void a429IsMarkedWithItsRetryAfterAndKeepsItsCode() throws Exception {
        HttpClient client = client(new ArrayList<>(), new ArrayDeque<>(List.of(response(429, "{}", "120"))));
        SdvDeliveryAdapter adapter = new SdvDeliveryAdapter(new OneConnection(connection(2)), new ObjectMapper(),
                client, true);

        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> adapter.deliverBatch(batch()));

        assertTrue(failure.rateLimited());
        assertEquals(Duration.ofSeconds(120), failure.retryAfter());
        assertEquals(LvErrorCode.DLV_002, LvErrorCodes.of(failure), "the code is unchanged");
        assertTrue(failure.retryable());
    }

    @Test
    void aServerErrorIsNotMarkedAsA429() throws Exception {
        HttpClient client = client(new ArrayList<>(), new ArrayDeque<>(List.of(response(503, "{}", null))));
        SdvDeliveryAdapter adapter = new SdvDeliveryAdapter(new OneConnection(connection(0)), new ObjectMapper(),
                client, true);
        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> adapter.deliverBatch(batch()));
        assertFalse(failure.rateLimited());
    }

    @Test
    void inlineModeStillRetriesA429InTheAdapterAsBefore() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient client = client(requests, new ArrayDeque<>(List.of(response(429, "{}", "1"), response(429, "{}", "1"),
                response(429, "{}", "1"))));
        SdvDeliveryAdapter adapter = new SdvDeliveryAdapter(new OneConnection(connection(2)), new ObjectMapper(),
                client);
        assertThrows(TechnicalDependencyException.class, () -> adapter.deliverBatch(batch()));
        assertEquals(3, requests.size(), "1 + retryMaxAttempts (2), as before");
    }

    @Test
    void retryAfterIsSecondsOrAnHttpDateCappedAtOneHour() {
        Instant now = Instant.parse("2026-11-21T10:00:00Z");
        assertEquals(Duration.ofSeconds(30), RetryAfter.parse("30", now));
        String inTwoMinutes = DateTimeFormatter.RFC_1123_DATE_TIME.format(now.plusSeconds(120).atOffset(ZoneOffset.UTC));
        assertEquals(Duration.ofSeconds(120), RetryAfter.parse(inTwoMinutes, now));
        assertEquals(Duration.ofHours(1), RetryAfter.parse("86400", now), "capped");
        assertNull(RetryAfter.parse(null, now));
        assertNull(RetryAfter.parse("soon", now));
        assertNull(RetryAfter.parse("0", now));
        assertNull(RetryAfter.parse("-5", now));
    }

    @Test
    void theSdvHealthCheckIsTheSafeConnectionTest() throws Exception {
        List<HttpRequest> requests = new ArrayList<>();
        HttpClient client = client(requests, new ArrayDeque<>(List.of(response(200, "{}", null), response(503, "{}", null))));
        SdvDeliveryAdapter adapter = new SdvDeliveryAdapter(new OneConnection(connection(0)), new ObjectMapper(),
                client, true);
        adapter.checkDestination(tenant);
        assertTrue(requests.get(0).uri().toString().endsWith("/api/v1/health"));
        assertEquals("GET", requests.get(0).method());
        assertThrows(TechnicalDependencyException.class, () -> adapter.checkDestination(tenant));
    }

    @Test
    void theLocalFolderCheckWritesAndDeletesATestFileAndFailsWhenItCanNotWrite(@TempDir Path root) throws Exception {
        LocalDeliveryProperties properties = new LocalDeliveryProperties();
        properties.setOutputPath(root.toString());
        LocalFolderDeliveryAdapter adapter = new LocalFolderDeliveryAdapter(properties);

        adapter.checkDestination(tenant);
        Path staging = root.resolve(tenant.value().toString()).resolve(".staging");
        assertTrue(Files.isDirectory(staging));
        try (var left = Files.list(staging)) {
            assertEquals(0, left.count(), "the test file is deleted again");
        }

        // A file where the tenant folder should be: the folder can't be written.
        TenantId blocked = TenantId.generate();
        Files.writeString(root.resolve(blocked.value().toString()), "not a folder");
        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> adapter.checkDestination(blocked));
        assertEquals(LvErrorCode.DLV_001, LvErrorCodes.of(failure));
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
    private static HttpResponse<String> response(int status, String body, String retryAfter) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenReturn(body);
        Map<String, List<String>> headers = retryAfter == null ? Map.of() : Map.of("Retry-After", List.of(retryAfter));
        when(response.headers()).thenReturn(HttpHeaders.of(headers, (a, b) -> true));
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
        return IntegrationConnection.create(tenant, "SDV", null, IntegrationPurpose.SDV_DELIVERY,
                IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.NONE,
                null, "https://sdv.example.test", null, null, 5, retryMaxAttempts, 24, 10, 10_000_000L, 100,
                IntegrationAcknowledgementMode.SDV_SUBMISSION_ACK, true, Instant.now());
    }

    private static final class OneConnection implements IntegrationConnectionRepository {
        private IntegrationConnection current;
        private OneConnection(IntegrationConnection current) { this.current = current; }
        public List<IntegrationConnection> findByTenantId(TenantId tenantId) { return List.of(current); }
        public Optional<IntegrationConnection> findByTenantIdAndId(TenantId tenantId, IntegrationConnectionId id) { return Optional.of(current); }
        public Optional<IntegrationConnection> findEnabledPushSourceByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal) { return Optional.empty(); }
        public Optional<IntegrationConnection> findEnabledScannerSourceByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal) { return Optional.empty(); }
        public Optional<IntegrationConnection> findEnabledSdvDeliveryByTenantId(TenantId tenantId) { return Optional.of(current); }
        public boolean existsByTenantIdAndNameIgnoreCase(TenantId tenantId, String name, IntegrationConnectionId excludeId) { return false; }
        public boolean existsByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal, IntegrationConnectionId excludeId) { return false; }
        public void save(IntegrationConnection connection) { current = connection; }
    }
}

package com.securedoc.platform.application.integration;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.intake.polling.PollingSourceException;
import com.securedoc.platform.application.integration.IntegrationConnectionService.ConnectionInput;
import com.securedoc.platform.application.port.out.DerivedDestinationDeliveryPort;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.port.out.PollingSourcePort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditOutcome;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.integration.*;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** The owner's conditions for testing before saving (U008 answer 1). */
class DraftConnectionTestServiceTest {
    private static final String STORED_REF = "env:LEXA_TEST_STORE_KEY_REF";
    private static final String SECRET_VALUE = "s3cr3t-key-value";
    private static final String REMOTE_BODY = "<html>remote body Set-Cookie: session=abc</html>";

    private final TenantId entityA = TenantId.generate();
    private final TenantId entityB = TenantId.generate();
    private final ActorId actor = ActorId.generate();
    private final CorrelationId correlation = CorrelationId.generate();
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-18T10:00:00Z"), ZoneOffset.UTC);
    private final FakeRepository repository = new FakeRepository();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final AuditService auditService = new AuditService(audits::add);
    // Hardening: secret references limited to approved names; this test's own approved name (the app's rule is tested in
    // securedoc-api).
    private final IntegrationConnectionService connections = new IntegrationConnectionService(repository, auditService, clock)
            .withSecretReferences((entity, reference) -> {
                if (!reference.equals(STORED_REF)) throw new IllegalArgumentException("refused");
            });
    private final List<IntegrationConnection> storeProbes = new ArrayList<>();
    private RuntimeException storeFailure;
    private final FakePolling polling = new FakePolling();
    private final FakeDerived derived = new FakeDerived();
    private final Map<String, String> dns = new HashMap<>(Map.of(
            "store.example.test", "203.0.113.10",
            "title.example.test", "203.0.113.11",
            "metadata-alias.example.test", "169.254.169.254",
            "v6-metadata.example.test", "fd00:ec2::254"));

    private final DraftConnectionTestService service = new DraftConnectionTestService(connections,
            c -> { storeProbes.add(c); if (storeFailure != null) throw storeFailure; },
            polling, derived, new OutboundAddressGuard(this::resolve), auditService, clock);

    @Test
    void storedKeyIsReusedOnlyForAConnectionOfTheCallersOwnEntity() {
        IntegrationConnection ofB = connections.create(entityB, actor, correlation, store("Store B", "https://store.example.test", STORED_REF, true));
        audits.clear();

        assertThrows(NotFoundException.class,
                () -> service.test(entityA, ofB.id(), actor, correlation, store("Store B", "https://store.example.test", null, true)));
        assertTrue(storeProbes.isEmpty(), "another entity's connection is never probed");
        assertTrue(audits.isEmpty());

        IntegrationConnection ofA = connections.create(entityA, actor, correlation, store("Store A", "https://store.example.test", STORED_REF, true));
        audits.clear();
        var result = service.test(entityA, ofA.id(), actor, correlation, store("Store A", "https://store.example.test/v2", null, true));

        assertTrue(result.works());
        assertNull(result.reasonCode());
        assertEquals(STORED_REF, storeProbes.get(0).secretReference(), "an empty key reference reuses the stored key");
        assertEquals("https://store.example.test/v2", storeProbes.get(0).endpoint(), "the changed settings are what is tested");
    }

    @Test
    void savesNothingAndLeavesHealthAsItWas() {
        IntegrationConnection saved = connections.create(entityA, actor, correlation, store("Store A", "https://store.example.test", STORED_REF, true));
        storeFailure = new TechnicalDependencyException(TechnicalDependency.DELIVERY, TechnicalFailureCode.DELIVERY_IO, true, "The document store is unavailable");

        service.test(entityA, saved.id(), actor, correlation, store("Renamed", "https://store.example.test", null, true));
        service.test(entityA, null, actor, correlation, store("New store", "https://store.example.test", STORED_REF, false));

        assertEquals(List.of(saved), repository.all(), "nothing new stored; the stored one, its health included, unchanged");
        assertEquals(2, storeProbes.size());
    }

    @Test
    void answerAndAuditCarryNoSecretNoRemoteTextAndAreMarkedDraft() {
        IntegrationConnection saved = connections.create(entityA, actor, correlation, store("Store A", "https://store.example.test", STORED_REF, true));
        audits.clear();
        storeFailure = new TechnicalDependencyException(TechnicalDependency.DELIVERY, TechnicalFailureCode.DELIVERY_DESTINATION_INVALID,
                false, "rejected " + SECRET_VALUE + " " + REMOTE_BODY);

        var result = service.test(entityA, saved.id(), actor, correlation, store("Store A", "https://store.example.test", null, true));

        assertFalse(result.works());
        assertEquals("DELIVERY_DESTINATION_INVALID", result.reasonCode());
        assertNoLeak(result.toString());
        assertEquals(1, audits.size());
        AuditRecord audit = audits.get(0);
        assertEquals(AuditAction.INTEGRATION_CONNECTION_TESTED, audit.action());
        assertEquals(AuditOutcome.FAILURE, audit.outcome());
        assertEquals(saved.id().toString(), audit.resourceId());
        assertTrue(audit.detail().contains("draft=true"), audit.detail());
        assertNoLeak(audit.detail());
        assertFalse(audit.detail().contains("store.example.test"), "no address in the audit line");
    }

    @Test
    void anythingButAFixedCodeBecomesTheGenericCode() {
        storeFailure = new IllegalStateException("boom " + SECRET_VALUE + " " + REMOTE_BODY);
        var generic = service.test(entityA, null, actor, correlation, store("Store A", "https://store.example.test", STORED_REF, false));
        assertEquals(DraftConnectionTestService.CONNECTION_TEST_FAILED, generic.reasonCode());
        assertNoLeak(generic.toString());

        polling.failure = new PollingSourceException("HTTP 401 " + REMOTE_BODY, "detail " + SECRET_VALUE);
        var poll = service.test(entityA, null, actor, correlation, poll("Collect", "https://title.example.test"));
        assertEquals(DraftConnectionTestService.CONNECTION_TEST_FAILED, poll.reasonCode());

        polling.failure = new PollingSourceException("POLL_AUTH_CONFIGURATION_ERROR", "detail " + SECRET_VALUE);
        assertEquals("POLL_AUTH_CONFIGURATION_ERROR",
                service.test(entityA, null, actor, correlation, poll("Collect", "https://title.example.test")).reasonCode());
        assertTrue(audits.stream().allMatch(a -> !a.detail().contains(SECRET_VALUE) && !a.detail().contains("remote body")));
        assertEquals(List.of(1, 1), polling.calls, "polling tests only ask for the list (limit 1), never collect");
    }

    @Test
    void cloudMetadataAddressesAreAlwaysRefusedWithoutAnyCall() {
        for (String address : List.of("http://169.254.169.254/latest/meta-data", "http://metadata.google.internal/computeMetadata/v1",
                "http://METADATA.google.internal./", "http://metadata-alias.example.test/", "http://[fe80::1]/",
                "http://[fd00:ec2::254]/", "http://v6-metadata.example.test/", "http://100.100.100.200/")) {
            var storeResult = service.test(entityA, null, actor, correlation, store("Store", address, STORED_REF, false));
            assertEquals(DraftConnectionTestService.ADDRESS_NOT_ALLOWED, storeResult.reasonCode(), address);
            var derivedResult = service.test(entityA, null, actor, correlation, derivedWeb("Title", address));
            assertEquals(DraftConnectionTestService.ADDRESS_NOT_ALLOWED, derivedResult.reasonCode(), address);
            var pollResult = service.test(entityA, null, actor, correlation, poll("Collect", address));
            assertEquals(DraftConnectionTestService.ADDRESS_NOT_ALLOWED, pollResult.reasonCode(), address);
        }
        assertEquals(DraftConnectionTestService.ADDRESS_NOT_RESOLVED,
                service.test(entityA, null, actor, correlation, store("Store", "https://nowhere.example.test", STORED_REF, false)).reasonCode());
        assertTrue(storeProbes.isEmpty());
        assertTrue(polling.calls.isEmpty());
        assertTrue(derived.calls.isEmpty());
    }

    @Test
    void theAddressGoesThroughTheSameChecksAsSave() {
        connections.create(entityA, actor, correlation, store("Taken", "https://store.example.test", STORED_REF, false));
        ConnectionInput noAddress = store("Store", null, STORED_REF, false);
        ConnectionInput duplicate = store("taken", "https://store.example.test", STORED_REF, false);

        for (ConnectionInput input : List.of(noAddress, duplicate)) {
            ValidationException save = assertThrows(ValidationException.class, () -> connections.create(entityA, actor, correlation, input));
            ValidationException test = assertThrows(ValidationException.class, () -> service.test(entityA, null, actor, correlation, input));
            assertEquals(save.getMessage(), test.getMessage());
        }
        assertTrue(storeProbes.isEmpty());
    }

    @Test
    void aSystemThatSendsToLexaVaultHasNothingToTest() {
        ConnectionInput push = new ConnectionInput("Push", null, IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND,
                IntegrationProviderType.PUSH_API, IntegrationAuthenticationType.NONE, null, null, null, null, false,
                60, 2, 24, 50, 262144000L, 500, IntegrationAcknowledgementMode.NONE, false);
        assertThrows(ValidationException.class, () -> service.test(entityA, null, actor, correlation, push));
    }

    @Test
    void aWorkingBarcodeDestinationAnswersWorksAndIsAuditedAsDraft() {
        var result = service.test(entityA, null, actor, correlation, derivedWeb("Title", "https://title.example.test/in"));
        assertTrue(result.works());
        assertEquals(1, derived.calls.size());
        assertEquals("draft", audits.get(audits.size() - 1).resourceId());
        assertEquals(AuditOutcome.SUCCESS, audits.get(audits.size() - 1).outcome());
    }

    private void assertNoLeak(String text) {
        assertFalse(text.contains(SECRET_VALUE), text);
        assertFalse(text.contains(STORED_REF), text);
        assertFalse(text.contains("remote body"), text);
        assertFalse(text.contains("Set-Cookie"), text);
    }

    private InetAddress[] resolve(String host) throws UnknownHostException {
        String literal = dns.get(host);
        if (literal == null && (host.matches("[0-9.]+") || host.contains(":"))) literal = host;
        if (literal == null) throw new UnknownHostException(host);
        return new InetAddress[]{InetAddress.getByName(literal)};
    }

    private static ConnectionInput store(String name, String endpoint, String secretRef, boolean preserve) {
        return new ConnectionInput(name, null, IntegrationPurpose.DOCUMENT_STORE_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.API_KEY, null, endpoint, null, secretRef, preserve,
                30, 2, 24, 50, 262144000L, 500, IntegrationAcknowledgementMode.SUBMISSION_ACK, false);
    }

    private static ConnectionInput derivedWeb(String name, String endpoint) {
        return new ConnectionInput(name, null, IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.NONE, null, endpoint, null, null, false,
                30, 2, 24, 50, 262144000L, 500, IntegrationAcknowledgementMode.DESTINATION_RECEIPT, false);
    }

    private static ConnectionInput poll(String name, String endpoint) {
        return new ConnectionInput(name, null, IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.NONE, null, endpoint, null, null, false,
                30, 2, 24, 50, 262144000L, 500, IntegrationPollingScheduleType.INTERVAL, 15, null, null, 100,
                IntegrationAcknowledgementMode.SOURCE_PICKUP_ACK, false);
    }

    private static final class FakePolling implements PollingSourcePort {
        final List<Integer> calls = new ArrayList<>();
        RuntimeException failure;
        @Override public List<com.securedoc.platform.application.intake.polling.PollingSourcePackage> discoverAvailable(IntegrationConnection c, int limit) {
            calls.add(limit);
            if (failure != null) throw failure;
            return List.of();
        }
        @Override public com.securedoc.platform.application.intake.polling.RetrievedPollingDocument retrieveDocument(IntegrationConnection c,
                com.securedoc.platform.application.intake.polling.PollingSourcePackage p, com.securedoc.platform.application.intake.polling.PollingSourceDocument d) {
            throw new AssertionError("a test never collects a document");
        }
        @Override public String acknowledgeReceived(IntegrationConnection c, com.securedoc.platform.application.intake.polling.PollingSourcePackage p, int attempt) {
            throw new AssertionError("a test never confirms pickup");
        }
    }

    private static final class FakeDerived implements DerivedDestinationDeliveryPort {
        final List<IntegrationConnection> calls = new ArrayList<>();
        @Override public DeliveryReceipt deliver(IntegrationConnection c, DeliveryRequest request) {
            throw new AssertionError("a test never sends a customer document");
        }
        @Override public void testConnection(IntegrationConnection c) { calls.add(c); }
    }

    private static final class FakeRepository implements IntegrationConnectionRepository {
        private final Map<UUID, IntegrationConnection> values = new LinkedHashMap<>();
        List<IntegrationConnection> all() { return List.copyOf(values.values()); }
        @Override public List<IntegrationConnection> findByTenantId(TenantId tenantId) {
            return values.values().stream().filter(v -> v.tenantId().equals(tenantId)).toList();
        }
        @Override public Optional<IntegrationConnection> findByTenantIdAndId(TenantId tenantId, IntegrationConnectionId id) {
            return Optional.ofNullable(values.get(id.value())).filter(v -> v.tenantId().equals(tenantId));
        }
        @Override public boolean existsByTenantIdAndNameIgnoreCase(TenantId tenantId, String name, IntegrationConnectionId excludeId) {
            return values.values().stream().anyMatch(v -> v.tenantId().equals(tenantId)
                    && (excludeId == null || !v.id().equals(excludeId)) && v.name().equalsIgnoreCase(name));
        }
        @Override public Optional<IntegrationConnection> findEnabledPushSourceByTenantIdAndClientPrincipal(TenantId t, String p) { return Optional.empty(); }
        @Override public Optional<IntegrationConnection> findEnabledScannerSourceByTenantIdAndClientPrincipal(TenantId t, String p) { return Optional.empty(); }
        @Override public boolean existsByTenantIdAndClientPrincipal(TenantId t, String p, IntegrationConnectionId e) { return false; }
        @Override public void save(IntegrationConnection connection) { values.put(connection.id().value(), connection); }
    }
}

package com.securedoc.platform.application.intake.polling;

import com.securedoc.platform.application.integration.IntegrationConnectionService;
import com.securedoc.platform.application.intake.command.UploadContent;
import com.securedoc.platform.application.intake.external.*;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.integration.*;
import com.securedoc.platform.domain.intake.ExternalIntakeStatus;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.io.ByteArrayInputStream;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PollingIntakeServiceBehaviorTest {
    private final TenantId tenant = TenantId.generate();
    private final IntegrationConnection source = pollingSource();
    private final IntegrationConnectionService connections = mock(IntegrationConnectionService.class);
    private final ExternalIntakeService external = mock(ExternalIntakeService.class);
    private final PollingSourcePort sourcePort = mock(PollingSourcePort.class);
    private final AuditService audit = mock(AuditService.class);
    private final FakeRuns runs = new FakeRuns();
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-03T20:00:00Z"), ZoneOffset.UTC);
    private final PollingIntakeService service = new PollingIntakeService(connections, runs, sourcePort, external, audit, clock);

    @Test
    void partialPackagePreservesSuccessfulRetrievalCountAndDoesNotAck() {
        when(connections.get(tenant, source.id())).thenReturn(source);
        when(connections.recordOperationalFailure(any(), anyString())).thenReturn(source);
        var pkg = packageWith("DOC-A", "DOC-B");
        when(sourcePort.discoverAvailable(source, source.pollingBatchLimit())).thenReturn(List.of(pkg));
        UUID packageId = UUID.randomUUID();
        when(external.createPollingIntake(any(), eq(source), any(), any())).thenReturn(snapshot(packageId,
                ExternalIntakeStatus.AWAITING_DOCUMENTS, 0, List.of("DOC-A", "DOC-B")));
        when(sourcePort.retrieveDocument(eq(source), eq(pkg), argThat(d -> d.externalSourceDocumentId().equals("DOC-A"))))
                .thenReturn(retrieved("a"));
        when(external.receivePollingDocument(eq(PackageId.of(packageId)), eq("DOC-A"), anyString(), anyString(), eq("application/pdf"), any(), eq(source), any(), any()))
                .thenReturn(receipt(packageId, "DOC-A", ExternalIntakeStatus.AWAITING_DOCUMENTS, 1, List.of("DOC-B")));
        when(sourcePort.retrieveDocument(eq(source), eq(pkg), argThat(d -> d.externalSourceDocumentId().equals("DOC-B"))))
                .thenThrow(new PollingSourceException("RETRIEVAL_FAILED", "source unavailable"));

        PollingRunSnapshot result = service.runNow(tenant, source.id(), ActorId.generate()).orElseThrow();

        assertEquals(PollingRunStatus.PARTIAL, result.status());
        assertEquals(2, result.documentsExpected());
        assertEquals(1, result.documentsRetrieved(), "successful document must still be counted when a later document fails");
        assertEquals(0, result.packagesAcknowledged());
        assertEquals("RETRIEVAL_FAILED", result.failureCategory());
        verify(sourcePort, never()).acknowledgeReceived(any(), any(), anyInt());
    }

    /** Task 0021: a polling source's levels reach the shared intake rule as sent (it reads, never refuses, them). */
    @Test
    void pollingPassesTheSourceLevelsOnAsSent() {
        when(connections.get(tenant, source.id())).thenReturn(source);
        when(connections.recordOperationalFailure(any(), anyString())).thenReturn(source);
        when(connections.recordOperationalSuccess(any())).thenReturn(source);
        var pkg = new PollingSourcePackage("SRC-1", "1", false, Map.of("contractNumber", "C-1"), Map.of(),
                List.of(new PollingSourceDocument("DOC-A", "a.pdf", "a".repeat(64), "HIGHLY_CONFIDENTIAL"),
                        new PollingSourceDocument("DOC-B", "b.pdf", "a".repeat(64))), "CONFIDENTIAL");
        when(sourcePort.discoverAvailable(source, source.pollingBatchLimit())).thenReturn(List.of(pkg));
        when(external.createPollingIntake(any(), eq(source), any(), any())).thenReturn(snapshot(UUID.randomUUID(),
                ExternalIntakeStatus.INCOMPLETE_EXPIRED, 0, List.of()));

        service.runNow(tenant, source.id(), ActorId.generate());

        var input = org.mockito.ArgumentCaptor.forClass(PushIntakeCreateInput.class);
        verify(external).createPollingIntake(input.capture(), eq(source), any(), any());
        assertEquals("CONFIDENTIAL", input.getValue().caseSensitivityLevel());
        assertEquals("HIGHLY_CONFIDENTIAL", input.getValue().documents().get(0).sensitivityLevel());
        assertNull(input.getValue().documents().get(1).sensitivityLevel(), "nothing sent");
    }

    @Test
    void sourceAckOccursOnlyAfterFinalStatusConfirmsDurableAcceptance() {
        when(connections.get(tenant, source.id())).thenReturn(source);
        when(connections.recordOperationalSuccess(any())).thenReturn(source);
        var pkg = packageWith("DOC-A");
        when(sourcePort.discoverAvailable(source, source.pollingBatchLimit())).thenReturn(List.of(pkg));
        UUID packageId = UUID.randomUUID();
        when(external.createPollingIntake(any(), eq(source), any(), any())).thenReturn(snapshot(packageId,
                ExternalIntakeStatus.AWAITING_DOCUMENTS, 0, List.of("DOC-A")));
        when(sourcePort.retrieveDocument(eq(source), eq(pkg), any())).thenReturn(retrieved("a"));
        when(external.receivePollingDocument(eq(PackageId.of(packageId)), eq("DOC-A"), anyString(), anyString(), eq("application/pdf"), any(), eq(source), any(), any()))
                .thenReturn(receipt(packageId, "DOC-A", ExternalIntakeStatus.ACCEPTED_FOR_PROCESSING, 1, List.of()));
        when(external.pollingStatus(eq(PackageId.of(packageId)), eq(source), any(), any())).thenReturn(snapshot(packageId,
                ExternalIntakeStatus.ACCEPTED_FOR_PROCESSING, 1, List.of()));
        when(sourcePort.acknowledgeReceived(source, pkg, 1)).thenReturn("ACK-1");

        PollingRunSnapshot result = service.runNow(tenant, source.id(), ActorId.generate()).orElseThrow();
        assertEquals(PollingRunStatus.SUCCESS, result.status());
        assertEquals(1, result.documentsRetrieved());
        assertEquals(1, result.packagesAcknowledged());

        InOrder order = inOrder(external, sourcePort);
        order.verify(external).receivePollingDocument(eq(PackageId.of(packageId)), eq("DOC-A"), anyString(), anyString(),
                eq("application/pdf"), any(), eq(source), any(), any());
        order.verify(external).pollingStatus(eq(PackageId.of(packageId)), eq(source), any(), any());
        order.verify(sourcePort).acknowledgeReceived(source, pkg, 1);
    }

    /* Task 0018 A6: a scheduled run's records are named "Polling: <connection name>", and only during the run. */

    private final List<com.securedoc.platform.domain.audit.AuditRecord> records = new ArrayList<>();
    private final com.securedoc.platform.application.audit.RequestActorDisplayName names =
            new com.securedoc.platform.application.audit.RequestActorDisplayName();
    private final AuditService realAudit = new AuditService(records::add, names);
    private final PollingIntakeService namedService =
            new PollingIntakeService(connections, runs, sourcePort, external, realAudit, clock);

    @Test
    void scheduledRunRecordsAreNamedAfterTheConnectionAndTheNameIsClearedAfterwards() {
        when(connections.get(tenant, source.id())).thenReturn(source);
        when(connections.recordOperationalSuccess(any())).thenReturn(source);
        when(sourcePort.discoverAvailable(source, source.pollingBatchLimit())).thenReturn(List.of());

        namedService.runScheduled(tenant, source.id()).orElseThrow();

        assertEquals(List.of(com.securedoc.platform.domain.audit.AuditAction.POLLING_RUN_STARTED,
                        com.securedoc.platform.domain.audit.AuditAction.POLLING_RUN_COMPLETED),
                records.stream().map(com.securedoc.platform.domain.audit.AuditRecord::action).toList());
        records.forEach(r -> assertEquals("Polling: Polling", r.actorDisplayName(), r.action().name()));

        // A later record on the same thread, by the same polling actor, gets no name.
        ActorId pollingActor = records.get(0).actorId();
        realAudit.recordSuccess(tenant, pollingActor, com.securedoc.platform.domain.audit.AuditAction.PLATFORM_HEALTH_CHECKED,
                "Platform", "x", CorrelationId.generate(), "later");
        assertNull(records.get(records.size() - 1).actorDisplayName());
        assertTrue(names.displayNameFor(pollingActor).isEmpty());
    }

    @Test
    void aFailedScheduledRunIsNamedAndStillClearsTheName() {
        when(connections.get(tenant, source.id())).thenReturn(source);
        when(connections.recordOperationalFailure(any(), anyString())).thenReturn(source);
        when(sourcePort.discoverAvailable(source, source.pollingBatchLimit()))
                .thenThrow(new PollingSourceException("SOURCE_UNAVAILABLE", "down"));

        assertEquals(PollingRunStatus.FAILED, namedService.runScheduled(tenant, source.id()).orElseThrow().status());
        var failed = records.stream()
                .filter(r -> r.action() == com.securedoc.platform.domain.audit.AuditAction.POLLING_RUN_FAILED)
                .findFirst().orElseThrow();
        assertEquals("Polling: Polling", failed.actorDisplayName());
        assertTrue(names.displayNameFor(failed.actorId()).isEmpty(), "never left bound on the thread");
    }

    @Test
    void anExceptionOutOfTheRunStillClearsTheName() {
        when(connections.get(tenant, source.id())).thenReturn(source);
        // The repository fails before the run's own error handling: the binding is still cleared.
        PollingRunRepository failingRuns = mock(PollingRunRepository.class);
        when(failingRuns.tryStart(any(), any(), any(), any(), any())).thenThrow(new IllegalStateException("db down"));
        PollingIntakeService failing = new PollingIntakeService(connections, failingRuns, sourcePort, external, realAudit, clock);
        assertThrows(IllegalStateException.class, () -> failing.runScheduled(tenant, source.id()));
        UUID pollingActor = UUID.nameUUIDFromBytes(("lexavault-polling:" + tenant + ":" + source.id())
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertTrue(names.displayNameFor(ActorId.of(pollingActor)).isEmpty());
    }

    @Test
    void aManualRunKeepsThePersonsOwnName() {
        when(connections.get(tenant, source.id())).thenReturn(source);
        when(connections.recordOperationalSuccess(any())).thenReturn(source);
        when(sourcePort.discoverAvailable(source, source.pollingBatchLimit())).thenReturn(List.of());
        ActorId person = ActorId.generate();
        names.bind(person, "Pat Example", "pat");
        try {
            namedService.runNow(tenant, source.id(), person).orElseThrow();
            records.forEach(r -> assertEquals("Pat Example", r.actorDisplayName()));
        } finally {
            names.clear();
        }
    }

    /* Task 0023 B1: every failure of a run is kept as a code and an exception type, never its message. */

    @Test
    void everyFailedPackageIsKeptAsCodeAndTypeAndThePartialRunIsNotASuccess() {
        when(connections.get(tenant, source.id())).thenReturn(source);
        when(connections.recordOperationalFailure(any(), anyString())).thenReturn(source);
        var first = new PollingSourcePackage("SRC-SECRET-1", "1", false, Map.of("contractNumber", "C-1"), Map.of(),
                List.of(new PollingSourceDocument("DOC-A", "Jane Doe payslip.pdf", "a".repeat(64))));
        var second = new PollingSourcePackage("SRC-SECRET-2", "1", false, Map.of("contractNumber", "C-2"), Map.of(),
                List.of(new PollingSourceDocument("DOC-B", "b.pdf", "a".repeat(64))));
        var third = new PollingSourcePackage("SRC-OK-3", "1", false, Map.of("contractNumber", "C-3"), Map.of(),
                List.of(new PollingSourceDocument("DOC-C", "c.pdf", "a".repeat(64))));
        when(sourcePort.discoverAvailable(source, source.pollingBatchLimit())).thenReturn(List.of(first, second, third));
        when(external.createPollingIntake(argThat(i -> i != null && i.externalSourcePackageId().equals("SRC-SECRET-1")), eq(source), any(), any()))
                .thenThrow(new com.securedoc.platform.application.exception.IntakeValidationException(
                        com.securedoc.platform.application.intake.model.IntakeRejectionCode.UNKNOWN_IDENTIFIER,
                        "Unknown business identifier: secret-key for Jane Doe"));
        when(external.createPollingIntake(argThat(i -> i != null && i.externalSourcePackageId().equals("SRC-SECRET-2")), eq(source), any(), any()))
                .thenThrow(new com.securedoc.platform.application.exception.IntakeConflictException("SOURCE_REVISION_REUSED",
                        "Package SRC-SECRET-2 already exists"));
        when(external.createPollingIntake(argThat(i -> i != null && i.externalSourcePackageId().equals("SRC-OK-3")), eq(source), any(), any()))
                .thenReturn(snapshot(UUID.randomUUID(), ExternalIntakeStatus.INCOMPLETE_EXPIRED, 0, List.of()));

        PollingRunSnapshot result = namedService.runNow(tenant, source.id(), ActorId.generate()).orElseThrow();

        assertEquals(PollingRunStatus.PARTIAL, result.status());
        assertEquals("INTAKE_UNKNOWN_IDENTIFIER", result.failureCategory(), "the stable intake code, not a class name");
        assertEquals("#1 INTAKE_UNKNOWN_IDENTIFIER:IntakeValidationException;#2 SOURCE_REVISION_REUSED:IntakeConflictException",
                result.failureDetail(), "both failures are kept, not only the last");
        var packageAudits = records.stream()
                .filter(r -> r.action() == com.securedoc.platform.domain.audit.AuditAction.POLLING_PACKAGE_FAILED).toList();
        assertEquals(2, packageAudits.size(), "one audit row per failed package");
        assertEquals("code=LV-INT-014, detail=INTAKE_UNKNOWN_IDENTIFIER, package=#1,exceptionType=IntakeValidationException", packageAudits.get(0).detail());
        assertEquals("code=LV-INT-018, detail=SOURCE_REVISION_REUSED, package=#2,exceptionType=IntakeConflictException", packageAudits.get(1).detail());
        var completed = records.stream()
                .filter(r -> r.action() == com.securedoc.platform.domain.audit.AuditAction.POLLING_RUN_COMPLETED).findFirst().orElseThrow();
        assertEquals(com.securedoc.platform.domain.audit.AuditOutcome.FAILURE, completed.outcome(), "a partly failed run is not SUCCESS");
        assertTrue(completed.detail().contains("status=PARTIAL") && completed.detail().contains("failedPackages=2"));
        for (String leaked : List.of("secret-key", "Jane Doe", "SRC-SECRET", "payslip")) {
            assertFalse(result.failureDetail().contains(leaked), leaked);
            records.forEach(r -> assertFalse(r.detail() != null && r.detail().contains(leaked), r.action() + " " + leaked));
        }
        verify(connections).recordOperationalFailure(source, "INTAKE_UNKNOWN_IDENTIFIER");
    }

    @Test
    void aFailedRunKeepsOnlyTheCodeAndTheExceptionType() {
        when(connections.get(tenant, source.id())).thenReturn(source);
        when(connections.recordOperationalFailure(any(), anyString())).thenReturn(source);
        when(sourcePort.discoverAvailable(source, source.pollingBatchLimit()))
                .thenThrow(new IllegalStateException("Could not reach https://user:secret@source.example.test/Jane-Doe"));

        PollingRunSnapshot result = namedService.runNow(tenant, source.id(), ActorId.generate()).orElseThrow();

        assertEquals(PollingRunStatus.FAILED, result.status());
        assertEquals("INVALID_STATE", result.failureCategory());
        assertEquals("INVALID_STATE:IllegalStateException", result.failureDetail());
        var failed = records.stream()
                .filter(r -> r.action() == com.securedoc.platform.domain.audit.AuditAction.POLLING_RUN_FAILED).findFirst().orElseThrow();
        assertEquals("code=LV-SYS-001, detail=INVALID_STATE, exceptionType=IllegalStateException", failed.detail());
    }

    @Test
    void aSourceFailureKeepsItsOwnCategory() {
        when(connections.get(tenant, source.id())).thenReturn(source);
        when(connections.recordOperationalFailure(any(), anyString())).thenReturn(source);
        when(sourcePort.discoverAvailable(source, source.pollingBatchLimit()))
                .thenThrow(new PollingSourceException("SOURCE_UNAVAILABLE", "host source.example.test refused"));
        PollingRunSnapshot result = service.runNow(tenant, source.id(), ActorId.generate()).orElseThrow();
        assertEquals("SOURCE_UNAVAILABLE", result.failureCategory());
        assertEquals("SOURCE_UNAVAILABLE:PollingSourceException", result.failureDetail());
    }

    @Test
    void theFailureListStaysWithinTheStoredLength() {
        List<PollingIntakeService.PackageFailure> many = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            many.add(new PollingIntakeService.PackageFailure(i,
                    new com.securedoc.platform.application.intake.model.IntakeFailure("INTAKE_UNKNOWN_IDENTIFIER", "IntakeValidationException")));
        }
        String detail = PollingIntakeService.failureDetail(many);
        assertTrue(detail.length() <= PollingIntakeService.MAX_FAILURE_DETAIL, "length " + detail.length());
        assertTrue(detail.startsWith("#1 INTAKE_UNKNOWN_IDENTIFIER:IntakeValidationException;#2 "));
        assertTrue(detail.matches(".*;\\+\\d+ more$"), detail);
    }

    private IntegrationConnection pollingSource() {
        return IntegrationConnection.create(tenant, "Polling", null,
                IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API,
                IntegrationAuthenticationType.NONE, null, "https://source.example.test", null, null,
                30, 0, 24, 10, 10_000_000L, 100,
                IntegrationPollingScheduleType.INTERVAL, 60, null, null, 10,
                IntegrationAcknowledgementMode.SOURCE_PICKUP_ACK, true, Instant.parse("2026-09-03T19:00:00Z"));
    }

    private PollingSourcePackage packageWith(String... ids) {
        return new PollingSourcePackage("SRC-1", "1", false, Map.of("contractNumber", "C-1"), Map.of(),
                Arrays.stream(ids).map(id -> new PollingSourceDocument(id, id + ".pdf", "a".repeat(64))).toList());
    }

    private RetrievedPollingDocument retrieved(String value) {
        byte[] bytes = value.getBytes();
        UploadContent content = new UploadContent() {
            public java.io.InputStream openStream() { return new ByteArrayInputStream(bytes); }
            public long contentLength() { return bytes.length; }
        };
        return new RetrievedPollingDocument("application/pdf", "a".repeat(64), content, () -> {});
    }

    private ExternalIntakeSnapshot snapshot(UUID packageId, ExternalIntakeStatus status, int received, List<String> missing) {
        return new ExternalIntakeSnapshot(packageId, "SRC-1", "1", 1, status,
                status == ExternalIntakeStatus.ACCEPTED_FOR_PROCESSING ? PackageStatus.SUBMITTED : PackageStatus.CREATED,
                received + missing.size(), received, missing, clock.instant(), clock.instant().plus(Duration.ofHours(24)),
                status == ExternalIntakeStatus.ACCEPTED_FOR_PROCESSING ? clock.instant() : null, null);
    }

    private ExternalDocumentReceipt receipt(UUID packageId, String externalId, ExternalIntakeStatus status,
                                            int received, List<String> missing) {
        return new ExternalDocumentReceipt(packageId, UUID.randomUUID(), externalId, "a".repeat(64), false,
                status, received + missing.size(), received, missing, clock.instant().plus(Duration.ofHours(24)));
    }

    private static final class FakeRuns implements PollingRunRepository {
        private PollingRun latest;
        public Optional<PollingRun> tryStart(TenantId tenantId, IntegrationConnectionId connectionId,
                                             PollingRunTrigger trigger, Instant now, Instant staleBefore) {
            latest = PollingRun.start(tenantId, connectionId, trigger, now);
            return Optional.of(latest);
        }
        public void save(PollingRun run) { latest = run; }
        public Optional<PollingRun> findLatest(IntegrationConnectionId connectionId) { return Optional.ofNullable(latest); }
        public List<PollingRun> findRecent(TenantId tenantId, IntegrationConnectionId connectionId, int limit) {
            return latest == null ? List.of() : List.of(latest);
        }
    }
}

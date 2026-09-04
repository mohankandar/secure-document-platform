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

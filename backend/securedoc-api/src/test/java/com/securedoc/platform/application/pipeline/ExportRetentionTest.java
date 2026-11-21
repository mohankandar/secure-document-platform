package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.lifecycle.command.PurgeDueRetentionCommand;
import com.securedoc.platform.application.lifecycle.service.ExportRetentionService;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DerivedDeliveryRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.ExportRetentionRepository;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.port.out.PackageDashboardVisibilityPort;
import com.securedoc.platform.application.port.out.PackageExportRecordRepository;
import com.securedoc.platform.application.port.out.PackageLifecycleRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.SplitCopyRetentionPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TenantLifecyclePolicyRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.technical.service.ProcessingIncidentService;
import com.securedoc.platform.application.telemetry.ProductTelemetryService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.IntegrationConnection;
import com.securedoc.platform.domain.integration.IntegrationPurpose;
import com.securedoc.platform.domain.lifecycle.PackageExportRecord;
import com.securedoc.platform.domain.lifecycle.PackageLifecycle;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import com.securedoc.platform.domain.routing.DerivedDelivery;
import com.securedoc.platform.domain.routing.DerivedDeliveryStatus;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Task 0028: retention per export destination. Each copy's clock starts at its own destination's receipt; nothing is
 * deleted while its export job is open; the original stays while any export job of the case is open.
 */
class ExportRetentionTest {

    // ------------------------------------------------------------------ the clocks

    @Test
    void aSplitCopyIsKeptForItsOwnPeriodFromItsReceiptIndependentlyOfTheMasterCopyAndTheOriginal() {
        World w = new World();
        w.store.setSplit(w.tenant, w.splitDestination, 60, ActorId.generate());
        w.exportMaster();
        Instant t0 = w.lifecycle().lastExportedAt().orElseThrow();
        w.splitDelivered(t0.plus(Duration.ofHours(1)));

        assertEquals(Duration.ofHours(24), Duration.between(t0, w.lifecycle().redactedDeleteAt().orElseThrow()),
                "the master copy follows the master period (default: the redacted-copy retention)");

        w.purge(t0.plus(Duration.ofHours(24)));
        assertEquals(ArtifactStatus.DELETED, w.masterCopy.status(), "master copy: 24 h from the master's receipt");
        assertEquals(ArtifactStatus.AVAILABLE, w.splitCopy.status(), "split copy: its own clock");

        w.purge(t0.plus(Duration.ofHours(48)));
        assertEquals(ArtifactStatus.DELETED, w.original.status(), "the original follows the case's own rule");
        assertEquals(ArtifactStatus.AVAILABLE, w.splitCopy.status(), "the final purge keeps a split copy in its period");

        w.purge(t0.plus(Duration.ofHours(61)).minusSeconds(1));
        assertEquals(ArtifactStatus.AVAILABLE, w.splitCopy.status(), "not before 60 h after its receipt");
        w.purge(t0.plus(Duration.ofHours(61)));
        assertEquals(ArtifactStatus.DELETED, w.splitCopy.status());
        verify(w.audit).recordSuccess(eq(w.tenant), any(), eq(AuditAction.SPLIT_COPY_PURGED), anyString(), anyString(),
                eq(w.pkg.id()), any(), argThat(s -> s.contains("hours=60")));
    }

    @Test
    void aShorterMasterPeriodStartsTheMasterClockAndASplitDestinationWithoutItsOwnTakesTheMasters() {
        World w = new World();
        w.store.setMaster(w.tenant, 10, ActorId.generate());
        w.exportMaster();
        Instant t0 = w.lifecycle().lastExportedAt().orElseThrow();
        w.splitDelivered(t0.plus(Duration.ofHours(3)));

        assertEquals(Duration.ofHours(10), Duration.between(t0, w.lifecycle().redactedDeleteAt().orElseThrow()));
        w.purge(t0.plus(Duration.ofHours(10)));
        assertEquals(ArtifactStatus.DELETED, w.masterCopy.status());
        assertEquals(ArtifactStatus.AVAILABLE, w.splitCopy.status(), "its 10 h run from its own receipt, 3 h later");
        w.purge(t0.plus(Duration.ofHours(13)));
        assertEquals(ArtifactStatus.DELETED, w.splitCopy.status());
    }

    @Test
    void aSplitCopyNeverReceivedFollowsTheMasterCopyAsBefore() {
        World w = new World();
        w.exportMaster();
        Instant t0 = w.lifecycle().lastExportedAt().orElseThrow();
        // The split delivery was never received and has no open job (inline mode, it failed).
        w.purge(t0.plus(Duration.ofHours(24)));
        assertEquals(ArtifactStatus.DELETED, w.masterCopy.status());
        assertEquals(ArtifactStatus.DELETED, w.splitCopy.status());
    }

    // ------------------------------------------------------------------ open export jobs

    @Test
    void aSplitCopyIsNotDeletedWhileItsExportJobIsOpenEvenPastItsPeriod() {
        World w = new World();
        w.exportMaster();
        Instant t0 = w.lifecycle().lastExportedAt().orElseThrow();
        PipelineJob split = w.openJob(PipelineStage.SPLIT_EXPORT);

        w.purge(t0.plus(Duration.ofHours(24)));
        assertEquals(ArtifactStatus.DELETED, w.masterCopy.status());
        assertEquals(ArtifactStatus.AVAILABLE, w.splitCopy.status(), "its job is open (it waits for its destination)");

        // Received later; its own clock starts then. While the job is still open nothing is deleted.
        w.splitDelivered(t0.plus(Duration.ofHours(30)));
        w.purge(t0.plus(Duration.ofHours(60)));
        assertEquals(ArtifactStatus.AVAILABLE, w.splitCopy.status(), "its job is still open");

        w.done(split);
        w.purge(t0.plus(Duration.ofHours(60)));
        assertEquals(ArtifactStatus.DELETED, w.splitCopy.status());
    }

    @Test
    void theOriginalIsKeptWhileAnyExportJobOfTheCaseIsOpen() {
        World w = new World();
        w.exportMaster();
        Instant t0 = w.lifecycle().lastExportedAt().orElseThrow();
        PipelineJob split = w.openJob(PipelineStage.SPLIT_EXPORT);

        w.purge(t0.plus(Duration.ofHours(48)));
        assertEquals(ArtifactStatus.AVAILABLE, w.original.status(), "a split export is still open");
        assertTrue(w.lifecycle().purgedAt().isEmpty());

        w.done(split);
        w.purge(t0.plus(Duration.ofHours(48)));
        assertEquals(ArtifactStatus.DELETED, w.original.status());
    }

    @Test
    void theMasterCopyIsKeptWhileTheMasterExportJobIsOpen() {
        World w = new World();
        w.exportMaster();
        Instant t0 = w.lifecycle().lastExportedAt().orElseThrow();
        PipelineJob master = w.openJob(PipelineStage.EXPORT);

        w.purge(t0.plus(Duration.ofHours(24)));
        assertEquals(ArtifactStatus.AVAILABLE, w.masterCopy.status());

        w.done(master);
        w.purge(t0.plus(Duration.ofHours(24)));
        assertEquals(ArtifactStatus.DELETED, w.masterCopy.status());
    }

    @Test
    void withNoExportJobsAndNoPeriodsSetTheMasterCopyFollowsTheRedactedRetentionAsBefore() {
        World w = new World();
        w.exportMaster();
        Instant t0 = w.lifecycle().lastExportedAt().orElseThrow();
        assertEquals(Duration.ofHours(24), Duration.between(t0, w.lifecycle().redactedDeleteAt().orElseThrow()));
        w.purge(t0.plus(Duration.ofHours(24)));
        assertEquals(ArtifactStatus.DELETED, w.masterCopy.status());
        assertEquals(ArtifactStatus.AVAILABLE, w.original.status());
    }

    // ------------------------------------------------------------------ the Business Admin's settings

    @Test
    void theBusinessAdminSetsAPeriodPerDestinationWithDefaultsAndLimits() {
        World w = new World();
        ExportRetentionService.RetentionSettings defaults = w.retention.settings(w.tenant);
        assertNull(defaults.master().setHours());
        assertEquals(24, defaults.master().hours(), "default: the redacted-copy retention");
        assertEquals(1, defaults.splitDestinations().size());
        assertEquals(24, defaults.splitDestinations().get(0).hours(), "default: the master's");

        Map<UUID, Integer> splits = new LinkedHashMap<>();
        splits.put(w.splitDestination.value(), 48);
        ExportRetentionService.RetentionSettings set = w.retention.update(w.tenant, ActorId.generate(),
                CorrelationId.generate(), 12, splits);
        assertEquals(12, set.master().hours());
        assertEquals(48, set.splitDestinations().get(0).hours());
        verify(w.audit, times(2)).recordSuccess(eq(w.tenant), any(), eq(AuditAction.EXPORT_RETENTION_PERIOD_CHANGED),
                anyString(), anyString(), any(), anyString());

        splits.put(w.splitDestination.value(), null);
        ExportRetentionService.RetentionSettings cleared = w.retention.update(w.tenant, ActorId.generate(),
                CorrelationId.generate(), null, splits);
        assertEquals(24, cleared.master().hours());
        assertEquals(24, cleared.splitDestinations().get(0).hours());

        for (Integer bad : List.of(0, 73)) {
            ValidationException e = assertThrows(ValidationException.class, () -> w.retention.update(w.tenant,
                    ActorId.generate(), CorrelationId.generate(), bad, Map.of()));
            assertEquals(LvErrorCode.CFG_001, e.lvCode());
        }
        assertThrows(ValidationException.class, () -> w.retention.update(w.tenant, ActorId.generate(),
                CorrelationId.generate(), 12, Map.of(UUID.randomUUID(), 12)), "not one of the tenant's split destinations");
        assertEquals(24, w.retention.settings(w.tenant).master().hours(), "a refused change changes nothing");
    }

    // ------------------------------------------------------------------ the world

    /** One completed case: an original, the master's redacted copy and one split copy for a split destination. */
    private static final class World {
        final TenantId tenant = TenantId.generate();
        final TestClock clock = new TestClock();
        final InMemoryPipelineJobs jobs = new InMemoryPipelineJobs(clock, new InMemoryDestinationStates(clock));
        final IntegrationConnectionId splitDestination = IntegrationConnectionId.generate();
        final AuditService audit = mock(AuditService.class);
        final MapStore store = new MapStore();
        final DocumentPackage pkg;
        final Document document;
        final Artifact original;
        final Artifact masterCopy;
        final Artifact splitCopy;
        final List<DerivedDelivery> deliveries = new ArrayList<>();
        final Map<UUID, PackageLifecycle> lifecycles = new HashMap<>();
        final ExportRetentionService retention;
        final PackageLifecycleService service;
        final UUID derivedDeliveryId = UUID.randomUUID();

        World() {
            pkg = DocumentPackage.create(tenant, PackageType.MANUAL_UPLOAD, ActorId.generate(), PackageMetadata.empty());
            Instant now = Instant.now();
            document = Document.reconstitute(DocumentId.generate(), tenant, pkg.id(), FileName.of("case.pdf"),
                    MimeType.pdf(), 10, Checksum.sha256("a".repeat(64)), DocumentType.PDF, now,
                    DocumentStatus.PROCESSED, List.of(), now);
            pkg.addDocument(document);
            pkg.submit();
            pkg.startProcessing();
            pkg.completeSuccessfully();
            original = artifact(ArtifactType.ORIGINAL);
            masterCopy = artifact(ArtifactType.REDACTED);
            splitCopy = artifact(ArtifactType.DERIVED_REDACTED);
            deliveries.add(delivery(DerivedDeliveryStatus.RETRY_PENDING, null));

            PackageRepository packages = mock(PackageRepository.class);
            when(packages.findByIdAndTenantId(any(), any())).thenReturn(Optional.of(pkg));
            DocumentRepository documents = mock(DocumentRepository.class);
            when(documents.findByPackageIdAndTenantId(any(), any())).thenReturn(List.of(document));
            ArtifactRepository artifacts = mock(ArtifactRepository.class);
            when(artifacts.findByDocumentIdAndTenantId(any(), any())).thenReturn(List.of(original, masterCopy, splitCopy));
            when(artifacts.findByIdAndTenantId(any(), any())).thenAnswer(i -> List.of(original, masterCopy, splitCopy)
                    .stream().filter(a -> a.id().equals(i.getArgument(0))).findFirst());
            DerivedDeliveryRepository deliveryRepository = mock(DerivedDeliveryRepository.class);
            when(deliveryRepository.findByTenantIdAndPackageId(any(), any())).thenAnswer(i -> List.copyOf(deliveries));
            TenantLifecyclePolicyRepository policies = mock(TenantLifecyclePolicyRepository.class);
            when(policies.findByTenantId(tenant)).thenReturn(Optional.of(new TenantLifecyclePolicy(tenant, 48, 24, 72,
                    false, Instant.now())));
            PackageLifecycleRepository lifecycleRepository = mock(PackageLifecycleRepository.class);
            when(lifecycleRepository.findByPackageIdAndTenantId(any(), any()))
                    .thenAnswer(i -> Optional.ofNullable(lifecycles.get(pkg.id().value())));
            doAnswer(i -> {
                PackageLifecycle l = i.getArgument(0);
                lifecycles.put(l.packageId().value(), l);
                return null;
            }).when(lifecycleRepository).save(any());
            when(lifecycleRepository.findDueRedactedPurge(any(), anyInt())).thenAnswer(i -> lifecycles.values().stream()
                    .filter(l -> l.isRedactedPurgeDue(i.getArgument(0))).toList());
            when(lifecycleRepository.findDueFinalPurge(any(), anyInt())).thenAnswer(i -> lifecycles.values().stream()
                    .filter(l -> l.isFinalPurgeDue(i.getArgument(0))).toList());
            IntegrationConnectionRepository connections = mock(IntegrationConnectionRepository.class);
            IntegrationConnection split = mock(IntegrationConnection.class);
            when(split.id()).thenReturn(splitDestination);
            when(split.name()).thenReturn("Split destination");
            when(split.purpose()).thenReturn(IntegrationPurpose.DERIVED_DOCUMENT_DELIVERY);
            when(connections.findByTenantId(tenant)).thenReturn(List.of(split));
            when(connections.findByTenantIdAndId(tenant, splitDestination)).thenReturn(Optional.of(split));

            ExportRetentionService[] self = new ExportRetentionService[1];
            // What JdbcExportRetentionRepository's due query does (its SQL is proven against PostgreSQL).
            SplitCopyRetentionPort due = (now2, limit) -> deliveries.stream()
                    .filter(d -> d.status() == DerivedDeliveryStatus.DELIVERED && d.deliveredAt() != null)
                    .filter(d -> splitCopy.status() != ArtifactStatus.DELETED)
                    .filter(d -> !d.deliveredAt().plus(Duration.ofHours(self[0].splitCopyHours(tenant,
                            d.destinationConnectionId()))).isAfter(now2))
                    .filter(d -> jobs.all().stream().noneMatch(j -> j.stage() == PipelineStage.SPLIT_EXPORT
                            && d.id().equals(j.itemId()) && j.status().open()))
                    .map(d -> new SplitCopyRetentionPort.SplitCopy(tenant, pkg.id(), d.id(), d.destinationConnectionId(),
                            d.artifactId(), d.deliveredAt()))
                    .toList();
            retention = new ExportRetentionService(store, jobs, deliveryRepository, artifacts, due, connections,
                    policies, audit);
            self[0] = retention;
            service = new PackageLifecycleService(policies, lifecycleRepository, mock(PackageExportRecordRepository.class),
                    packages, mock(PackageDashboardVisibilityPort.class), documents, artifacts, mock(StoragePort.class),
                    audit, ProcessingIncidentService.noop(), ProductTelemetryService.noop(), retention);
        }

        Artifact artifact(ArtifactType type) {
            Artifact a = Artifact.create(tenant, document.id(), type, MimeType.pdf(), 3, Checksum.sha256("c".repeat(64)));
            a.markAvailable(ArtifactStorageReference.local(type.name().toLowerCase() + "/" + UUID.randomUUID() + ".pdf"));
            return a;
        }

        DerivedDelivery delivery(DerivedDeliveryStatus status, Instant deliveredAt) {
            Instant created = Instant.now();
            return DerivedDelivery.reconstitute(derivedDeliveryId, tenant, pkg.id(), document.id(), 1, UUID.randomUUID(),
                    splitDestination, 1, "Split destination", null, null, List.of(1), splitCopy.id(),
                    "derived-" + derivedDeliveryId, "split.pdf", status, 1, null, status != DerivedDeliveryStatus.DELIVERED,
                    null, created, created, deliveredAt);
        }

        void splitDelivered(Instant at) {
            deliveries.set(0, delivery(DerivedDeliveryStatus.DELIVERED, at));
        }

        PackageLifecycle lifecycle() {
            return lifecycles.get(pkg.id().value());
        }

        void exportMaster() {
            service.recordSuccessfulExport(pkg.id(), tenant, 1, "sdv", List.of(new PackageExportRecord.ExportedDocument(
                    document.id(), "document-" + document.id(), 3, Checksum.sha256("c".repeat(64)), "sdv://x")),
                    CorrelationId.generate());
        }

        PipelineJob openJob(PipelineStage stage) {
            PipelineJob job = PipelineJob.ready(tenant, stage, pkg.id(), document.id(),
                    stage == PipelineStage.EXPORT ? IntegrationConnectionId.generate() : splitDestination,
                    stage == PipelineStage.SPLIT_EXPORT ? derivedDeliveryId : null, stage == PipelineStage.EXPORT ? 2 : 1,
                    clock.instant());
            jobs.enqueue(job);
            return job;
        }

        void done(PipelineJob job) {
            jobs.changeStatus(tenant, job.id(), PipelineJobStatus.READY, PipelineJobStatus.DONE, 1, null, null);
        }

        void purge(Instant at) {
            service.execute(new PurgeDueRetentionCommand(at, 100, ActorId.generate(), CorrelationId.generate()));
        }
    }

    /** The retention periods in memory, as {@code JdbcExportRetentionRepository} keeps them. */
    private static final class MapStore implements ExportRetentionRepository {
        Integer master;
        final Map<IntegrationConnectionId, Integer> splits = new LinkedHashMap<>();

        @Override public Optional<Integer> masterHours(TenantId tenantId) { return Optional.ofNullable(master); }
        @Override public Map<IntegrationConnectionId, Integer> splitHours(TenantId tenantId) { return Map.copyOf(splits); }
        @Override public void setMaster(TenantId tenantId, Integer hours, ActorId actorId) { master = hours; }
        @Override public void setSplit(TenantId tenantId, IntegrationConnectionId id, Integer hours, ActorId actorId) {
            if (hours == null) splits.remove(id); else splits.put(id, hours);
        }
    }
}

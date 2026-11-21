package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.export.service.PackageDocumentExportService;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.port.in.ProcessDerivedRoutingUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.BusinessRecordRepository;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.application.port.out.DeliverySubmissionRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.ExportDestinationProbePort;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.routing.service.DerivedDeliveryService;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.technical.service.ProcessingIncidentService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.delivery.DeliverySubmission;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 0027 test set-up: the real export service ({@link PackageDocumentExportService}, which owns the idempotency
 * key) and the real job service, with a fake destination, an in-memory queue and a hand-moved clock.
 */
final class ExportJobFixture {
    final TenantId tenant = TenantId.generate();
    final TestClock clock = new TestClock();
    final InMemoryDestinationStates states = new InMemoryDestinationStates(clock);
    final InMemoryPipelineJobs jobs = new InMemoryPipelineJobs(clock, states);
    final FakeDestination destination = new FakeDestination();

    final Map<PackageId, DocumentPackage> packages = new HashMap<>();
    final Map<PackageId, List<Document>> documents = new HashMap<>();
    final Map<String, Artifact> artifacts = new HashMap<>();
    final Map<PackageId, Integer> nextCycle = new HashMap<>();
    final Map<String, DeliverySubmission> submissions = new HashMap<>();

    final AuditService audit = mock(AuditService.class);
    final ProcessingIncidentService incidents = mock(ProcessingIncidentService.class);
    final ProcessDerivedRoutingUseCase routing = mock(ProcessDerivedRoutingUseCase.class);
    final DerivedDeliveryService derived = mock(DerivedDeliveryService.class);
    final PackageLifecycleService lifecycle = mock(PackageLifecycleService.class);
    final PackageRepository packageRepository = mock(PackageRepository.class);
    final IntegrationConnectionRepository connections = mock(IntegrationConnectionRepository.class);

    final ExportJobSettings settings;
    final ExportJobQueue queue;
    final ExportJobService service;

    ExportJobFixture() {
        this(ExportJobSettings.defaults(ExportMode.JOB));
    }

    ExportJobFixture(ExportJobSettings settings) {
        this.settings = settings;
        DocumentRepository documentRepository = mock(DocumentRepository.class);
        ArtifactRepository artifactRepository = mock(ArtifactRepository.class);
        StoragePort storage = mock(StoragePort.class);
        when(packageRepository.findByIdAndTenantId(any(), any()))
                .thenAnswer(i -> Optional.ofNullable(packages.get(i.<PackageId>getArgument(0))));
        when(documentRepository.findByPackageIdAndTenantId(any(), any()))
                .thenAnswer(i -> documents.getOrDefault(i.<PackageId>getArgument(0), List.of()));
        when(artifactRepository.findByDocumentIdAndTenantId(any(), any()))
                .thenAnswer(i -> List.of(artifacts.get(i.getArgument(0).toString())));
        when(storage.retrieve(any())).thenAnswer(i -> new ByteArrayInputStream("PDF".getBytes()));
        when(lifecycle.nextExportCycle(any(), any())).thenAnswer(i -> nextCycle.getOrDefault(i.<PackageId>getArgument(0), 1));
        doAnswer(i -> {
            PackageId id = i.getArgument(0);
            nextCycle.put(id, nextCycle.getOrDefault(id, 1) + 1);
            return null;
        }).when(lifecycle).recordSuccessfulExport(any(), any(), anyInt(), anyString(), anyList(), any());
        when(connections.findByTenantIdAndId(any(), any())).thenReturn(Optional.empty());

        DeliverySubmissionRepository submissionRepository = new DeliverySubmissionRepository() {
            @Override
            public Optional<DeliverySubmission> findByTenantIdAndPackageIdAndExportCycle(TenantId t, PackageId p, int c) {
                return Optional.ofNullable(submissions.get(p + "#" + c));
            }

            @Override
            public void save(DeliverySubmission submission) {
                submissions.put(submission.packageId() + "#" + submission.exportCycle(), submission);
            }

            @Override
            public void saveAttempt(com.securedoc.platform.domain.delivery.DeliveryAttempt attempt) { }
        };

        PackageDocumentExportService export = new PackageDocumentExportService(documentRepository, packageRepository,
                mock(BusinessRecordRepository.class), artifactRepository, storage, destination, submissionRepository,
                lifecycle, audit);
        ExportDestinationProbePort probe = (tenantId, connectionId) -> {
            if (!destination.answersProbe()) {
                throw new TechnicalDependencyException(TechnicalDependency.DELIVERY, TechnicalFailureCode.DELIVERY_IO,
                        true, "down");
            }
        };
        queue = new ExportJobQueue(jobs, destination, audit, settings, clock);
        service = new ExportJobService(jobs, states, export, derived, packageRepository, lifecycle,
                new MasterExportCompletion(packageRepository, routing, incidents, audit), incidents, audit, probe,
                connections, settings);
    }

    /** A case whose documents are all redacted, still PROCESSING, with its EXPORT job queued. */
    DocumentPackage caseReadyForExport() {
        DocumentPackage pkg = DocumentPackage.create(tenant, PackageType.MANUAL_UPLOAD, ActorId.generate(),
                PackageMetadata.empty());
        Instant now = Instant.now();
        Document document = Document.reconstitute(com.securedoc.platform.domain.identity.DocumentId.generate(), tenant,
                pkg.id(), FileName.of("case.pdf"), MimeType.pdf(), 10, Checksum.sha256("a".repeat(64)),
                DocumentType.PDF, now, DocumentStatus.PROCESSED, List.of(), now);
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        Artifact artifact = Artifact.create(tenant, document.id(), ArtifactType.REDACTED, MimeType.pdf(), 10,
                Checksum.sha256("b".repeat(64)));
        artifact.markAvailable(ArtifactStorageReference.local("redacted/" + document.id() + ".pdf"));
        packages.put(pkg.id(), pkg);
        documents.put(pkg.id(), List.of(document));
        artifacts.put(document.id().toString(), artifact);
        queue.queueCaseExport(tenant, pkg.id(), 1, CorrelationId.generate());
        return pkg;
    }

    /** One worker step: claim the next due job and run it. Empty when nothing is due. */
    Optional<PipelineJob> step(String owner) {
        Optional<PipelineJob> claimed = service.claimNext(owner);
        claimed.ifPresent(service::run);
        return claimed;
    }

    /** A worker until nothing is due. */
    int drain() {
        int runs = 0;
        while (step("worker-1").isPresent()) runs++;
        return runs;
    }

    PipelineJob jobOf(DocumentPackage pkg) {
        return jobs.onlyJobOf(pkg.id(), com.securedoc.platform.domain.pipeline.PipelineStage.EXPORT);
    }

    /** A destination the test switches between answers. It stores each idempotency key once, as Vero does. */
    static final class FakeDestination implements DeliveryPort {
        final IntegrationConnectionId id = IntegrationConnectionId.generate();
        volatile LvErrorCode failWith;
        volatile boolean answerAlreadyReceived;
        volatile boolean stopServerOnNextCall;
        volatile boolean probeAnswers = true;
        final List<String> keys = new ArrayList<>();
        final Set<String> stored = new LinkedHashSet<>();
        final List<PackageId> deliveredInOrder = new ArrayList<>();

        void up() { failWith = null; probeAnswers = true; }

        void down() { failWith = LvErrorCode.DLV_001; probeAnswers = false; }

        boolean answersProbe() { return probeAnswers; }

        @Override
        public DeliveryDescriptor describe(TenantId tenantId) {
            return new DeliveryDescriptor("sdv", id, 1);
        }

        @Override
        public synchronized BatchDeliveryReference deliverBatch(DeliveryBatch batch) {
            keys.add(batch.sourceSubmissionId());
            if (stopServerOnNextCall) {
                stopServerOnNextCall = false;
                // The request reached the destination, then LexaVault's server stopped before it saved anything.
                if (stored.add(batch.sourceSubmissionId())) deliveredInOrder.add(batch.packageId());
                throw new ServerStopped();
            }
            if (failWith != null) throw failure(failWith);
            if (stored.add(batch.sourceSubmissionId())) deliveredInOrder.add(batch.packageId());
            List<DeliveryReference> refs = batch.items().stream().map(item -> new DeliveryReference(item.documentId(),
                    item.sourceDocumentId(), "sdv", "remote-" + item.sourceDocumentId(), "sdv://remote")).toList();
            return new BatchDeliveryReference("sdv", "remote-" + batch.sourceSubmissionId(), refs,
                    answerAlreadyReceived);
        }

        static TechnicalDependencyException failure(LvErrorCode code) {
            boolean transientFailure = code == LvErrorCode.DLV_001 || code == LvErrorCode.DLV_002;
            TechnicalFailureCode reason = switch (code) {
                case DLV_003 -> TechnicalFailureCode.DELIVERY_SENSITIVITY_REFUSED;
                case DLV_004, DLV_005, DLV_006 -> TechnicalFailureCode.DELIVERY_DESTINATION_INVALID;
                default -> TechnicalFailureCode.DELIVERY_IO;
            };
            return new TechnicalDependencyException(TechnicalDependency.DELIVERY, reason, transientFailure,
                    "fake destination answer").withLvCode(code);
        }
    }

    /** Stands in for the process dying mid-export: not an Exception, so nothing catches it and nothing is saved. */
    static final class ServerStopped extends Error {
        ServerStopped() { super("server stopped"); }
    }

    static Duration minutes(int m) {
        return Duration.ofMinutes(m);
    }
}

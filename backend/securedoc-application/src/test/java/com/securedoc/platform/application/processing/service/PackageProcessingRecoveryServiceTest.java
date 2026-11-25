package com.securedoc.platform.application.processing.service;

import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.technical.service.ProcessingIncidentService;
import com.securedoc.platform.application.telemetry.ProductTelemetryService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.lifecycle.PackageExportRecord;
import com.securedoc.platform.domain.lifecycle.PackageLifecycle;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;
import com.securedoc.platform.domain.processing.ProcessingIncident;
import com.securedoc.platform.domain.processing.ProcessingIncidentEvent;
import com.securedoc.platform.domain.processing.ProcessingIncidentStatus;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PackageProcessingRecoveryServiceTest {

    @Test
    void exhaustedThirdAttemptMovesProcessingPackageToTechnicalAttentionAndSchedulesRetention() {
        TenantId tenantId = TenantId.generate();
        ActorId actorId = ActorId.generate();
        DocumentPackage pkg = DocumentPackage.create(
                tenantId, PackageType.MANUAL_UPLOAD, actorId, PackageMetadata.empty());
        Document document = Document.create(tenantId, pkg.id(), FileName.of("recovery.pdf"), MimeType.pdf(),
                64, Checksum.sha256("recovery-document"), DocumentType.PDF);
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();

        MutablePackageRepository packages = new MutablePackageRepository(pkg);
        MutableLifecycleRepository lifecycles = new MutableLifecycleRepository();
        CapturingIncidents incidents = new CapturingIncidents();
        List<AuditRecord> audits = new ArrayList<>();

        ProcessingIncidentService incidentService = new ProcessingIncidentService(
                incidents, ProcessingFingerprintProvider.empty());
        PackageLifecycleService lifecycleService = new PackageLifecycleService(
                new DefaultPolicyRepository(), lifecycles, new EmptyExportRepository(), packages,
                (packageId, tenant, actor, hiddenAt) -> { }, new EmptyDocumentRepository(),
                new EmptyArtifactRepository(),
                new com.securedoc.platform.application.port.out.DirectErasure(new EmptyArtifactRepository(), new EmptyStorage()), new AuditService(audits::add),
                incidentService, ProductTelemetryService.noop());

        PackageProcessingRecoveryService service = new PackageProcessingRecoveryService(
                packages, lifecycleService, incidentService, new AuditService(audits::add),
                ProductTelemetryService.noop());

        Instant before = Instant.now();
        boolean exhausted = service.exhaustIfStillProcessing(
                pkg.id(), tenantId, 3, 3, CorrelationId.generate());
        Instant after = Instant.now();

        assertTrue(exhausted);
        assertEquals(PackageStatus.FAILED, pkg.status());
        PackageLifecycle lifecycle = lifecycles.value.orElseThrow();
        Instant holdUntil = lifecycle.technicalHoldUntil().orElseThrow();
        assertFalse(holdUntil.isBefore(before.plus(Duration.ofHours(167))));
        assertFalse(holdUntil.isAfter(after.plus(Duration.ofHours(169))));

        ProcessingIncident incident = incidents.latest.orElseThrow();
        assertEquals("PROCESSING_RECOVERY_EXHAUSTED", incident.reasonCode());
        assertEquals(ProcessingIncidentStatus.OPEN, incident.status());
        assertEquals(3, incident.attempts());
        assertTrue(audits.stream().anyMatch(a -> a.action() == AuditAction.PACKAGE_PROCESSING_RECOVERY_EXHAUSTED));
        assertTrue(audits.stream().anyMatch(a -> a.action() == AuditAction.RETENTION_SCHEDULED));
    }

    @Test
    void completedOrAlreadyFailedPackageIsNotReclassifiedByRecoveryExhaustion() {
        TenantId tenantId = TenantId.generate();
        ActorId actorId = ActorId.generate();
        DocumentPackage pkg = DocumentPackage.create(
                tenantId, PackageType.MANUAL_UPLOAD, actorId, PackageMetadata.empty());
        Document document = Document.create(tenantId, pkg.id(), FileName.of("recovery.pdf"), MimeType.pdf(),
                64, Checksum.sha256("recovery-document"), DocumentType.PDF);
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        pkg.fail();

        MutablePackageRepository packages = new MutablePackageRepository(pkg);
        MutableLifecycleRepository lifecycles = new MutableLifecycleRepository();
        CapturingIncidents incidents = new CapturingIncidents();
        List<AuditRecord> audits = new ArrayList<>();
        ProcessingIncidentService incidentService = new ProcessingIncidentService(
                incidents, ProcessingFingerprintProvider.empty());
        PackageLifecycleService lifecycleService = new PackageLifecycleService(
                new DefaultPolicyRepository(), lifecycles, new EmptyExportRepository(), packages,
                (packageId, tenant, actor, hiddenAt) -> { }, new EmptyDocumentRepository(),
                new EmptyArtifactRepository(),
                new com.securedoc.platform.application.port.out.DirectErasure(new EmptyArtifactRepository(), new EmptyStorage()), new AuditService(audits::add),
                incidentService, ProductTelemetryService.noop());
        PackageProcessingRecoveryService service = new PackageProcessingRecoveryService(
                packages, lifecycleService, incidentService, new AuditService(audits::add),
                ProductTelemetryService.noop());

        assertFalse(service.exhaustIfStillProcessing(pkg.id(), tenantId, 3, 3, CorrelationId.generate()));
        assertTrue(incidents.latest.isEmpty());
        assertTrue(lifecycles.value.isEmpty());
    }

    // ---- Task 0023 C1 (#346, #358) ----

    @Test
    void submittedCaseIsStoppedAfterRepeatedDispatchFailuresWithCodeIncidentAndOneAudit() {
        Fixture f = new Fixture(false);
        RuntimeException failure = new IllegalStateException("text that must never be stored: Jane Doe 123-45-6789");

        assertTrue(f.service.stopAfterDispatchFailure(f.pkg.id(), f.tenantId, failure, 3, 3, CorrelationId.generate()));

        assertEquals(PackageStatus.FAILED, f.pkg.status());
        ProcessingIncident incident = f.incidents.latest.orElseThrow();
        assertEquals("PROCESSING_DISPATCH_EXHAUSTED", incident.reasonCode());
        assertEquals("PROCESSING", incident.component());
        assertEquals("DISPATCH_EXHAUSTED", incident.failureCategory());
        assertEquals(3, incident.attempts());
        assertFalse(incident.retryable());
        assertEquals("IllegalStateException", incident.safeMetadata().get("failureType"));
        List<AuditRecord> failed = f.audits.stream()
                .filter(a -> a.action() == AuditAction.DOCUMENT_PROCESSING_FAILED).toList();
        assertEquals(1, failed.size());
        assertEquals("Package processing stopped: code=LV-PRC-008, detail=PROCESSING_DISPATCH_EXHAUSTED, dependency=PROCESSING, retryable=false, attempts=3",
                failed.get(0).detail());
        assertTrue(f.lifecycles.value.flatMap(PackageLifecycle::technicalHoldUntil).isPresent());
        String everything = f.audits.toString() + incident;
        assertFalse(everything.contains("Jane Doe"), "No exception message may reach the incident or audit");
        assertFalse(everything.contains("123-45-6789"));
    }

    @Test
    void licenceDenialStopsSubmittedAndProcessingCasesWithItsOwnCode() {
        for (boolean processing : new boolean[]{false, true}) {
            Fixture f = new Fixture(processing);
            var denial = new com.securedoc.platform.application.licensing.LicenseEnforcementException(
                    "LICENSE_EXPIRED", "denied", com.securedoc.platform.domain.licensing.LicenseValidationStatus.EXPIRED);

            assertTrue(f.service.stopAfterDispatchFailure(f.pkg.id(), f.tenantId, denial, 1, 3, CorrelationId.generate()));

            assertEquals(PackageStatus.FAILED, f.pkg.status());
            ProcessingIncident incident = f.incidents.latest.orElseThrow();
            assertEquals("PROCESSING_LICENSE_DENIED", incident.reasonCode());
            assertEquals("LICENSE", incident.failureCategory());
            assertEquals("EXPIRED", incident.safeMetadata().get("licenceStatus"));
            assertEquals(1, f.audits.stream().filter(a -> a.action() == AuditAction.DOCUMENT_PROCESSING_FAILED).count());
        }
    }

    @Test
    void processingCaseWithOrdinaryFailureKeepsTheDurableRecoveryBudget() {
        Fixture f = new Fixture(true);

        assertFalse(f.service.stopAfterDispatchFailure(f.pkg.id(), f.tenantId,
                new IllegalStateException("x"), 3, 3, CorrelationId.generate()));

        assertEquals(PackageStatus.PROCESSING, f.pkg.status());
        assertTrue(f.incidents.latest.isEmpty());
        assertTrue(f.audits.isEmpty());
    }

    @Test
    void reviewContinuationIsNeverStoppedByTheDispatchCap() {
        Fixture f = new Fixture(true);
        f.pkg.completeProcessing(); // PROCESSING -> PENDING_REVIEW
        var denial = new com.securedoc.platform.application.licensing.LicenseEnforcementException(
                "LICENSE_MISSING", "denied", com.securedoc.platform.domain.licensing.LicenseValidationStatus.MISSING);

        assertFalse(f.service.stopAfterDispatchFailure(f.pkg.id(), f.tenantId, denial, 1, 3, CorrelationId.generate()));
        assertEquals(PackageStatus.PENDING_REVIEW, f.pkg.status());
    }

    private static final class Fixture {
        final TenantId tenantId = TenantId.generate();
        final DocumentPackage pkg;
        final MutableLifecycleRepository lifecycles = new MutableLifecycleRepository();
        final CapturingIncidents incidents = new CapturingIncidents();
        final List<AuditRecord> audits = new ArrayList<>();
        final PackageProcessingRecoveryService service;

        Fixture(boolean processing) {
            pkg = DocumentPackage.create(tenantId, PackageType.MANUAL_UPLOAD, ActorId.generate(), PackageMetadata.empty());
            Document document = Document.create(tenantId, pkg.id(), FileName.of("dispatch.pdf"), MimeType.pdf(),
                    64, Checksum.sha256("dispatch-document"), DocumentType.PDF);
            pkg.addDocument(document);
            pkg.submit();
            if (processing) pkg.startProcessing();
            MutablePackageRepository packages = new MutablePackageRepository(pkg);
            ProcessingIncidentService incidentService = new ProcessingIncidentService(
                    incidents, ProcessingFingerprintProvider.empty());
            List<AuditRecord> lifecycleAudits = new ArrayList<>();
            PackageLifecycleService lifecycleService = new PackageLifecycleService(
                    new DefaultPolicyRepository(), lifecycles, new EmptyExportRepository(), packages,
                    (packageId, tenant, actor, hiddenAt) -> { }, new EmptyDocumentRepository(),
                    new EmptyArtifactRepository(),
                new com.securedoc.platform.application.port.out.DirectErasure(new EmptyArtifactRepository(), new EmptyStorage()), new AuditService(lifecycleAudits::add),
                    incidentService, ProductTelemetryService.noop());
            service = new PackageProcessingRecoveryService(
                    packages, lifecycleService, incidentService, new AuditService(audits::add),
                    ProductTelemetryService.noop());
        }
    }

    private static final class MutablePackageRepository implements PackageRepository {
        private DocumentPackage value;
        private MutablePackageRepository(DocumentPackage value) { this.value = value; }
        @Override public void save(DocumentPackage documentPackage) { this.value = documentPackage; }
        @Override public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId tenantId) {
            return value.id().equals(id) && value.tenantId().equals(tenantId) ? Optional.of(value) : Optional.empty();
        }
        @Override public List<DocumentPackage> findByTenantId(TenantId tenantId, int offset, int limit) { return List.of(value); }
        @Override public long countByTenantId(TenantId tenantId) { return 1; }
        @Override public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId tenantId, String fingerprint) { return Optional.empty(); }
        @Override public List<DocumentPackage> findByStatus(PackageStatus status, int limit) {
            return value.status() == status ? List.of(value) : List.of();
        }
    }

    private static final class MutableLifecycleRepository implements PackageLifecycleRepository {
        private Optional<PackageLifecycle> value = Optional.empty();
        @Override public Optional<PackageLifecycle> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) { return value; }
        @Override public void save(PackageLifecycle lifecycle) { value = Optional.of(lifecycle); }
        @Override public List<PackageLifecycle> findDueRedactedPurge(Instant now, int limit) { return List.of(); }
        @Override public List<PackageLifecycle> findDueFinalPurge(Instant now, int limit) { return List.of(); }
    }

    private static final class DefaultPolicyRepository implements TenantLifecyclePolicyRepository {
        @Override public Optional<TenantLifecyclePolicy> findByTenantId(TenantId tenantId) {
            return Optional.of(TenantLifecyclePolicy.defaults(tenantId));
        }
        @Override public void save(TenantLifecyclePolicy policy) { }
    }

    private static final class EmptyExportRepository implements PackageExportRecordRepository {
        @Override public void save(PackageExportRecord record) { }
        @Override public List<PackageExportRecord> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) { return List.of(); }
    }

    private static final class EmptyDocumentRepository implements DocumentRepository {
        @Override public void save(Document document) { }
        @Override public Optional<Document> findByIdAndTenantId(DocumentId id, TenantId tenantId) { return Optional.empty(); }
        @Override public List<Document> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) { return List.of(); }
    }

    private static final class EmptyArtifactRepository implements ArtifactRepository {
        @Override public void save(Artifact artifact) { }
        @Override public Optional<Artifact> findByIdAndTenantId(ArtifactId id, TenantId tenantId) { return Optional.empty(); }
        @Override public List<Artifact> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) { return List.of(); }
    }

    private static final class EmptyStorage implements StoragePort {
        @Override public ArtifactStorageReference store(TenantId tenantId, String path, InputStream content, long sizeBytes, String mimeType) {
            throw new UnsupportedOperationException();
        }
        @Override public InputStream retrieve(ArtifactStorageReference reference) { return new ByteArrayInputStream(new byte[0]); }
        @Override public void delete(ArtifactStorageReference reference) { }
    }

    private static final class CapturingIncidents implements ProcessingIncidentRepository {
        private Optional<ProcessingIncident> latest = Optional.empty();
        private final List<ProcessingIncidentEvent> events = new ArrayList<>();
        @Override public void save(ProcessingIncident incident) { latest = Optional.of(incident); }
        @Override public Optional<ProcessingIncident> findByIdAndTenantId(UUID incidentId, TenantId tenantId) {
            return latest.filter(i -> i.id().equals(incidentId) && i.tenantId().equals(tenantId));
        }
        @Override public Optional<ProcessingIncident> findLatestByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
            return latest.filter(i -> i.packageId().equals(packageId) && i.tenantId().equals(tenantId));
        }
        @Override public List<ProcessingIncident> findRecentByPackageIdAndTenantId(PackageId packageId, TenantId tenantId, int limit) {
            return findLatestByPackageIdAndTenantId(packageId, tenantId).stream().toList();
        }
        @Override public List<ProcessingIncident> findRecentByTenantId(TenantId tenantId, int limit) { return latest.stream().toList(); }
        @Override public void saveEvent(ProcessingIncidentEvent event) { events.add(event); }
        @Override public List<ProcessingIncidentEvent> findEvents(UUID incidentId, TenantId tenantId, int limit) { return List.copyOf(events); }
    }
}

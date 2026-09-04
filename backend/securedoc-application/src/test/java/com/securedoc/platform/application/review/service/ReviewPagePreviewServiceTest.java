package com.securedoc.platform.application.review.service;

import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.ReviewPageRenderPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.Confidence;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReviewPagePreviewServiceTest {

    @Test
    void masksOnlyPreviouslyConfirmedSensitiveFindingsOnTheReviewPage() {
        TenantId tenantId = TenantId.generate();
        ActorId actorId = ActorId.generate();
        CorrelationId correlationId = CorrelationId.generate();
        DocumentPackage pkg = DocumentPackage.create(
                tenantId, PackageType.MANUAL_UPLOAD, actorId, PackageMetadata.empty());
        Document document = Document.create(
                tenantId, pkg.id(), FileName.of("fixture.pdf"), MimeType.pdf(), 100,
                Checksum.sha256("fixture"), DocumentType.PDF);
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        pkg.completeProcessing();

        Finding pendingScore = finding(tenantId, document.id(), EntityType.CREDIT_SCORE,
                FindingLocation.withBounds(15, 0, 3,
                        FindingLocation.BoundingBox.of(0.40, 0.55, 0.08, 0.03)));
        Finding confirmedSsn = finding(tenantId, document.id(), EntityType.SSN,
                FindingLocation.withBounds(15, 10, 21,
                        FindingLocation.BoundingBox.of(0.40, 0.30, 0.15, 0.03)));
        confirmedSsn.confirm(actorId, "auto-confirmed");
        Finding confirmedDob = finding(tenantId, document.id(), EntityType.DATE_OF_BIRTH,
                FindingLocation.withBounds(15, 30, 40,
                        FindingLocation.BoundingBox.of(0.40, 0.40, 0.14, 0.03)));
        confirmedDob.confirm(actorId, "auto-confirmed");
        Finding confirmedOtherPage = finding(tenantId, document.id(), EntityType.CREDIT_CARD_NUMBER,
                FindingLocation.withBounds(4, 50, 66,
                        FindingLocation.BoundingBox.of(0.40, 0.50, 0.20, 0.03)));
        confirmedOtherPage.confirm(actorId, "auto-confirmed");
        Finding dismissedSamePage = finding(tenantId, document.id(), EntityType.CREDIT_SCORE,
                FindingLocation.withBounds(15, 70, 73,
                        FindingLocation.BoundingBox.of(0.40, 0.60, 0.08, 0.03)));
        dismissedSamePage.dismiss(actorId, "not sensitive");

        List<Finding> findings = new ArrayList<>(List.of(
                pendingScore, confirmedSsn, confirmedDob, confirmedOtherPage, dismissedSamePage));
        Artifact original = Artifact.create(
                tenantId, document.id(), ArtifactType.ORIGINAL, MimeType.pdf(), 100,
                Checksum.sha256("original"));
        original.markAvailable(ArtifactStorageReference.local("fixture.pdf"));

        CapturingRenderer renderer = new CapturingRenderer();
        ReviewPagePreviewService service = new ReviewPagePreviewService(
                new FakeFindingRepository(findings),
                new FakeDocumentRepository(document),
                new FakePackageRepository(pkg),
                new FakeArtifactRepository(original),
                new FakeStoragePort(),
                renderer,
                new AuditService(record -> { }));

        service.execute(tenantId, pendingScore.id(), actorId, correlationId);

        assertEquals(15, renderer.pageNumber);
        assertEquals(List.of(
                confirmedSsn.location().boundingBox(),
                confirmedDob.location().boundingBox()), renderer.masks);
    }

    private static Finding finding(TenantId tenantId, DocumentId documentId,
                                   EntityType type, FindingLocation location) {
        return Finding.create(tenantId, documentId, type, location, Confidence.of(0.95),
                DetectorSource.of("test"), null);
    }

    private static final class CapturingRenderer implements ReviewPageRenderPort {
        int pageNumber;
        List<FindingLocation.BoundingBox> masks = List.of();

        @Override
        public RenderedPage render(InputStream pdfContent, int pageNumber) {
            return render(pdfContent, pageNumber, List.of());
        }

        @Override
        public RenderedPage render(InputStream pdfContent, int pageNumber,
                                   List<FindingLocation.BoundingBox> previewMasks) {
            this.pageNumber = pageNumber;
            this.masks = List.copyOf(previewMasks);
            return new RenderedPage(new byte[]{1, 2, 3}, "image/png", 800, 1000);
        }
    }

    private record FakeFindingRepository(List<Finding> findings) implements FindingRepository {
        @Override public void save(Finding finding) { }
        @Override public void saveAll(List<Finding> findings) { }
        @Override public Optional<Finding> findByIdAndTenantId(FindingId id, TenantId tenantId) {
            return findings.stream().filter(f -> f.id().equals(id) && f.tenantId().equals(tenantId)).findFirst();
        }
        @Override public List<Finding> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) {
            return findings.stream()
                    .filter(f -> f.documentId().equals(documentId) && f.tenantId().equals(tenantId))
                    .toList();
        }
        @Override public void deleteByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) { }
    }

    private record FakeDocumentRepository(Document document) implements DocumentRepository {
        @Override public void save(Document document) { }
        @Override public Optional<Document> findByIdAndTenantId(DocumentId id, TenantId tenantId) {
            return document.id().equals(id) && document.tenantId().equals(tenantId)
                    ? Optional.of(document) : Optional.empty();
        }
        @Override public List<Document> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
            return document.packageId().equals(packageId) && document.tenantId().equals(tenantId)
                    ? List.of(document) : List.of();
        }
    }

    private record FakePackageRepository(DocumentPackage pkg) implements PackageRepository {
        @Override public void save(DocumentPackage documentPackage) { }
        @Override public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId tenantId) {
            return pkg.id().equals(id) && pkg.tenantId().equals(tenantId)
                    ? Optional.of(pkg) : Optional.empty();
        }
        @Override public List<DocumentPackage> findByTenantId(TenantId tenantId, int offset, int limit) {
            return pkg.tenantId().equals(tenantId) ? List.of(pkg) : List.of();
        }
        @Override public long countByTenantId(TenantId tenantId) { return 1; }
        @Override public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(
                TenantId tenantId, String fingerprint) { return Optional.empty(); }
        @Override public List<DocumentPackage> findByStatus(
                com.securedoc.platform.domain.document.PackageStatus status, int limit) { return List.of(); }
    }

    private record FakeArtifactRepository(Artifact original) implements ArtifactRepository {
        @Override public void save(Artifact artifact) { }
        @Override public Optional<Artifact> findByIdAndTenantId(ArtifactId id, TenantId tenantId) {
            return original.id().equals(id) && original.tenantId().equals(tenantId)
                    ? Optional.of(original) : Optional.empty();
        }
        @Override public List<Artifact> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) {
            return original.documentId().equals(documentId) && original.tenantId().equals(tenantId)
                    ? List.of(original) : List.of();
        }
    }

    private static final class FakeStoragePort implements StoragePort {
        @Override
        public ArtifactStorageReference store(TenantId tenantId, String path, InputStream content,
                                              long sizeBytes, String mimeType) {
            throw new UnsupportedOperationException();
        }
        @Override public InputStream retrieve(ArtifactStorageReference reference) {
            return new ByteArrayInputStream(new byte[]{1, 2, 3});
        }
        @Override public void delete(ArtifactStorageReference reference) { }
    }
}

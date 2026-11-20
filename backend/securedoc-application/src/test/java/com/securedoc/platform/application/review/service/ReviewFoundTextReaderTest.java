package com.securedoc.platform.application.review.service;

import com.securedoc.platform.application.extraction.service.TextExtractionService;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.review.result.ReviewFoundText;
import com.securedoc.platform.application.review.result.ReviewItemSummary;
import com.securedoc.platform.application.review.result.ReviewQueueResult;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.Page;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.ArtifactId;
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
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReviewFoundTextReaderTest {

    private static final String PAGE_1 = "Loan agreement\nBorrower: Jane Example";
    private static final String PAGE_2 = "Second page header\nBorrower SSN: 412-55-0193 (primary applicant)\nFooter";
    private static final String SSN = "412-55-0193";

    private final TenantId tenantId = TenantId.of(UUID.randomUUID());
    private final PackageId packageId = PackageId.of(UUID.randomUUID());

    @Test
    void readsTheTextAndTheSameLineAroundItFromTheStoredPageText() {
        Document document = document(2);
        Finding ssn = locatedOnPage2(document, PAGE_2.indexOf(SSN), PAGE_2.indexOf(SSN) + SSN.length());

        Map<FindingId, ReviewFoundText> texts = reader(document, joined(PAGE_1, PAGE_2)).read(document, List.of(ssn));

        ReviewFoundText text = texts.get(ssn.id());
        assertEquals(SSN, text.text());
        assertEquals("Borrower SSN: ", text.before());
        assertEquals(" (primary applicant)", text.after());
    }

    @Test
    void contextIsCutToFortyCharactersAndNeverCrossesALine() {
        String longLine = "x".repeat(60) + "SECRET" + "y".repeat(60);
        Document document = document(1);
        Finding finding = finding(document, FindingLocation.withBounds(1, 60, 66, box()));

        ReviewFoundText text = reader(document, longLine).read(document, List.of(finding)).get(finding.id());

        assertEquals("SECRET", text.text());
        assertEquals(ReviewFoundTextReader.CONTEXT_LENGTH, text.before().length());
        assertEquals(ReviewFoundTextReader.CONTEXT_LENGTH, text.after().length());
    }

    @Test
    void aSpanThatCrossesALineIsShownOnOneLine() {
        String page = "Address: 1 Main St\nSpringfield 12345";
        Document document = document(1);
        Finding finding = finding(document, FindingLocation.withBounds(1, 9, page.length(), box()));

        ReviewFoundText text = reader(document, page).read(document, List.of(finding)).get(finding.id());

        assertEquals("1 Main St Springfield 12345", text.text());
    }

    @Test
    void locationUncertainFindingsWithOffsetsStillGetTheirText() {
        Document document = document(2);
        int start = PAGE_2.indexOf(SSN);
        Finding finding = finding(document, FindingLocation.textOnly(2, start, start + SSN.length()));

        assertEquals(SSN, reader(document, joined(PAGE_1, PAGE_2))
                .read(document, List.of(finding)).get(finding.id()).text());
    }

    @Test
    void failsClosedWhenTheTextCannotBeReadWithCertainty() {
        Document document = document(2);
        String text = joined(PAGE_1, PAGE_2);
        int start = PAGE_2.indexOf(SSN);

        Finding unlocated = finding(document, FindingLocation.unlocated(2));
        Finding pastEnd = finding(document, FindingLocation.withBounds(2, start, PAGE_2.length() + 1, box()));
        Finding wrongPage = finding(document, FindingLocation.withBounds(3, 0, 4, box()));
        Finding tooLong = finding(document, FindingLocation.withBounds(1, 0,
                ReviewFoundTextReader.MAX_TEXT_LENGTH + 1, box()));
        Finding blank = finding(document, FindingLocation.withBounds(1, 14, 15, box()));

        Map<FindingId, ReviewFoundText> texts = reader(document, text)
                .read(document, List.of(unlocated, pastEnd, wrongPage, tooLong, blank));

        assertTrue(texts.isEmpty(), "no finding may get text it cannot be sure of");
    }

    @Test
    void pageCountMismatchHidesEveryText() {
        // A page whose own text contains the separator would shift every later page.
        Document document = document(2);
        Finding ssn = locatedOnPage2(document, PAGE_2.indexOf(SSN), PAGE_2.indexOf(SSN) + SSN.length());
        String text = joined(PAGE_1 + TextExtractionService.PAGE_SEPARATOR + "extra", PAGE_2);

        assertTrue(reader(document, text).read(document, List.of(ssn)).isEmpty());
    }

    @Test
    void noSingleAvailableTextArtifactHidesEveryText() {
        Document document = document(2);
        Finding ssn = locatedOnPage2(document, PAGE_2.indexOf(SSN), PAGE_2.indexOf(SSN) + SSN.length());
        String text = joined(PAGE_1, PAGE_2);

        assertTrue(new ReviewFoundTextReader(new FakeArtifactRepository(List.of()), new FakeStoragePort(text))
                .read(document, List.of(ssn)).isEmpty(), "no artifact");
        assertTrue(new ReviewFoundTextReader(
                new FakeArtifactRepository(List.of(textArtifact(document), textArtifact(document))),
                new FakeStoragePort(text)).read(document, List.of(ssn)).isEmpty(), "two artifacts");

        Artifact failed = Artifact.create(tenantId, document.id(), ArtifactType.EXTRACTED_TEXT,
                MimeType.plainText(), 10, Checksum.sha256("t"));
        failed.markFailed();
        assertTrue(new ReviewFoundTextReader(new FakeArtifactRepository(List.of(failed)), new FakeStoragePort(text))
                .read(document, List.of(ssn)).isEmpty(), "artifact not available");
    }

    @Test
    void aReadErrorHidesTheTextInsteadOfFailingTheQueue() {
        Document document = document(2);
        Finding ssn = locatedOnPage2(document, PAGE_2.indexOf(SSN), PAGE_2.indexOf(SSN) + SSN.length());
        StoragePort broken = new FakeStoragePort(null);

        assertTrue(new ReviewFoundTextReader(new FakeArtifactRepository(List.of(textArtifact(document))), broken)
                .read(document, List.of(ssn)).isEmpty());
    }

    @Test
    void queueAddsTheTextOnlyToTheItemsItReturns() {
        DocumentPackage pkg = DocumentPackage.create(tenantId, PackageType.MANUAL_UPLOAD,
                ActorId.of(UUID.randomUUID()), PackageMetadata.empty());
        Document document = Document.reconstitute(DocumentId.of(UUID.randomUUID()), tenantId, pkg.id(),
                FileName.of("loan.pdf"), MimeType.pdf(), 100, Checksum.sha256("d"), DocumentType.PDF,
                Instant.now(), DocumentStatus.PROCESSED, pages(2), Instant.now());
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        pkg.completeProcessing();
        assertEquals(PackageStatus.PENDING_REVIEW, pkg.status());

        int start = PAGE_2.indexOf(SSN);
        Finding first = finding(document, FindingLocation.withBounds(1, 5, 14, box()));
        Finding second = finding(document, FindingLocation.withBounds(2, start, start + SSN.length(), box()));
        CountingStoragePort storage = new CountingStoragePort(joined(PAGE_1, PAGE_2));
        FocusedReviewService service = new FocusedReviewService(
                new FakePackageRepository(pkg), new FakeDocumentRepository(document),
                new FakeFindingRepository(List.of(first, second)), new FakePolicyRepository(),
                command -> { }, new AuditService(record -> { }),
                new ReviewFoundTextReader(new FakeArtifactRepository(List.of(textArtifact(document))), storage));

        ReviewQueueResult all = service.execute(tenantId, Optional.of(pkg.id()), 50);
        assertEquals(2, all.items().size());
        assertTrue(all.items().stream().allMatch(item -> item.foundText() != null));
        assertEquals(1, storage.reads, "one read per document, not per finding");
        assertEquals(TenantRedactionPolicy.defaults(tenantId).autoRedactThreshold(), all.autoRedactThreshold());

        ReviewQueueResult limited = service.execute(tenantId, Optional.of(pkg.id()), 1);
        assertEquals(1, limited.items().size());
        assertEquals(2, limited.totalItems());
        ReviewItemSummary only = limited.items().get(0);
        assertEquals(only.findingId().equals(first.id()) ? "agreement" : SSN, only.foundText().text());
    }

    @Test
    void afterTheStoredTextIsGoneTheQueueShowsTypeAndPageOnly() {
        // Task 0015: no value is saved with the finding, so once the extracted-text artifact is
        // deleted (or its file is gone) nothing can bring the found text back.
        DocumentPackage pkg = DocumentPackage.create(tenantId, PackageType.MANUAL_UPLOAD,
                ActorId.of(UUID.randomUUID()), PackageMetadata.empty());
        Document document = Document.reconstitute(DocumentId.of(UUID.randomUUID()), tenantId, pkg.id(),
                FileName.of("loan.pdf"), MimeType.pdf(), 100, Checksum.sha256("d"), DocumentType.PDF,
                Instant.now(), DocumentStatus.PROCESSED, pages(2), Instant.now());
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        pkg.completeProcessing();
        int start = PAGE_2.indexOf(SSN);
        Finding ssn = finding(document, FindingLocation.withBounds(2, start, start + SSN.length(), box()));

        Artifact deleted = textArtifact(document);
        deleted.delete();
        ReviewQueueResult artifactDeleted = queue(pkg, document, ssn,
                new ReviewFoundTextReader(new FakeArtifactRepository(List.of(deleted)),
                        new FakeStoragePort(joined(PAGE_1, PAGE_2))));
        ReviewQueueResult fileGone = queue(pkg, document, ssn,
                new ReviewFoundTextReader(new FakeArtifactRepository(List.of(textArtifact(document))),
                        new FakeStoragePort(null)));

        for (ReviewQueueResult result : List.of(artifactDeleted, fileGone)) {
            assertEquals(1, result.items().size());
            ReviewItemSummary item = result.items().get(0);
            assertNull(item.foundText(), "no found text once the original is gone");
            assertEquals(EntityType.SSN, item.entityType());
            assertEquals(2, item.pageNumber());
            assertTrue(!item.toString().contains("0193"), "no part of the value in the item");
        }
    }

    private ReviewQueueResult queue(DocumentPackage pkg, Document document, Finding finding,
                                    ReviewFoundTextReader reader) {
        FocusedReviewService service = new FocusedReviewService(
                new FakePackageRepository(pkg), new FakeDocumentRepository(document),
                new FakeFindingRepository(List.of(finding)), new FakePolicyRepository(),
                command -> { }, new AuditService(record -> { }), reader);
        return service.execute(tenantId, Optional.of(pkg.id()), 50);
    }

    // --- helpers ------------------------------------------------------------------------------

    private Document document(int pageCount) {
        return Document.reconstitute(DocumentId.of(UUID.randomUUID()), tenantId, packageId,
                FileName.of("loan.pdf"), MimeType.pdf(), 100, Checksum.sha256("d"), DocumentType.PDF,
                Instant.now(), DocumentStatus.PROCESSED, pages(pageCount), Instant.now());
    }

    private static List<Page> pages(int count) {
        return new ArrayList<>(IntStream.rangeClosed(1, count).mapToObj(n -> Page.of(n, 0, 0)).toList());
    }

    private Finding locatedOnPage2(Document document, int start, int end) {
        return finding(document, FindingLocation.withBounds(2, start, end, box()));
    }

    private Finding finding(Document document, FindingLocation location) {
        return Finding.create(tenantId, document.id(), EntityType.SSN, location, Confidence.of(0.62),
                DetectorSource.of("test"));
    }

    private static FindingLocation.BoundingBox box() {
        return FindingLocation.BoundingBox.of(0.1, 0.2, 0.3, 0.04);
    }

    private static String joined(String... pages) {
        return String.join(TextExtractionService.PAGE_SEPARATOR, pages);
    }

    private Artifact textArtifact(Document document) {
        Artifact artifact = Artifact.create(tenantId, document.id(), ArtifactType.EXTRACTED_TEXT,
                MimeType.plainText(), 10, Checksum.sha256("t"));
        artifact.markAvailable(ArtifactStorageReference.local("text.txt"));
        return artifact;
    }

    private ReviewFoundTextReader reader(Document document, String fullText) {
        return new ReviewFoundTextReader(new FakeArtifactRepository(List.of(textArtifact(document))),
                new FakeStoragePort(fullText));
    }

    private record FakeArtifactRepository(List<Artifact> artifacts) implements ArtifactRepository {
        @Override public void save(Artifact artifact) { }
        @Override public Optional<Artifact> findByIdAndTenantId(ArtifactId id, TenantId tenantId) {
            return artifacts.stream().filter(a -> a.id().equals(id)).findFirst();
        }
        @Override public List<Artifact> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) {
            return artifacts.stream()
                    .filter(a -> a.documentId().equals(documentId) && a.tenantId().equals(tenantId))
                    .toList();
        }
    }

    private static class FakeStoragePort implements StoragePort {
        private final String text;
        FakeStoragePort(String text) { this.text = text; }
        @Override
        public ArtifactStorageReference store(TenantId tenantId, String path, InputStream content,
                                              long sizeBytes, String mimeType) {
            throw new UnsupportedOperationException();
        }
        @Override public InputStream retrieve(ArtifactStorageReference reference) {
            if (text == null) {
                throw new UncheckedIOException(new java.io.IOException("storage unavailable"));
            }
            return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        }
        @Override public void delete(ArtifactStorageReference reference) { }
    }

    private static final class CountingStoragePort extends FakeStoragePort {
        int reads;
        CountingStoragePort(String text) { super(text); }
        @Override public InputStream retrieve(ArtifactStorageReference reference) {
            reads++;
            return super.retrieve(reference);
        }
    }

    private record FakeFindingRepository(List<Finding> findings) implements FindingRepository {
        @Override public void save(Finding finding) { }
        @Override public void saveAll(List<Finding> findings) { }
        @Override public Optional<Finding> findByIdAndTenantId(FindingId id, TenantId tenantId) {
            return findings.stream().filter(f -> f.id().equals(id)).findFirst();
        }
        @Override public List<Finding> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) {
            return findings.stream().filter(f -> f.documentId().equals(documentId)).toList();
        }
        @Override public void deleteByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) { }
    }

    private record FakeDocumentRepository(Document document) implements DocumentRepository {
        @Override public void save(Document document) { }
        @Override public Optional<Document> findByIdAndTenantId(DocumentId id, TenantId tenantId) {
            return document.id().equals(id) ? Optional.of(document) : Optional.empty();
        }
        @Override public List<Document> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
            return document.packageId().equals(packageId) ? List.of(document) : List.of();
        }
    }

    private record FakePackageRepository(DocumentPackage pkg) implements PackageRepository {
        @Override public void save(DocumentPackage documentPackage) { }
        @Override public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId tenantId) {
            return pkg.id().equals(id) ? Optional.of(pkg) : Optional.empty();
        }
        @Override public List<DocumentPackage> findByTenantId(TenantId tenantId, int offset, int limit) {
            return List.of(pkg);
        }
        @Override public long countByTenantId(TenantId tenantId) { return 1; }
        @Override public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(
                TenantId tenantId, String fingerprint) { return Optional.empty(); }
        @Override public List<DocumentPackage> findByStatus(PackageStatus status, int limit) { return List.of(); }
    }

    private static final class FakePolicyRepository implements TenantRedactionPolicyRepository {
        @Override public Optional<TenantRedactionPolicy> findByTenantId(TenantId tenantId) {
            return Optional.empty();
        }
        @Override public void save(TenantRedactionPolicy policy) { }
    }
}

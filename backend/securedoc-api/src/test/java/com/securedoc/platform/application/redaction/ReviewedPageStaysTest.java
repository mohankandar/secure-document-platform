package com.securedoc.platform.application.redaction;

import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.AuditQueryPort;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.PageRouteRepository;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.redaction.command.RedactDocumentCommand;
import com.securedoc.platform.application.redaction.service.DocumentRedactionService;
import com.securedoc.platform.application.secondcheck.FinalPatternCheck;
import com.securedoc.platform.application.secondcheck.SecondCheck;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.business.PageBatchSettings;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.business.VisionSettings;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.document.Page;
import com.securedoc.platform.domain.finding.AiModel;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingLocation.BoundingBox;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vision.PageRoute;
import com.securedoc.platform.domain.vision.VisionPageResult;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.Confidence;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import com.securedoc.platform.infrastructure.detection.LocalRuleBasedDetectionAdapter;
import com.securedoc.platform.infrastructure.extraction.PdfBoxTextExtractionAdapter;
import com.securedoc.platform.infrastructure.extraction.PdfPageImageAdapter;
import com.securedoc.platform.infrastructure.redaction.PdfBoxBurnFlatRedactionAdapter;
import com.securedoc.platform.infrastructure.redaction.PdfBoxRedactionVerificationAdapter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 0119 (owner test 2026-11-27, case 000642000001, long-20 page 5): a page a person reviewed must not come back,
 * and a case stops for a person once. Page 1 is typed, page 2 is the handwritten note (a picture in the original),
 * page 3 is typed. The vision model answers as the owner's did on page 5: an answer that can't be used. Fake values.
 */
class ReviewedPageStaysTest {
    private static final String SSN_ONE = "412-55-7531";
    private static final String DOB = "01/25/1966";
    private static final String SSN_THREE = "518-22-7741";
    private static final AiModel VISION = AiModel.of("vl:1", "second-check-vision-v1", "bb");
    private static final VisionPageResult UNUSABLE = VisionPageResult.notRead(2, "LV-AI-005", "ANSWER_NOT_READABLE");

    private final TenantId tenant = TenantId.generate();
    private final DocumentPackage pkg = processingPackage(tenant);
    private Document document;
    private final List<Finding> stored = new ArrayList<>();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final List<byte[]> outputs = new ArrayList<>();
    private final List<Integer> visionAsked = new ArrayList<>();
    private final Set<String> decidedByAPerson = new HashSet<>();
    private VisionPageResult visionAnswer = UNUSABLE;
    private byte[] source;
    private boolean secondCheck;
    private boolean reread = true;

    @BeforeEach
    void setUp() throws Exception {
        source = pdf();
        document = Document.reconstitute(com.securedoc.platform.domain.identity.DocumentId.generate(), tenant, pkg.id(),
                FileName.of("long-20-pages.pdf"), MimeType.pdf(), 64, Checksum.sha256("a"), DocumentType.PDF,
                Instant.now(), DocumentStatus.REDACTING,
                List.of(Page.of(1, 612, 792), Page.of(2, 612, 792), Page.of(3, 612, 792)), Instant.now());
    }

    @AfterEach
    void noValueAnywhere() {
        for (AuditRecord audit : audits) {
            for (String value : List.of(SSN_ONE, DOB, SSN_THREE)) {
                assertFalse(audit.detail() != null && audit.detail().contains(value), "an audit record holds a value");
            }
        }
    }

    /** The owner's case: the person confirms the date of birth on page 2; the final check does not send it back. */
    @Test
    void theOwnersHandwrittenPageIsReviewedOnceAndNeverReadAgain() {
        stored.add(finding(1, SSN_ONE, EntityType.SSN, FindingStatus.CONFIRMED));
        Finding dob = finding(2, DOB, EntityType.DATE_OF_BIRTH, FindingStatus.DETECTED);
        stored.add(dob);
        stored.add(finding(3, SSN_THREE, EntityType.SSN, FindingStatus.CONFIRMED));

        assertFalse(service().checkBeforeReview(command()), "nothing more for the review");
        assertEquals(List.of(), visionAsked, "a page a person will look at is not read before the review");

        personConfirms(dob);
        service().execute(command());

        assertEquals(1, outputs.size(), "the file is kept: one review only");
        assertEquals(DocumentStatus.PROCESSED, document.status());
        assertTrue(stored.stream().noneMatch(f -> f.entityType() == EntityType.UNREAD_PAGE), "no page sent back");
        assertEquals(List.of(), visionAsked, "the page the person finished is never read again");
        assertTrue(finalCheck().contains("checkedByAPersonBefore=1"), finalCheck());
    }

    /** One review stop: a page nobody reviewed that can't be read joins the review the case stops for anyway. */
    @Test
    void anUnreadablePageNobodyReviewedGoesToAPersonInTheSameReview() {
        Finding ssn = finding(1, SSN_ONE, EntityType.SSN, FindingStatus.DETECTED);
        stored.add(ssn);
        stored.add(finding(2, DOB, EntityType.DATE_OF_BIRTH, FindingStatus.CONFIRMED));
        stored.add(finding(3, SSN_THREE, EntityType.SSN, FindingStatus.CONFIRMED));

        assertTrue(service().checkBeforeReview(command()));
        Finding page = onlyPageCheck();
        assertEquals(2, page.location().pageNumber());
        assertEquals(List.of("UNREAD"), FinalPatternCheck.kindsOf(page));
        assertEquals(List.of(2, 2), visionAsked, "asked once more, then held");
        assertEquals(List.of(), outputs, "nothing is kept before the review");
        assertEquals(DocumentStatus.REDACTING, document.status());
        assertTrue(finalCheck().startsWith("Before review: Final check:"), finalCheck());

        // Run again (a job run twice): the page is not added twice.
        service().checkBeforeReview(command());
        assertEquals(1, stored.stream().filter(f -> f.entityType() == EntityType.UNREAD_PAGE).count());

        // The person decides both; the case goes on without a second stop.
        personConfirms(ssn);
        personChecksPage(page);
        visionAsked.clear();
        service().execute(command());
        assertEquals(1, outputs.size());
        assertEquals(List.of(), visionAsked);
    }

    /** A value still readable on a page a person finished is our defect: Stopped, never back to the reviewer. */
    @Test
    void aValueLeftOnAPageAPersonFinishedStopsTheDocument() {
        Finding kept = finding(3, "Co-borrower", EntityType.DATE_OF_BIRTH, FindingStatus.DETECTED);
        stored.add(kept);
        personDismisses(kept);

        TechnicalDependencyException stopped = assertThrows(TechnicalDependencyException.class,
                () -> service().execute(command()));
        assertEquals(TechnicalFailureCode.REDACTION_VALUE_LEFT_ON_CHECKED_PAGE, stopped.failureCode());
        assertTrue(stored.stream().noneMatch(f -> f.entityType() == EntityType.UNREAD_PAGE), "not sent back");
        assertEquals(List.of(), outputs, "never kept");
        assertEquals(DocumentStatus.FAILED, document.status());
        assertTrue(finalCheck().contains("leftOnCheckedPages=[p3]"), finalCheck());
    }

    /** The same value on a page nobody looked at still goes to a person. */
    @Test
    void aValueOnAPageNobodyLookedAtGoesToAPerson() {
        stored.add(finding(1, SSN_ONE, EntityType.SSN, FindingStatus.CONFIRMED));
        stored.add(finding(2, DOB, EntityType.DATE_OF_BIRTH, FindingStatus.CONFIRMED));
        visionAnswer = VisionPageResult.read(2, VISION, List.of(), false);
        service().execute(command());
        Finding page = onlyPageCheck();
        assertEquals(3, page.location().pageNumber());
        assertEquals(List.of("SSN"), FinalPatternCheck.kindsOf(page));
        assertEquals(List.of(), outputs);
    }

    /** The Business Admin's switch: off, picture pages are not read again; the text search of every page still runs. */
    @Test
    void withTheReReadOffNoPicturePageIsReadAgainButEveryPageIsStillSearched() {
        reread = false;
        stored.add(finding(1, SSN_ONE, EntityType.SSN, FindingStatus.CONFIRMED));
        stored.add(finding(2, DOB, EntityType.DATE_OF_BIRTH, FindingStatus.CONFIRMED));
        service().execute(command());
        assertEquals(List.of(), visionAsked);
        assertTrue(finalCheck().contains("readAgain=0"), finalCheck());
        assertEquals(List.of("SSN"), FinalPatternCheck.kindsOf(onlyPageCheck()), "page 3 is still found");
    }

    /** With the second check on, one vision reading of a picture page serves both checks. */
    @Test
    void oneVisionReadingServesTheFinalCheckAndTheSecondCheck() {
        secondCheck = true;
        visionAnswer = VisionPageResult.read(2, VISION, List.of(), false);
        stored.add(finding(1, SSN_ONE, EntityType.SSN, FindingStatus.CONFIRMED));
        stored.add(finding(2, DOB, EntityType.DATE_OF_BIRTH, FindingStatus.CONFIRMED));
        stored.add(finding(3, SSN_THREE, EntityType.SSN, FindingStatus.CONFIRMED));
        service().execute(command());
        assertEquals(1, outputs.size());
        assertEquals(1, visionAsked.stream().filter(p -> p == 2).count(), "page 2 is read once: " + visionAsked);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private void personConfirms(Finding finding) {
        finding.confirm(ActorId.generate(), "checked");
        decidedByAPerson.add(finding.id().value().toString());
    }

    private void personDismisses(Finding finding) {
        finding.dismiss(ActorId.generate(), "keep");
        decidedByAPerson.add(finding.id().value().toString());
    }

    private void personChecksPage(Finding page) {
        page.dismiss(ActorId.generate(), "Page reviewed by hand");
        decidedByAPerson.add(page.id().value().toString());
    }

    private Finding onlyPageCheck() {
        List<Finding> pages = stored.stream().filter(f -> f.entityType() == EntityType.UNREAD_PAGE).toList();
        assertEquals(1, pages.size(), pages.toString());
        return pages.get(0);
    }

    private String finalCheck() {
        return audits.stream().filter(a -> a.action() == AuditAction.DOCUMENT_FINAL_CHECK).reduce((a, b) -> b)
                .orElseThrow().detail();
    }

    private static DocumentPackage processingPackage(TenantId tenant) {
        DocumentPackage value = DocumentPackage.create(tenant, PackageType.MANUAL_UPLOAD, ActorId.generate(),
                PackageMetadata.empty());
        value.addDocument(Document.create(tenant, value.id(), FileName.of("a.pdf"), MimeType.pdf(), 64,
                Checksum.sha256("a"), DocumentType.PDF));
        value.submit();
        value.startProcessing();
        return value;
    }

    private RedactDocumentCommand command() {
        return RedactDocumentCommand.of(document.id(), pkg.id(), tenant, CorrelationId.generate());
    }

    private DocumentRedactionService service() {
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findByIdAndTenantId(document.id(), tenant)).thenAnswer(i -> Optional.of(document));
        PackageRepository packages = mock(PackageRepository.class);
        when(packages.findByIdAndTenantId(pkg.id(), tenant)).thenReturn(Optional.of(pkg));
        Artifact original = Artifact.create(tenant, document.id(), ArtifactType.ORIGINAL, MimeType.pdf(), source.length,
                Checksum.sha256("original"));
        original.markAvailable(ArtifactStorageReference.local("t/p/d/ORIGINAL/a"));
        ArtifactRepository artifacts = mock(ArtifactRepository.class);
        when(artifacts.findByDocumentIdAndTenantId(document.id(), tenant)).thenReturn(List.of(original));
        StoragePort storage = mock(StoragePort.class);
        when(storage.retrieve(any())).thenAnswer(i -> new ByteArrayInputStream(source));
        when(storage.storeForPackage(any(), any(), anyString(), any(), anyLong(), anyString())).thenAnswer(i -> {
            outputs.add(((InputStream) i.getArgument(3)).readAllBytes());
            return ArtifactStorageReference.local("t/p/d/REDACTED/r");
        });
        FindingRepository findings = mock(FindingRepository.class);
        when(findings.findByDocumentIdAndTenantId(document.id(), tenant)).thenAnswer(i -> List.copyOf(stored));
        doAnswer(i -> {
            for (Finding f : i.<List<Finding>>getArgument(0)) if (!stored.contains(f)) stored.add(f);
            return null;
        }).when(findings).saveAll(any());
        doAnswer(i -> {
            Document saved = i.getArgument(0);
            document = saved;
            return null;
        }).when(documents).save(any());
        ChecksumPort checksum = mock(ChecksumPort.class);
        when(checksum.compute(any())).thenReturn(Checksum.sha256("redacted"));
        TenantRedactionPolicy policy = new TenantRedactionPolicy(tenant,
                EnumSet.of(EntityType.SSN, EntityType.DATE_OF_BIRTH), 0.8, ReviewMode.UNCERTAIN_ONLY, true,
                Instant.now(), false, PageBatchSettings.DEFAULTS, Set.of(), Set.of(), false,
                new VisionSettings(true, 0.85), secondCheck, false, reread);
        TenantRedactionPolicyRepository policies = new TenantRedactionPolicyRepository() {
            @Override public Optional<TenantRedactionPolicy> findByTenantId(TenantId t) { return Optional.of(policy); }
            @Override public void save(TenantRedactionPolicy p) { }
        };
        // Page 2 was a picture in the original (read by OCR, 0.88), as the owner's page 5.
        PageRouteRepository routes = new PageRouteRepository() {
            @Override public void save(TenantId t, com.securedoc.platform.domain.identity.DocumentId d,
                                       List<PageRoute> r) { }
            @Override public List<PageRoute> findByDocument(TenantId t,
                                                            com.securedoc.platform.domain.identity.DocumentId d) {
                return List.of(new PageRoute(1, PageRoute.Path.FAST, Set.of(), null, List.of()),
                        new PageRoute(2, PageRoute.Path.FAST, Set.of(), 0.88, List.of()),
                        new PageRoute(3, PageRoute.Path.FAST, Set.of(), null, List.of()));
            }
        };
        com.securedoc.platform.application.port.out.VisionModelPort vision = (image, page) -> {
            visionAsked.add(page);
            return visionAnswer.read() ? VisionPageResult.read(page, VISION, List.of(), false)
                    : VisionPageResult.notRead(page, visionAnswer.code(), visionAnswer.reason());
        };
        AuditQueryPort decisions = new AuditQueryPort() {
            @Override public List<com.securedoc.platform.application.audit.query.AuditRecordSummary> findByFilter(
                    com.securedoc.platform.application.audit.query.AuditRecordFilter filter) {
                return List.of();
            }

            @Override public Set<String> findingsDecidedByAPerson(TenantId t, String caseId) {
                assertEquals(pkg.id().value().toString(), caseId);
                return Set.copyOf(decidedByAPerson);
            }
        };
        SensitiveContentAnalysisPort text = request -> new SensitiveContentAnalysisPort.AnalysisOutput(List.of(),
                List.of(AiModel.of("text:1", "second-check-text-v1", "aa")));
        PdfBoxTextExtractionAdapter reading = new PdfBoxTextExtractionAdapter();
        return new DocumentRedactionService(documents, packages, artifacts, findings, storage,
                new PdfBoxBurnFlatRedactionAdapter(), new PdfBoxRedactionVerificationAdapter(), checksum,
                mock(DomainEventPublisher.class), new AuditService(audits::add), policies, reading)
                .withFinalCheck(new FinalPatternCheck(reading, new LocalRuleBasedDetectionAdapter())
                        .withVision(routes, new PdfPageImageAdapter(), vision))
                .withSecondCheck(new SecondCheck(reading, text, new PdfPageImageAdapter(), vision))
                .withPersonDecisions(decisions);
    }

    private Finding finding(int number, String value, EntityType kind, FindingStatus status) {
        var page = new PdfBoxTextExtractionAdapter().extract(new ByteArrayInputStream(source), "application/pdf").pages()
                .get(number - 1);
        int start = page.text().indexOf(value);
        assertTrue(start >= 0, value);
        int end = start + value.length();
        double left = 1, top = 1, right = 0, bottom = 0;
        for (var s : page.spans()) {
            if (s.endOffset() <= start || s.startOffset() >= end) continue;
            left = Math.min(left, s.left());
            top = Math.min(top, s.top());
            right = Math.max(right, s.left() + s.width());
            bottom = Math.max(bottom, s.top() + s.height());
        }
        return Finding.reconstitute(FindingId.generate(), tenant, document.id(), kind,
                FindingLocation.withBounds(number, start, end, BoundingBox.of(left - 0.004, top - 0.004,
                        right - left + 0.008, bottom - top + 0.008)), new Confidence(0.99), DetectorSource.of("test"),
                Instant.now(), status, List.of(), Instant.now(),
                com.securedoc.platform.domain.business.RedactionStyle.REMOVE_FULLY);
    }

    private static byte[] pdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String line : List.of("Borrower SSN: " + SSN_ONE, "Handwritten note p.5 DOB " + DOB,
                    "Co-borrower of the same loan, SSN: " + SSN_THREE)) {
                PDPage page = new PDPage(new PDRectangle(612, 792));
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 14);
                    cs.newLineAtOffset(72, 700);
                    cs.showText(line);
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}

package com.securedoc.platform.application.review.service;

import com.securedoc.platform.application.exception.ReviewAlreadyDecidedException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.review.command.ResolveReviewItemCommand;
import com.securedoc.platform.application.review.model.ReviewResolution;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.Confidence;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Task 0074: pages checked by hand in Review. */
class ReviewPageCheckTest {

    private static final FindingLocation.BoundingBox BOX = FindingLocation.BoundingBox.of(0.1, 0.2, 0.3, 0.04);
    private static final FindingLocation.BoundingBox OTHER = FindingLocation.BoundingBox.of(0.5, 0.6, 0.2, 0.05);

    private final TenantId tenant = TenantId.generate();
    private final ActorId reviewer = ActorId.generate();
    private final DocumentPackage pkg = DocumentPackage.create(tenant, PackageType.MANUAL_UPLOAD, reviewer, PackageMetadata.empty());
    private final Document document = Document.reconstitute(DocumentId.generate(), tenant, pkg.id(), FileName.of("loan.pdf"),
            MimeType.pdf(), 100, Checksum.sha256("d".repeat(64)), DocumentType.PDF, Instant.now(),
            DocumentStatus.REDACTING, List.of(), Instant.now());
    private final List<Finding> stored = new ArrayList<>();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final FindingRepository findings = mock(FindingRepository.class);
    private final PackageProcessingDispatcher dispatcher = mock(PackageProcessingDispatcher.class);
    private FocusedReviewService service;
    private Finding page2;
    private Finding page5;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(pkg, "status", PackageStatus.PENDING_REVIEW);
        page2 = toCheck(2);
        page5 = toCheck(5);
        when(findings.findByIdAndTenantId(any(), any())).thenAnswer(call -> stored.stream()
                .filter(f -> f.id().equals(call.getArgument(0))).findFirst());
        when(findings.findByDocumentIdAndTenantId(document.id(), tenant)).thenAnswer(call -> List.copyOf(stored));
        when(findings.saveDecisionIfPending(any())).thenReturn(true);
        org.mockito.Mockito.doAnswer(call -> stored.addAll(call.getArgument(0))).when(findings).saveAll(any());
        DocumentRepository documents = mock(DocumentRepository.class);
        when(documents.findByIdAndTenantId(document.id(), tenant)).thenReturn(Optional.of(document));
        when(documents.findByPackageIdAndTenantId(pkg.id(), tenant)).thenReturn(List.of(document));
        PackageRepository packages = mock(PackageRepository.class);
        when(packages.findByIdAndTenantId(pkg.id(), tenant)).thenReturn(Optional.of(pkg));
        when(packages.changeStatus(any(), any())).thenReturn(true);
        service = new FocusedReviewService(packages, documents, findings, mock(TenantRedactionPolicyRepository.class),
                dispatcher, new AuditService(audits::add), mock(ReviewFoundTextReader.class));
    }

    @Test
    void theCaseGoesOnOnlyWhenBothPagesAreConfirmed() {
        assertFalse(check(page2, true, List.of(BOX, OTHER)).processingResumed());
        verify(dispatcher, never()).dispatch(any());
        assertEquals(PackageStatus.PENDING_REVIEW, pkg.status());

        assertTrue(check(page5, true, List.of()).processingResumed());
        verify(dispatcher, times(1)).dispatch(any());
        assertEquals(PackageStatus.PROCESSING, pkg.status());

        List<Finding> drawn = stored.stream().filter(f -> f.status() == FindingStatus.CONFIRMED).toList();
        assertEquals(List.of(BOX, OTHER), drawn.stream().map(f -> f.location().boundingBox()).toList());
        assertTrue(drawn.stream().allMatch(f -> f.location().pageNumber() == 2 && f.location().startOffset() == 0
                && f.location().endOffset() == 0), "a box only: no text is kept");
        assertEquals(List.of(FindingStatus.DISMISSED, FindingStatus.DISMISSED), List.of(page2.status(), page5.status()));
    }

    @Test
    void whoAndThePageNumbersAreAudited() {
        check(page2, true, List.of(BOX, OTHER));
        check(page5, true, List.of());
        List<AuditRecord> checked = audits.stream().filter(a -> a.action() == AuditAction.REVIEW_PAGE_CHECKED).toList();
        assertEquals(List.of("Unreadable page checked by hand: document=" + document.id() + ", page=2, boxes=2",
                "Unreadable page checked by hand: document=" + document.id() + ", page=5, boxes=0"),
                checked.stream().map(AuditRecord::detail).toList());
        assertTrue(checked.stream().allMatch(a -> a.actorId().equals(reviewer)));
    }

    @Test
    void nothingGetsRoundTheConfirmation() {
        assertRefused(LvErrorCode.REV_004, () -> check(page2, false, List.of(BOX)));
        assertRefused(LvErrorCode.REV_004, () -> service.execute(decision(ReviewResolution.DISMISS, null)));
        assertRefused(LvErrorCode.REV_004, () -> service.execute(decision(ReviewResolution.CONFIRM, 0.1)));
        assertRefused(LvErrorCode.REV_006, () -> check(page2, true, Collections.nCopies(201, BOX)));
        assertEquals(FindingStatus.DETECTED, page2.status());
        assertEquals(2, stored.size(), "no box was saved");
        verify(findings, never()).saveDecisionIfPending(any());
        assertTrue(audits.isEmpty());
    }

    @Test
    void onlyAPageToCheckAndOnlyWhileTheCaseWaits() {
        Finding ssn = Finding.create(tenant, document.id(), EntityType.SSN, FindingLocation.withBounds(1, 0, 9, BOX),
                Confidence.of(0.6), DetectorSource.of("test"));
        stored.add(ssn);
        assertRefused(LvErrorCode.REV_006, () -> check(ssn, true, List.of()));
        for (PackageStatus status : List.of(PackageStatus.REJECTED, PackageStatus.PROCESSING, PackageStatus.COMPLETED)) {
            ReflectionTestUtils.setField(pkg, "status", status);
            assertRefused(LvErrorCode.REV_002, () -> check(page2, true, List.of(BOX)));
        }
        assertEquals(3, stored.size());
        assertTrue(audits.isEmpty());
    }

    @Test
    void aPageSomeoneElseCheckedIsRefusedAndNotAudited() {
        when(findings.saveDecisionIfPending(any())).thenReturn(false);
        assertThrows(ReviewAlreadyDecidedException.class, () -> check(page2, true, List.of(BOX)));
        assertTrue(audits.isEmpty(), "the transaction also takes the boxes back (ReviewPageCheckController)");
        assertThrows(ReviewAlreadyDecidedException.class, () -> check(page2, true, List.of(BOX)));
    }

    private Finding toCheck(int page) {
        Finding finding = Finding.create(tenant, document.id(), EntityType.UNREAD_PAGE, FindingLocation.unlocated(page),
                Confidence.of(0.0), new DetectorSource("vision-page-check", "LV-PRC-002"));
        stored.add(finding);
        return finding;
    }

    private com.securedoc.platform.application.review.result.ResolveReviewItemResult check(Finding page, boolean reviewed,
                                                                                         List<FindingLocation.BoundingBox> boxes) {
        return service.checkPage(page.id(), tenant, reviewer, CorrelationId.generate(), reviewed, boxes);
    }

    private ResolveReviewItemCommand decision(ReviewResolution resolution, Double at) {
        return new ResolveReviewItemCommand(page2.id(), tenant, reviewer, resolution, null, at, at, at, at,
                CorrelationId.generate());
    }

    private static void assertRefused(LvErrorCode code, org.junit.jupiter.api.function.Executable call) {
        assertEquals(code, assertThrows(ValidationException.class, call).lvCode());
    }
}

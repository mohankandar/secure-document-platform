package com.securedoc.platform.application.review.service;

import com.securedoc.platform.application.exception.ReviewAlreadyDecidedException;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.review.command.ResolveReviewItemCommand;
import com.securedoc.platform.application.review.model.ReviewResolution;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.finding.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.vo.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0023 (D5, #160): "already decided by someone else" is one code, also when two reviewers race. */
class ReviewAlreadyDecidedTest {
    private final TenantId tenant = TenantId.generate();
    private FindingRepository findings;
    private AuditService audit;
    private FocusedReviewService service;
    private Finding finding;

    @BeforeEach
    void setUp() {
        DocumentPackage pkg = DocumentPackage.create(tenant, PackageType.MANUAL_UPLOAD, ActorId.generate(), PackageMetadata.empty());
        Document document = Document.reconstitute(DocumentId.generate(), tenant, pkg.id(), FileName.of("loan.pdf"),
                MimeType.pdf(), 100, Checksum.sha256("d".repeat(64)), DocumentType.PDF, Instant.now(),
                DocumentStatus.PROCESSED, List.of(), Instant.now());
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        pkg.completeProcessing();
        finding = Finding.create(tenant, document.id(), EntityType.SSN,
                FindingLocation.withBounds(1, 0, 9, FindingLocation.BoundingBox.of(0.1, 0.2, 0.3, 0.04)),
                Confidence.of(0.62), DetectorSource.of("test"));

        findings = mock(FindingRepository.class);
        DocumentRepository documents = mock(DocumentRepository.class);
        PackageRepository packages = mock(PackageRepository.class);
        audit = mock(AuditService.class);
        when(findings.findByIdAndTenantId(finding.id(), tenant)).thenReturn(Optional.of(finding));
        when(documents.findByIdAndTenantId(document.id(), tenant)).thenReturn(Optional.of(document));
        when(packages.findByIdAndTenantId(pkg.id(), tenant)).thenReturn(Optional.of(pkg));
        // Task 0071: the case is still waiting when the decision is saved.
        when(packages.changeStatus(any(), any())).thenReturn(true);
        service = new FocusedReviewService(packages, documents, findings, mock(TenantRedactionPolicyRepository.class),
                mock(PackageProcessingDispatcher.class), audit, mock(ReviewFoundTextReader.class));
    }

    private ResolveReviewItemCommand dismiss() {
        return new ResolveReviewItemCommand(finding.id(), tenant, ActorId.generate(), ReviewResolution.DISMISS,
                null, null, null, null, null, CorrelationId.generate());
    }

    @Test
    void anItemAlreadyDecidedGivesTheOneCode() {
        finding.confirm(ActorId.generate(), null);

        ReviewAlreadyDecidedException ex = assertThrows(ReviewAlreadyDecidedException.class, () -> service.execute(dismiss()));

        assertEquals("REVIEW_ALREADY_DECIDED", ex.errorCode());
        assertEquals("This item was already decided by someone else.", ex.getMessage());
        verify(findings, never()).saveDecisionIfPending(any());
        verifyNoInteractions(audit);
    }

    @Test
    void aDecisionThatLosesTheRaceGivesTheSameCodeAndWritesNoDecisionAudit() {
        // Both reviewers read the item while it was pending; the other one's save landed first.
        when(findings.saveDecisionIfPending(any())).thenReturn(false);

        ReviewAlreadyDecidedException ex = assertThrows(ReviewAlreadyDecidedException.class, () -> service.execute(dismiss()));

        assertEquals(ReviewAlreadyDecidedException.ERROR_CODE, ex.errorCode());
        verifyNoInteractions(audit);
    }

    @Test
    void aDecisionThatWinsIsSavedAndAudited() {
        when(findings.saveDecisionIfPending(any())).thenReturn(true);
        when(findings.findByDocumentIdAndTenantId(any(), eq(tenant))).thenReturn(List.of(finding));

        service.execute(dismiss());

        verify(findings).saveDecisionIfPending(finding);
        verify(audit).recordSuccess(eq(tenant), any(), eq(com.securedoc.platform.domain.audit.AuditAction.FINDING_DISMISSED),
                eq("Finding"), eq(finding.id().toString()), any(PackageId.class), any(), anyString());
    }
}

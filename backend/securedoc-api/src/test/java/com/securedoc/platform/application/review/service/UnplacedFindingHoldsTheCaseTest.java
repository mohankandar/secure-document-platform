package com.securedoc.platform.application.review.service;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.review.command.ResolveReviewItemCommand;
import com.securedoc.platform.application.review.model.ReviewResolution;
import com.securedoc.platform.application.service.AuditService;
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

/**
 * Task 0100 (R8): a finding of a kind the entity redacts that has no place on the page (here a date of birth found in
 * the page text of a handwritten form) is never skipped. It holds the case until a person marks its place or decides
 * to keep it visible; "Redact" without a place is refused. Only then does the case go on.
 */
class UnplacedFindingHoldsTheCaseTest {

    private final TenantId tenant = TenantId.generate();
    private final ActorId reviewer = ActorId.generate();
    private final DocumentPackage pkg = DocumentPackage.create(tenant, PackageType.MANUAL_UPLOAD, reviewer,
            PackageMetadata.empty());
    private final Document document = Document.reconstitute(DocumentId.generate(), tenant, pkg.id(),
            FileName.of("form.pdf"), MimeType.pdf(), 100, Checksum.sha256("d".repeat(64)), DocumentType.PDF,
            Instant.now(), DocumentStatus.REDACTING, List.of(), Instant.now());
    private final List<Finding> stored = new ArrayList<>();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final FindingRepository findings = mock(FindingRepository.class);
    private final PackageProcessingDispatcher dispatcher = mock(PackageProcessingDispatcher.class);
    private FocusedReviewService service;
    private Finding placed;
    private Finding unplaced;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(pkg, "status", PackageStatus.PENDING_REVIEW);
        placed = finding(EntityType.SSN, FindingLocation.withBounds(1, 10, 21,
                FindingLocation.BoundingBox.of(0.1, 0.2, 0.2, 0.03)));
        unplaced = finding(EntityType.DATE_OF_BIRTH, FindingLocation.unlocated(1));
        when(findings.findByIdAndTenantId(any(), any())).thenAnswer(call -> stored.stream()
                .filter(f -> f.id().equals(call.getArgument(0))).findFirst());
        when(findings.findByDocumentIdAndTenantId(document.id(), tenant)).thenAnswer(call -> List.copyOf(stored));
        when(findings.saveDecisionIfPending(any())).thenReturn(true);
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
    void redactWithoutAPlaceIsRefusedAndTheCaseWaits() {
        ValidationException refused = assertThrows(ValidationException.class,
                () -> service.execute(decide(unplaced, ReviewResolution.CONFIRM, null)));
        assertEquals(LvErrorCode.REV_006, refused.lvCode());
        assertEquals(FindingStatus.DETECTED, unplaced.status());

        assertFalse(service.execute(decide(placed, ReviewResolution.CONFIRM, null)).processingResumed(),
                "every other finding decided, and still the case waits for the one without a place");
        assertEquals(PackageStatus.PENDING_REVIEW, pkg.status());
        verify(dispatcher, never()).dispatch(any());
    }

    @Test
    void aPlaceMarkedByAPersonLetsTheCaseGoOn() {
        service.execute(decide(placed, ReviewResolution.CONFIRM, null));
        assertTrue(service.execute(decide(unplaced, ReviewResolution.CONFIRM, 0.4)).processingResumed());
        assertEquals(FindingStatus.CONFIRMED, unplaced.status());
        assertTrue(unplaced.location().hasBoundingBox(), "it is redacted where the person marked it");
        verify(dispatcher, times(1)).dispatch(any());
    }

    @Test
    void keepVisibleIsAPersonsDecisionToo() {
        service.execute(decide(placed, ReviewResolution.CONFIRM, null));
        assertTrue(service.execute(decide(unplaced, ReviewResolution.DISMISS, null)).processingResumed());
        assertEquals(FindingStatus.DISMISSED, unplaced.status());
        verify(dispatcher, times(1)).dispatch(any());
    }

    private Finding finding(EntityType kind, FindingLocation location) {
        Finding finding = Finding.create(tenant, document.id(), kind, location, Confidence.of(0.9),
                new DetectorSource("local-rule-based", "1.8.0"));
        stored.add(finding);
        return finding;
    }

    private ResolveReviewItemCommand decide(Finding finding, ReviewResolution resolution, Double at) {
        return new ResolveReviewItemCommand(finding.id(), tenant, reviewer, resolution, null, at,
                at == null ? null : 0.3, at == null ? null : 0.2, at == null ? null : 0.03, CorrelationId.generate());
    }
}

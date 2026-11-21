package com.securedoc.platform.application.redaction;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.PdfRedactionPort;
import com.securedoc.platform.application.port.out.RedactionVerificationPort;
import com.securedoc.platform.application.port.out.ReplayableContent;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.redaction.command.RedactDocumentCommand;
import com.securedoc.platform.application.redaction.service.DocumentRedactionService;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.document.Page;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.Confidence;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Task 0023 C3 (#137, #142): every redaction safety stop keeps its exact code through to the
 * thrown failure (which the processing service turns into a Support incident) and the audit, and
 * the redacted temp output is released on every stop.
 */
class DocumentRedactionServiceSafetyCodeTest {

    private final TenantId tenantId = TenantId.generate();
    private final DocumentPackage pkg = processingPackage();
    private final Document document = redactingDocument();
    private final DocumentRepository documents = mock(DocumentRepository.class);
    private final PackageRepository packages = mock(PackageRepository.class);
    private final ArtifactRepository artifacts = mock(ArtifactRepository.class);
    private final FindingRepository findings = mock(FindingRepository.class);
    private final StoragePort storage = mock(StoragePort.class);
    private final PdfRedactionPort redaction = mock(PdfRedactionPort.class);
    private final RedactionVerificationPort verification = mock(RedactionVerificationPort.class);
    private final List<AuditRecord> audits = new ArrayList<>();
    private final DocumentRedactionService service = new DocumentRedactionService(documents, packages, artifacts,
            findings, storage, redaction, verification, mock(ChecksumPort.class), mock(DomainEventPublisher.class),
            new AuditService(audits::add));

    DocumentRedactionServiceSafetyCodeTest() {
        when(documents.findByIdAndTenantId(document.id(), tenantId)).thenReturn(Optional.of(document));
        when(packages.findByIdAndTenantId(pkg.id(), tenantId)).thenReturn(Optional.of(pkg));
        Artifact original = Artifact.create(tenantId, document.id(), ArtifactType.ORIGINAL, MimeType.pdf(), 64,
                Checksum.sha256("original"));
        original.markAvailable(ArtifactStorageReference.local("t/p/d/ORIGINAL/a"));
        when(artifacts.findByDocumentIdAndTenantId(document.id(), tenantId)).thenReturn(List.of(original));
        when(storage.retrieve(any())).thenReturn(new ByteArrayInputStream(new byte[]{1, 2, 3}));
    }

    @Test
    void confirmedFindingWithoutLocationThrowsItsCodeSoAnIncidentIsRecorded() {
        when(findings.findByDocumentIdAndTenantId(document.id(), tenantId))
                .thenReturn(List.of(confirmed(FindingLocation.unlocated(1))));

        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> service.execute(command()));

        assertEquals(TechnicalFailureCode.REDACTION_LOCATION_MISSING, failure.failureCode());
        assertEquals(TechnicalDependency.REDACTION, failure.dependency());
        assertFalse(failure.retryable());
        assertEquals(DocumentStatus.FAILED, document.status(), "Fail closed is unchanged");
        verify(redaction, never()).redact(any(), anyList());
        assertFailureAudit("REDACTION_LOCATION_MISSING");
    }

    @Test
    void eachVerificationFailureKeepsItsExactCodeAndReleasesTheTempOutput() {
        for (TechnicalFailureCode code : List.of(
                TechnicalFailureCode.REDACTION_OUTPUT_EMPTY,
                TechnicalFailureCode.REDACTION_TEXT_LAYER_REMAINS,
                TechnicalFailureCode.REDACTION_ANNOTATIONS_REMAIN,
                TechnicalFailureCode.REDACTION_REGION_NOT_COVERED,
                TechnicalFailureCode.REDACTION_OUTPUT_UNPARSABLE)) {
            DocumentRedactionServiceSafetyCodeTest fresh = new DocumentRedactionServiceSafetyCodeTest();
            fresh.assertVerificationCodeKept(code);
        }
    }

    @Test
    void outputPageCountMismatchHasItsOwnCodeAndReleasesTheTempOutput() {
        when(findings.findByDocumentIdAndTenantId(document.id(), tenantId))
                .thenReturn(List.of(confirmed(located())));
        TrackingContent content = new TrackingContent();
        when(redaction.redact(any(), anyList())).thenReturn(new PdfRedactionPort.RedactionOutput(content, 2, 1));

        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> service.execute(command()));

        assertEquals(TechnicalFailureCode.REDACTION_PAGE_COUNT_MISMATCH, failure.failureCode());
        assertTrue(content.closed.get(), "The redacted temp output must be released when a check fails");
        assertFailureAudit("REDACTION_PAGE_COUNT_MISMATCH");
    }

    private void assertVerificationCodeKept(TechnicalFailureCode code) {
        when(findings.findByDocumentIdAndTenantId(document.id(), tenantId))
                .thenReturn(List.of(confirmed(located())));
        TrackingContent content = new TrackingContent();
        when(redaction.redact(any(), anyList())).thenReturn(new PdfRedactionPort.RedactionOutput(content, 1, 1));
        when(verification.verify(any(InputStream.class), anyInt(), anyList()))
                .thenReturn(RedactionVerificationPort.VerificationResult.failed(1, 1, 0, false, code, "fixed sentence"));

        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> service.execute(command()));

        assertEquals(code, failure.failureCode());
        assertTrue(content.closed.get());
        assertEquals(DocumentStatus.FAILED, document.status());
        assertFailureAudit(code.name());
        assertTrue(audits.stream().noneMatch(a -> a.action() == AuditAction.DOCUMENT_REDACTION_VERIFIED));
    }

    private void assertFailureAudit(String code) {
        List<AuditRecord> failed = audits.stream()
                .filter(a -> a.action() == AuditAction.DOCUMENT_PROCESSING_FAILED).toList();
        assertEquals(1, failed.size());
        assertEquals("Redaction failed: dependency=REDACTION, code=" + code + ", retryable=false, attempts=1",
                failed.get(0).detail());
    }

    private RedactDocumentCommand command() {
        return RedactDocumentCommand.of(document.id(), pkg.id(), tenantId, CorrelationId.generate());
    }

    private Finding confirmed(FindingLocation location) {
        return Finding.reconstitute(FindingId.generate(), tenantId, document.id(), EntityType.SSN, location,
                new Confidence(0.99), DetectorSource.of("test"), Instant.now(), FindingStatus.CONFIRMED,
                List.of(), Instant.now());
    }

    private static FindingLocation located() {
        return FindingLocation.withBounds(1, 0, 11, FindingLocation.BoundingBox.of(0.1, 0.1, 0.2, 0.05));
    }

    private DocumentPackage processingPackage() {
        DocumentPackage value = DocumentPackage.create(tenantId, PackageType.MANUAL_UPLOAD, ActorId.generate(),
                PackageMetadata.empty());
        Document placeholder = Document.create(tenantId, value.id(), FileName.of("a.pdf"), MimeType.pdf(), 64,
                Checksum.sha256("a"), DocumentType.PDF);
        value.addDocument(placeholder);
        value.submit();
        value.startProcessing();
        return value;
    }

    private Document redactingDocument() {
        return Document.reconstitute(DocumentId.generate(), tenantId, pkg.id(), FileName.of("a.pdf"), MimeType.pdf(),
                64, Checksum.sha256("a"), DocumentType.PDF, Instant.now(), DocumentStatus.REDACTING,
                List.of(Page.of(1, 612, 792)), Instant.now());
    }

    private static final class TrackingContent implements ReplayableContent {
        final AtomicBoolean closed = new AtomicBoolean();

        @Override public InputStream openStream() { return new ByteArrayInputStream(new byte[]{1}); }
        @Override public long contentLength() { return 1; }
        @Override public void close() { closed.set(true); }
    }
}

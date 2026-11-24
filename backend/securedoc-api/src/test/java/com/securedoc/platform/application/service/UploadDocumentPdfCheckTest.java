package com.securedoc.platform.application.service;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.intake.command.UploadContent;
import com.securedoc.platform.application.intake.command.UploadDocumentCommand;
import com.securedoc.platform.application.licensing.IntakeLicence;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.tenant.Tenant;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.infrastructure.intake.PdfBoxIntakeValidationAdapter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Task 0023 D4 (#45), D3 (#22) and #55: the server checks a manual or watched-folder file is a readable PDF. */
class UploadDocumentPdfCheckTest {

    private final TenantId tenantId = TenantId.generate();
    private final AuthenticatedPrincipal principal = AuthenticatedPrincipal.of(ActorId.generate(), tenantId, "person", Set.of());
    private final PackageRepository packages = mock(PackageRepository.class);
    private final StoragePort storage = mock(StoragePort.class);
    private final PdfIntakeValidationPort realPdfCheck = new PdfBoxIntakeValidationAdapter();

    private UploadDocumentService service(PdfIntakeValidationPort pdfCheck) {
        TenantContextService tenants = mock(TenantContextService.class);
        when(tenants.resolveAndValidate(any())).thenReturn(Tenant.create("Tenant", "tenant"));
        ChecksumPort checksum = in -> Checksum.sha256("a".repeat(64));
        try {
            when(storage.storeForPackage(any(), any(), anyString(), any(), anyLong(), anyString()))
                    .thenReturn(ArtifactStorageReference.local("t/p/d/ORIGINAL/a"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return new UploadDocumentService(tenants, mock(CapabilityEnforcementService.class), mock(IntakeLicence.class),
                packages, mock(DocumentRepository.class), mock(ArtifactRepository.class), storage, checksum,
                mock(DomainEventPublisher.class), new AuditService(r -> { }), pdfCheck);
    }

    private PackageId packageOf(PackageType type) {
        DocumentPackage pkg = type == PackageType.MANUAL_UPLOAD
                ? DocumentPackage.create(tenantId, type, principal.actorId(), PackageMetadata.empty())
                : DocumentPackage.createIntake(tenantId, type, BusinessRecordId.generate(), "f", "ref", principal.actorId(),
                        PackageMetadata.empty());
        when(packages.findByIdAndTenantId(pkg.id(), tenantId)).thenReturn(Optional.of(pkg));
        return pkg.id();
    }

    @Test
    void aManualOrWatchedFileThatIsNotAReadablePdfIsRefusedBeforeItIsStored() throws Exception {
        for (PackageType type : new PackageType[] {PackageType.MANUAL_UPLOAD, PackageType.FOLDER_INTAKE}) {
            PackageId id = packageOf(type);
            ValidationException refused = assertThrows(ValidationException.class, () -> service(realPdfCheck).execute(
                    UploadDocumentCommand.of(id, "statement.pdf", "application/pdf", content("not a pdf".getBytes())), principal));
            assertEquals("Uploaded content is not a valid readable PDF", refused.getMessage(), type.name());
        }
        verify(storage, never()).storeForPackage(any(), any(), anyString(), any(), anyLong(), anyString());
    }

    @Test
    void aReadablePdfIsTakenIn() throws Exception {
        PackageId id = packageOf(PackageType.MANUAL_UPLOAD);
        assertNotNull(service(realPdfCheck).execute(
                UploadDocumentCommand.of(id, "statement.pdf", "application/pdf", content(onePagePdf())), principal).documentId());
        verify(storage).storeForPackage(any(), any(), anyString(), any(), anyLong(), anyString());
    }

    @Test
    void machineIntakeFilesAreNotParsedASecondTime() {
        PdfIntakeValidationPort pdfCheck = mock(PdfIntakeValidationPort.class);
        for (PackageType type : new PackageType[] {PackageType.API_SUBMISSION, PackageType.SCANNER_INTAKE}) {
            PackageId id = packageOf(type);
            service(pdfCheck).execute(UploadDocumentCommand.of(id, "scan.pdf", "application/pdf", content("x".getBytes())), principal);
        }
        verifyNoInteractions(pdfCheck);
    }

    @Test
    void anEmptyFileIsSaidToBeEmpty() {
        PackageId id = packageOf(PackageType.MANUAL_UPLOAD);
        ValidationException refused = assertThrows(ValidationException.class, () -> service(realPdfCheck).execute(
                UploadDocumentCommand.of(id, "empty.pdf", "application/pdf", content(new byte[0])), principal));
        assertEquals("The file is empty", refused.getMessage());
    }

    @Test
    void theTypeOrNameSentIsNotRepeated() {
        PackageId id = packageOf(PackageType.MANUAL_UPLOAD);
        ValidationException type = assertThrows(ValidationException.class, () -> service(realPdfCheck).execute(
                UploadDocumentCommand.of(id, "a.docx", "application/x-jane-doe", content("x".getBytes())), principal));
        assertEquals("Only PDF files can be uploaded (application/pdf)", type.getMessage());
        ValidationException name = assertThrows(ValidationException.class, () -> service(realPdfCheck).execute(
                UploadDocumentCommand.of(id, "clients/Jane Doe.pdf", "application/pdf", content("x".getBytes())), principal));
        assertEquals("FileName must not contain path separators", name.getMessage());
    }

    /* Task 0038: the case limits, however the case is filled. */

    @Test
    void aManualOrWatchedPdfOfMoreThanFiveHundredPagesIsRefusedBeforeItIsStored() throws Exception {
        byte[] over = pdfOf(501);
        for (PackageType type : new PackageType[] {PackageType.MANUAL_UPLOAD, PackageType.FOLDER_INTAKE}) {
            PackageId id = packageOf(type);
            ValidationException refused = assertThrows(ValidationException.class, () -> service(realPdfCheck).execute(
                    UploadDocumentCommand.of(id, "big.pdf", "application/pdf", content(over)), principal));
            assertEquals(com.securedoc.platform.domain.error.LvErrorCode.INT_028, refused.lvCode(), type.name());
            assertFalse(refused.getMessage().contains("big"), "never the file name");
        }
        verify(storage, never()).storeForPackage(any(), any(), anyString(), any(), anyLong(), anyString());
        PackageId id = packageOf(PackageType.MANUAL_UPLOAD);
        assertNotNull(service(realPdfCheck).execute(
                UploadDocumentCommand.of(id, "limit.pdf", "application/pdf", content(pdfOf(500))), principal).documentId(),
                "500 pages is taken in");
    }

    @Test
    void aCaseHoldsUpToFiftyFilesHoweverItIsFilled() {
        PdfIntakeValidationPort onePage = in -> new PdfIntakeValidationPort.PdfInfo(1);
        for (PackageType type : new PackageType[] {PackageType.MANUAL_UPLOAD, PackageType.FOLDER_INTAKE,
                PackageType.API_SUBMISSION, PackageType.SCANNER_INTAKE}) {
            PackageId id = packageOf(type);
            UploadDocumentService service = service(onePage);
            for (int i = 0; i < 50; i++) {
                service.execute(UploadDocumentCommand.of(id, "f" + i + ".pdf", "application/pdf", content("x".getBytes())),
                        principal);
            }
            ValidationException refused = assertThrows(ValidationException.class, () -> service.execute(
                    UploadDocumentCommand.of(id, "f50.pdf", "application/pdf", content("x".getBytes())), principal));
            assertEquals(com.securedoc.platform.domain.error.LvErrorCode.INT_029, refused.lvCode(), type.name());
            assertEquals("The case already holds 50 files", refused.getMessage());
        }
    }

    private static byte[] pdfOf(int pages) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        }
    }

    private static UploadContent content(byte[] bytes) {
        return new UploadContent() {
            @Override public InputStream openStream() { return new ByteArrayInputStream(bytes); }
            @Override public long contentLength() { return bytes.length; }
        };
    }

    private static byte[] onePagePdf() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            document.addPage(new PDPage());
            document.save(out);
            return out.toByteArray();
        }
    }
}

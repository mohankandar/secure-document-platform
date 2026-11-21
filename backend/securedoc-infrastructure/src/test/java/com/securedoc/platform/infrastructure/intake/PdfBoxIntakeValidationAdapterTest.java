package com.securedoc.platform.infrastructure.intake;

import com.securedoc.platform.application.exception.ValidationException;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0023 #62: a password-protected PDF is told apart from a corrupt one; neither message holds content. */
class PdfBoxIntakeValidationAdapterTest {

    private final PdfBoxIntakeValidationAdapter adapter = new PdfBoxIntakeValidationAdapter();

    @Test
    void aReadablePdfGivesItsPageCount() throws Exception {
        assertEquals(2, adapter.inspect(new ByteArrayInputStream(pdf(2, null))).pageCount());
    }

    @Test
    void aCorruptFileIsNotAReadablePdf() {
        ValidationException refused = assertThrows(ValidationException.class,
                () -> adapter.inspect(new ByteArrayInputStream("%PDF-1.4 Jane Doe truncated".getBytes())));
        assertEquals(PdfBoxIntakeValidationAdapter.NOT_READABLE, refused.getMessage());
    }

    @Test
    void aPasswordProtectedPdfIsSaidToBePasswordProtected() throws Exception {
        ValidationException refused = assertThrows(ValidationException.class,
                () -> adapter.inspect(new ByteArrayInputStream(pdf(1, "open-sesame"))));
        assertEquals(PdfBoxIntakeValidationAdapter.PASSWORD_PROTECTED, refused.getMessage());
    }

    private static byte[] pdf(int pages, String userPassword) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (int i = 0; i < pages; i++) document.addPage(new PDPage());
            if (userPassword != null) {
                StandardProtectionPolicy policy = new StandardProtectionPolicy("owner-secret", userPassword, new AccessPermission());
                policy.setEncryptionKeyLength(128);
                document.protect(policy);
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}

package com.securedoc.platform.infrastructure.intake;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.PdfDecryptionPort;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.infrastructure.pdf.PdfBoxTempFileSupport;
import com.securedoc.platform.infrastructure.pdf.TempFileCleanup;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/** Task 0063: PDFBox decryption (RC4 and AES, user or owner password). Temporary files are removed on every path. */
@Component
public class PdfBoxDecryptionAdapter implements PdfDecryptionPort {
    private static final Logger log = LoggerFactory.getLogger(PdfBoxDecryptionAdapter.class);

    static final String WRONG_PASSWORD = "The password does not open this PDF";

    @Override
    public Optional<DecryptedPdf> decrypt(InputStream content, char[] password) {
        Path staged = null;
        Path output = null;
        boolean done = false;
        try {
            staged = Files.createTempFile("lexavault-decrypt-in-", ".pdf");
            Files.copy(content, staged, StandardCopyOption.REPLACE_EXISTING);
            try (PDDocument document = Loader.loadPDF(staged.toFile(), new String(password), null, null,
                    IOUtils.createTempFileOnlyStreamCache())) {
                if (!document.isEncrypted()) return Optional.empty();
                document.setAllSecurityToBeRemoved(true);
                output = PdfBoxTempFileSupport.newOutputPath("lexavault-decrypt-out-");
                document.save(output.toFile());
            }
            Copy copy = new Copy(output, Files.size(output));
            done = true;
            return Optional.of(copy);
        } catch (InvalidPasswordException wrong) {
            // Never the exception's message or the exception itself: only that it was refused.
            log.info("PDF_DECRYPTION_REFUSED reasonCode=PASSWORD_NOT_ACCEPTED");
            throw new ValidationException(LvErrorCode.INT_036, WRONG_PASSWORD);
        } catch (IOException | RuntimeException e) {
            log.info("PDF_DECRYPTION_REFUSED reasonCode=PDF_UNREADABLE exceptionType={}", e.getClass().getSimpleName());
            throw new ValidationException(LvErrorCode.INT_008, PdfBoxIntakeValidationAdapter.NOT_READABLE);
        } finally {
            if (staged != null) TempFileCleanup.deleteQuietly(staged, "PDF_DECRYPT_IN");
            if (!done && output != null) TempFileCleanup.deleteQuietly(output, "PDF_DECRYPT_OUT");
        }
    }

    private record Copy(Path path, long contentLength) implements DecryptedPdf {
        @Override
        public InputStream openStream() {
            try {
                return Files.newInputStream(path);
            } catch (IOException e) {
                throw new UncheckedIOException("The decrypted copy could not be read", e);
            }
        }

        @Override
        public void close() {
            TempFileCleanup.deleteQuietly(path, "PDF_DECRYPT_OUT");
        }
    }
}

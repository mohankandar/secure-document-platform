package com.securedoc.platform.infrastructure.intake;

import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.PdfIntakeValidationPort;
import com.securedoc.platform.infrastructure.pdf.PdfBoxTempFileSupport;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Component
public class PdfBoxIntakeValidationAdapter implements PdfIntakeValidationPort {
    private static final Logger log = LoggerFactory.getLogger(PdfBoxIntakeValidationAdapter.class);

    static final String NOT_READABLE = "Uploaded content is not a valid readable PDF";
    static final String PASSWORD_PROTECTED = "The PDF is password protected and cannot be read";
    static final String NO_PAGES = "PDF must contain at least one page";

    @Override
    public PdfInfo inspect(InputStream content) {
        try (PdfBoxTempFileSupport.StagedPdf staged = PdfBoxTempFileSupport.stage(content, "lexavault-intake-")) {
            int pageCount = staged.document().getNumberOfPages();
            if (pageCount < 1) {
                log.info("PDF_INTAKE_REFUSED reasonCode=PDF_NO_PAGES");
                throw new ValidationException(LvErrorCode.INT_008, NO_PAGES);
            }
            return new PdfInfo(pageCount);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ValidationException validationException) throw validationException;
            // Task 0023 #62: the PDFBox cause is no longer swallowed: its type and a code are logged (never its
            // message), and a password-protected file is told apart from a corrupt one.
            boolean encrypted = e instanceof InvalidPasswordException;
            log.info("PDF_INTAKE_REFUSED reasonCode={} exceptionType={}",
                    encrypted ? "PDF_PASSWORD_PROTECTED" : "PDF_UNREADABLE", e.getClass().getSimpleName());
            throw new ValidationException(LvErrorCode.INT_008, encrypted ? PASSWORD_PROTECTED : NOT_READABLE);
        }
    }
}

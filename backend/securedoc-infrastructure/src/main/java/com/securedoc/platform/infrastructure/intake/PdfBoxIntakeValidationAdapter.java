package com.securedoc.platform.infrastructure.intake;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.PdfIntakeValidationPort;
import com.securedoc.platform.infrastructure.pdf.PdfBoxTempFileSupport;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;

@Component
public class PdfBoxIntakeValidationAdapter implements PdfIntakeValidationPort {
    @Override
    public PdfInfo inspect(InputStream content) {
        try (PdfBoxTempFileSupport.StagedPdf staged = PdfBoxTempFileSupport.stage(content, "lexavault-intake-")) {
            int pageCount = staged.document().getNumberOfPages();
            if (pageCount < 1) throw new ValidationException("PDF must contain at least one page");
            return new PdfInfo(pageCount);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ValidationException validationException) throw validationException;
            throw new ValidationException("Uploaded content is not a valid readable PDF");
        }
    }
}

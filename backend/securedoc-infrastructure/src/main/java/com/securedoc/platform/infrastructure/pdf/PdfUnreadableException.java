package com.securedoc.platform.infrastructure.pdf;

import com.securedoc.platform.domain.error.LvCoded;
import com.securedoc.platform.domain.error.LvErrorCode;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileSystemException;

/**
 * Task 0026 (#113): during processing the PDF engine could not open the document (damaged, cut short or protected
 * with a password), {@code LV-PRC-001}. It is an {@link UncheckedIOException}, as the extraction adapters threw before,
 * so code that catches those behaves exactly as before. The message is fixed; the cause keeps the engine's type.
 */
public class PdfUnreadableException extends UncheckedIOException implements LvCoded {

    public PdfUnreadableException(IOException cause) {
        super("PDF could not be opened for processing", cause);
    }

    /**
     * The exception for a failure of {@link PdfBoxTempFileSupport#stage}: opening the PDF failed gives this one; a
     * failure to write the temporary copy (a file system problem, not the PDF) keeps the plain
     * {@link UncheckedIOException} with {@code message}, as before.
     */
    public static UncheckedIOException forStageFailure(IOException failure, String message) {
        if (failure instanceof FileSystemException) return new UncheckedIOException(message, failure);
        return new PdfUnreadableException(failure);
    }

    @Override
    public LvErrorCode lvCode() {
        return LvErrorCode.PRC_001;
    }
}

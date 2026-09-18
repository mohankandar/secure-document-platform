package com.securedoc.platform.infrastructure.pdf;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Shared bounded-memory PDFBox staging helpers for large PDF processing. */
public final class PdfBoxTempFileSupport {
    private PdfBoxTempFileSupport() {}

    public static StagedPdf stage(InputStream content, String prefix) throws IOException {
        if (content == null) {
            throw new IllegalArgumentException("PDF content is required");
        }
        Path path = Files.createTempFile(safePrefix(prefix), ".pdf");
        boolean success = false;
        try {
            Files.copy(content, path, StandardCopyOption.REPLACE_EXISTING);
            PDDocument document = Loader.loadPDF(path.toFile(), IOUtils.createTempFileOnlyStreamCache());
            success = true;
            return new StagedPdf(path, document);
        } finally {
            if (!success) {
                Files.deleteIfExists(path);
            }
        }
    }

    public static PDDocument newDiskBackedDocument() {
        return new PDDocument(IOUtils.createTempFileOnlyStreamCache());
    }

    public static Path newOutputPath(String prefix) throws IOException {
        return Files.createTempFile(safePrefix(prefix), ".pdf");
    }

    private static String safePrefix(String prefix) {
        String value = prefix == null ? "lexavault-pdf-" : prefix.replaceAll("[^A-Za-z0-9._-]", "-");
        while (value.length() < 3) value += "-";
        return value;
    }

    public static final class StagedPdf implements AutoCloseable {
        private final Path path;
        private final PDDocument document;

        private StagedPdf(Path path, PDDocument document) {
            this.path = path;
            this.document = document;
        }

        public PDDocument document() {
            return document;
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                document.close();
            } catch (IOException e) {
                failure = e;
            }
            try {
                Files.deleteIfExists(path);
            } catch (IOException e) {
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
            if (failure != null) throw failure;
        }
    }
}

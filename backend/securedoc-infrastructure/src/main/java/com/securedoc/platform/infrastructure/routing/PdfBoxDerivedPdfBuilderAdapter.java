package com.securedoc.platform.infrastructure.routing;

import com.securedoc.platform.application.port.out.DerivedPdfBuilderPort;
import com.securedoc.platform.application.port.out.ReplayableContent;
import com.securedoc.platform.infrastructure.pdf.PdfBoxTempFileSupport;
import com.securedoc.platform.infrastructure.pdf.TempFileCleanup;
import com.securedoc.platform.infrastructure.pdf.TempFileReplayableContent;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/** Builds a page-subset PDF exclusively from the permanently redacted master. */
@Component
public class PdfBoxDerivedPdfBuilderAdapter implements DerivedPdfBuilderPort {
    @Override
    public ReplayableContent build(InputStream redactedPdf, List<Integer> pageNumbers) {
        if (pageNumbers == null || pageNumbers.isEmpty()) {
            throw new IllegalArgumentException("At least one derived page is required");
        }
        List<Integer> ordered = pageNumbers.stream().distinct().sorted().toList();
        Path outputPath = null;
        TempFileReplayableContent result = null;
        try (PdfBoxTempFileSupport.StagedPdf staged = PdfBoxTempFileSupport.stage(redactedPdf, "lexavault-derived-source-");
             PDDocument output = PdfBoxTempFileSupport.newDiskBackedDocument()) {
            PDDocument source = staged.document();
            for (int page : ordered) {
                if (page < 1 || page > source.getNumberOfPages()) {
                    throw new IllegalArgumentException("Derived page number is outside the source PDF");
                }
                output.importPage(source.getPage(page - 1));
            }
            // Task 0057: pages of a marked output make a marked file.
            com.securedoc.platform.infrastructure.redaction.NonProductionMark.carry(source, output);
            outputPath = PdfBoxTempFileSupport.newOutputPath("lexavault-derived-output-");
            output.save(outputPath.toFile());
            result = TempFileReplayableContent.owned(outputPath);
            outputPath = null;
            return result;
        } catch (IOException e) {
            // Task 0023 B9: also reached when closing the source fails after the output exists.
            if (result != null) result.close();
            throw new UncheckedIOException("Failed to build derived redacted PDF", e);
        } catch (RuntimeException e) {
            if (result != null) result.close();
            throw e;
        } finally {
            if (outputPath != null) {
                TempFileCleanup.deleteQuietly(outputPath, "DERIVED_OUTPUT");
            }
        }
    }
}

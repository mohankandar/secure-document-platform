package com.securedoc.platform.infrastructure.extraction;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.OcrPort;
import com.securedoc.platform.application.resilience.ResilientOcrPort;
import com.securedoc.platform.application.resilience.SelectiveRetryExecutor;
import com.securedoc.platform.application.resilience.TechnicalFailureClassifier;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0039: a text-reading failure on one page carries that page number (1-based, only the number). The code, the
 * retries and the outcome are as before.
 */
class HybridPdfOcrFailurePageTest {

    private static final String READ_TEXT = "Synthetic page text";

    @Test
    void aTimeoutOnPageTwoCarriesPageTwoAfterTheSameRetries() throws Exception {
        AtomicInteger callsForPageTwo = new AtomicInteger();
        OcrPort provider = input -> {
            if (input.pageNumber() == 2) {
                callsForPageTwo.incrementAndGet();
                // A provider failure that knows nothing about pages.
                throw new TechnicalDependencyException(TechnicalDependency.OCR, TechnicalFailureCode.OCR_TIMEOUT,
                        true, "OCR provider timed out");
            }
            return readable();
        };
        OcrPort resilient = new ResilientOcrPort(provider, new SelectiveRetryExecutor(3, Duration.ZERO));

        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> adapter(resilient).extract(new ByteArrayInputStream(scannedPdf(3)), "application/pdf"));

        assertEquals(2, failure.page());
        assertEquals(TechnicalFailureCode.OCR_TIMEOUT, failure.failureCode());
        assertTrue(failure.retryable());
        assertEquals(3, failure.attempts());
        assertEquals(3, callsForPageTwo.get(), "same retries as before");
        assertEquals("OCR provider timed out", failure.getMessage());
    }

    @Test
    void aPageThatCannotBeReadCarriesItsPageWithItsOwnCode() throws Exception {
        // Page 3 comes back empty although it is not blank: LV-PRC-002, as before, now with the page.
        OcrPort provider = input -> input.pageNumber() == 3
                ? new OcrPort.OcrPageOutput("", List.of(), 0.0)
                : readable();

        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> adapter(provider).extract(new ByteArrayInputStream(scannedPdf(3)), "application/pdf"));

        assertEquals(3, failure.page());
        assertEquals(LvErrorCode.PRC_002, failure.lvCode());
        assertEquals(TechnicalFailureCode.OCR_PROCESS_FAILED, failure.failureCode());
    }

    @Test
    void aPageNumberTheProviderAlreadySetIsKept() throws Exception {
        OcrPort provider = input -> {
            throw new TechnicalDependencyException(TechnicalDependency.OCR, TechnicalFailureCode.OCR_PROCESS_FAILED,
                    false, "OCR provider returned a non-zero exit status").withPage(input.pageNumber());
        };

        TechnicalDependencyException failure = assertThrows(TechnicalDependencyException.class,
                () -> adapter(provider).extract(new ByteArrayInputStream(scannedPdf(2)), "application/pdf"));

        assertEquals(1, failure.page());
    }

    @Test
    void aFailureNotOnOnePageCarriesNoPage() {
        OcrPort provider = input -> readable();

        // Not a PDF at all: the whole document fails before any page is read.
        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> adapter(provider).extract(new ByteArrayInputStream(new byte[]{1, 2, 3}), "application/pdf"));

        assertTrue(TechnicalFailureClassifier.page(failure).isEmpty());
    }

    @Test
    void allPagesReadMeansNoFailureAndTheSameOutput() throws Exception {
        var output = adapter(input -> readable()).extract(new ByteArrayInputStream(scannedPdf(3)), "application/pdf");
        assertEquals(3, output.pages().size());
        assertTrue(output.usedOcr());
    }

    private static OcrPort.OcrPageOutput readable() {
        return new OcrPort.OcrPageOutput(READ_TEXT,
                List.of(new OcrPort.OcrTextSpan(0, READ_TEXT.length(), 0.1, 0.1, 0.5, 0.1, 0.99)), 0.99);
    }

    private static HybridPdfTextExtractionAdapter adapter(OcrPort ocr) {
        TesseractOcrProperties properties = new TesseractOcrProperties();
        properties.setMinimumPageConfidence(0.55);
        return new HybridPdfTextExtractionAdapter(new PdfBoxTextExtractionAdapter(), ocr, properties);
    }

    /** Pages with no text layer and a dark block, so each page goes to text reading and none is blank. */
    private static byte[] scannedPdf(int pages) throws Exception {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage(PDRectangle.LETTER);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.addRect(72, 200, 468, 400);
                    content.fill();
                }
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}

package com.securedoc.platform.infrastructure.ocr;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.OcrPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0023 B9 (#370) and C7 (#110): the OCR temp folder (page image + recognized text) is removed
 * on every failure path, and the failure is logged with a code, never with content or paths.
 */
class TesseractOcrAdapterCleanupTest {

    @TempDir
    Path tempRoot;

    @Test
    void nonZeroExitRemovesTheTempFolderAndLogsTheExitStatus() throws IOException {
        // The Java launcher exits non-zero when given an image path as its main class.
        String java = ProcessHandle.current().info().command().orElse("java");
        TesseractOcrAdapter adapter = adapter(java);

        ListAppender<ILoggingEvent> logs = capture();
        OcrProcessingException failure;
        try {
            failure = assertThrows(OcrProcessingException.class, () -> adapter.recognize(page()));
        } finally {
            release(logs);
        }

        assertEquals(TechnicalFailureCode.OCR_PROCESS_FAILED, failure.failureCode());
        assertEmpty(tempRoot);
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage()
                .startsWith("OCR_PROCESS_FAILED reasonCode=OCR_NON_ZERO_EXIT exitStatus=")));
        assertTrue(logs.list.stream().noneMatch(e -> e.getFormattedMessage().contains(tempRoot.toString())));
    }

    @Test
    void missingExecutableRemovesTheTempFolderAndLogsTheTypeOnly() throws IOException {
        TesseractOcrAdapter adapter = adapter("definitely-not-installed-ocr-" + System.nanoTime());

        ListAppender<ILoggingEvent> logs = capture();
        OcrProcessingException failure;
        try {
            failure = assertThrows(OcrProcessingException.class, () -> adapter.recognize(page()));
        } finally {
            release(logs);
        }

        assertEquals(TechnicalFailureCode.OCR_UNAVAILABLE, failure.failureCode());
        assertEmpty(tempRoot);
        assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage()
                .equals("OCR_PROCESS_FAILED reasonCode=OCR_UNAVAILABLE failureType=IOException")));
        assertTrue(logs.list.stream().noneMatch(e -> e.getFormattedMessage().contains("definitely-not-installed")));
    }

    /** Task 0039: the failure carries the page number it happened on, and the message names no page content. */
    @Test
    void aFailureCarriesThePageNumberOnly() {
        TesseractOcrAdapter adapter = adapter("definitely-not-installed-ocr-" + System.nanoTime());
        OcrProcessingException failure = assertThrows(OcrProcessingException.class,
                () -> adapter.recognize(new OcrPort.OcrPageInput(7, new byte[]{1, 2, 3, 4}, "image/png", 10, 10)));

        assertEquals(7, failure.page());
        assertEquals(TechnicalFailureCode.OCR_UNAVAILABLE, failure.failureCode());
        assertFalse(failure.retryable());
        assertEquals("OCR provider could not be executed", failure.getMessage());
    }

    private TesseractOcrAdapter adapter(String executable) {
        TesseractOcrProperties properties = new TesseractOcrProperties();
        properties.setExecutable(executable);
        properties.setTimeoutSeconds(30);
        TesseractOcrAdapter adapter = new TesseractOcrAdapter(properties);
        adapter.useTempRoot(tempRoot);
        return adapter;
    }

    private static OcrPort.OcrPageInput page() {
        return new OcrPort.OcrPageInput(1, new byte[]{1, 2, 3, 4}, "image/png", 10, 10);
    }

    private static void assertEmpty(Path folder) throws IOException {
        try (Stream<Path> left = Files.list(folder)) {
            assertFalse(left.findAny().isPresent(), "The OCR temp folder must be deleted");
        }
    }

    private static ListAppender<ILoggingEvent> capture() {
        Logger logger = (Logger) LoggerFactory.getLogger(TesseractOcrAdapter.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        return logs;
    }

    private static void release(ListAppender<ILoggingEvent> logs) {
        ((Logger) LoggerFactory.getLogger(TesseractOcrAdapter.class)).detachAppender(logs);
    }
}

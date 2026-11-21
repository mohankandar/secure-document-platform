package com.securedoc.platform.infrastructure.pdf;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0023 B9 (#370, #372, #373): temp content is removed on every path; a failed delete is logged by id. */
class TempFileCleanupTest {

    @TempDir
    Path temp;

    private Logger logger;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(TempFileCleanup.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void stopCapture() {
        logger.detachAppender(logs);
    }

    @Test
    void failedDeleteIsLoggedWithTempIdAndTypeButNoNameOrPath() throws IOException {
        Path folder = Files.createDirectory(temp.resolve("Jane-Doe-Statement-4711123.pdf"));
        Files.writeString(folder.resolve("inside.txt"), "content");

        boolean deleted = assertDoesNotThrow(() -> TempFileCleanup.deleteQuietly(folder, "TEST"));

        assertFalse(deleted);
        assertEquals(1, logs.list.size());
        String line = logs.list.get(0).getFormattedMessage();
        assertEquals("TEMP_FILE_DELETE_FAILED component=TEST tempId=4711123 reasonCode=DirectoryNotEmptyException", line);
        assertFalse(line.contains("Jane"));
        assertFalse(line.contains(temp.toString()));
        assertTrue(logs.list.get(0).getThrowableProxy() == null, "No exception message or stack trace");
    }

    @Test
    void tempIdIsTheRandomNumberOrAHashWhenThereIsNone() {
        assertEquals("812345678901", TempFileCleanup.tempId(Path.of("lexavault-redaction-output-812345678901.pdf")));
        String hashed = TempFileCleanup.tempId(Path.of("no-digits-here.pdf"));
        assertTrue(hashed.matches("h[0-9a-f]{12}"), hashed);
        assertFalse(hashed.contains("digits"));
    }

    @Test
    void treeDeleteRemovesTheOcrFolder() throws IOException {
        Path folder = Files.createTempDirectory(temp, "securedoc-ocr-");
        Files.write(folder.resolve("page.png"), new byte[]{1, 2, 3});
        Files.writeString(folder.resolve("result.tsv"), "recognized text");

        assertTrue(TempFileCleanup.deleteTreeQuietly(folder, "OCR"));
        assertFalse(Files.exists(folder));
        assertTrue(logs.list.isEmpty());
    }

    @Test
    void stagedCopyOfTheOriginalIsRemovedWhenTheStagedPdfIsClosed() throws IOException {
        byte[] pdf;
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument(); var out = new java.io.ByteArrayOutputStream()) {
            doc.addPage(new org.apache.pdfbox.pdmodel.PDPage());
            doc.save(out);
            pdf = out.toByteArray();
        }
        Path staged;
        try (PdfBoxTempFileSupport.StagedPdf stagedPdf = PdfBoxTempFileSupport.stage(new ByteArrayInputStream(pdf), "lexavault-test-")) {
            staged = stagedPdf.path();
            assertTrue(Files.exists(staged));
        }
        assertFalse(Files.exists(staged), "The full copy of the original must not stay in the temp folder");
    }

    @Test
    void stagedCopyIsRemovedWhenThePdfCannotBeLoaded() throws IOException {
        String prefix = "lexavault-test-" + UUID.randomUUID() + "-";
        assertThrows(IOException.class, () -> PdfBoxTempFileSupport.stage(
                new ByteArrayInputStream("not a pdf".getBytes(StandardCharsets.UTF_8)), prefix));

        Path tmp = Path.of(System.getProperty("java.io.tmpdir"));
        try (Stream<Path> files = Files.list(tmp)) {
            List<Path> left = files.filter(p -> p.getFileName().toString().startsWith(prefix)).toList();
            assertTrue(left.isEmpty(), "No staged copy may be left after a failed load");
        }
    }
}

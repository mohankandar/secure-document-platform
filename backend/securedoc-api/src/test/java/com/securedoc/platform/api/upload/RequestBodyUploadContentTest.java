package com.securedoc.platform.api.upload;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class RequestBodyUploadContentTest {

    @Test
    void spoolsLargeRequestBodyToRepeatableDiskBackedContent() throws Exception {
        byte[] bytes = "%PDF-1.4\nscanner\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
        try (RequestBodyUploadContent content = RequestBodyUploadContent.from(
                new ByteArrayInputStream(bytes), bytes.length, 1024 * 1024)) {
            assertEquals(bytes.length, content.contentLength());
            try (var first = content.openStream(); var second = content.openStream()) {
                assertArrayEquals(bytes, first.readAllBytes());
                assertArrayEquals(bytes, second.readAllBytes());
            }
        }
    }

    @Test
    void rejectsBodyThatExceedsAbsoluteScannerCeilingWithoutBufferingItAll() {
        byte[] bytes = new byte[32];
        assertThrows(IllegalArgumentException.class, () -> RequestBodyUploadContent.from(
                new ByteArrayInputStream(bytes), -1, 16));
    }

    @Test
    void rejectsDeclaredLengthMismatch() {
        byte[] bytes = new byte[8];
        assertThrows(IllegalArgumentException.class, () -> RequestBodyUploadContent.from(
                new ByteArrayInputStream(bytes), 9, 16));
    }
}

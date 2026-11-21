package com.securedoc.platform.domain.vo;

import com.securedoc.platform.domain.integration.IntegrationConnection;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0023 #55, D3: domain messages say what is wrong without repeating the value. */
class ValueMessagesDoNotEchoInputTest {

    @Test
    void aFileNameWithAPathSeparatorIsRefusedWithoutTheName() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> FileName.of("clients\\Jane Doe payslip.pdf"));
        assertEquals("FileName must not contain path separators", refused.getMessage());
    }

    @Test
    void aMalformedMimeTypeIsRefusedWithoutTheValue() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> MimeType.of("Jane-Doe"));
        assertEquals("MimeType must contain type/subtype format", refused.getMessage());
    }

    @Test
    void thePushDefaultMaxPdfSizeIsTheMultipartLimit() {
        // spring.servlet.multipart.max-file-size is 50MB in securedoc-api application.yml.
        assertEquals(50L * 1024 * 1024, IntegrationConnection.DEFAULT_MAX_PDF_SIZE_BYTES);
    }
}

package com.securedoc.platform.api.config;

import com.securedoc.platform.api.testsupport.LogCapture;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0023 B8 (#328) and C7 (#325). */
class AiPoolLoggingTest {

    @Test
    void aPoolErrorNeverEchoesTheRawEntryOrAPassword() {
        for (String configured : new String[]{
                "admin:s3cret@ai-east:11434",
                "gpu=ftp://admin:s3cret@ai-east:11434",
                "gpu=http://admin:s3cret@bad host:11434",
                "bad id s3cret=http://ai:1"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> OllamaInferencePoolConfiguration.parse(configured));
            assertFalse(error.getMessage().contains("s3cret"), error.getMessage());
            assertFalse(error.getMessage().contains("admin"), error.getMessage());
        }
        IllegalArgumentException scheme = assertThrows(IllegalArgumentException.class,
                () -> OllamaInferencePoolConfiguration.parse("gpu=ftp://admin:s3cret@ai-east:11434/x"));
        assertEquals("AI pool node URL for node gpu must be an absolute http/https URL: ftp://ai-east:11434/x",
                scheme.getMessage());
    }
}

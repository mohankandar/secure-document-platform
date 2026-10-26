package com.securedoc.platform.api.controller;

import com.securedoc.platform.application.exception.AccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ArtifactDownloadDisabledTest {
    @Test
    void legacyRedactedArtifactRouteAlwaysDenies() throws Exception {
        var controller = new ArtifactDownloadController();
        assertThrows(AccessDeniedException.class,
                () -> controller.downloadRedacted("00000000-0000-0000-0000-000000000000"));
        assertEquals("/api/v1/documents",
                ArtifactDownloadController.class.getAnnotation(RequestMapping.class).value()[0]);
        assertEquals("/{documentId}/artifacts/redacted/content",
                ArtifactDownloadController.class.getMethod("downloadRedacted", String.class)
                        .getAnnotation(GetMapping.class).value()[0]);
    }
}

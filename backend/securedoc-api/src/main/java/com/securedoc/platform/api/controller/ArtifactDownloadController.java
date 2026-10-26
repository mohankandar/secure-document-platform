package com.securedoc.platform.api.controller;

import com.securedoc.platform.application.exception.AccessDeniedException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Legacy compatibility route; user-initiated artifact downloads are prohibited.
 * Outbound system-to-system delivery uses its separate authenticated integration pipeline.
 */
@RestController
@RequestMapping("/api/v1/documents")
public class ArtifactDownloadController {

    @GetMapping("/{documentId}/artifacts/redacted/content")
    public void downloadRedacted(@PathVariable String documentId) {
        throw new AccessDeniedException("Document downloads are not permitted");
    }
}

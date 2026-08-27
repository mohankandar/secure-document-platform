package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.port.in.DownloadArtifactUseCase;
import com.securedoc.platform.application.vault.command.DownloadArtifactCommand;
import com.securedoc.platform.application.vault.result.DownloadArtifactResult;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
public class ArtifactDownloadController {

    private final DownloadArtifactUseCase downloadArtifactUseCase;
    private final RequestContextResolver contextResolver;

    public ArtifactDownloadController(DownloadArtifactUseCase downloadArtifactUseCase,
                                       RequestContextResolver contextResolver) {
        this.downloadArtifactUseCase = downloadArtifactUseCase;
        this.contextResolver = contextResolver;
    }

    @GetMapping("/{documentId}/artifacts/redacted/content")
    public ResponseEntity<InputStreamResource> downloadRedacted(
            @PathVariable String documentId,
            HttpServletRequest httpRequest) {
        return downloadArtifact(documentId, ArtifactType.REDACTED, httpRequest);
    }

    @GetMapping("/{documentId}/artifacts/original/content")
    public ResponseEntity<InputStreamResource> downloadOriginal(
            @PathVariable String documentId,
            HttpServletRequest httpRequest) {
        return downloadArtifact(documentId, ArtifactType.ORIGINAL, httpRequest);
    }

    private ResponseEntity<InputStreamResource> downloadArtifact(
            String documentId, ArtifactType artifactType, HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        DocumentId docId = DocumentId.of(UUID.fromString(documentId));

        DownloadArtifactCommand command = DownloadArtifactCommand.of(
                principal.tenantId(), docId, artifactType, principal.actorId(), correlationId);

        DownloadArtifactResult result = downloadArtifactUseCase.execute(command);

        InputStreamResource resource = new InputStreamResource(result.content());

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.downloadFilename() + "\"")
                .contentType(MediaType.parseMediaType(result.mimeType()))
                .contentLength(result.sizeBytes())
                .body(resource);
    }
}

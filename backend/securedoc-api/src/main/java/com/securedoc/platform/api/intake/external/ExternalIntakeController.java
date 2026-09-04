package com.securedoc.platform.api.intake.external;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.upload.MultipartUploadContent;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.external.ExternalIntakeService;
import com.securedoc.platform.application.intake.external.PushIntakeCreateInput;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

/** Versioned machine-to-machine, manifest-first push ingestion contract. */
@RestController
@RequestMapping("/api/v1/external-intakes")
public class ExternalIntakeController {
    private final ExternalIntakeService service;
    private final RequestContextResolver contextResolver;

    public ExternalIntakeController(ExternalIntakeService service, RequestContextResolver contextResolver) {
        this.service = service;
        this.contextResolver = contextResolver;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<ExternalIntakeResponse> create(@RequestBody ExternalIntakeCreateRequest request,
                                                         HttpServletRequest httpRequest) {
        AuthenticatedPrincipal principal = contextResolver.resolveIntegrationPrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        var documents = request.documents() == null ? java.util.List.<ExternalIntakeCreateRequest.ManifestDocument>of() : request.documents();
        var input = new PushIntakeCreateInput(request.externalSourcePackageId(), request.sourceRevision(),
                request.businessIdentifiers(), request.metadata(), documents.stream()
                .map(d -> new PushIntakeCreateInput.ManifestDocumentInput(
                        d.externalSourceDocumentId(), d.fileName(), d.sha256())).toList(), Boolean.TRUE.equals(request.retry()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ExternalIntakeResponse.from(service.createPushIntake(input, principal, correlationId)));
    }

    @PostMapping(value = "/{packageId}/documents/{externalSourceDocumentId}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<ExternalDocumentReceiptResponse> uploadDocument(
            @PathVariable UUID packageId,
            @PathVariable String externalSourceDocumentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Content-SHA256") String sourceSha256,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest httpRequest) {
        AuthenticatedPrincipal principal = contextResolver.resolveIntegrationPrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        String contentType = file.getContentType() == null ? MediaType.APPLICATION_PDF_VALUE : file.getContentType();
        try (MultipartUploadContent content = MultipartUploadContent.from(file)) {
            var receipt = service.receivePushDocument(PackageId.of(packageId), externalSourceDocumentId,
                    idempotencyKey, sourceSha256, contentType, content, principal, correlationId);
            return ResponseEntity.status(receipt.duplicateAcknowledgement() ? HttpStatus.OK : HttpStatus.CREATED)
                    .body(ExternalDocumentReceiptResponse.from(receipt));
        }
    }

    @GetMapping("/{packageId}")
    @Transactional
    public ResponseEntity<ExternalIntakeResponse> status(@PathVariable UUID packageId,
                                                          HttpServletRequest httpRequest) {
        AuthenticatedPrincipal principal = contextResolver.resolveIntegrationPrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        return ResponseEntity.ok(ExternalIntakeResponse.from(
                service.status(PackageId.of(packageId), principal, correlationId)));
    }
}

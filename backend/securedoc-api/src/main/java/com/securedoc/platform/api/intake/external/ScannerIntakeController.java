package com.securedoc.platform.api.intake.external;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.upload.RequestBodyUploadContent;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.external.PushIntakeCreateInput;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.integration.IntegrationConnection;
import com.securedoc.platform.domain.tenant.Tenant;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

/**
 * Tenant-bound machine contract used by the local Scanner Bridge.
 *
 * <p>The transport is intentionally manifest-first and reuses the existing external-intake
 * idempotency/checksum/reconciliation machinery. Scanner-specific behavior stops at source
 * classification/provenance; accepted packages join the common LexaVault processing lifecycle.</p>
 */
@RestController
@RequestMapping("/api/v1/scanner-intakes")
public class ScannerIntakeController {
    private final ScannerIntakeTransactionBoundary transactions;
    private final RequestContextResolver contextResolver;
    private final TenantRepository tenants;

    public ScannerIntakeController(ScannerIntakeTransactionBoundary transactions, RequestContextResolver contextResolver,
                                   TenantRepository tenants) {
        this.transactions = transactions;
        this.contextResolver = contextResolver;
        this.tenants = tenants;
    }

    @PostMapping
    public ResponseEntity<ExternalIntakeResponse> create(@RequestBody ExternalIntakeCreateRequest request,
                                                         HttpServletRequest httpRequest) {
        AuthenticatedPrincipal principal = contextResolver.resolveIntegrationPrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        PushIntakeCreateInput input = request.toInput();
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ExternalIntakeResponse.from(transactions.create(input, principal, correlationId)).in(entity(principal)));
    }

    @PostMapping(value = "/{packageId}/documents/{externalSourceDocumentId}", consumes = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<ExternalDocumentReceiptResponse> uploadDocument(
            @PathVariable UUID packageId,
            @PathVariable String externalSourceDocumentId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader("X-Content-SHA256") String sourceSha256,
            HttpServletRequest httpRequest) {
        AuthenticatedPrincipal principal = contextResolver.resolveIntegrationPrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        try (RequestBodyUploadContent content = RequestBodyUploadContent.from(
                httpRequest.getInputStream(), httpRequest.getContentLengthLong(), IntegrationConnection.MAX_PDF_SIZE_BYTES)) {
            var receipt = transactions.receive(PackageId.of(packageId), externalSourceDocumentId,
                    idempotencyKey, sourceSha256, MediaType.APPLICATION_PDF_VALUE, content, principal, correlationId);
            return ResponseEntity.status(receipt.duplicateAcknowledgement() ? HttpStatus.OK : HttpStatus.CREATED)
                    .body(ExternalDocumentReceiptResponse.from(receipt).in(entity(principal)));
        } catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException("Unable to read scanner upload", ex);
        }
    }

    @GetMapping("/{packageId}")
    public ResponseEntity<ExternalIntakeResponse> status(@PathVariable UUID packageId,
                                                          HttpServletRequest httpRequest) {
        AuthenticatedPrincipal principal = contextResolver.resolveIntegrationPrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        return ResponseEntity.ok(ExternalIntakeResponse.from(
                transactions.status(PackageId.of(packageId), principal, correlationId)).in(entity(principal)));
    }

    private String entity(AuthenticatedPrincipal principal) {
        return tenants.findById(principal.tenantId()).map(Tenant::name).orElse(null);
    }
}

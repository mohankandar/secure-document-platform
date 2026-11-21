package com.securedoc.platform.api.intake.external;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.command.UploadContent;
import com.securedoc.platform.application.intake.external.ExternalDocumentReceipt;
import com.securedoc.platform.application.intake.external.ExternalIntakeService;
import com.securedoc.platform.application.intake.external.ExternalIntakeSnapshot;
import com.securedoc.platform.application.intake.external.PushIntakeCreateInput;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps database transactions around LexaVault state changes, not around a potentially very large
 * scanner HTTP request body. The API controller spools the raw PDF to disk first, then enters this
 * boundary for authoritative validation, storage, receipt, submission and dispatch.
 */
@Component
public class ScannerIntakeTransactionBoundary {
    private final ExternalIntakeService service;

    public ScannerIntakeTransactionBoundary(ExternalIntakeService service) {
        this.service = service;
    }

    @Transactional
    public ExternalIntakeSnapshot create(PushIntakeCreateInput input, AuthenticatedPrincipal principal,
                                         CorrelationId correlationId) {
        return service.createScannerIntake(input, principal, correlationId);
    }

    // Task 0023 C6: a 410 keeps the expiry (state and EXTERNAL_INTAKE_EXPIRED audit) it recorded in this request.
    @Transactional(noRollbackFor = com.securedoc.platform.application.exception.IntakeExpiredException.class)
    public ExternalDocumentReceipt receive(PackageId packageId, String externalSourceDocumentId,
                                           String idempotencyKey, String sourceSha256, String mimeType,
                                           UploadContent content, AuthenticatedPrincipal principal,
                                           CorrelationId correlationId) {
        return service.receiveScannerDocument(packageId, externalSourceDocumentId, idempotencyKey, sourceSha256,
                mimeType, content, principal, correlationId);
    }

    @Transactional
    public ExternalIntakeSnapshot status(PackageId packageId, AuthenticatedPrincipal principal,
                                         CorrelationId correlationId) {
        return service.scannerStatus(packageId, principal, correlationId);
    }
}

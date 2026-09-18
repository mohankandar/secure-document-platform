package com.securedoc.platform.application.service;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.intake.command.SubmitPackageCommand;
import com.securedoc.platform.application.licensing.TenantLicensingService;
import com.securedoc.platform.application.port.in.SubmitPackageUseCase;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.event.PackageSubmittedEvent;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.tenant.Capability;
import com.securedoc.platform.domain.tenant.Tenant;

import java.util.Objects;

/**
 * Application service implementing the submit-package use case.
 * Transitions the package from CREATED to SUBMITTED.
 */
public final class SubmitPackageService implements SubmitPackageUseCase {

    private final TenantContextService tenantContextService;
    private final CapabilityEnforcementService capabilityEnforcement;
    private final PackageRepository packageRepository;
    private final TenantLicensingService tenantLicensingService;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public SubmitPackageService(TenantContextService tenantContextService,
                                 CapabilityEnforcementService capabilityEnforcement,
                                 TenantLicensingService tenantLicensingService,
                                 PackageRepository packageRepository,
                                 DomainEventPublisher eventPublisher,
                                 AuditService auditService) {
        this.tenantContextService = Objects.requireNonNull(tenantContextService);
        this.capabilityEnforcement = Objects.requireNonNull(capabilityEnforcement);
        this.tenantLicensingService = Objects.requireNonNull(tenantLicensingService);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public void execute(SubmitPackageCommand command, AuthenticatedPrincipal principal) {
        Objects.requireNonNull(command, "Command must not be null");
        Objects.requireNonNull(principal, "Principal must not be null");

        // 1. Validate tenant context
        Tenant tenant = tenantContextService.resolveAndValidate(principal);
        capabilityEnforcement.require(tenant, Capability.DOCUMENT_UPLOAD);
        tenantLicensingService.requireDocumentProcessing(principal.tenantId(), principal.actorId(), command.correlationId());

        // 2. Validate package exists and belongs to tenant
        DocumentPackage documentPackage = packageRepository
                .findByIdAndTenantId(command.packageId(), principal.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));

        // 3. Validate package has documents (domain enforces this, but provide clear app-layer message)
        if (documentPackage.documentCount() == 0) {
            throw new ValidationException("Cannot submit package with no documents");
        }

        // 4. Transition to SUBMITTED via domain lifecycle method
        //    The domain will reject if current status is not CREATED
        documentPackage.submit();

        // 5. Persist
        packageRepository.save(documentPackage);

        // 6. Publish domain event
        CorrelationId correlationId = command.correlationId();
        PackageSubmittedEvent event = PackageSubmittedEvent.of(
                documentPackage.id(),
                principal.tenantId(),
                documentPackage.documentCount(),
                correlationId
        );
        eventPublisher.publish(event);

        // 7. Audit
        auditService.recordSuccess(
                principal.tenantId(),
                principal.actorId(),
                AuditAction.PACKAGE_SUBMITTED,
                "DocumentPackage",
                documentPackage.id().toString(),
                correlationId,
                "Package submitted with " + documentPackage.documentCount() + " document(s)"
        );
    }
}

package com.securedoc.platform.application.service;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.command.CreatePackageCommand;
import com.securedoc.platform.application.intake.command.CreatePackageResult;
import com.securedoc.platform.application.port.in.CreatePackageUseCase;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.event.PackageCreatedEvent;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.tenant.Capability;
import com.securedoc.platform.domain.tenant.Tenant;

import java.util.Objects;

/**
 * Application service implementing the create-package use case.
 */
public final class CreatePackageService implements CreatePackageUseCase {

    private final TenantContextService tenantContextService;
    private final CapabilityEnforcementService capabilityEnforcement;
    private final PackageRepository packageRepository;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public CreatePackageService(TenantContextService tenantContextService,
                                 CapabilityEnforcementService capabilityEnforcement,
                                 PackageRepository packageRepository,
                                 DomainEventPublisher eventPublisher,
                                 AuditService auditService) {
        this.tenantContextService = Objects.requireNonNull(tenantContextService);
        this.capabilityEnforcement = Objects.requireNonNull(capabilityEnforcement);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public CreatePackageResult execute(CreatePackageCommand command, AuthenticatedPrincipal principal) {
        Objects.requireNonNull(command, "Command must not be null");
        Objects.requireNonNull(principal, "Principal must not be null");

        // 1. Validate tenant context
        Tenant tenant = tenantContextService.resolveAndValidate(principal);

        // 2. Validate capability for document upload/intake
        capabilityEnforcement.require(tenant, Capability.DOCUMENT_UPLOAD);

        // 3. Create domain aggregate
        PackageMetadata metadata = PackageMetadata.of(command.metadata());
        DocumentPackage documentPackage = DocumentPackage.create(
                principal.tenantId(),
                command.packageType(),
                principal.actorId(),
                metadata
        );

        // 4. Persist
        packageRepository.save(documentPackage);

        // 5. Publish domain event
        CorrelationId correlationId = command.correlationId();
        PackageCreatedEvent event = PackageCreatedEvent.of(
                documentPackage.id(),
                principal.tenantId(),
                command.packageType(),
                principal.actorId(),
                correlationId
        );
        eventPublisher.publish(event);

        // 6. Audit
        auditService.recordSuccess(
                principal.tenantId(),
                principal.actorId(),
                AuditAction.PACKAGE_CREATED,
                "DocumentPackage",
                documentPackage.id().toString(),
                correlationId,
                "Package created: type=" + command.packageType()
        );

        // 7. Return result
        return CreatePackageResult.of(
                documentPackage.id(),
                documentPackage.status(),
                documentPackage.createdAt()
        );
    }
}

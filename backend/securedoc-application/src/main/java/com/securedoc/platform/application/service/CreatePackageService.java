package com.securedoc.platform.application.service;

import com.securedoc.platform.application.business.model.BusinessIdentitySnapshot;
import com.securedoc.platform.application.business.service.BusinessIdentityService;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.IntakeValidationException;
import com.securedoc.platform.application.intake.command.CreatePackageCommand;
import com.securedoc.platform.application.licensing.IntakeLicence;
import com.securedoc.platform.application.intake.command.CreatePackageResult;
import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
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

/** Creates one intake batch and links it to the long-lived BusinessRecord resolved from configured identifiers. */
public final class CreatePackageService implements CreatePackageUseCase {
    private final TenantContextService tenantContextService;
    private final CapabilityEnforcementService capabilityEnforcement;
    private final BusinessIdentityService businessIdentityService;
    private final IntakeLicence intakeLicence;
    private final PackageRepository packageRepository;
    private com.securedoc.platform.application.intake.CasePriorityService priorities;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public CreatePackageService(TenantContextService tenantContextService,
                                CapabilityEnforcementService capabilityEnforcement,
                                BusinessIdentityService businessIdentityService,
                                IntakeLicence intakeLicence,
                                PackageRepository packageRepository,
                                DomainEventPublisher eventPublisher,
                                AuditService auditService) {
        this.tenantContextService = Objects.requireNonNull(tenantContextService);
        this.capabilityEnforcement = Objects.requireNonNull(capabilityEnforcement);
        this.businessIdentityService = Objects.requireNonNull(businessIdentityService);
        this.intakeLicence = Objects.requireNonNull(intakeLicence);
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.eventPublisher = Objects.requireNonNull(eventPublisher);
        this.auditService = Objects.requireNonNull(auditService);
    }

    /** Task 0073: the same service, keeping priorities. Without it (tests of creation itself) every case is Normal. */
    public CreatePackageService withPriorities(com.securedoc.platform.application.intake.CasePriorityService priorities) {
        this.priorities = Objects.requireNonNull(priorities);
        return this;
    }

    @Override
    public CreatePackageResult execute(CreatePackageCommand command, AuthenticatedPrincipal principal) {
        Objects.requireNonNull(command); Objects.requireNonNull(principal);
        Tenant tenant = tenantContextService.resolveAndValidate(principal);
        capabilityEnforcement.require(tenant, Capability.DOCUMENT_UPLOAD);
        intakeLicence.requireIntake(principal.tenantId(), principal.actorId(), command.correlationId());

        if (command.intakeFingerprint() != null && packageRepository
                .findByTenantIdAndIntakeFingerprint(principal.tenantId(), command.intakeFingerprint()).isPresent()) {
            throw new IntakeValidationException(IntakeRejectionCode.DUPLICATE_INTAKE,
                    "This intake batch has already been accepted");
        }

        BusinessIdentitySnapshot identity = businessIdentityService.resolveOrCreate(
                principal.tenantId(), command.businessIdentifiers());

        DocumentPackage documentPackage = DocumentPackage.createIntake(
                principal.tenantId(), command.packageType(), identity.businessRecordId(),
                command.intakeFingerprint(), command.sourceReference(), principal.actorId(),
                PackageMetadata.of(command.metadata()), command.caseSensitivity().level());
        packageRepository.save(documentPackage);
        var priority = priorities == null ? com.securedoc.platform.domain.pipeline.PipelinePriority.NORMAL
                : priorities.atIntake(principal.tenantId(), documentPackage.id(), command.priority(), command.sourceKey());

        CorrelationId correlationId = command.correlationId();
        eventPublisher.publish(PackageCreatedEvent.of(documentPackage.id(), principal.tenantId(),
                command.packageType(), principal.actorId(), correlationId));
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.PACKAGE_CREATED,
                "IntakeBatch", documentPackage.id().toString(), correlationId,
                "Intake batch created: channel=" + command.packageType()
                        + ", priority=" + priority.name()
                        + ", sensitivityLevel=" + documentPackage.sensitivityLevel().name());
        if (command.caseSensitivity().unknown()) {
            auditService.recordUnknownSensitivityLevel(principal.tenantId(), principal.actorId(), documentPackage.id(),
                    AuditService.SensitivityScope.CASE, correlationId);
        }
        if (identity.createdNew()) {
            auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.BUSINESS_RECORD_CREATED,
                    "BusinessRecord", identity.businessRecordId().toString(), correlationId,
                    "Business record created from configured identifiers");
        }
        auditService.recordSuccess(principal.tenantId(), principal.actorId(), AuditAction.BUSINESS_RECORD_LINKED,
                "BusinessRecord", identity.businessRecordId().toString(), correlationId,
                "Business record linked to intake batch");
        return CreatePackageResult.of(documentPackage.id(), identity.businessRecordId(),
                documentPackage.status(), documentPackage.createdAt());
    }
}

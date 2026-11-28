package com.securedoc.platform.application.service;

import com.securedoc.platform.application.casefields.CaseFieldSnapshot;
import com.securedoc.platform.application.casefields.CaseFieldService;
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

/** Creates one intake batch and links it to the long-lived business record its case fields resolve to. */
public final class CreatePackageService implements CreatePackageUseCase {
    private final TenantContextService tenantContextService;
    private final CapabilityEnforcementService capabilityEnforcement;
    private final CaseFieldService caseFields;
    private final IntakeLicence intakeLicence;
    private final PackageRepository packageRepository;
    private com.securedoc.platform.application.intake.CasePriorityService priorities;
    private final DomainEventPublisher eventPublisher;
    private final AuditService auditService;

    public CreatePackageService(TenantContextService tenantContextService,
                                CapabilityEnforcementService capabilityEnforcement,
                                CaseFieldService caseFields,
                                IntakeLicence intakeLicence,
                                PackageRepository packageRepository,
                                DomainEventPublisher eventPublisher,
                                AuditService auditService) {
        this.tenantContextService = Objects.requireNonNull(tenantContextService);
        this.capabilityEnforcement = Objects.requireNonNull(capabilityEnforcement);
        this.caseFields = Objects.requireNonNull(caseFields);
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

    private com.securedoc.platform.application.port.out.CaseBatchStore contractKeys;

    /**
     * Task 0104 (owner 2026-11-28): keeps the contract's keyed fingerprint with each new case, so a batch arriving
     * after the case's files and case-field values were deleted still joins it. Without it nothing is kept.
     */
    public CreatePackageService withContractKeys(com.securedoc.platform.application.port.out.CaseBatchStore store) {
        this.contractKeys = Objects.requireNonNull(store);
        return this;
    }

    private com.securedoc.platform.application.intake.CaseInstructionsService instructions;

    /** Task 0065: without it case instructions are refused. */
    public CreatePackageService withInstructions(com.securedoc.platform.application.intake.CaseInstructionsService service) {
        this.instructions = Objects.requireNonNull(service);
        return this;
    }

    /** One transaction, the caller's when there is one. */
    public interface Unit {
        <T> T run(java.util.function.Supplier<T> work);
    }

    private Unit unit = new Unit() {
        @Override
        public <T> T run(java.util.function.Supplier<T> work) {
            return work.get();
        }
    };

    /** Task 0061: the business record's values and the case that points to it are stored in one transaction. */
    public CreatePackageService withUnit(Unit unit) {
        this.unit = Objects.requireNonNull(unit);
        return this;
    }

    private record Created(CaseFieldSnapshot identity, DocumentPackage documentPackage,
                           com.securedoc.platform.domain.pipeline.PipelinePriority priority) {
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

        boolean instructed = command.instructions() != null && !command.instructions().isBlank();
        if (instructed && instructions == null) {
            throw new com.securedoc.platform.application.exception.ValidationException("Case instructions are not taken in here");
        }
        if (instructed) instructions.check(principal.tenantId(), command.instructions());
        Created created = unit.run(() -> {
            CaseFieldSnapshot record = caseFields.resolveOrCreate(principal.tenantId(), command.businessIdentifiers());
            DocumentPackage made = DocumentPackage.createIntake(
                    principal.tenantId(), command.packageType(), record.businessRecordId(),
                    command.intakeFingerprint(), command.sourceReference(), principal.actorId(),
                    PackageMetadata.of(command.metadata()), command.caseSensitivity().level());
            packageRepository.save(made);
            if (contractKeys != null) {
                caseFields.contractKeys(principal.tenantId(), command.businessIdentifiers()).stream().findFirst()
                        .ifPresent(key -> contractKeys.rememberContract(principal.tenantId(), made.id(), key));
            }
            if (instructed) {
                instructions.atIntake(principal.tenantId(), principal.actorId(), made.id(), command.instructions(),
                        command.correlationId());
            }
            return new Created(record, made, priorities == null ? com.securedoc.platform.domain.pipeline.PipelinePriority.NORMAL
                    : priorities.atIntake(principal.tenantId(), made.id(), command.priority(), command.sourceKey()));
        });
        CaseFieldSnapshot identity = created.identity();
        DocumentPackage documentPackage = created.documentPackage();
        var priority = created.priority();

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

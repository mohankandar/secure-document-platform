package com.securedoc.platform.application.lifecycle.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.lifecycle.command.ApproveFinalDeliveryCommand;
import com.securedoc.platform.application.port.in.ApproveFinalDeliveryUseCase;
import com.securedoc.platform.application.port.in.TriggerPackageProcessingUseCase;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.processing.command.TriggerPackageProcessingCommand;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;

import java.time.Instant;
import java.util.Objects;

/** Approves a fully processed package for final customer delivery and resumes export automatically. */
public final class FinalDeliveryApprovalService implements ApproveFinalDeliveryUseCase {
    private final PackageRepository packageRepository;
    private final PackageLifecycleService lifecycleService;
    private final TriggerPackageProcessingUseCase triggerPackageProcessingUseCase;
    private final AuditService auditService;

    public FinalDeliveryApprovalService(PackageRepository packageRepository,
                                        PackageLifecycleService lifecycleService,
                                        TriggerPackageProcessingUseCase triggerPackageProcessingUseCase,
                                        AuditService auditService) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.lifecycleService = Objects.requireNonNull(lifecycleService);
        this.triggerPackageProcessingUseCase = Objects.requireNonNull(triggerPackageProcessingUseCase);
        this.auditService = Objects.requireNonNull(auditService);
    }

    @Override
    public void execute(ApproveFinalDeliveryCommand command) {
        DocumentPackage pkg = packageRepository.findByIdAndTenantId(command.packageId(), command.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", command.packageId().toString()));
        if (pkg.status() != PackageStatus.PENDING_FINAL_APPROVAL) {
            throw new ValidationException("Package is not waiting for final delivery approval");
        }
        lifecycleService.grantFinalApproval(command.packageId(), command.tenantId(), Instant.now());
        pkg.resumeAfterFinalApproval();
        packageRepository.save(pkg);
        auditService.recordSuccess(command.tenantId(), command.actorId(), AuditAction.PACKAGE_FINAL_APPROVED,
                "DocumentPackage", command.packageId().toString(), command.correlationId(),
                "Final delivery approved");
        triggerPackageProcessingUseCase.execute(TriggerPackageProcessingCommand.of(
                command.packageId(), command.tenantId(), command.actorId(), command.correlationId()));
    }
}

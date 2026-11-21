package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.export.result.ExportPackageDocumentsResult;
import com.securedoc.platform.application.port.in.ProcessDerivedRoutingUseCase;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.technical.service.ProcessingIncidentService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * What happens after the master export succeeded: derived routing, then the case is COMPLETED. Moved here unchanged
 * from {@code TriggerPackageProcessingService} (task 0027) so the inline path and the export job do the same thing.
 */
public final class MasterExportCompletion {
    private static final Logger log = LoggerFactory.getLogger(MasterExportCompletion.class);

    private final PackageRepository packageRepository;
    private final ProcessDerivedRoutingUseCase processDerivedRoutingUseCase;
    private final ProcessingIncidentService processingIncidentService;
    private final AuditService auditService;

    public MasterExportCompletion(PackageRepository packageRepository,
                                  ProcessDerivedRoutingUseCase processDerivedRoutingUseCase,
                                  ProcessingIncidentService processingIncidentService,
                                  AuditService auditService) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
        this.processDerivedRoutingUseCase = Objects.requireNonNull(processDerivedRoutingUseCase);
        this.processingIncidentService = Objects.requireNonNull(processingIncidentService);
        this.auditService = Objects.requireNonNull(auditService);
    }

    public void complete(DocumentPackage pkg, TenantId tenantId, PackageId packageId,
                         ExportPackageDocumentsResult exportResult, int documentCount, CorrelationId correlationId) {
        // Option A: master SDV/local export is authoritative for package completion. Derived
        // routing is an independent post-export track; failures are recorded there and never
        // undo the successful master export or its retention semantics.
        try {
            processDerivedRoutingUseCase.processPackage(packageId, tenantId, exportResult.exportCycle(), correlationId);
        } catch (Exception e) {
            log.warn("DERIVED_ROUTING_POST_EXPORT_FAILED package={} tenant={} correlation={} type={}",
                    packageId, tenantId, correlationId, e.getClass().getSimpleName());
            // Task 0023 (C4, #181): was log only. Audit it with a code and put it in the attention list; the
            // master export stays successful and nothing is retried.
            try {
                processDerivedRoutingUseCase.recordRoutingFailure(packageId, tenantId, exportResult.exportCycle(),
                        "DERIVED_ROUTING_POST_EXPORT_FAILED", correlationId);
            } catch (RuntimeException recordFailure) {
                log.warn("DERIVED_ROUTING_FAILURE_NOT_RECORDED package={} correlation={} type={}",
                        packageId, correlationId, recordFailure.getClass().getSimpleName());
            }
        }

        pkg.completeSuccessfully();
        packageRepository.save(pkg);
        processingIncidentService.markProcessingCompleted(packageId, tenantId, correlationId);
        auditService.recordSuccess(
                tenantId, ExportJobQueue.SYSTEM_ACTOR, AuditAction.PACKAGE_PROCESSING_COMPLETED,
                "DocumentPackage", packageId.toString(), correlationId,
                "Package processing and delivery completed: " + documentCount + " document(s)");
    }
}

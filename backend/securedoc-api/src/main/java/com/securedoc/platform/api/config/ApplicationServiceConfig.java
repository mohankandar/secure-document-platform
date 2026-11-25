package com.securedoc.platform.api.config;

import com.securedoc.platform.application.audit.RequestActorDisplayName;
import com.securedoc.platform.application.storage.ProcessingStorageConfigurationService;
import com.securedoc.platform.application.business.admin.BusinessAdminConfigurationService;
import com.securedoc.platform.application.intelligence.service.DocumentIntelligenceConfigurationService;
import com.securedoc.platform.application.intelligence.service.DocumentIntelligenceExecutionService;
import com.securedoc.platform.application.port.out.ReportingConfigurationRepository;
import com.securedoc.platform.application.port.out.ProcessingStorageConfigurationRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.TenantLifecyclePolicyRepository;
import com.securedoc.platform.application.port.out.PackageLifecycleRepository;
import com.securedoc.platform.application.port.out.PackageExportRecordRepository;
import com.securedoc.platform.application.intake.watched.WatchedFolderIntakeService;
import com.securedoc.platform.application.intake.external.ExternalIntakeService;
import com.securedoc.platform.application.intake.polling.PollingIntakeService;
import com.securedoc.platform.application.intake.external.ExternalIntakeMaintenanceService;
import com.securedoc.platform.application.port.out.WatchedFolderSourcePort;
import com.securedoc.platform.application.port.out.WatchedFolderLeasePort;
import com.securedoc.platform.application.port.out.IntakeObservationRepository;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.port.out.ExternalIntakeRepository;
import com.securedoc.platform.application.port.out.PdfIntakeValidationPort;
import com.securedoc.platform.application.port.out.PollingRunRepository;
import com.securedoc.platform.application.port.out.PollingSourcePort;
import com.securedoc.platform.application.integration.IntegrationConnectionService;
import com.securedoc.platform.application.integration.DocumentStoreConnectionTestService;
import com.securedoc.platform.application.routing.service.RoutingRuleService;
import com.securedoc.platform.application.routing.service.DerivedRoutingService;
import com.securedoc.platform.application.routing.service.DerivedDeliveryService;
import com.securedoc.platform.application.routing.service.DerivedConnectionTestService;
import com.securedoc.platform.application.port.in.ProcessDerivedRoutingUseCase;
import com.securedoc.platform.application.port.out.RoutingRuleRepository;
import com.securedoc.platform.application.port.out.DerivedDeliveryRepository;
import com.securedoc.platform.application.port.out.RoutingMarkerDetectionPort;
import com.securedoc.platform.application.port.out.DerivedPdfBuilderPort;
import com.securedoc.platform.application.port.out.DerivedDestinationDeliveryPort;
import com.securedoc.platform.application.port.out.DocumentStoreConnectionTestPort;
import com.securedoc.platform.infrastructure.intake.WatchedFolderProperties;
import com.securedoc.platform.application.port.in.CreatePackageUseCase;
import com.securedoc.platform.application.port.in.DetectSensitiveDataUseCase;
import com.securedoc.platform.application.audit.service.AuditQueryService;
import com.securedoc.platform.application.port.out.AuditQueryPort;
import com.securedoc.platform.application.port.in.ExtractDocumentTextUseCase;
import com.securedoc.platform.application.port.in.FailDocumentProcessingUseCase;
import com.securedoc.platform.application.port.in.GetPackageQuery;
import com.securedoc.platform.application.port.in.HidePackageFromDashboardUseCase;
import com.securedoc.platform.application.port.in.GetReviewPagePreviewQuery;
import com.securedoc.platform.application.port.in.StartManualFallbackUseCase;
import com.securedoc.platform.application.port.in.GetManualFallbackQuery;
import com.securedoc.platform.application.port.in.AddManualRedactionRegionUseCase;
import com.securedoc.platform.application.port.in.RemoveManualRedactionRegionUseCase;
import com.securedoc.platform.application.port.in.GetManualFallbackPagePreviewQuery;
import com.securedoc.platform.application.port.in.SubmitManualFallbackUseCase;
import com.securedoc.platform.application.port.in.ListPackagesQuery;
import com.securedoc.platform.application.port.in.RedactDocumentUseCase;
import com.securedoc.platform.application.port.in.StartDocumentProcessingUseCase;
import com.securedoc.platform.application.port.in.StartPackageProcessingUseCase;
import com.securedoc.platform.application.port.in.SubmitPackageUseCase;
import com.securedoc.platform.application.port.in.TriggerPackageProcessingUseCase;
import com.securedoc.platform.application.port.in.ReprocessPackageUseCase;
import com.securedoc.platform.application.port.in.GetTechnicalExceptionQuery;
import com.securedoc.platform.application.port.in.RetryTechnicalFailureUseCase;
import com.securedoc.platform.application.port.in.ApproveFinalDeliveryUseCase;
import com.securedoc.platform.application.port.in.UploadDocumentUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.AuditPort;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.port.out.PackageDashboardVisibilityPort;
import com.securedoc.platform.application.port.out.OperationsReadPort;
import com.securedoc.platform.application.port.out.DashboardColumnSettingsRepository;
import com.securedoc.platform.application.port.out.PdfRedactionPort;
import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.application.port.out.ProcessingIncidentRepository;
import com.securedoc.platform.application.port.out.SupportIncidentSearchRepository;
import com.securedoc.platform.application.port.out.CasePrimaryIdentifierLookup;
import com.securedoc.platform.application.routing.service.DerivedDeliveryAttentionQuery;
import com.securedoc.platform.application.port.out.ProcessingFingerprintProvider;
import com.securedoc.platform.application.port.out.DocumentIntelligenceBundleRepository;
import com.securedoc.platform.application.port.out.TenantDocumentIntelligencePolicyRepository;
import com.securedoc.platform.application.port.out.PackageProcessingIntelligenceSnapshotRepository;
import com.securedoc.platform.application.port.out.ReviewTaskRepository;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort;
import com.securedoc.platform.application.port.out.SensitiveDataValidationPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.RedactionVerificationPort;
import com.securedoc.platform.application.port.out.ReviewPageRenderPort;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.application.licensing.IntakeLicence;
import com.securedoc.platform.application.branding.BrandingConfigurationRepository;
import com.securedoc.platform.application.branding.BrandingService;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.application.port.out.AccessRequestRepository;
import com.securedoc.platform.application.port.out.VaultEntryRepository;
import com.securedoc.platform.application.detection.service.SensitiveDataDetectionService;
import com.securedoc.platform.application.extraction.service.TextExtractionService;
import com.securedoc.platform.application.redaction.service.DocumentRedactionService;
import com.securedoc.platform.application.resilience.ResilientSensitiveContentAnalysisPort;
import com.securedoc.platform.application.resilience.TenantRoutingSensitiveContentAnalysisPort;
import com.securedoc.platform.application.resilience.SelectiveRetryExecutor;
import com.securedoc.platform.application.review.service.FindingReviewService;
import com.securedoc.platform.application.review.service.FocusedReviewService;
import com.securedoc.platform.application.review.service.ReviewFoundTextReader;
import com.securedoc.platform.application.review.service.ReviewWorkflowService;
import com.securedoc.platform.application.review.service.ReviewPagePreviewService;
import com.securedoc.platform.application.manual.service.ManualFallbackService;
import com.securedoc.platform.application.manual.service.ManualFallbackPagePreviewService;
import com.securedoc.platform.application.processing.service.DocumentProcessingService;
import com.securedoc.platform.application.processing.service.PackageFinalizationService;
import com.securedoc.platform.application.processing.service.PackageProcessingService;
import com.securedoc.platform.application.processing.service.PackageProcessingRecoveryService;
import com.securedoc.platform.application.processing.service.TriggerPackageProcessingService;
import com.securedoc.platform.application.operations.service.OperationsQueryService;
import com.securedoc.platform.application.operations.service.PackageDashboardVisibilityService;
import com.securedoc.platform.application.operations.service.DashboardColumnConfigurationService;
import com.securedoc.platform.application.export.service.PackageDocumentExportService;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.lifecycle.service.ReprocessPackageService;
import com.securedoc.platform.application.lifecycle.service.FinalDeliveryApprovalService;
import com.securedoc.platform.application.technical.service.TechnicalExceptionQueryService;
import com.securedoc.platform.application.technical.service.RetryTechnicalFailureService;
import com.securedoc.platform.application.technical.service.ProcessingIncidentService;
import com.securedoc.platform.application.telemetry.ProductTelemetryService;
import com.securedoc.platform.application.support.service.SupportIncidentQueryService;
import com.securedoc.platform.application.support.service.SupportIncidentActionService;
import com.securedoc.platform.application.port.in.ExportPackageDocumentsUseCase;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.application.port.out.DeliverySubmissionRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.vault.service.ArtifactAccessService;
import com.securedoc.platform.application.vault.service.VaultService;
import com.securedoc.platform.application.service.CapabilityEnforcementService;
import com.securedoc.platform.application.service.CreatePackageService;
import com.securedoc.platform.application.service.GetPackageQueryService;
import com.securedoc.platform.application.service.ListPackagesQueryService;
import com.securedoc.platform.application.service.SubmitPackageService;
import com.securedoc.platform.application.service.TenantContextService;
import com.securedoc.platform.application.service.UploadDocumentService;
import com.securedoc.platform.ai.analysis.LocalHeuristicSensitiveContentAnalysisAdapter;
import com.securedoc.platform.ai.analysis.OllamaSensitiveContentAnalysisAdapter;
import com.securedoc.platform.ai.analysis.OllamaInferencePoolSensitiveContentAnalysisAdapter;
import com.securedoc.platform.ai.analysis.OpenAiCompatibleSensitiveContentAnalysisAdapter;
import com.securedoc.platform.api.processing.InProcessPackageProcessingDispatcher;
import com.securedoc.platform.api.processing.PackageProcessingDispatchLease;
import com.securedoc.platform.api.storage.ProcessingStorageNodeReadiness;
import com.securedoc.platform.api.observability.MdcTaskDecorator;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.resilience.TechnicalResilienceProperties;
import com.securedoc.platform.worker.processing.ProcessingWorkCoordinator;
import com.securedoc.platform.worker.processing.ProcessingWorker;
import com.securedoc.platform.worker.processing.ProcessingWorkerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Wires application services with infrastructure adapters.
 * Application classes remain framework-free — Spring instantiation happens here at the edge.
 */
@Configuration
public class ApplicationServiceConfig {

    @Bean
    public BrandingService brandingService(BrandingConfigurationRepository brandingConfigurationRepository,
                                           TenantRepository tenantRepository,
                                           AuditService auditService) {
        return new BrandingService(brandingConfigurationRepository, tenantRepository, auditService);
    }

    @Bean
    public ProcessingStorageConfigurationService processingStorageConfigurationService(
            ProcessingStorageConfigurationRepository repository,
            TenantRepository tenantRepository) {
        return new ProcessingStorageConfigurationService(repository, tenantRepository);
    }

    @Bean
    public TenantContextService tenantContextService(TenantRepository tenantRepository) {
        return new TenantContextService(tenantRepository);
    }

    @Bean
    public ProcessingFingerprintProvider processingFingerprintProvider(
            Environment environment,
            PackageProcessingIntelligenceSnapshotRepository intelligenceSnapshotRepository) {
        return (tenantId, packageId) -> {
            Map<String, String> fingerprint = new LinkedHashMap<>();
            putIfPresent(fingerprint, "applicationVersion", environment.getProperty("securedoc.build.version", "0.1.0-SNAPSHOT"));
            putIfPresent(fingerprint, "aiProvider", environment.getProperty("securedoc.ai.provider"));
            putIfPresent(fingerprint, "aiModel", environment.getProperty("securedoc.ai.model"));
            putIfPresent(fingerprint, "profile", environment.getProperty("spring.profiles.active", "default"));
            if (tenantId != null && packageId != null) {
                try {
                    intelligenceSnapshotRepository.findLatestByTenantIdAndPackageId(tenantId, packageId).ifPresent(snapshot -> {
                        fingerprint.put("intelligenceRequestedMode", snapshot.requestedMode().name());
                        fingerprint.put("intelligenceEffectiveMode", snapshot.effectiveMode().name());
                        fingerprint.put("intelligenceBundleVersion", snapshot.bundleVersion());
                        fingerprint.put("intelligencePolicyVersion", Integer.toString(snapshot.policyConfigurationVersion()));
                        fingerprint.put("intelligenceExecutionAllowed", Boolean.toString(snapshot.intelligenceExecutionAllowed()));
                        fingerprint.put("intelligenceInfluenceAllowed", Boolean.toString(snapshot.decisionInfluenceAllowed()));
                        fingerprint.put("baselineDiscoveryRequired", Boolean.toString(snapshot.baselineDiscoveryRequired()));
                        fingerprint.put("intelligenceFallbackToBaseline", Boolean.toString(snapshot.fallbackToBaseline()));
                    });
                } catch (RuntimeException ignored) {
                    // Support fingerprint enrichment must never interfere with incident capture.
                }
            }
            return Map.copyOf(fingerprint);
        };
    }

    @Bean
    public DocumentIntelligenceConfigurationService documentIntelligenceConfigurationService(
            DocumentIntelligenceBundleRepository bundleRepository,
            TenantDocumentIntelligencePolicyRepository policyRepository,
            AuditService auditService) {
        return new DocumentIntelligenceConfigurationService(bundleRepository, policyRepository, auditService);
    }

    @Bean
    public DocumentIntelligenceExecutionService documentIntelligenceExecutionService(
            TenantDocumentIntelligencePolicyRepository policyRepository,
            DocumentIntelligenceBundleRepository bundleRepository,
            PackageProcessingIntelligenceSnapshotRepository snapshotRepository) {
        return new DocumentIntelligenceExecutionService(policyRepository, bundleRepository, snapshotRepository);
    }

    @Bean
    public ProductTelemetryService productTelemetryService(
            @org.springframework.beans.factory.annotation.Value("${securedoc.product-telemetry.mode:OFF}") String mode,
            @org.springframework.beans.factory.annotation.Value("${securedoc.product-telemetry.installation-id:}") String installationId,
            @org.springframework.beans.factory.annotation.Value("${securedoc.product-telemetry.application-version:unknown}") String applicationVersion) {
        return new ProductTelemetryService(mode, installationId, applicationVersion);
    }

    @Bean
    public ProcessingIncidentService processingIncidentService(ProcessingIncidentRepository processingIncidentRepository,
                                                               ProcessingFingerprintProvider processingFingerprintProvider,
                                                               ProductTelemetryService productTelemetryService) {
        return new ProcessingIncidentService(processingIncidentRepository, processingFingerprintProvider,
                productTelemetryService)
                .withFailureReferences(code -> com.documentplatform.errors.FailureLog.record(
                        new com.securedoc.platform.api.error.LvPlatformCode(code)));
    }



    @Bean
    public SupportIncidentActionService supportIncidentActionService(
            ProcessingIncidentRepository processingIncidentRepository,
            PackageLifecycleService packageLifecycleService,
            ProcessingIncidentService processingIncidentService,
            RetryTechnicalFailureService retryTechnicalFailureUseCase,
            AuditService auditService,
            ProductTelemetryService productTelemetryService) {
        return new SupportIncidentActionService(processingIncidentRepository, packageLifecycleService,
                processingIncidentService, retryTechnicalFailureUseCase, auditService, productTelemetryService);
    }

    @Bean
    public SupportIncidentQueryService supportIncidentQueryService(
            SupportIncidentSearchRepository supportIncidentSearchRepository,
            ProcessingIncidentRepository processingIncidentRepository,
            PackageLifecycleRepository packageLifecycleRepository,
            DocumentRepository documentRepository) {
        return new SupportIncidentQueryService(supportIncidentSearchRepository, processingIncidentRepository,
                packageLifecycleRepository, documentRepository);
    }

    @Bean
    public IntegrationConnectionService integrationConnectionService(
            IntegrationConnectionRepository integrationConnectionRepository,
            AuditService auditService,
            com.securedoc.platform.application.port.out.EntityTimeZonePort entityTimeZones,
            com.securedoc.platform.application.port.out.DestinationProfilePort destinationProfiles) {
        return new IntegrationConnectionService(integrationConnectionRepository, auditService)
                .withEntityTimeZones(entityTimeZones)
                .withDestinationProfiles(destinationProfiles);
    }

    @Bean
    public com.securedoc.platform.application.integration.DraftConnectionTestService draftConnectionTestService(
            IntegrationConnectionService integrationConnectionService,
            org.springframework.beans.factory.ObjectProvider<DocumentStoreConnectionTestPort> documentStoreConnectionTestPort,
            PollingSourcePort pollingSourcePort,
            DerivedDestinationDeliveryPort derivedDestinationDeliveryPort,
            AuditService auditService) {
        return new com.securedoc.platform.application.integration.DraftConnectionTestService(integrationConnectionService,
                documentStoreConnectionTestPort.getIfAvailable(), pollingSourcePort, derivedDestinationDeliveryPort,
                new com.securedoc.platform.application.integration.OutboundAddressGuard(), auditService);
    }

    @Bean
    @ConditionalOnProperty(name = "securedoc.delivery.type", havingValue = "document-store")
    public DocumentStoreConnectionTestService documentStoreConnectionTestService(
            IntegrationConnectionService integrationConnectionService,
            DocumentStoreConnectionTestPort documentStoreConnectionTestPort,
            AuditService auditService) {
        return new DocumentStoreConnectionTestService(integrationConnectionService, documentStoreConnectionTestPort, auditService);
    }

    @Bean
    public ExternalIntakeMaintenanceService externalIntakeMaintenanceService(
            ExternalIntakeRepository externalIntakeRepository,
            PackageRepository packageRepository,
            ArtifactRepository artifactRepository,
            com.securedoc.platform.application.port.out.ErasurePort erasure,
            AuditService auditService) {
        return new ExternalIntakeMaintenanceService(externalIntakeRepository, packageRepository,
                artifactRepository, erasure, auditService);
    }

    @Bean
    public ExternalIntakeService externalIntakeService(
            com.securedoc.platform.application.port.out.IntakeRefusalPort intakeRefusals,
            IntegrationConnectionService integrationConnectionService,
            ExternalIntakeRepository externalIntakeRepository,
            PackageRepository packageRepository,
            CreatePackageUseCase createPackageUseCase,
            UploadDocumentUseCase uploadDocumentUseCase,
            SubmitPackageUseCase submitPackageUseCase,
            PackageProcessingDispatcher packageProcessingDispatcher,
            ChecksumPort checksumPort,
            PdfIntakeValidationPort pdfIntakeValidationPort,
            com.securedoc.platform.application.port.out.PdfDecryptionPort pdfDecryptionPort,
            com.securedoc.platform.application.casefields.CaseFieldService caseFields,
            com.securedoc.platform.application.port.out.PackageRejectionRepository packageRejectionRepository,
            AuditService auditService,
            ExternalIntakeMaintenanceService maintenanceService,
            RequestActorDisplayName requestActorDisplayName) {
        return new ExternalIntakeService(integrationConnectionService, externalIntakeRepository, packageRepository,
                createPackageUseCase, uploadDocumentUseCase, submitPackageUseCase, packageProcessingDispatcher,
                checksumPort, pdfIntakeValidationPort, auditService, maintenanceService, requestActorDisplayName)
                .withPdfDecryption(pdfDecryptionPort).withRejections(packageRejectionRepository)
                .withCaseFields(caseFields).withRefusals(intakeRefusals);
    }

    @Bean
    public PollingIntakeService pollingIntakeService(
            com.securedoc.platform.application.port.out.IntakeRefusalPort intakeRefusals,
            IntegrationConnectionService integrationConnectionService,
            PollingRunRepository pollingRunRepository,
            PollingSourcePort pollingSourcePort,
            ExternalIntakeService externalIntakeService,
            AuditService auditService,
            IntakeLicence intakeLicence,
            com.securedoc.platform.application.port.out.EntityTimeZonePort entityTimeZones,
            com.securedoc.platform.application.intake.IntakeGate intakeGate) {
        return new PollingIntakeService(integrationConnectionService, pollingRunRepository, pollingSourcePort,
                externalIntakeService, auditService).withLicence(intakeLicence).withEntityTimeZones(entityTimeZones)
                .withGate(intakeGate).withRefusals(intakeRefusals);
    }

    @Bean
    public CapabilityEnforcementService capabilityEnforcementService() {
        return new CapabilityEnforcementService();
    }

    /** The current request's display name for audit records (task 0016); filled by RequestContextResolver. */
    @Bean
    public RequestActorDisplayName requestActorDisplayName() {
        return new RequestActorDisplayName();
    }

    @Bean
    public AuditService auditService(AuditPort auditPort, RequestActorDisplayName requestActorDisplayName) {
        return new AuditService(auditPort, requestActorDisplayName);
    }

    @Bean
    public AuditQueryService auditQueryService(AuditQueryPort auditQueryPort) {
        return new AuditQueryService(auditQueryPort);
    }

    @Bean
    public BusinessAdminConfigurationService businessAdminConfigurationService(
            com.securedoc.platform.application.casefields.CaseFieldSettings caseFieldSettings,
            TenantRedactionPolicyRepository tenantRedactionPolicyRepository,
            TenantLifecyclePolicyRepository tenantLifecyclePolicyRepository,
            ReportingConfigurationRepository reportingConfigurationRepository,
            AuditService auditService) {
        return new BusinessAdminConfigurationService(
                caseFieldSettings, tenantRedactionPolicyRepository, tenantLifecyclePolicyRepository,
                reportingConfigurationRepository, auditService);
    }

    /** Task 0079. */
    @Bean
    public com.securedoc.platform.application.intake.BulkSourceService bulkSourceService(
            com.securedoc.platform.application.port.out.BulkSourceRepository bulkSourceRepository,
            com.securedoc.platform.application.port.out.EntityTimeZonePort entityTimeZones, AuditService auditService) {
        return new com.securedoc.platform.application.intake.BulkSourceService(bulkSourceRepository, entityTimeZones,
                auditService, java.time.Clock.systemUTC());
    }

    /** Task 0073. */
    @Bean
    public com.securedoc.platform.application.intake.CasePriorityService casePriorityService(
            com.securedoc.platform.application.port.out.CasePriorityRepository casePriorityRepository,
            PackageRepository packageRepository, AuditService auditService,
            com.securedoc.platform.application.intake.BulkSourceService bulkSourceService) {
        return new com.securedoc.platform.application.intake.CasePriorityService(casePriorityRepository, packageRepository,
                auditService).withBulk(bulkSourceService);
    }

    @Bean
    public CreatePackageUseCase createPackageUseCase(
            org.springframework.transaction.PlatformTransactionManager transactionManager,
            com.securedoc.platform.application.intake.CasePriorityService casePriorityService,
            TenantContextService tenantContextService,
            CapabilityEnforcementService capabilityEnforcementService,
            com.securedoc.platform.application.casefields.CaseFieldService caseFields,
            IntakeLicence intakeLicence,
            PackageRepository packageRepository,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new CreatePackageService(
                tenantContextService, capabilityEnforcementService, caseFields, intakeLicence,
                packageRepository, eventPublisher, auditService).withPriorities(casePriorityService)
                // Task 0061: the case's values and the case are stored together or not at all.
                .withUnit(new CreatePackageService.Unit() {
                    private final org.springframework.transaction.support.TransactionTemplate template =
                            new org.springframework.transaction.support.TransactionTemplate(transactionManager);
                    @Override
                    public <T> T run(java.util.function.Supplier<T> work) {
                        return template.execute(status -> work.get());
                    }
                });
    }

    @Bean
    public WatchedFolderIntakeService watchedFolderIntakeService(
            WatchedFolderSourcePort sourcePort,
            WatchedFolderLeasePort watchedFolderLeasePort,
            IntakeObservationRepository observationRepository,
            PackageRepository packageRepository,
            CreatePackageUseCase createPackageUseCase,
            UploadDocumentUseCase uploadDocumentUseCase,
            SubmitPackageUseCase submitPackageUseCase,
            PackageProcessingDispatcher packageProcessingDispatcher,
            AuditService auditService,
            WatchedFolderProperties properties,
            com.securedoc.platform.application.casefields.CaseFieldService caseFields,
            IntakeLicence intakeLicence,
            com.securedoc.platform.application.intake.watched.WatchedFolderSettingsService watchedFolderSettings,
            com.securedoc.platform.application.intake.IntakeGate intakeGate) {
        return new WatchedFolderIntakeService(sourcePort, watchedFolderLeasePort, observationRepository, packageRepository,
                createPackageUseCase, uploadDocumentUseCase, submitPackageUseCase,
                packageProcessingDispatcher, auditService,
                Duration.ofMinutes(Math.max(0, properties.getIncompleteVisibilityMinutes())),
                Duration.ofMillis(Math.max(1000L, properties.getStaleClaimAfterMs())),
                tenant -> watchedFolderSettings.sensitivityLevel(tenant,
                        com.securedoc.platform.application.intake.watched.WatchedFolderSettingsService.INSTALLED_FOLDER)).withLicence(intakeLicence)
                .withGate(intakeGate).withCaseFields(caseFields);
    }

    /** Task 0021: the watched folder's sensitivity level, set by Tenant IT. */
    @Bean
    public com.securedoc.platform.application.intake.watched.WatchedFolderSettingsService watchedFolderSettingsService(
            com.securedoc.platform.application.port.out.WatchedFolderSettingsRepository repository,
            AuditService auditService) {
        return new com.securedoc.platform.application.intake.watched.WatchedFolderSettingsService(
                repository, auditService, java.time.Clock.systemUTC());
    }

    @Bean
    public UploadDocumentUseCase uploadDocumentUseCase(
            TenantContextService tenantContextService,
            CapabilityEnforcementService capabilityEnforcementService,
            IntakeLicence intakeLicence,
            PackageRepository packageRepository,
            DocumentRepository documentRepository,
            ArtifactRepository artifactRepository,
            StoragePort storagePort,
            ChecksumPort checksumPort,
            DomainEventPublisher eventPublisher,
            AuditService auditService,
            PdfIntakeValidationPort pdfIntakeValidationPort) {
        return new UploadDocumentService(
                tenantContextService, capabilityEnforcementService, intakeLicence,
                packageRepository, documentRepository, artifactRepository,
                storagePort, checksumPort, eventPublisher, auditService, pdfIntakeValidationPort);
    }

    @Bean
    public SubmitPackageUseCase submitPackageUseCase(
            TenantContextService tenantContextService,
            CapabilityEnforcementService capabilityEnforcementService,
            IntakeLicence intakeLicence,
            PackageRepository packageRepository,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new SubmitPackageService(
                tenantContextService, capabilityEnforcementService, intakeLicence,
                packageRepository, eventPublisher, auditService);
    }

    @Bean
    public GetPackageQuery getPackageQuery(PackageRepository packageRepository,
                                           com.securedoc.platform.application.casefields.CaseFieldService caseFields,
                                           com.securedoc.platform.api.security.ProductAuthorizationPolicy authorization) {
        return new GetPackageQueryService(packageRepository, caseFields).withSensitiveFor(principal -> authorization.has(
                principal, com.securedoc.platform.api.security.UserCapability.CASE_FIELD_SENSITIVE_VIEW));
    }

    @Bean
    public ListPackagesQuery listPackagesQuery(PackageRepository packageRepository, com.securedoc.platform.application.casefields.CaseFieldService caseFields,
                                               ProcessingIncidentRepository processingIncidentRepository,
            com.securedoc.platform.application.port.out.PackageRejectionRepository packageRejectionRepository) {
        return new ListPackagesQueryService(packageRepository, caseFields, processingIncidentRepository)
                .withRejections(packageRejectionRepository);
    }

    @Bean
    public OperationsQueryService operationsQueryService(OperationsReadPort operationsReadPort) {
        return new OperationsQueryService(operationsReadPort);
    }

    /** Task 0081: Reports › Daily intake and delivery. */
    @Bean
    public com.securedoc.platform.application.operations.daily.DailyReportService dailyReportService(
            com.securedoc.platform.application.port.out.DailyReportPort dailyReportPort,
            com.securedoc.platform.application.port.out.EntityTimeZonePort entityTimeZones,
            com.securedoc.platform.application.intake.BulkSourceService bulkSources,
            com.securedoc.platform.infrastructure.intake.WatchedFolderProperties folder,
            com.securedoc.platform.api.platform.PlatformAdminProperties platformAdmin) {
        return new com.securedoc.platform.application.operations.daily.DailyReportService(dailyReportPort, entityTimeZones,
                bulkSources::mayTakeIn, java.time.Clock.systemUTC()).withWatchedFolder(tenant -> folder.isEnabled()
                && platformAdmin.isWatchedFolderSchedulingEnabled()
                && tenant.value().toString().equalsIgnoreCase(String.valueOf(platformAdmin.getWatchedFolderTenantId()).trim()));
    }

    /** Task 0017: the compliance report PDF. */
    @Bean
    public com.securedoc.platform.application.compliance.ComplianceReportService complianceReportService(
            com.securedoc.platform.application.port.out.ComplianceReportReadPort complianceReportReadPort,
            com.securedoc.platform.application.port.out.ComplianceReportPdfPort complianceReportPdfPort,
            OperationsQueryService operationsQueryService,
            TenantRepository tenantRepository,
            TenantRedactionPolicyRepository tenantRedactionPolicyRepository,
            TenantLifecyclePolicyRepository tenantLifecyclePolicyRepository,
            AuditService auditService,
            RequestActorDisplayName requestActorDisplayName,
            IntakeLicence intakeLicence,
            com.securedoc.platform.application.port.out.EntityTimeZonePort entityTimeZones,
            // Task 0060: checked by platform-core (30 to 90, else the app does not start).
            @org.springframework.beans.factory.annotation.Value("${platform.erase-log.backup-retention-days:35}") int backupRetentionDays) {
        return new com.securedoc.platform.application.compliance.ComplianceReportService(complianceReportReadPort,
                complianceReportPdfPort, operationsQueryService, tenantRepository, tenantRedactionPolicyRepository,
                tenantLifecyclePolicyRepository, auditService, requestActorDisplayName,
                tenant -> intakeLicence.customerName(), java.time.Clock.systemUTC()).withEntityTimeZones(entityTimeZones)
                .withBackupRetention(backupRetentionDays);
    }

    /** Task 0019: the policy estimate (counts only, read-only). */
    @Bean
    public com.securedoc.platform.application.business.admin.PolicyEstimateService policyEstimateService(
            TenantRedactionPolicyRepository tenantRedactionPolicyRepository,
            com.securedoc.platform.application.port.out.PolicyEstimateReadPort policyEstimateReadPort) {
        return new com.securedoc.platform.application.business.admin.PolicyEstimateService(
                tenantRedactionPolicyRepository, policyEstimateReadPort, java.time.Clock.systemUTC());
    }

    @Bean
    public DashboardColumnConfigurationService dashboardColumnConfigurationService(
            com.securedoc.platform.application.casefields.CaseFieldSettings caseFieldSettings,
            DashboardColumnSettingsRepository dashboardColumnSettingsRepository,
            AuditService auditService) {
        return new DashboardColumnConfigurationService(
                caseFieldSettings, dashboardColumnSettingsRepository, auditService);
    }


    // --- Processing services ---

    @Bean
    public StartPackageProcessingUseCase startPackageProcessingUseCase(
            PackageRepository packageRepository,
            DocumentRepository documentRepository,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new PackageProcessingService(
                packageRepository, documentRepository, eventPublisher, auditService);
    }

    @Bean
    public DocumentProcessingService documentProcessingService(
            DocumentRepository documentRepository,
            PackageRepository packageRepository,
            ProcessingLockPort processingLockPort,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new DocumentProcessingService(
                documentRepository, packageRepository, processingLockPort, eventPublisher, auditService);
    }

    @Bean
    public StartDocumentProcessingUseCase startDocumentProcessingUseCase(
            DocumentProcessingService documentProcessingService) {
        return documentProcessingService;
    }

    @Bean
    public FailDocumentProcessingUseCase failDocumentProcessingUseCase(
            DocumentProcessingService documentProcessingService) {
        return documentProcessingService;
    }

    // --- Text extraction service ---

    @Bean
    public ExtractDocumentTextUseCase extractDocumentTextUseCase(
            DocumentRepository documentRepository,
            PackageRepository packageRepository,
            ArtifactRepository artifactRepository,
            StoragePort storagePort,
            TextExtractionPort textExtractionPort,
            ChecksumPort checksumPort,
            ProcessingLockPort processingLockPort,
            DomainEventPublisher eventPublisher,
            AuditService auditService,
            com.securedoc.platform.application.port.out.PageRouteRepository pageRouteRepository,
            TenantRedactionPolicyRepository tenantRedactionPolicyRepository) {
        // Task 0064: the pages are routed while their text is read.
        return new TextExtractionService(
                documentRepository, packageRepository, artifactRepository,
                storagePort, textExtractionPort, checksumPort,
                processingLockPort, eventPublisher, auditService,
                new TextExtractionService.PageRouting(pageRouteRepository, tenantRedactionPolicyRepository));
    }

    @Bean
    @ConfigurationProperties(prefix = "securedoc.ai")
    public AiRuntimeProperties aiRuntimeProperties() {
        return new AiRuntimeProperties();
    }

    @Bean
    public SelectiveRetryExecutor selectiveRetryExecutor(TechnicalResilienceProperties properties) {
        return new SelectiveRetryExecutor(
                Math.max(1, properties.getMaxAttempts()),
                Duration.ofMillis(Math.max(0L, properties.getInitialBackoffMs())));
    }

    @Bean
    public SensitiveContentAnalysisPort sensitiveContentAnalysisPort(
            AiRuntimeProperties properties,
            SelectiveRetryExecutor selectiveRetryExecutor) {
        SensitiveContentAnalysisPort defaultProfile = buildResilientAnalysisProfile(
                properties.getProvider(), properties.getBaseUrl(), properties.getModel(), properties.getApiKey(),
                properties.getTimeoutSeconds(), properties.getParallelism(), properties.getPageMaxAttempts(),
                properties.getPageRetryBackoffMs(),
                properties.getOllamaKeepAlive(), properties.isOllamaStructuredOutput(), properties.isOllamaThinkingEnabled(),
                properties.getOllamaContextWindowTokens(), properties.getOllamaMaxOutputTokens(),
                properties.getOllamaPageBatchSize(), properties.getOllamaPageBatchMaxCharacters(),
                properties.isPoolEnabled(), properties.getPoolNodeUrls(), properties.getPoolMaxConcurrentPerNode(),
                properties.getPoolMaxNodeAttempts(), properties.getPoolHealthProbeTimeoutMs(),
                properties.getPoolHealthCheckIntervalMs(), properties.getPoolCircuitOpenMs(), properties.getPoolQueueWaitMs(),
                properties.isSecondaryEnabled(), properties.getSecondaryProvider(),
                properties.getSecondaryBaseUrl(), properties.getSecondaryModel(), properties.getSecondaryApiKey(),
                properties.getSecondaryTimeoutSeconds(),
                selectiveRetryExecutor, true);

        Map<TenantId, SensitiveContentAnalysisPort> tenantProfiles = new LinkedHashMap<>();
        for (Map.Entry<String, AiRuntimeProperties.TenantProfile> entry : properties.getTenantProfiles().entrySet()) {
            final TenantId tenantId;
            try {
                tenantId = TenantId.fromString(entry.getKey());
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Invalid tenant UUID in securedoc.ai.tenant-profiles: " + entry.getKey(), e);
            }
            AiRuntimeProperties.TenantProfile profile = entry.getValue();
            if (profile == null) {
                throw new IllegalArgumentException("AI tenant profile must not be null for tenant " + tenantId);
            }

            int timeoutSeconds = positiveOrDefault(profile.getTimeoutSeconds(), properties.getTimeoutSeconds());
            int parallelism = positiveOrDefault(profile.getParallelism(), properties.getParallelism());
            int pageMaxAttempts = positiveOrDefault(profile.getPageMaxAttempts(), properties.getPageMaxAttempts());
            long pageBackoffMs = profile.getPageRetryBackoffMs() >= 0
                    ? profile.getPageRetryBackoffMs()
                    : properties.getPageRetryBackoffMs();
            int secondaryTimeout = positiveOrDefault(profile.getSecondaryTimeoutSeconds(), timeoutSeconds);
            String keepAlive = stringOrDefault(profile.getOllamaKeepAlive(), properties.getOllamaKeepAlive());
            boolean structuredOutput = booleanOrDefault(profile.getOllamaStructuredOutput(), properties.isOllamaStructuredOutput());
            boolean thinkingEnabled = booleanOrDefault(profile.getOllamaThinkingEnabled(), properties.isOllamaThinkingEnabled());
            int contextWindowTokens = nonNegativeOrDefault(profile.getOllamaContextWindowTokens(), properties.getOllamaContextWindowTokens());
            int maxOutputTokens = nonNegativeOrDefault(profile.getOllamaMaxOutputTokens(), properties.getOllamaMaxOutputTokens());
            int pageBatchSize = positiveOrDefault(profile.getOllamaPageBatchSize(), properties.getOllamaPageBatchSize());
            int pageBatchMaxCharacters = positiveOrDefault(profile.getOllamaPageBatchMaxCharacters(), properties.getOllamaPageBatchMaxCharacters());
            boolean poolEnabled = booleanOrDefault(profile.getPoolEnabled(), properties.isPoolEnabled());
            String poolNodeUrls = stringOrDefault(profile.getPoolNodeUrls(), properties.getPoolNodeUrls());
            int poolMaxConcurrentPerNode = positiveOrDefault(profile.getPoolMaxConcurrentPerNode(), properties.getPoolMaxConcurrentPerNode());
            int poolMaxNodeAttempts = positiveOrDefault(profile.getPoolMaxNodeAttempts(), properties.getPoolMaxNodeAttempts());
            long poolHealthProbeTimeoutMs = nonNegativeOrDefault(profile.getPoolHealthProbeTimeoutMs(), properties.getPoolHealthProbeTimeoutMs());
            long poolHealthCheckIntervalMs = nonNegativeOrDefault(profile.getPoolHealthCheckIntervalMs(), properties.getPoolHealthCheckIntervalMs());
            long poolCircuitOpenMs = nonNegativeOrDefault(profile.getPoolCircuitOpenMs(), properties.getPoolCircuitOpenMs());
            long poolQueueWaitMs = nonNegativeOrDefault(profile.getPoolQueueWaitMs(), properties.getPoolQueueWaitMs());

            SensitiveContentAnalysisPort tenantProfile = buildResilientAnalysisProfile(
                    profile.getProvider(), profile.getBaseUrl(), profile.getModel(), profile.getApiKey(),
                    timeoutSeconds, parallelism, pageMaxAttempts, pageBackoffMs,
                    keepAlive, structuredOutput, thinkingEnabled, contextWindowTokens, maxOutputTokens,
                    pageBatchSize, pageBatchMaxCharacters,
                    poolEnabled, poolNodeUrls, poolMaxConcurrentPerNode, poolMaxNodeAttempts,
                    poolHealthProbeTimeoutMs, poolHealthCheckIntervalMs, poolCircuitOpenMs, poolQueueWaitMs,
                    profile.isSecondaryEnabled(), profile.getSecondaryProvider(), profile.getSecondaryBaseUrl(),
                    profile.getSecondaryModel(), profile.getSecondaryApiKey(), secondaryTimeout,
                    selectiveRetryExecutor, false);
            tenantProfiles.put(tenantId, tenantProfile);
        }

        return new TenantRoutingSensitiveContentAnalysisPort(
                defaultProfile, tenantProfiles, properties.isRequireTenantProfile());
    }

    private SensitiveContentAnalysisPort buildResilientAnalysisProfile(
            String provider,
            String baseUrl,
            String model,
            String apiKey,
            int timeoutSeconds,
            int parallelism,
            int pageMaxAttempts,
            long pageRetryBackoffMs,
            String ollamaKeepAlive,
            boolean ollamaStructuredOutput,
            boolean ollamaThinkingEnabled,
            int ollamaContextWindowTokens,
            int ollamaMaxOutputTokens,
            int ollamaPageBatchSize,
            int ollamaPageBatchMaxCharacters,
            boolean poolEnabled,
            String poolNodeUrls,
            int poolMaxConcurrentPerNode,
            int poolMaxNodeAttempts,
            long poolHealthProbeTimeoutMs,
            long poolHealthCheckIntervalMs,
            long poolCircuitOpenMs,
            long poolQueueWaitMs,
            boolean secondaryEnabled,
            String secondaryProvider,
            String secondaryBaseUrl,
            String secondaryModel,
            String secondaryApiKey,
            int secondaryTimeoutSeconds,
            SelectiveRetryExecutor selectiveRetryExecutor,
            boolean allowHeuristic) {
        SensitiveContentAnalysisPort primary = buildPrimaryAnalysisProvider(
                provider, baseUrl, model, apiKey, timeoutSeconds, parallelism,
                pageMaxAttempts, pageRetryBackoffMs, ollamaKeepAlive, ollamaStructuredOutput,
                ollamaThinkingEnabled, ollamaContextWindowTokens, ollamaMaxOutputTokens,
                ollamaPageBatchSize, ollamaPageBatchMaxCharacters,
                poolEnabled, poolNodeUrls, poolMaxConcurrentPerNode, poolMaxNodeAttempts,
                poolHealthProbeTimeoutMs, poolHealthCheckIntervalMs, poolCircuitOpenMs, poolQueueWaitMs,
                allowHeuristic);
        SensitiveContentAnalysisPort secondary = null;
        if (secondaryEnabled) {
            secondary = buildAnalysisProvider(
                    secondaryProvider, secondaryBaseUrl, secondaryModel, secondaryApiKey,
                    secondaryTimeoutSeconds, parallelism, pageMaxAttempts, pageRetryBackoffMs,
                    ollamaKeepAlive, ollamaStructuredOutput, ollamaThinkingEnabled,
                    ollamaContextWindowTokens, ollamaMaxOutputTokens, ollamaPageBatchSize,
                    ollamaPageBatchMaxCharacters, false);
        }
        return new ResilientSensitiveContentAnalysisPort(primary, secondary, selectiveRetryExecutor);
    }

    private SensitiveContentAnalysisPort buildPrimaryAnalysisProvider(
            String providerValue,
            String baseUrl,
            String model,
            String apiKey,
            int timeoutSeconds,
            int parallelism,
            int pageMaxAttempts,
            long pageRetryBackoffMs,
            String ollamaKeepAlive,
            boolean ollamaStructuredOutput,
            boolean ollamaThinkingEnabled,
            int ollamaContextWindowTokens,
            int ollamaMaxOutputTokens,
            int ollamaPageBatchSize,
            int ollamaPageBatchMaxCharacters,
            boolean poolEnabled,
            String poolNodeUrls,
            int poolMaxConcurrentPerNode,
            int poolMaxNodeAttempts,
            long poolHealthProbeTimeoutMs,
            long poolHealthCheckIntervalMs,
            long poolCircuitOpenMs,
            long poolQueueWaitMs,
            boolean allowHeuristic) {
        String provider = providerValue == null ? "" : providerValue.trim().toLowerCase();
        if (!poolEnabled) {
            return buildAnalysisProvider(providerValue, baseUrl, model, apiKey, timeoutSeconds, parallelism,
                    pageMaxAttempts, pageRetryBackoffMs, ollamaKeepAlive, ollamaStructuredOutput,
                    ollamaThinkingEnabled, ollamaContextWindowTokens, ollamaMaxOutputTokens,
                    ollamaPageBatchSize, ollamaPageBatchMaxCharacters, allowHeuristic);
        }
        if (!"ollama".equals(provider)) {
            throw new IllegalArgumentException("securedoc.ai.pool-enabled is currently supported only for provider=ollama");
        }

        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        int boundedParallelism = Math.max(1, Math.min(16, parallelism));
        int boundedPageAttempts = Math.max(1, Math.min(5, pageMaxAttempts));
        Duration pageBackoff = Duration.ofMillis(Math.max(0L, pageRetryBackoffMs));
        int boundedMaxConcurrent = Math.max(1, Math.min(64, poolMaxConcurrentPerNode));
        List<OllamaInferencePoolSensitiveContentAnalysisAdapter.NodeDefinition> nodes =
                OllamaInferencePoolConfiguration.parse(poolNodeUrls).stream()
                        .map(node -> new OllamaInferencePoolSensitiveContentAnalysisAdapter.NodeDefinition(
                                node.id(), node.baseUrl(), boundedMaxConcurrent))
                        .toList();

        return new OllamaInferencePoolSensitiveContentAnalysisAdapter(
                nodes, model, timeout, boundedParallelism, boundedPageAttempts, pageBackoff,
                ollamaKeepAlive, ollamaStructuredOutput, ollamaThinkingEnabled,
                Math.max(0, ollamaContextWindowTokens), Math.max(0, ollamaMaxOutputTokens),
                Math.max(1, Math.min(8, ollamaPageBatchSize)),
                Math.max(1_000, Math.min(200_000, ollamaPageBatchMaxCharacters)),
                Duration.ofMillis(Math.max(100L, poolHealthProbeTimeoutMs)),
                Duration.ofMillis(Math.max(0L, poolHealthCheckIntervalMs)),
                Duration.ofMillis(Math.max(0L, poolCircuitOpenMs)),
                Duration.ofMillis(Math.max(0L, poolQueueWaitMs)),
                Math.max(1, Math.min(nodes.size(), poolMaxNodeAttempts)));
    }

    private SensitiveContentAnalysisPort buildAnalysisProvider(String providerValue,
                                                               String baseUrl,
                                                               String model,
                                                               String apiKey,
                                                               int timeoutSeconds,
                                                               int parallelism,
                                                               int pageMaxAttempts,
                                                               long pageRetryBackoffMs,
                                                               String ollamaKeepAlive,
                                                               boolean ollamaStructuredOutput,
                                                               boolean ollamaThinkingEnabled,
                                                               int ollamaContextWindowTokens,
                                                               int ollamaMaxOutputTokens,
                                                               int ollamaPageBatchSize,
                                                               int ollamaPageBatchMaxCharacters,
                                                               boolean allowHeuristic) {
        String provider = providerValue == null ? "" : providerValue.trim().toLowerCase();
        Duration timeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        int boundedParallelism = Math.max(1, Math.min(16, parallelism));
        int boundedPageAttempts = Math.max(1, Math.min(5, pageMaxAttempts));
        Duration pageBackoff = Duration.ofMillis(Math.max(0L, pageRetryBackoffMs));

        return switch (provider) {
            case "ollama" -> new OllamaSensitiveContentAnalysisAdapter(
                    baseUrl, model, timeout, boundedParallelism, boundedPageAttempts, pageBackoff,
                    ollamaKeepAlive, ollamaStructuredOutput, ollamaThinkingEnabled,
                    Math.max(0, ollamaContextWindowTokens), Math.max(0, ollamaMaxOutputTokens),
                    Math.max(1, Math.min(8, ollamaPageBatchSize)),
                    Math.max(1_000, Math.min(200_000, ollamaPageBatchMaxCharacters)));
            case "openai-compatible", "openai_compatible", "vllm" ->
                    new OpenAiCompatibleSensitiveContentAnalysisAdapter(
                            baseUrl, model, apiKey, timeout, boundedParallelism, boundedPageAttempts, pageBackoff);
            case "heuristic" -> {
                if (!allowHeuristic) {
                    throw new IllegalArgumentException("Heuristic adapter cannot be configured for a tenant or AI failover");
                }
                yield new LocalHeuristicSensitiveContentAnalysisAdapter();
            }
            default -> throw new IllegalArgumentException("Unsupported securedoc.ai provider: " + providerValue);
        };
    }

    private int positiveOrDefault(int value, int fallback) {
        return value > 0 ? value : fallback;
    }

    private int nonNegativeOrDefault(int value, int fallback) {
        return value >= 0 ? value : fallback;
    }

    private long nonNegativeOrDefault(long value, long fallback) {
        return value >= 0L ? value : fallback;
    }

    private String stringOrDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private boolean booleanOrDefault(Boolean value, boolean fallback) {
        return value == null ? fallback : value;
    }

    // --- Detection service ---

    @Bean
    public DetectSensitiveDataUseCase detectSensitiveDataUseCase(
            DocumentRepository documentRepository,
            PackageRepository packageRepository,
            ArtifactRepository artifactRepository,
            StoragePort storagePort,
            SensitiveContentAnalysisPort semanticAnalysisPort,
            SensitiveDataDetectionPort detectionPort,
            SensitiveDataValidationPort validationPort,
            TenantRedactionPolicyRepository tenantRedactionPolicyRepository,
            TextExtractionPort textExtractionPort,
            FindingRepository findingRepository,
            DomainEventPublisher eventPublisher,
            AuditService auditService,
            ChecksumPort checksumPort,
            com.securedoc.platform.application.port.out.PageRouteRepository pageRouteRepository,
            com.securedoc.platform.application.port.out.PageImagePort pageImagePort,
            com.securedoc.platform.application.port.out.VisionModelPort visionModelPort) {
        // Task 0049: the checksum port is the page batch results'. Task 0064: routed pages are read by vision.
        return new SensitiveDataDetectionService(
                documentRepository, packageRepository, artifactRepository,
                storagePort, semanticAnalysisPort, detectionPort, validationPort, tenantRedactionPolicyRepository, textExtractionPort, findingRepository,
                eventPublisher, auditService, checksumPort,
                new SensitiveDataDetectionService.Vision(pageRouteRepository,
                        new com.securedoc.platform.application.vision.VisionPageReader(artifactRepository, storagePort,
                                pageImagePort, visionModelPort)));
    }

    /**
     * Task 0064: the local vision model, pinned by digest. A model name without a 64-character digest is refused at
     * start (fail closed); a missing or different model on the server sends the routed pages to a person.
     */
    @Bean
    public com.securedoc.platform.application.port.out.VisionModelPort visionModelPort(
            @org.springframework.beans.factory.annotation.Value("${securedoc.ai.vision.base-url}") String baseUrl,
            @org.springframework.beans.factory.annotation.Value("${securedoc.ai.vision.model}") String model,
            @org.springframework.beans.factory.annotation.Value("${securedoc.ai.vision.model-digest}") String digest,
            @org.springframework.beans.factory.annotation.Value("${securedoc.ai.vision.timeout-seconds}") int timeoutSeconds,
            AiRuntimeProperties ai) {
        return new com.securedoc.platform.ai.analysis.OllamaVisionModelAdapter(baseUrl, model, digest,
                Duration.ofSeconds(Math.max(1, timeoutSeconds)), ai.getOllamaKeepAlive());
    }

    // --- Redaction service ---

    @Bean
    public RedactDocumentUseCase redactDocumentUseCase(
            DocumentRepository documentRepository,
            PackageRepository packageRepository,
            ArtifactRepository artifactRepository,
            FindingRepository findingRepository,
            StoragePort storagePort,
            PdfRedactionPort pdfRedactionPort,
            RedactionVerificationPort redactionVerificationPort,
            ChecksumPort checksumPort,
            DomainEventPublisher eventPublisher,
            AuditService auditService,
            TenantRedactionPolicyRepository tenantRedactionPolicyRepository,
            TextExtractionPort textExtractionPort,
            com.securedoc.platform.application.licensing.UsageMeter usageMeter,
            com.securedoc.platform.api.licensing.PlatformLicence licence,
            SensitiveContentAnalysisPort semanticAnalysisPort,
            com.securedoc.platform.application.port.out.PageImagePort pageImagePort,
            @org.springframework.beans.factory.annotation.Value("${securedoc.ai.vision.base-url}") String visionUrl,
            @org.springframework.beans.factory.annotation.Value("${securedoc.ai.vision.model}") String visionModel,
            @org.springframework.beans.factory.annotation.Value("${securedoc.ai.vision.model-digest}") String visionDigest,
            @org.springframework.beans.factory.annotation.Value("${securedoc.ai.vision.timeout-seconds}") int visionTimeoutSeconds,
            AiRuntimeProperties ai) {
        // Task 0044: the entity policy (searchable redacted pages) and the same text extraction as the detection.
        return new DocumentRedactionService(
                documentRepository, packageRepository, artifactRepository,
                findingRepository, storagePort, pdfRedactionPort, redactionVerificationPort, checksumPort,
                eventPublisher, auditService, tenantRedactionPolicyRepository, textExtractionPort)
                .withUsageMeter(usageMeter).withNonProductionMark(licence::testEnvironment)
                // Task 0083: the same models, each with the second check's own prompt. Not a bean: Find must never get it.
                .withSecondCheck(new com.securedoc.platform.application.secondcheck.SecondCheck(textExtractionPort,
                        semanticAnalysisPort, pageImagePort,
                        new com.securedoc.platform.ai.analysis.OllamaVisionModelAdapter(visionUrl, visionModel,
                                visionDigest, Duration.ofSeconds(Math.max(1, visionTimeoutSeconds)), ai.getOllamaKeepAlive(),
                                com.securedoc.platform.ai.analysis.OllamaVisionModelAdapter.SECOND_CHECK_VERSION)));
    }

    @Bean
    public HidePackageFromDashboardUseCase hidePackageFromDashboardUseCase(
            PackageRepository packageRepository,
            PackageDashboardVisibilityPort packageDashboardVisibilityPort,
            AuditService auditService) {
        return new PackageDashboardVisibilityService(packageRepository, packageDashboardVisibilityPort, auditService);
    }

    // --- Review services ---

    @Bean
    public ReviewWorkflowService reviewWorkflowService(
            ReviewTaskRepository reviewTaskRepository,
            PackageRepository packageRepository,
            DocumentRepository documentRepository,
            FindingRepository findingRepository,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new ReviewWorkflowService(
                reviewTaskRepository, packageRepository, documentRepository,
                findingRepository, eventPublisher, auditService);
    }

    @Bean
    public FindingReviewService findingReviewService(
            FindingRepository findingRepository,
            ReviewTaskRepository reviewTaskRepository,
            DocumentRepository documentRepository,
            AuditService auditService) {
        return new FindingReviewService(findingRepository, reviewTaskRepository,
                documentRepository, auditService);
    }

    @Bean
    public FocusedReviewService focusedReviewService(
            PackageRepository packageRepository,
            DocumentRepository documentRepository,
            FindingRepository findingRepository,
            TenantRedactionPolicyRepository tenantRedactionPolicyRepository,
            PackageProcessingDispatcher packageProcessingDispatcher,
            AuditService auditService,
            ArtifactRepository artifactRepository,
            StoragePort storagePort,
            com.securedoc.platform.application.intake.CasePriorityService casePriorityService) {
        return new FocusedReviewService(packageRepository, documentRepository, findingRepository,
                tenantRedactionPolicyRepository, packageProcessingDispatcher, auditService,
                new ReviewFoundTextReader(artifactRepository, storagePort)).withPriorities(casePriorityService);
    }

    @Bean
    public GetReviewPagePreviewQuery reviewPagePreviewQuery(
            FindingRepository findingRepository,
            DocumentRepository documentRepository,
            PackageRepository packageRepository,
            ArtifactRepository artifactRepository,
            StoragePort storagePort,
            ReviewPageRenderPort reviewPageRenderPort,
            AuditService auditService) {
        return new ReviewPagePreviewService(
                findingRepository, documentRepository, packageRepository, artifactRepository,
                storagePort, reviewPageRenderPort, auditService);
    }

    @Bean
    public ManualFallbackService manualFallbackService(
            PackageRepository packageRepository,
            DocumentRepository documentRepository,
            FindingRepository findingRepository,
            ArtifactRepository artifactRepository,
            TenantRedactionPolicyRepository tenantRedactionPolicyRepository,
            AuditQueryPort auditQueryPort,
            GetTechnicalExceptionQuery technicalExceptionQuery,
            PackageProcessingDispatcher packageProcessingDispatcher,
            AuditService auditService,
            ProcessingIncidentService processingIncidentService) {
        return new ManualFallbackService(packageRepository, documentRepository, findingRepository,
                artifactRepository, tenantRedactionPolicyRepository, auditQueryPort, technicalExceptionQuery,
                packageProcessingDispatcher, auditService, processingIncidentService);
    }

    @Bean
    public GetManualFallbackPagePreviewQuery manualFallbackPagePreviewQuery(
            PackageRepository packageRepository,
            DocumentRepository documentRepository,
            FindingRepository findingRepository,
            ArtifactRepository artifactRepository,
            AuditQueryPort auditQueryPort,
            StoragePort storagePort,
            ReviewPageRenderPort reviewPageRenderPort,
            AuditService auditService) {
        return new ManualFallbackPagePreviewService(packageRepository, documentRepository, findingRepository,
                artifactRepository, auditQueryPort, storagePort, reviewPageRenderPort, auditService);
    }


    // --- Vault and access services ---

    @Bean
    public VaultService vaultService(
            VaultEntryRepository vaultEntryRepository,
            ArtifactRepository artifactRepository,
            DocumentRepository documentRepository,
            PackageRepository packageRepository,
            AccessRequestRepository accessRequestRepository,
            StoragePort storagePort,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new VaultService(vaultEntryRepository, artifactRepository,
                documentRepository, packageRepository, accessRequestRepository,
                storagePort, eventPublisher, auditService);
    }

    @Bean
    public ArtifactAccessService artifactAccessService(
            AccessRequestRepository accessRequestRepository,
            VaultEntryRepository vaultEntryRepository,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new ArtifactAccessService(accessRequestRepository, vaultEntryRepository,
                eventPublisher, auditService);
    }

    // --- Delivery lifecycle / retention ---

    @Bean
    public PackageLifecycleService packageLifecycleService(
            TenantLifecyclePolicyRepository tenantLifecyclePolicyRepository,
            PackageLifecycleRepository packageLifecycleRepository,
            PackageExportRecordRepository packageExportRecordRepository,
            PackageRepository packageRepository,
            PackageDashboardVisibilityPort packageDashboardVisibilityPort,
            DocumentRepository documentRepository,
            ArtifactRepository artifactRepository,
            com.securedoc.platform.application.port.out.ErasurePort erasure,
            AuditService auditService,
            ProcessingIncidentService processingIncidentService,
            ProductTelemetryService productTelemetryService,
            com.securedoc.platform.application.lifecycle.service.ExportRetentionService exportRetentionService) {
        return new PackageLifecycleService(tenantLifecyclePolicyRepository, packageLifecycleRepository,
                packageExportRecordRepository, packageRepository, packageDashboardVisibilityPort,
                documentRepository, artifactRepository, erasure, auditService, processingIncidentService,
                productTelemetryService, exportRetentionService);
    }

    /** Task 0028: retention per export destination (master and each split destination), set by the Business Admin. */
    @Bean
    public com.securedoc.platform.application.lifecycle.service.ExportRetentionService exportRetentionService(
            com.securedoc.platform.application.port.out.ExportRetentionRepository exportRetentionRepository,
            com.securedoc.platform.application.port.out.PipelineJobRepository pipelineJobRepository,
            com.securedoc.platform.application.port.out.DerivedDeliveryRepository derivedDeliveryRepository,
            ArtifactRepository artifactRepository,
            com.securedoc.platform.application.port.out.SplitCopyRetentionPort splitCopyRetentionPort,
            IntegrationConnectionRepository integrationConnectionRepository,
            TenantLifecyclePolicyRepository tenantLifecyclePolicyRepository,
            AuditService auditService) {
        return new com.securedoc.platform.application.lifecycle.service.ExportRetentionService(
                exportRetentionRepository, pipelineJobRepository, derivedDeliveryRepository, artifactRepository,
                splitCopyRetentionPort, integrationConnectionRepository, tenantLifecyclePolicyRepository,
                auditService);
    }

    /**
     * Task 0034: how long finished jobs and cleared reachability alerts are kept. A value outside its range stops
     * start-up (LV-SYS-005 by {@link StartupFailureLogger}).
     */
    @Bean
    public com.securedoc.platform.application.pipeline.FinishedJobCleanupSettings finishedJobCleanupSettings(
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-jobs.keep-finished-days:90}") int keepFinishedDays,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-jobs.cleanup-batch-size:1000}") int cleanupBatchSize) {
        return new com.securedoc.platform.application.pipeline.FinishedJobCleanupSettings(keepFinishedDays,
                cleanupBatchSize);
    }

    /** Task 0049: the sweep of stored files without a record, run by the retention purge scheduler. */
    @Bean
    public com.securedoc.platform.application.lifecycle.service.UnrecordedFileSweepService unrecordedFileSweepService(
            com.securedoc.platform.application.port.out.UnrecordedFileSweepPort unrecordedFileSweepPort,
            AuditService auditService,
            @org.springframework.beans.factory.annotation.Value("${securedoc.lifecycle.unrecorded-files-keep-hours:24}") int keepHours) {
        return new com.securedoc.platform.application.lifecycle.service.UnrecordedFileSweepService(
                unrecordedFileSweepPort, auditService, keepHours);
    }

    /** Task 0034: the clean-up of finished jobs, run by the retention purge scheduler. */
    @Bean
    public com.securedoc.platform.application.pipeline.FinishedJobCleanupService finishedJobCleanupService(
            com.securedoc.platform.application.port.out.FinishedJobCleanupPort finishedJobCleanupPort,
            AuditService auditService,
            com.securedoc.platform.application.pipeline.FinishedJobCleanupSettings finishedJobCleanupSettings) {
        return new com.securedoc.platform.application.pipeline.FinishedJobCleanupService(finishedJobCleanupPort,
                auditService, finishedJobCleanupSettings);
    }

    // --- Package finalization ---

    @Bean
    public PackageFinalizationService packageFinalizationService(
            PackageRepository packageRepository,
            DocumentRepository documentRepository,
            FindingRepository findingRepository,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new PackageFinalizationService(packageRepository, documentRepository,
                findingRepository, eventPublisher, auditService);
    }

    @Bean
    public PackageProcessingRecoveryService packageProcessingRecoveryService(
            PackageRepository packageRepository,
            PackageLifecycleService packageLifecycleService,
            ProcessingIncidentService processingIncidentService,
            AuditService auditService,
            ProductTelemetryService productTelemetryService) {
        return new PackageProcessingRecoveryService(packageRepository, packageLifecycleService,
                processingIncidentService, auditService, productTelemetryService);
    }

    @Bean
    public TriggerPackageProcessingService triggerPackageProcessingService(
            PackageRepository packageRepository,
            DocumentRepository documentRepository,
            FindingRepository findingRepository,
            ArtifactRepository artifactRepository,
            ProcessingLockPort processingLockPort,
            StartPackageProcessingUseCase startPackageProcessingUseCase,
            StartDocumentProcessingUseCase startDocumentProcessingUseCase,
            ExtractDocumentTextUseCase extractDocumentTextUseCase,
            DetectSensitiveDataUseCase detectSensitiveDataUseCase,
            RedactDocumentUseCase redactDocumentUseCase,
            ExportPackageDocumentsUseCase exportPackageDocumentsUseCase,
            ProcessDerivedRoutingUseCase processDerivedRoutingUseCase,
            SelectiveRetryExecutor selectiveRetryExecutor,
            PackageLifecycleService packageLifecycleService,
            DocumentIntelligenceExecutionService documentIntelligenceExecutionService,
            AuditService auditService,
            ProcessingIncidentService processingIncidentService,
            ProductTelemetryService productTelemetryService,
            com.securedoc.platform.application.pipeline.ExportJobQueue exportJobQueue) {
        return new TriggerPackageProcessingService(packageRepository, documentRepository,
                findingRepository, artifactRepository, processingLockPort,
                startPackageProcessingUseCase, startDocumentProcessingUseCase,
                extractDocumentTextUseCase, detectSensitiveDataUseCase, redactDocumentUseCase,
                exportPackageDocumentsUseCase, processDerivedRoutingUseCase, selectiveRetryExecutor, packageLifecycleService,
                documentIntelligenceExecutionService, auditService, processingIncidentService,
                productTelemetryService, exportJobQueue);
    }

    /**
     * Task 0048: {@code securedoc.processing.pipeline-mode} is inline (default, today's flow) or staged (Text, Find and
     * Redact as per-document jobs). Staged needs export-mode job: a staged case is Ready for export at its last gate and
     * its EXPORT jobs are queued (P1). Anything else is refused at start (no silent fallback).
     */
    @Bean
    public com.securedoc.platform.application.pipeline.StagedProcessingSettings stagedProcessingSettings(
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.pipeline-mode:inline}") String pipelineMode,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-mode:inline}") String exportMode,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.stage-jobs.claim-lease-ms:120000}") long claimLeaseMs,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.stage-jobs.max-attempts:3}") int maxAttempts,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.stage-jobs.poll-interval-ms:1000}") long pollIntervalMs) {
        var mode = com.securedoc.platform.application.pipeline.PipelineMode.parse(pipelineMode);
        if (mode == com.securedoc.platform.application.pipeline.PipelineMode.STAGED
                && com.securedoc.platform.application.pipeline.ExportMode.parse(exportMode)
                != com.securedoc.platform.application.pipeline.ExportMode.JOB) {
            throw new IllegalStateException(
                    "securedoc.processing.pipeline-mode=staged needs securedoc.processing.export-mode=job");
        }
        // Task 0067: the numbers of workers and of AI requests at once are ProcessingCapacity's.
        var defaults = com.securedoc.platform.application.pipeline.StagedProcessingSettings.defaults(mode);
        return new com.securedoc.platform.application.pipeline.StagedProcessingSettings(mode,
                java.time.Duration.ofMillis(claimLeaseMs), maxAttempts, defaults.textWorkers(), defaults.findWorkers(),
                defaults.redactWorkers(), java.time.Duration.ofMillis(pollIntervalMs), defaults.visionWorkers());
    }

    /** Task 0048: each staged step (and each gate) is one transaction of its own. */
    @Bean
    public com.securedoc.platform.application.port.out.TransactionPort transactionPort(
            org.springframework.transaction.PlatformTransactionManager transactionManager) {
        var template = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return new com.securedoc.platform.application.port.out.TransactionPort() {
            @Override
            public <T> T inNewTransaction(java.util.function.Supplier<T> work) {
                return template.execute(status -> work.get());
            }
        };
    }

    @Bean
    public com.securedoc.platform.application.processing.service.StagedProcessingService stagedProcessingService(
            com.securedoc.platform.application.pipeline.StagedProcessingSettings stagedProcessingSettings,
            com.securedoc.platform.application.port.out.PipelineJobRepository pipelineJobRepository,
            PackageRepository packageRepository,
            DocumentRepository documentRepository,
            ProcessingLockPort processingLockPort,
            StartPackageProcessingUseCase startPackageProcessingUseCase,
            StartDocumentProcessingUseCase startDocumentProcessingUseCase,
            ExtractDocumentTextUseCase extractDocumentTextUseCase,
            DetectSensitiveDataUseCase detectSensitiveDataUseCase,
            RedactDocumentUseCase redactDocumentUseCase,
            PackageLifecycleService packageLifecycleService,
            ProcessingIncidentService processingIncidentService,
            PackageProcessingRecoveryService packageProcessingRecoveryService,
            AuditService auditService,
            ProductTelemetryService productTelemetryService,
            TriggerPackageProcessingService triggerPackageProcessingService,
            com.securedoc.platform.application.port.out.TransactionPort transactionPort,
            StoragePort storagePort,
            ArtifactRepository artifactRepository,
            com.securedoc.platform.application.port.out.PdfIntakeValidationPort pdfIntakeValidationPort,
            TenantRedactionPolicyRepository tenantRedactionPolicyRepository,
            SensitiveContentAnalysisPort sensitiveContentAnalysisPort) {
        // Task 0049: page batches. A page count that cannot be told means one job, whose step reports the problem.
        var batching = new com.securedoc.platform.application.processing.service.StagedProcessingService.PageBatching(
                tenant -> tenantRedactionPolicyRepository.findByTenantId(tenant)
                        .map(com.securedoc.platform.domain.business.TenantRedactionPolicy::pageBatches)
                        .orElse(com.securedoc.platform.domain.business.PageBatchSettings.DEFAULTS),
                document -> {
                    if (!"application/pdf".equals(document.mimeType().value())) return java.util.OptionalInt.empty();
                    var original = artifactRepository.findByDocumentIdAndTenantId(document.id(), document.tenantId())
                            .stream().filter(a -> a.isAvailable() && a.artifactType()
                                    == com.securedoc.platform.domain.artifact.ArtifactType.ORIGINAL).findFirst();
                    if (original.isEmpty()) return java.util.OptionalInt.empty();
                    try (var content = storagePort.retrieve(original.get().storageReference())) {
                        return java.util.OptionalInt.of(pdfIntakeValidationPort.inspect(content).pageCount());
                    } catch (java.io.IOException | RuntimeException unreadable) {
                        return java.util.OptionalInt.empty();
                    }
                },
                // Task 0052: shared pages only when one AI request may cover several pages.
                sensitiveContentAnalysisPort::mayCoverSeveralPages);
        return new com.securedoc.platform.application.processing.service.StagedProcessingService(
                stagedProcessingSettings, pipelineJobRepository, packageRepository, documentRepository,
                processingLockPort, startPackageProcessingUseCase, startDocumentProcessingUseCase,
                extractDocumentTextUseCase, detectSensitiveDataUseCase, redactDocumentUseCase, packageLifecycleService,
                processingIncidentService, packageProcessingRecoveryService, auditService,
                productTelemetryService, triggerPackageProcessingService, transactionPort, storagePort, batching);
    }

    /**
     * Task 0048: the trigger the dispatcher runs. A staged case is planned as jobs; every other case runs inline
     * through {@link TriggerPackageProcessingService}, unchanged.
     */
    @Bean
    @org.springframework.context.annotation.Primary
    public TriggerPackageProcessingUseCase pipelineModeRouter(
            TriggerPackageProcessingService triggerPackageProcessingService,
            com.securedoc.platform.application.processing.service.StagedProcessingService stagedProcessingService) {
        return new com.securedoc.platform.application.processing.service.PipelineModeRouter(
                triggerPackageProcessingService, stagedProcessingService);
    }

    /** Task 0027: export job settings. {@code securedoc.processing.export-mode} is inline (default) or job. */
    @Bean
    public com.securedoc.platform.application.pipeline.ExportJobSettings exportJobSettings(
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-mode:inline}") String exportMode,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-jobs.retry-delay-ms:300000}") long retryDelayMs,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-jobs.max-attempts:3}") int maxAttempts,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-jobs.pause-after-failures:3}") int pauseAfterFailures,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-jobs.probe-interval-ms:300000}") long probeIntervalMs,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-jobs.claim-lease-ms:120000}") long claimLeaseMs,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-jobs.workers-per-destination:4}") int workersPerDestination,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-jobs.retry-schedule:5m,15m,1h,4h,12h}") String retrySchedule,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-jobs.rebuild-before-retry:3}") int rebuildBeforeRetry,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-jobs.failing-cases-to-pause:3}") int failingCasesToPause,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-jobs.failing-cases-window-ms:600000}") long failingCasesWindowMs,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-jobs.rate-limit-default-wait-ms:60000}") long rateLimitDefaultWaitMs,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-jobs.rate-limit-recovery-ms:600000}") long rateLimitRecoveryMs,
            @org.springframework.beans.factory.annotation.Value("${securedoc.processing.export-jobs.all-unreachable-window-ms:120000}") long allUnreachableWindowMs) {
        // Task 0028: retry-delay-ms and pause-after-failures are still read (and checked) but no longer used.
        return new com.securedoc.platform.application.pipeline.ExportJobSettings(
                com.securedoc.platform.application.pipeline.ExportMode.parse(exportMode),
                java.time.Duration.ofMillis(retryDelayMs), maxAttempts, pauseAfterFailures,
                java.time.Duration.ofMillis(probeIntervalMs), java.time.Duration.ofMillis(claimLeaseMs),
                // Task 0067: the number of workers is ProcessingCapacity's.
                workersPerDestination, Math.max(8, workersPerDestination),
                com.securedoc.platform.application.pipeline.ExportJobSettings.parseSchedule(retrySchedule),
                rebuildBeforeRetry, failingCasesToPause, java.time.Duration.ofMillis(failingCasesWindowMs),
                java.time.Duration.ofMillis(rateLimitDefaultWaitMs), java.time.Duration.ofMillis(rateLimitRecoveryMs),
                java.time.Duration.ofMillis(allUnreachableWindowMs));
    }

    @Bean
    public com.securedoc.platform.application.pipeline.ExportJobQueue exportJobQueue(
            com.securedoc.platform.application.port.out.PipelineJobRepository pipelineJobRepository,
            DeliveryPort deliveryPort,
            AuditService auditService,
            com.securedoc.platform.application.pipeline.ExportJobSettings exportJobSettings) {
        return new com.securedoc.platform.application.pipeline.ExportJobQueue(pipelineJobRepository, deliveryPort,
                auditService, exportJobSettings, java.time.Clock.systemUTC());
    }

    /**
     * Task 0027: a paused destination is probed with the existing connection test for its kind. Task 0028: the same
     * check runs at once on a connection failure; the local export folder (its derived id) writes and deletes a test
     * file.
     */
    @Bean
    public com.securedoc.platform.application.port.out.ExportDestinationProbePort exportDestinationProbePort(
            IntegrationConnectionRepository integrationConnectionRepository,
            org.springframework.beans.factory.ObjectProvider<DocumentStoreConnectionTestPort> documentStoreConnectionTestPort,
            DerivedDestinationDeliveryPort derivedDestinationDeliveryPort,
            DeliveryPort deliveryPort) {
        return (tenantId, connectionId) -> {
            if (com.securedoc.platform.domain.pipeline.ExportDestinations.isLocalFolder(tenantId, connectionId)) {
                deliveryPort.checkDestination(tenantId);
                return;
            }
            var connection = integrationConnectionRepository.findByTenantIdAndId(tenantId, connectionId)
                    .orElseThrow(() -> new com.securedoc.platform.application.exception.NotFoundException(
                            "IntegrationConnection", connectionId.value().toString()));
            switch (connection.purpose()) {
                case DOCUMENT_STORE_DELIVERY -> {
                    DocumentStoreConnectionTestPort store = documentStoreConnectionTestPort.getIfAvailable();
                    if (store == null) throw new IllegalStateException("Document store delivery is not configured");
                    store.testConnection(connection);
                }
                case DERIVED_DOCUMENT_DELIVERY -> derivedDestinationDeliveryPort.testConnection(connection);
                default -> throw new IllegalStateException("Connection is not an export destination");
            }
        };
    }

    @Bean
    public com.securedoc.platform.application.pipeline.ExportJobService exportJobService(
            com.securedoc.platform.application.port.out.PipelineJobRepository pipelineJobRepository,
            com.securedoc.platform.application.port.out.ExportDestinationStateRepository exportDestinationStateRepository,
            ExportPackageDocumentsUseCase exportPackageDocumentsUseCase,
            DerivedDeliveryService derivedDeliveryService,
            PackageRepository packageRepository,
            PackageLifecycleService packageLifecycleService,
            ProcessDerivedRoutingUseCase processDerivedRoutingUseCase,
            ProcessingIncidentService processingIncidentService,
            AuditService auditService,
            com.securedoc.platform.application.port.out.ExportDestinationProbePort exportDestinationProbePort,
            IntegrationConnectionRepository integrationConnectionRepository,
            com.securedoc.platform.application.pipeline.ExportJobSettings exportJobSettings,
            com.securedoc.platform.application.port.out.ExportReachabilityAlertRepository exportReachabilityAlertRepository,
            com.securedoc.platform.application.port.in.RebuildPackageExportUseCase rebuildPackageExportUseCase,
            com.securedoc.platform.application.pipeline.ExportJobQueue exportJobQueue,
            CasePrimaryIdentifierLookup casePrimaryIdentifierLookup) {
        return new com.securedoc.platform.application.pipeline.ExportJobService(pipelineJobRepository,
                exportDestinationStateRepository, exportReachabilityAlertRepository, exportPackageDocumentsUseCase,
                rebuildPackageExportUseCase, derivedDeliveryService, packageRepository, packageLifecycleService,
                new com.securedoc.platform.application.pipeline.MasterExportCompletion(packageRepository,
                        processDerivedRoutingUseCase, processingIncidentService, auditService),
                processingIncidentService, auditService, exportDestinationProbePort, integrationConnectionRepository,
                exportJobQueue, exportJobSettings, java.time.Clock.systemUTC(), casePrimaryIdentifierLookup);
    }


    /**
     * Bounded V1 executor for post-review continuation. Keeping this separate from request threads
     * prevents the final reviewer action from waiting on redaction, verification and export.
     */
    @Bean(name = "pollingTaskExecutor")
    public ThreadPoolTaskExecutor pollingTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("polling-intake-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.initialize();
        return executor;
    }

    @Bean(name = "reviewResumeTaskExecutor")
    public ThreadPoolTaskExecutor reviewResumeTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setThreadNamePrefix("review-resume-");
        executor.setTaskDecorator(new MdcTaskDecorator());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        return executor;
    }

    @Bean
    public PackageProcessingDispatcher packageProcessingDispatcher(
            @Qualifier("reviewResumeTaskExecutor") TaskExecutor reviewResumeTaskExecutor,
            TriggerPackageProcessingUseCase triggerPackageProcessingUseCase,
            PackageProcessingDispatchLease packageProcessingDispatchLease,
            ProcessingStorageNodeReadiness processingStorageNodeReadiness,
            com.securedoc.platform.application.port.out.WorkGate workGate,
            PackageProcessingRecoveryService packageProcessingRecoveryService) {
        // Task 0023 C1: after 3 failed dispatches of the same case (at once for a licence denial),
        // the case is stopped as FAILED with a code and a Support incident instead of looping.
        return new InProcessPackageProcessingDispatcher(
                reviewResumeTaskExecutor, triggerPackageProcessingUseCase, packageProcessingDispatchLease,
                // Task 0060: nothing is dispatched while no work may be taken; the recovery takes it up afterwards.
                tenantId -> workGate.mayTakeWork() && processingStorageNodeReadiness.readyFor(tenantId),
                (command, failure, failedAttempts, maxAttempts) ->
                        packageProcessingRecoveryService.stopAfterDispatchFailure(
                                command.packageId(), command.tenantId(), failure, failedAttempts, maxAttempts,
                                command.correlationId()),
                3);
    }

    @Bean
    public ExportPackageDocumentsUseCase exportPackageDocumentsUseCase(
            DocumentRepository documentRepository,
            PackageRepository packageRepository,
            com.securedoc.platform.application.casefields.CaseFieldService caseFields,
            ArtifactRepository artifactRepository,
            StoragePort storagePort,
            DeliveryPort deliveryPort,
            DeliverySubmissionRepository deliverySubmissionRepository,
            PackageLifecycleService packageLifecycleService,
            AuditService auditService) {
        return new PackageDocumentExportService(documentRepository, packageRepository, caseFields,
                artifactRepository, storagePort, deliveryPort, deliverySubmissionRepository, packageLifecycleService, auditService);
    }

    /** Task 0028: the export job's one rebuild, done by the same export service that owns the idempotency identities. */
    @Bean
    public com.securedoc.platform.application.port.in.RebuildPackageExportUseCase rebuildPackageExportUseCase(
            ExportPackageDocumentsUseCase exportPackageDocumentsUseCase) {
        if (exportPackageDocumentsUseCase instanceof com.securedoc.platform.application.port.in.RebuildPackageExportUseCase rebuild) {
            return rebuild;
        }
        throw new IllegalStateException("The export service can't rebuild an export");
    }


    @Bean
    public RoutingRuleService routingRuleService(RoutingRuleRepository routingRuleRepository,
                                                 IntegrationConnectionRepository integrationConnectionRepository,
                                                 AuditService auditService) {
        return new RoutingRuleService(routingRuleRepository, integrationConnectionRepository, auditService);
    }

    @Bean
    public DerivedDeliveryService derivedDeliveryService(DerivedDeliveryRepository derivedDeliveryRepository,
                                                         IntegrationConnectionRepository integrationConnectionRepository,
                                                         ArtifactRepository artifactRepository,
                                                         StoragePort storagePort,
                                                         DerivedDestinationDeliveryPort derivedDestinationDeliveryPort,
                                                         AuditService auditService,
                                                         PackageRepository packageRepository) {
        return new DerivedDeliveryService(derivedDeliveryRepository, integrationConnectionRepository,
                artifactRepository, storagePort, derivedDestinationDeliveryPort, auditService, packageRepository);
    }

    @Bean
    public DerivedDeliveryAttentionQuery derivedDeliveryAttentionQuery(DerivedDeliveryRepository derivedDeliveryRepository,
                                                                       CasePrimaryIdentifierLookup casePrimaryIdentifierLookup) {
        return new DerivedDeliveryAttentionQuery(derivedDeliveryRepository, casePrimaryIdentifierLookup);
    }

    @Bean
    public ProcessDerivedRoutingUseCase processDerivedRoutingUseCase(RoutingRuleRepository routingRuleRepository,
                                                                     DerivedDeliveryRepository derivedDeliveryRepository,
                                                                     IntegrationConnectionRepository integrationConnectionRepository,
                                                                     DocumentRepository documentRepository,
                                                                     ArtifactRepository artifactRepository,
                                                                     StoragePort storagePort,
                                                                     RoutingMarkerDetectionPort routingMarkerDetectionPort,
                                                                     DerivedPdfBuilderPort derivedPdfBuilderPort,
                                                                     DerivedDeliveryService derivedDeliveryService,
                                                                     AuditService auditService,
                                                                     com.securedoc.platform.application.pipeline.ExportJobQueue exportJobQueue) {
        // Task 0027: with export jobs on, each split document is queued as a SPLIT_EXPORT job instead of sent at once.
        // Task 0052: after a switch back to inline, so are those of a case that its own export job exported.
        return new DerivedRoutingService(routingRuleRepository, derivedDeliveryRepository,
                integrationConnectionRepository, documentRepository, artifactRepository, storagePort,
                routingMarkerDetectionPort, derivedPdfBuilderPort, derivedDeliveryService, auditService,
                exportJobQueue);
    }

    @Bean
    public DerivedConnectionTestService derivedConnectionTestService(IntegrationConnectionRepository integrationConnectionRepository,
                                                                     DerivedDestinationDeliveryPort derivedDestinationDeliveryPort,
                                                                     AuditService auditService) {
        return new DerivedConnectionTestService(integrationConnectionRepository, derivedDestinationDeliveryPort, auditService);
    }

    @Bean
    public GetTechnicalExceptionQuery getTechnicalExceptionQuery(
            PackageRepository packageRepository,
            DocumentRepository documentRepository,
            PackageLifecycleRepository packageLifecycleRepository,
            ArtifactRepository artifactRepository,
            AuditQueryPort auditQueryPort,
            ProcessingIncidentRepository processingIncidentRepository) {
        return new TechnicalExceptionQueryService(
                packageRepository, documentRepository, packageLifecycleRepository, artifactRepository, auditQueryPort,
                processingIncidentRepository);
    }

    @Bean
    public RetryTechnicalFailureService retryTechnicalFailureUseCase(
            GetTechnicalExceptionQuery technicalExceptionQuery,
            PackageRepository packageRepository,
            DocumentRepository documentRepository,
            FindingRepository findingRepository,
            ArtifactRepository artifactRepository,
            PackageLifecycleService packageLifecycleService,
            PackageProcessingDispatcher packageProcessingDispatcher,
            AuditService auditService,
            ProcessingIncidentService processingIncidentService,
            ProcessingLockPort processingLockPort) {
        return new RetryTechnicalFailureService(technicalExceptionQuery, packageRepository, documentRepository,
                findingRepository, artifactRepository, packageLifecycleService, packageProcessingDispatcher, auditService,
                processingIncidentService, processingLockPort);
    }

    @Bean
    public ReprocessPackageUseCase reprocessPackageUseCase(
            PackageRepository packageRepository, DocumentRepository documentRepository,
            FindingRepository findingRepository, ArtifactRepository artifactRepository,
            PackageLifecycleService packageLifecycleService,
            PackageProcessingDispatcher packageProcessingDispatcher, AuditService auditService,
            IntakeLicence intakeLicence) {
        return new ReprocessPackageService(packageRepository, documentRepository, findingRepository,
                artifactRepository, packageLifecycleService, packageProcessingDispatcher, auditService,
                intakeLicence);
    }

    /** Task 0076. */
    @Bean
    public com.securedoc.platform.application.lifecycle.service.BulkApprovalService bulkApprovalService(
            ApproveFinalDeliveryUseCase approveFinalDeliveryUseCase, PackageLifecycleService packageLifecycleService,
            com.securedoc.platform.application.port.out.CaseReviewersPort caseReviewersPort, AuditService auditService) {
        return new com.securedoc.platform.application.lifecycle.service.BulkApprovalService(approveFinalDeliveryUseCase,
                packageLifecycleService, caseReviewersPort, auditService);
    }

    /** Task 0071. */
    @Bean
    public com.securedoc.platform.application.review.service.RejectCaseService rejectCaseService(
            PackageRepository packageRepository,
            com.securedoc.platform.application.port.out.PackageRejectionRepository packageRejectionRepository,
            PackageLifecycleService packageLifecycleService,
            com.securedoc.platform.application.port.out.PipelineJobRepository pipelineJobRepository,
            AuditService auditService) {
        return new com.securedoc.platform.application.review.service.RejectCaseService(packageRepository,
                packageRejectionRepository, packageLifecycleService, pipelineJobRepository, auditService);
    }

    @Bean
    public ApproveFinalDeliveryUseCase approveFinalDeliveryUseCase(
            PackageRepository packageRepository, PackageLifecycleService packageLifecycleService,
            PackageProcessingDispatcher packageProcessingDispatcher, AuditService auditService) {
        return new FinalDeliveryApprovalService(packageRepository, packageLifecycleService,
                packageProcessingDispatcher, auditService);
    }


    // --- Worker beans ---

    @Bean
    @ConfigurationProperties(prefix = "securedoc.worker")
    public ProcessingWorkerConfig processingWorkerConfig() {
        return new ProcessingWorkerConfig();
    }

    @Bean
    public ProcessingWorkCoordinator processingWorkCoordinator(
            StartPackageProcessingUseCase startPackageProcessingUseCase,
            StartDocumentProcessingUseCase startDocumentProcessingUseCase,
            ExtractDocumentTextUseCase extractDocumentTextUseCase,
            DetectSensitiveDataUseCase detectSensitiveDataUseCase,
            RedactDocumentUseCase redactDocumentUseCase) {
        return new ProcessingWorkCoordinator(startPackageProcessingUseCase,
                startDocumentProcessingUseCase, extractDocumentTextUseCase,
                detectSensitiveDataUseCase, redactDocumentUseCase);
    }

    @Bean
    @ConditionalOnProperty(name = "securedoc.worker.enabled", havingValue = "true", matchIfMissing = false)
    public ProcessingWorker processingWorker(
            PackageRepository packageRepository,
            ProcessingWorkCoordinator coordinator,
            ProcessingWorkerConfig config) {
        return new ProcessingWorker(packageRepository, coordinator, config);
    }
    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value.trim());
        }
    }

}

package com.securedoc.platform.api.config;

import com.securedoc.platform.application.business.service.BusinessIdentityService;
import com.securedoc.platform.application.storage.ProcessingStorageConfigurationService;
import com.securedoc.platform.application.business.admin.BusinessAdminConfigurationService;
import com.securedoc.platform.application.intelligence.service.DocumentIntelligenceConfigurationService;
import com.securedoc.platform.application.intelligence.service.DocumentIntelligenceExecutionService;
import com.securedoc.platform.application.port.out.ReportingConfigurationRepository;
import com.securedoc.platform.application.port.out.ProcessingStorageConfigurationRepository;
import com.securedoc.platform.application.port.out.BusinessIdentifierDefinitionRepository;
import com.securedoc.platform.application.port.out.BusinessRecordRepository;
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
import com.securedoc.platform.application.integration.SdvConnectionTestService;
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
import com.securedoc.platform.application.port.out.SdvConnectionTestPort;
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
import com.securedoc.platform.application.licensing.LicenseTokenVerifier;
import com.securedoc.platform.application.licensing.TenantLicenseRepository;
import com.securedoc.platform.application.licensing.TenantLicensingService;
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
import com.securedoc.platform.infrastructure.licensing.Ed25519LicenseTokenVerifier;
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
    @ConfigurationProperties(prefix = "securedoc.licensing")
    public LicensingProperties licensingProperties() {
        return new LicensingProperties();
    }

    @Bean
    public LicenseTokenVerifier licenseTokenVerifier(LicensingProperties licensingProperties,
                                                     ResourceLoader resourceLoader) {
        String publicKey = "";
        if (licensingProperties.isAllowDevelopmentKeyOverride()
                && licensingProperties.getDevelopmentPublicKey() != null
                && !licensingProperties.getDevelopmentPublicKey().isBlank()) {
            publicKey = licensingProperties.getDevelopmentPublicKey();
        } else {
            try {
                var resource = resourceLoader.getResource(licensingProperties.getVerificationKeyResource());
                if (resource.exists()) {
                    try (var in = resource.getInputStream()) {
                        publicKey = new String(in.readAllBytes(), StandardCharsets.US_ASCII);
                    }
                }
            } catch (Exception ignored) {
                // Missing/unreadable vendor key is represented as CONFIGURATION_ERROR by the verifier.
            }
        }
        return new Ed25519LicenseTokenVerifier(publicKey);
    }

    @Bean
    public TenantLicensingService tenantLicensingService(
            TenantLicenseRepository tenantLicenseRepository,
            TenantRepository tenantRepository,
            LicenseTokenVerifier licenseTokenVerifier,
            AuditService auditService,
            LicensingProperties licensingProperties) {
        return new TenantLicensingService(tenantLicenseRepository, tenantRepository, licenseTokenVerifier,
                auditService, licensingProperties.enforcementEnabled());
    }

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
                productTelemetryService);
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
            PackageLifecycleRepository packageLifecycleRepository) {
        return new SupportIncidentQueryService(supportIncidentSearchRepository, processingIncidentRepository,
                packageLifecycleRepository);
    }

    @Bean
    public IntegrationConnectionService integrationConnectionService(
            IntegrationConnectionRepository integrationConnectionRepository,
            AuditService auditService) {
        return new IntegrationConnectionService(integrationConnectionRepository, auditService);
    }

    @Bean
    @ConditionalOnProperty(name = "securedoc.delivery.type", havingValue = "sdv")
    public SdvConnectionTestService sdvConnectionTestService(
            IntegrationConnectionService integrationConnectionService,
            SdvConnectionTestPort sdvConnectionTestPort,
            AuditService auditService) {
        return new SdvConnectionTestService(integrationConnectionService, sdvConnectionTestPort, auditService);
    }

    @Bean
    public ExternalIntakeMaintenanceService externalIntakeMaintenanceService(
            ExternalIntakeRepository externalIntakeRepository,
            PackageRepository packageRepository,
            ArtifactRepository artifactRepository,
            StoragePort storagePort,
            AuditService auditService) {
        return new ExternalIntakeMaintenanceService(externalIntakeRepository, packageRepository,
                artifactRepository, storagePort, auditService);
    }

    @Bean
    public ExternalIntakeService externalIntakeService(
            IntegrationConnectionService integrationConnectionService,
            ExternalIntakeRepository externalIntakeRepository,
            PackageRepository packageRepository,
            CreatePackageUseCase createPackageUseCase,
            UploadDocumentUseCase uploadDocumentUseCase,
            SubmitPackageUseCase submitPackageUseCase,
            PackageProcessingDispatcher packageProcessingDispatcher,
            ChecksumPort checksumPort,
            PdfIntakeValidationPort pdfIntakeValidationPort,
            AuditService auditService,
            ExternalIntakeMaintenanceService maintenanceService) {
        return new ExternalIntakeService(integrationConnectionService, externalIntakeRepository, packageRepository,
                createPackageUseCase, uploadDocumentUseCase, submitPackageUseCase, packageProcessingDispatcher,
                checksumPort, pdfIntakeValidationPort, auditService, maintenanceService);
    }

    @Bean
    public PollingIntakeService pollingIntakeService(
            IntegrationConnectionService integrationConnectionService,
            PollingRunRepository pollingRunRepository,
            PollingSourcePort pollingSourcePort,
            ExternalIntakeService externalIntakeService,
            AuditService auditService) {
        return new PollingIntakeService(integrationConnectionService, pollingRunRepository, pollingSourcePort,
                externalIntakeService, auditService);
    }

    @Bean
    public CapabilityEnforcementService capabilityEnforcementService() {
        return new CapabilityEnforcementService();
    }

    @Bean
    public AuditService auditService(AuditPort auditPort) {
        return new AuditService(auditPort);
    }

    @Bean
    public AuditQueryService auditQueryService(AuditQueryPort auditQueryPort) {
        return new AuditQueryService(auditQueryPort);
    }

    @Bean
    public BusinessIdentityService businessIdentityService(
            BusinessIdentifierDefinitionRepository definitionRepository,
            BusinessRecordRepository businessRecordRepository) {
        return new BusinessIdentityService(definitionRepository, businessRecordRepository);
    }

    @Bean
    public BusinessAdminConfigurationService businessAdminConfigurationService(
            BusinessIdentifierDefinitionRepository definitionRepository,
            TenantRedactionPolicyRepository tenantRedactionPolicyRepository,
            TenantLifecyclePolicyRepository tenantLifecyclePolicyRepository,
            ReportingConfigurationRepository reportingConfigurationRepository,
            AuditService auditService) {
        return new BusinessAdminConfigurationService(
                definitionRepository, tenantRedactionPolicyRepository, tenantLifecyclePolicyRepository,
                reportingConfigurationRepository, auditService);
    }

    @Bean
    public CreatePackageUseCase createPackageUseCase(
            TenantContextService tenantContextService,
            CapabilityEnforcementService capabilityEnforcementService,
            BusinessIdentityService businessIdentityService,
            TenantLicensingService tenantLicensingService,
            PackageRepository packageRepository,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new CreatePackageService(
                tenantContextService, capabilityEnforcementService, businessIdentityService, tenantLicensingService,
                packageRepository, eventPublisher, auditService);
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
            WatchedFolderProperties properties) {
        return new WatchedFolderIntakeService(sourcePort, watchedFolderLeasePort, observationRepository, packageRepository,
                createPackageUseCase, uploadDocumentUseCase, submitPackageUseCase,
                packageProcessingDispatcher, auditService,
                Duration.ofMinutes(Math.max(0, properties.getIncompleteVisibilityMinutes())),
                Duration.ofMillis(Math.max(1000L, properties.getStaleClaimAfterMs())));
    }

    @Bean
    public UploadDocumentUseCase uploadDocumentUseCase(
            TenantContextService tenantContextService,
            CapabilityEnforcementService capabilityEnforcementService,
            TenantLicensingService tenantLicensingService,
            PackageRepository packageRepository,
            DocumentRepository documentRepository,
            ArtifactRepository artifactRepository,
            StoragePort storagePort,
            ChecksumPort checksumPort,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new UploadDocumentService(
                tenantContextService, capabilityEnforcementService, tenantLicensingService,
                packageRepository, documentRepository, artifactRepository,
                storagePort, checksumPort, eventPublisher, auditService);
    }

    @Bean
    public SubmitPackageUseCase submitPackageUseCase(
            TenantContextService tenantContextService,
            CapabilityEnforcementService capabilityEnforcementService,
            TenantLicensingService tenantLicensingService,
            PackageRepository packageRepository,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new SubmitPackageService(
                tenantContextService, capabilityEnforcementService, tenantLicensingService,
                packageRepository, eventPublisher, auditService);
    }

    @Bean
    public GetPackageQuery getPackageQuery(PackageRepository packageRepository, BusinessIdentityService businessIdentityService) {
        return new GetPackageQueryService(packageRepository, businessIdentityService);
    }

    @Bean
    public ListPackagesQuery listPackagesQuery(PackageRepository packageRepository, BusinessIdentityService businessIdentityService) {
        return new ListPackagesQueryService(packageRepository, businessIdentityService);
    }

    @Bean
    public OperationsQueryService operationsQueryService(OperationsReadPort operationsReadPort) {
        return new OperationsQueryService(operationsReadPort);
    }

    @Bean
    public DashboardColumnConfigurationService dashboardColumnConfigurationService(
            BusinessIdentifierDefinitionRepository definitionRepository,
            DashboardColumnSettingsRepository dashboardColumnSettingsRepository,
            AuditService auditService) {
        return new DashboardColumnConfigurationService(
                definitionRepository, dashboardColumnSettingsRepository, auditService);
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
            AuditService auditService) {
        return new TextExtractionService(
                documentRepository, packageRepository, artifactRepository,
                storagePort, textExtractionPort, checksumPort,
                processingLockPort, eventPublisher, auditService);
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
            AuditService auditService) {
        return new SensitiveDataDetectionService(
                documentRepository, packageRepository, artifactRepository,
                storagePort, semanticAnalysisPort, detectionPort, validationPort, tenantRedactionPolicyRepository, textExtractionPort, findingRepository,
                eventPublisher, auditService);
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
            AuditService auditService) {
        return new DocumentRedactionService(
                documentRepository, packageRepository, artifactRepository,
                findingRepository, storagePort, pdfRedactionPort, redactionVerificationPort, checksumPort,
                eventPublisher, auditService);
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
            AuditService auditService) {
        return new FocusedReviewService(packageRepository, documentRepository, findingRepository,
                tenantRedactionPolicyRepository, packageProcessingDispatcher, auditService);
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
            StoragePort storagePort,
            AuditService auditService,
            ProcessingIncidentService processingIncidentService,
            ProductTelemetryService productTelemetryService) {
        return new PackageLifecycleService(tenantLifecyclePolicyRepository, packageLifecycleRepository,
                packageExportRecordRepository, packageRepository, packageDashboardVisibilityPort,
                documentRepository, artifactRepository, storagePort, auditService, processingIncidentService,
                productTelemetryService);
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
            TenantLicensingService tenantLicensingService,
            ProductTelemetryService productTelemetryService) {
        return new TriggerPackageProcessingService(packageRepository, documentRepository,
                findingRepository, artifactRepository, processingLockPort,
                startPackageProcessingUseCase, startDocumentProcessingUseCase,
                extractDocumentTextUseCase, detectSensitiveDataUseCase, redactDocumentUseCase,
                exportPackageDocumentsUseCase, processDerivedRoutingUseCase, selectiveRetryExecutor, packageLifecycleService,
                documentIntelligenceExecutionService, auditService, processingIncidentService, tenantLicensingService,
                productTelemetryService);
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
            ProcessingStorageNodeReadiness processingStorageNodeReadiness) {
        return new InProcessPackageProcessingDispatcher(
                reviewResumeTaskExecutor, triggerPackageProcessingUseCase, packageProcessingDispatchLease,
                processingStorageNodeReadiness::readyFor);
    }

    @Bean
    public ExportPackageDocumentsUseCase exportPackageDocumentsUseCase(
            DocumentRepository documentRepository,
            PackageRepository packageRepository,
            BusinessRecordRepository businessRecordRepository,
            ArtifactRepository artifactRepository,
            StoragePort storagePort,
            DeliveryPort deliveryPort,
            DeliverySubmissionRepository deliverySubmissionRepository,
            PackageLifecycleService packageLifecycleService,
            AuditService auditService) {
        return new PackageDocumentExportService(documentRepository, packageRepository, businessRecordRepository,
                artifactRepository, storagePort, deliveryPort, deliverySubmissionRepository, packageLifecycleService, auditService);
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
                                                         AuditService auditService) {
        return new DerivedDeliveryService(derivedDeliveryRepository, integrationConnectionRepository,
                artifactRepository, storagePort, derivedDestinationDeliveryPort, auditService);
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
                                                                     AuditService auditService) {
        return new DerivedRoutingService(routingRuleRepository, derivedDeliveryRepository,
                integrationConnectionRepository, documentRepository, artifactRepository, storagePort,
                routingMarkerDetectionPort, derivedPdfBuilderPort, derivedDeliveryService, auditService);
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
            TenantLicensingService tenantLicensingService) {
        return new ReprocessPackageService(packageRepository, documentRepository, findingRepository,
                artifactRepository, packageLifecycleService, packageProcessingDispatcher, auditService,
                tenantLicensingService);
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

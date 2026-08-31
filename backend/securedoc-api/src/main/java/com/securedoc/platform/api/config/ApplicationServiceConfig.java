package com.securedoc.platform.api.config;

import com.securedoc.platform.application.business.service.BusinessIdentityService;
import com.securedoc.platform.application.business.admin.BusinessAdminConfigurationService;
import com.securedoc.platform.application.port.out.BusinessIdentifierDefinitionRepository;
import com.securedoc.platform.application.port.out.BusinessRecordRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.TenantLifecyclePolicyRepository;
import com.securedoc.platform.application.port.out.PackageLifecycleRepository;
import com.securedoc.platform.application.port.out.PackageExportRecordRepository;
import com.securedoc.platform.application.intake.watched.WatchedFolderIntakeService;
import com.securedoc.platform.application.port.out.WatchedFolderSourcePort;
import com.securedoc.platform.application.port.out.IntakeObservationRepository;
import com.securedoc.platform.infrastructure.intake.WatchedFolderProperties;
import com.securedoc.platform.application.port.in.CreatePackageUseCase;
import com.securedoc.platform.application.port.in.DetectSensitiveDataUseCase;
import com.securedoc.platform.application.audit.service.AuditQueryService;
import com.securedoc.platform.application.port.out.AuditQueryPort;
import com.securedoc.platform.application.port.in.ExtractDocumentTextUseCase;
import com.securedoc.platform.application.port.in.FailDocumentProcessingUseCase;
import com.securedoc.platform.application.port.in.GetPackageQuery;
import com.securedoc.platform.application.port.in.GetReviewPagePreviewQuery;
import com.securedoc.platform.application.port.in.ListPackagesQuery;
import com.securedoc.platform.application.port.in.RedactDocumentUseCase;
import com.securedoc.platform.application.port.in.StartDocumentProcessingUseCase;
import com.securedoc.platform.application.port.in.StartPackageProcessingUseCase;
import com.securedoc.platform.application.port.in.SubmitPackageUseCase;
import com.securedoc.platform.application.port.in.TriggerPackageProcessingUseCase;
import com.securedoc.platform.application.port.in.ReprocessPackageUseCase;
import com.securedoc.platform.application.port.in.ApproveFinalDeliveryUseCase;
import com.securedoc.platform.application.port.in.UploadDocumentUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.AuditPort;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.OperationsReadPort;
import com.securedoc.platform.application.port.out.DashboardColumnSettingsRepository;
import com.securedoc.platform.application.port.out.PdfRedactionPort;
import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.application.port.out.ReviewTaskRepository;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort;
import com.securedoc.platform.application.port.out.SensitiveDataValidationPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.RedactionVerificationPort;
import com.securedoc.platform.application.port.out.ReviewPageRenderPort;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.application.port.out.AccessRequestRepository;
import com.securedoc.platform.application.port.out.VaultEntryRepository;
import com.securedoc.platform.application.detection.service.SensitiveDataDetectionService;
import com.securedoc.platform.application.extraction.service.TextExtractionService;
import com.securedoc.platform.application.redaction.service.DocumentRedactionService;
import com.securedoc.platform.application.resilience.ResilientSensitiveContentAnalysisPort;
import com.securedoc.platform.application.resilience.SelectiveRetryExecutor;
import com.securedoc.platform.application.review.service.FindingReviewService;
import com.securedoc.platform.application.review.service.FocusedReviewService;
import com.securedoc.platform.application.review.service.ReviewWorkflowService;
import com.securedoc.platform.application.review.service.ReviewPagePreviewService;
import com.securedoc.platform.application.processing.service.DocumentProcessingService;
import com.securedoc.platform.application.processing.service.PackageFinalizationService;
import com.securedoc.platform.application.processing.service.PackageProcessingService;
import com.securedoc.platform.application.processing.service.TriggerPackageProcessingService;
import com.securedoc.platform.application.operations.service.OperationsQueryService;
import com.securedoc.platform.application.operations.service.DashboardColumnConfigurationService;
import com.securedoc.platform.application.export.service.PackageDocumentExportService;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.lifecycle.service.ReprocessPackageService;
import com.securedoc.platform.application.lifecycle.service.FinalDeliveryApprovalService;
import com.securedoc.platform.application.port.in.ExportPackageDocumentsUseCase;
import com.securedoc.platform.application.port.out.DeliveryPort;
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
import com.securedoc.platform.infrastructure.resilience.TechnicalResilienceProperties;
import com.securedoc.platform.worker.processing.ProcessingWorkCoordinator;
import com.securedoc.platform.worker.processing.ProcessingWorker;
import com.securedoc.platform.worker.processing.ProcessingWorkerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Wires application services with infrastructure adapters.
 * Application classes remain framework-free — Spring instantiation happens here at the edge.
 */
@Configuration
public class ApplicationServiceConfig {

    @Bean
    public TenantContextService tenantContextService(TenantRepository tenantRepository) {
        return new TenantContextService(tenantRepository);
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
            AuditService auditService) {
        return new BusinessAdminConfigurationService(
                definitionRepository, tenantRedactionPolicyRepository, tenantLifecyclePolicyRepository, auditService);
    }

    @Bean
    public CreatePackageUseCase createPackageUseCase(
            TenantContextService tenantContextService,
            CapabilityEnforcementService capabilityEnforcementService,
            BusinessIdentityService businessIdentityService,
            PackageRepository packageRepository,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new CreatePackageService(
                tenantContextService, capabilityEnforcementService, businessIdentityService,
                packageRepository, eventPublisher, auditService);
    }

    @Bean
    public WatchedFolderIntakeService watchedFolderIntakeService(
            WatchedFolderSourcePort sourcePort,
            IntakeObservationRepository observationRepository,
            PackageRepository packageRepository,
            CreatePackageUseCase createPackageUseCase,
            UploadDocumentUseCase uploadDocumentUseCase,
            SubmitPackageUseCase submitPackageUseCase,
            TriggerPackageProcessingUseCase triggerPackageProcessingUseCase,
            AuditService auditService,
            WatchedFolderProperties properties) {
        return new WatchedFolderIntakeService(sourcePort, observationRepository, packageRepository,
                createPackageUseCase, uploadDocumentUseCase, submitPackageUseCase,
                triggerPackageProcessingUseCase, auditService,
                Duration.ofMinutes(Math.max(0, properties.getIncompleteVisibilityMinutes())));
    }

    @Bean
    public UploadDocumentUseCase uploadDocumentUseCase(
            TenantContextService tenantContextService,
            CapabilityEnforcementService capabilityEnforcementService,
            PackageRepository packageRepository,
            DocumentRepository documentRepository,
            ArtifactRepository artifactRepository,
            StoragePort storagePort,
            ChecksumPort checksumPort,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new UploadDocumentService(
                tenantContextService, capabilityEnforcementService,
                packageRepository, documentRepository, artifactRepository,
                storagePort, checksumPort, eventPublisher, auditService);
    }

    @Bean
    public SubmitPackageUseCase submitPackageUseCase(
            TenantContextService tenantContextService,
            CapabilityEnforcementService capabilityEnforcementService,
            PackageRepository packageRepository,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new SubmitPackageService(
                tenantContextService, capabilityEnforcementService,
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
        SensitiveContentAnalysisPort primary = buildAnalysisProvider(
                properties.getProvider(), properties.getBaseUrl(), properties.getModel(),
                properties.getTimeoutSeconds(), properties.getParallelism(),
                properties.getPageMaxAttempts(), properties.getPageRetryBackoffMs(), true);
        SensitiveContentAnalysisPort secondary = null;
        if (properties.isSecondaryEnabled()) {
            secondary = buildAnalysisProvider(
                    properties.getSecondaryProvider(), properties.getSecondaryBaseUrl(),
                    properties.getSecondaryModel(), properties.getSecondaryTimeoutSeconds(),
                    properties.getParallelism(), properties.getPageMaxAttempts(),
                    properties.getPageRetryBackoffMs(), false);
        }
        return new ResilientSensitiveContentAnalysisPort(primary, secondary, selectiveRetryExecutor);
    }

    private SensitiveContentAnalysisPort buildAnalysisProvider(String providerValue,
                                                               String baseUrl,
                                                               String model,
                                                               int timeoutSeconds,
                                                               int parallelism,
                                                               int pageMaxAttempts,
                                                               long pageRetryBackoffMs,
                                                               boolean allowHeuristic) {
        String provider = providerValue == null ? "" : providerValue.trim().toLowerCase();
        return switch (provider) {
            case "ollama" -> new OllamaSensitiveContentAnalysisAdapter(
                    baseUrl,
                    model,
                    Duration.ofSeconds(Math.max(1, timeoutSeconds)),
                    Math.max(1, Math.min(16, parallelism)),
                    Math.max(1, Math.min(5, pageMaxAttempts)),
                    Duration.ofMillis(Math.max(0L, pageRetryBackoffMs)));
            case "heuristic" -> {
                if (!allowHeuristic) {
                    throw new IllegalArgumentException("Heuristic adapter cannot be configured as AI failover");
                }
                yield new LocalHeuristicSensitiveContentAnalysisAdapter();
            }
            default -> throw new IllegalArgumentException("Unsupported securedoc.ai provider: " + providerValue);
        };
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
            TriggerPackageProcessingUseCase triggerPackageProcessingUseCase,
            AuditService auditService) {
        return new FocusedReviewService(packageRepository, documentRepository, findingRepository,
                tenantRedactionPolicyRepository, triggerPackageProcessingUseCase, auditService);
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
            DocumentRepository documentRepository,
            ArtifactRepository artifactRepository,
            StoragePort storagePort,
            AuditService auditService) {
        return new PackageLifecycleService(tenantLifecyclePolicyRepository, packageLifecycleRepository,
                packageExportRecordRepository, packageRepository, documentRepository, artifactRepository,
                storagePort, auditService);
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
    public TriggerPackageProcessingService triggerPackageProcessingService(
            PackageRepository packageRepository,
            DocumentRepository documentRepository,
            FindingRepository findingRepository,
            StartPackageProcessingUseCase startPackageProcessingUseCase,
            StartDocumentProcessingUseCase startDocumentProcessingUseCase,
            ExtractDocumentTextUseCase extractDocumentTextUseCase,
            DetectSensitiveDataUseCase detectSensitiveDataUseCase,
            RedactDocumentUseCase redactDocumentUseCase,
            ExportPackageDocumentsUseCase exportPackageDocumentsUseCase,
            SelectiveRetryExecutor selectiveRetryExecutor,
            PackageLifecycleService packageLifecycleService,
            AuditService auditService) {
        return new TriggerPackageProcessingService(packageRepository, documentRepository,
                findingRepository, startPackageProcessingUseCase, startDocumentProcessingUseCase,
                extractDocumentTextUseCase, detectSensitiveDataUseCase, redactDocumentUseCase,
                exportPackageDocumentsUseCase, selectiveRetryExecutor, packageLifecycleService, auditService);
    }


    @Bean
    public ExportPackageDocumentsUseCase exportPackageDocumentsUseCase(
            DocumentRepository documentRepository,
            ArtifactRepository artifactRepository,
            StoragePort storagePort,
            DeliveryPort deliveryPort,
            PackageLifecycleService packageLifecycleService,
            AuditService auditService) {
        return new PackageDocumentExportService(documentRepository, artifactRepository,
                storagePort, deliveryPort, packageLifecycleService, auditService);
    }


    @Bean
    public ReprocessPackageUseCase reprocessPackageUseCase(
            PackageRepository packageRepository, DocumentRepository documentRepository,
            FindingRepository findingRepository, ArtifactRepository artifactRepository,
            PackageLifecycleService packageLifecycleService,
            TriggerPackageProcessingUseCase triggerPackageProcessingUseCase, AuditService auditService) {
        return new ReprocessPackageService(packageRepository, documentRepository, findingRepository,
                artifactRepository, packageLifecycleService, triggerPackageProcessingUseCase, auditService);
    }

    @Bean
    public ApproveFinalDeliveryUseCase approveFinalDeliveryUseCase(
            PackageRepository packageRepository, PackageLifecycleService packageLifecycleService,
            TriggerPackageProcessingUseCase triggerPackageProcessingUseCase, AuditService auditService) {
        return new FinalDeliveryApprovalService(packageRepository, packageLifecycleService,
                triggerPackageProcessingUseCase, auditService);
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
}

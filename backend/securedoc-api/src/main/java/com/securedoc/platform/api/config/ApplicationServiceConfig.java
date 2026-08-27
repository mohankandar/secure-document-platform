package com.securedoc.platform.api.config;

import com.securedoc.platform.application.port.in.CreatePackageUseCase;
import com.securedoc.platform.application.port.in.DetectSensitiveDataUseCase;
import com.securedoc.platform.application.port.in.ExtractDocumentTextUseCase;
import com.securedoc.platform.application.port.in.FailDocumentProcessingUseCase;
import com.securedoc.platform.application.port.in.GetPackageQuery;
import com.securedoc.platform.application.port.in.ListPackagesQuery;
import com.securedoc.platform.application.port.in.RedactDocumentUseCase;
import com.securedoc.platform.application.port.in.StartDocumentProcessingUseCase;
import com.securedoc.platform.application.port.in.StartPackageProcessingUseCase;
import com.securedoc.platform.application.port.in.SubmitPackageUseCase;
import com.securedoc.platform.application.port.in.UploadDocumentUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.AuditPort;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.PdfRedactionPort;
import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.application.port.out.ReviewTaskRepository;
import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TenantRepository;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.application.port.out.AccessRequestRepository;
import com.securedoc.platform.application.port.out.VaultEntryRepository;
import com.securedoc.platform.application.detection.service.SensitiveDataDetectionService;
import com.securedoc.platform.application.extraction.service.TextExtractionService;
import com.securedoc.platform.application.redaction.service.DocumentRedactionService;
import com.securedoc.platform.application.review.service.FindingReviewService;
import com.securedoc.platform.application.review.service.ReviewWorkflowService;
import com.securedoc.platform.application.processing.service.DocumentProcessingService;
import com.securedoc.platform.application.processing.service.PackageFinalizationService;
import com.securedoc.platform.application.processing.service.PackageProcessingService;
import com.securedoc.platform.application.processing.service.TriggerPackageProcessingService;
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
import com.securedoc.platform.worker.processing.ProcessingWorkCoordinator;
import com.securedoc.platform.worker.processing.ProcessingWorker;
import com.securedoc.platform.worker.processing.ProcessingWorkerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    public CreatePackageUseCase createPackageUseCase(
            TenantContextService tenantContextService,
            CapabilityEnforcementService capabilityEnforcementService,
            PackageRepository packageRepository,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new CreatePackageService(
                tenantContextService, capabilityEnforcementService,
                packageRepository, eventPublisher, auditService);
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
    public GetPackageQuery getPackageQuery(PackageRepository packageRepository) {
        return new GetPackageQueryService(packageRepository);
    }

    @Bean
    public ListPackagesQuery listPackagesQuery(PackageRepository packageRepository) {
        return new ListPackagesQueryService(packageRepository);
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

    // --- Detection service ---

    @Bean
    public DetectSensitiveDataUseCase detectSensitiveDataUseCase(
            DocumentRepository documentRepository,
            PackageRepository packageRepository,
            ArtifactRepository artifactRepository,
            StoragePort storagePort,
            SensitiveDataDetectionPort detectionPort,
            FindingRepository findingRepository,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new SensitiveDataDetectionService(
                documentRepository, packageRepository, artifactRepository,
                storagePort, detectionPort, findingRepository,
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
            ChecksumPort checksumPort,
            DomainEventPublisher eventPublisher,
            AuditService auditService) {
        return new DocumentRedactionService(
                documentRepository, packageRepository, artifactRepository,
                findingRepository, storagePort, pdfRedactionPort, checksumPort,
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
            AuditService auditService) {
        return new TriggerPackageProcessingService(packageRepository, documentRepository,
                findingRepository, startPackageProcessingUseCase, startDocumentProcessingUseCase,
                extractDocumentTextUseCase, detectSensitiveDataUseCase, redactDocumentUseCase,
                auditService);
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

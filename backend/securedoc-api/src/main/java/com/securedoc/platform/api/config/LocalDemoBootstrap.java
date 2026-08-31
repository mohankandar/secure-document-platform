package com.securedoc.platform.api.config;

import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.business.BusinessIdentifierDefinition;
import com.securedoc.platform.application.port.out.BusinessIdentifierDefinitionRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.tenant.Capability;
import com.securedoc.platform.domain.tenant.CapabilityStatus;
import com.securedoc.platform.infrastructure.persistence.entity.TenantCapabilityEntity;
import com.securedoc.platform.infrastructure.persistence.entity.TenantEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataTenantCapabilityRepository;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataTenantRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * LOCAL/DEMO ONLY: Creates a demo tenant on startup if it does not exist.
 * Active only with the "local" profile.
 */
@Component
@Profile("local")
public class LocalDemoBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(LocalDemoBootstrap.class);

    private final SpringDataTenantRepository tenantRepo;
    private final SpringDataTenantCapabilityRepository capabilityRepo;
    private final BusinessIdentifierDefinitionRepository identifierDefinitionRepository;
    private final TenantRedactionPolicyRepository tenantRedactionPolicyRepository;
    private final UUID demoTenantId;
    private final String demoTenantName;
    private final String demoTenantSlug;

    public LocalDemoBootstrap(
            SpringDataTenantRepository tenantRepo,
            SpringDataTenantCapabilityRepository capabilityRepo,
            BusinessIdentifierDefinitionRepository identifierDefinitionRepository,
            TenantRedactionPolicyRepository tenantRedactionPolicyRepository,
            @Value("${securedoc.demo.tenant-id}") String demoTenantId,
            @Value("${securedoc.demo.tenant-name}") String demoTenantName,
            @Value("${securedoc.demo.tenant-slug}") String demoTenantSlug) {
        this.tenantRepo = tenantRepo;
        this.capabilityRepo = capabilityRepo;
        this.identifierDefinitionRepository = identifierDefinitionRepository;
        this.tenantRedactionPolicyRepository = tenantRedactionPolicyRepository;
        this.demoTenantId = UUID.fromString(demoTenantId);
        this.demoTenantName = demoTenantName;
        this.demoTenantSlug = demoTenantSlug;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        boolean tenantExists = tenantRepo.findById(demoTenantId).isPresent();
        Instant now = Instant.now();
        if (!tenantExists) {
            log.info("Creating demo tenant: {} ({})", demoTenantSlug, demoTenantId);

            TenantEntity tenant = new TenantEntity();
            tenant.setId(demoTenantId);
            tenant.setName(demoTenantName);
            tenant.setSlug(demoTenantSlug);
            tenant.setStatus("ACTIVE");
            tenant.setCreatedAt(now);
            tenant.setUpdatedAt(now);
            tenantRepo.save(tenant);

            // Enable core capabilities for the demo tenant.
            for (Capability cap : new Capability[]{
                    Capability.DOCUMENT_UPLOAD,
                    Capability.OCR_PROCESSING,
                    Capability.SENSITIVE_DATA_DETECTION,
                    Capability.REDACTION,
                    Capability.HUMAN_REVIEW,
                    Capability.VAULT,
                    Capability.AUDIT
            }) {
                TenantCapabilityEntity capEntity = new TenantCapabilityEntity();
                capEntity.setId(UUID.randomUUID());
                capEntity.setTenantId(demoTenantId);
                capEntity.setCapability(cap.name());
                capEntity.setStatus(CapabilityStatus.ENABLED.name());
                capEntity.setCreatedAt(now);
                capEntity.setUpdatedAt(now);
                capabilityRepo.save(capEntity);
            }

            log.info("Demo tenant created with manual upload capability enabled");
        } else {
            log.info("Demo tenant already exists: {} ({})", demoTenantSlug, demoTenantId);
        }

        ensureDemoIdentifierDefinitions();
        ensureDemoRedactionPolicy();
    }

    private void ensureDemoIdentifierDefinitions() {
        TenantId tenantId = TenantId.of(demoTenantId);
        if (!identifierDefinitionRepository.findActiveByTenantId(tenantId).isEmpty()) {
            return;
        }
        identifierDefinitionRepository.save(BusinessIdentifierDefinition.create(tenantId,
                "contract_number", "Contract Number", true, true, true, true, true, 1));
        identifierDefinitionRepository.save(BusinessIdentifierDefinition.create(tenantId,
                "member_number", "Member Number", true, false, true, true, true, 2));
        log.info("Created demo business identifier definitions: Contract Number (Primary), Member Number");
    }
    private void ensureDemoRedactionPolicy() {
        TenantId tenantId = TenantId.of(demoTenantId);
        if (tenantRedactionPolicyRepository.findByTenantId(tenantId).isPresent()) {
            return;
        }
        tenantRedactionPolicyRepository.save(TenantRedactionPolicy.defaults(tenantId));
        log.info("Created demo sensitive-data policy with V1 categories and uncertain-only review");
    }

}

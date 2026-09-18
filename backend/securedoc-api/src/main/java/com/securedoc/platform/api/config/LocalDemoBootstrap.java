package com.securedoc.platform.api.config;

import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.business.BusinessIdentifierDefinition;
import com.securedoc.platform.domain.business.BusinessIdentifierValidationType;
import com.securedoc.platform.domain.business.BusinessIdentifierCaseNormalization;
import com.securedoc.platform.application.port.out.BusinessIdentifierDefinitionRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.integration.IntegrationAcknowledgementMode;
import com.securedoc.platform.domain.integration.IntegrationAuthenticationType;
import com.securedoc.platform.domain.integration.IntegrationConnection;
import com.securedoc.platform.domain.integration.IntegrationDirection;
import com.securedoc.platform.domain.integration.IntegrationProviderType;
import com.securedoc.platform.domain.integration.IntegrationPurpose;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

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
    private final IntegrationConnectionRepository integrationConnectionRepository;
    private final UUID demoTenantId;
    private final String demoTenantName;
    private final String demoTenantSlug;
    private final String scannerClientPrincipal;

    public LocalDemoBootstrap(
            SpringDataTenantRepository tenantRepo,
            SpringDataTenantCapabilityRepository capabilityRepo,
            BusinessIdentifierDefinitionRepository identifierDefinitionRepository,
            TenantRedactionPolicyRepository tenantRedactionPolicyRepository,
            IntegrationConnectionRepository integrationConnectionRepository,
            @Value("${securedoc.demo.tenant-id}") String demoTenantId,
            @Value("${securedoc.demo.tenant-name}") String demoTenantName,
            @Value("${securedoc.demo.tenant-slug}") String demoTenantSlug,
            @Value("${securedoc.demo.scanner-client-principal:scanner-bridge-local}") String scannerClientPrincipal) {
        this.tenantRepo = tenantRepo;
        this.capabilityRepo = capabilityRepo;
        this.identifierDefinitionRepository = identifierDefinitionRepository;
        this.tenantRedactionPolicyRepository = tenantRedactionPolicyRepository;
        this.integrationConnectionRepository = integrationConnectionRepository;
        this.demoTenantId = UUID.fromString(demoTenantId);
        this.demoTenantName = demoTenantName;
        this.demoTenantSlug = demoTenantSlug;
        this.scannerClientPrincipal = scannerClientPrincipal;
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
        ensureDemoScannerIntegration();
    }

    private void ensureDemoIdentifierDefinitions() {
        TenantId tenantId = TenantId.of(demoTenantId);
        List<BusinessIdentifierDefinition> active = identifierDefinitionRepository.findActiveByTenantId(tenantId);
        if (active.isEmpty()) {
            identifierDefinitionRepository.saveAll(referenceDemoIdentifierDefinitions(tenantId, null));
            log.info("Created demo business identifier definitions: Contract Number (Primary), Member Number, Service Entity");
            return;
        }

        // One-time compatibility upgrade for the exact legacy LOCAL bootstrap. Do not overwrite
        // tenant-admin custom configurations on later startups.
        List<BusinessIdentifierDefinition> allDefinitions = identifierDefinitionRepository.findAllByTenantId(tenantId);
        Map<String, BusinessIdentifierDefinition> byKey = active.stream()
                .collect(Collectors.toMap(BusinessIdentifierDefinition::key, Function.identity()));
        boolean legacyBootstrap = allDefinitions.size() == active.size()
                && (active.size() == 2 || active.size() == 3)
                && byKey.containsKey("contract_number") && byKey.containsKey("member_number")
                && byKey.keySet().stream().allMatch(Set.of("contract_number", "member_number", "service_entity")::contains)
                && active.stream().allMatch(this::hasLegacyDefaultValidation);
        if (legacyBootstrap) {
            identifierDefinitionRepository.saveAll(referenceDemoIdentifierDefinitions(tenantId, byKey));
            log.info("Upgraded legacy demo identifier configuration with validation rules and Service Entity");
            return;
        }

        // LOCAL-only cleanup for the exact stale Service Entity allow-list produced during the
        // validation-framework development cycle. This intentionally does not overwrite arbitrary
        // Business Admin configuration: only the known 000/001/002 reference fragment is repaired.
        BusinessIdentifierDefinition serviceEntity = byKey.get("service_entity");
        if (serviceEntity != null
                && serviceEntity.validationType() == BusinessIdentifierValidationType.NUMERIC
                && Integer.valueOf(3).equals(serviceEntity.exactLength())
                && serviceEntity.minLength() == null && serviceEntity.maxLength() == null
                && serviceEntity.caseNormalization() == BusinessIdentifierCaseNormalization.PRESERVE
                && !serviceEntity.stripSeparators() && serviceEntity.customPattern() == null
                && serviceEntity.allowedValues().equals(Set.of("000", "001", "002"))) {
            identifierDefinitionRepository.save(serviceEntity.reconfigure(
                    serviceEntity.label(), serviceEntity.required(), serviceEntity.primary(), serviceEntity.searchable(),
                    serviceEntity.extractable(), serviceEntity.displayable(), serviceEntity.displayOrder(),
                    BusinessIdentifierValidationType.NUMERIC, 3, null, null,
                    BusinessIdentifierCaseNormalization.PRESERVE, false,
                    Set.of("000", "001", "002", "003", "004", "005"), null));
            log.info("Repaired stale LOCAL Service Entity reference allow-list to 000-005");
        }
    }

    private boolean hasLegacyDefaultValidation(BusinessIdentifierDefinition definition) {
        // LOCAL compatibility: older demo definitions used TEXT + UPPER as their canonical
        // behavior. During validation-framework testing an admin may already have entered length
        // or allow-list constraints before the reference NUMERIC rules were applied. Treat that
        // partially configured shape as legacy too. Once upgraded, the reference definitions use
        // NUMERIC + PRESERVE, so later intentional Business Admin changes are not overwritten.
        return definition.validationType() == BusinessIdentifierValidationType.TEXT
                && definition.caseNormalization() == BusinessIdentifierCaseNormalization.UPPER
                && !definition.stripSeparators()
                && definition.customPattern() == null;
    }

    private List<BusinessIdentifierDefinition> referenceDemoIdentifierDefinitions(
            TenantId tenantId, Map<String, BusinessIdentifierDefinition> existing) {
        BusinessIdentifierDefinition contract = existing == null ? null : existing.get("contract_number");
        BusinessIdentifierDefinition member = existing == null ? null : existing.get("member_number");
        BusinessIdentifierDefinition configuredContract = contract == null
                ? BusinessIdentifierDefinition.create(tenantId, "contract_number", "Contract Number",
                        true, true, true, true, true, 1, BusinessIdentifierValidationType.NUMERIC,
                        12, null, null, BusinessIdentifierCaseNormalization.PRESERVE, false, Set.of(), null)
                : contract.reconfigure(contract.label(), contract.required(), true, contract.searchable(),
                        contract.extractable(), true, 1, BusinessIdentifierValidationType.NUMERIC,
                        12, null, null, BusinessIdentifierCaseNormalization.PRESERVE, false, Set.of(), null);
        BusinessIdentifierDefinition configuredMember = member == null
                ? BusinessIdentifierDefinition.create(tenantId, "member_number", "Member Number",
                        true, false, true, true, true, 2, BusinessIdentifierValidationType.NUMERIC,
                        11, null, null, BusinessIdentifierCaseNormalization.PRESERVE, false, Set.of(), null)
                : member.reconfigure(member.label(), member.required(), false, member.searchable(),
                        member.extractable(), member.displayable(), 2, BusinessIdentifierValidationType.NUMERIC,
                        11, null, null, BusinessIdentifierCaseNormalization.PRESERVE, false, Set.of(), null);
        BusinessIdentifierDefinition existingServiceEntity = existing == null ? null : existing.get("service_entity");
        BusinessIdentifierDefinition serviceEntity = existingServiceEntity == null
                ? BusinessIdentifierDefinition.create(tenantId,
                        "service_entity", "Service Entity", true, false, true, true, true, 3,
                        BusinessIdentifierValidationType.NUMERIC, 3, null, null,
                        BusinessIdentifierCaseNormalization.PRESERVE, false,
                        Set.of("000", "001", "002", "003", "004", "005"), null)
                : existingServiceEntity.reconfigure(existingServiceEntity.label(), existingServiceEntity.required(), false,
                        existingServiceEntity.searchable(), existingServiceEntity.extractable(), existingServiceEntity.displayable(), 3,
                        BusinessIdentifierValidationType.NUMERIC, 3, null, null,
                        BusinessIdentifierCaseNormalization.PRESERVE, false,
                        Set.of("000", "001", "002", "003", "004", "005"), null);
        return List.of(configuredContract, configuredMember, serviceEntity);
    }
    private void ensureDemoScannerIntegration() {
        TenantId tenantId = TenantId.of(demoTenantId);
        String requiredPrincipal = scannerClientPrincipal == null ? "" : scannerClientPrincipal.trim();
        if (requiredPrincipal.isEmpty()) {
            log.warn("LOCAL Scanner Bridge integration was not bootstrapped because scanner client principal is blank");
            return;
        }

        if (integrationConnectionRepository
                .findEnabledScannerSourceByTenantIdAndClientPrincipal(tenantId, requiredPrincipal)
                .isPresent()) {
            return;
        }

        List<IntegrationConnection> existingConnections = integrationConnectionRepository.findByTenantId(tenantId);
        boolean principalAlreadyBound = existingConnections.stream()
                .anyMatch(c -> requiredPrincipal.equals(c.clientPrincipal()));
        if (principalAlreadyBound) {
            // Do not silently rewrite Business/Tenant-IT integration configuration. The LOCAL bootstrap
            // only creates the reference source when the configured principal is currently unbound.
            log.warn("LOCAL Scanner Bridge client principal is already bound, but not to an enabled SCANNER source: clientPrincipal={}",
                    requiredPrincipal);
            return;
        }

        IntegrationConnection scanner = IntegrationConnection.create(
                tenantId,
                "Local Scanner Bridge",
                "LOCAL/demo scanner source used by the workstation Scanner Bridge",
                IntegrationPurpose.INGESTION_SOURCE,
                IntegrationDirection.INBOUND,
                IntegrationProviderType.SCANNER,
                IntegrationAuthenticationType.NONE,
                requiredPrincipal,
                null,
                null,
                null,
                60,
                2,
                24,
                1,
                2L * 1024L * 1024L * 1024L,
                10_000,
                IntegrationAcknowledgementMode.NONE,
                true,
                Instant.now());
        integrationConnectionRepository.save(scanner);
        log.info("Created LOCAL Scanner Bridge integration source: clientPrincipal={}", requiredPrincipal);
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

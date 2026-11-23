package com.securedoc.install;

import com.securedoc.platform.application.port.out.BusinessIdentifierDefinitionRepository;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.domain.document.CaseLimits;
import com.securedoc.platform.domain.business.BusinessIdentifierCaseNormalization;
import com.securedoc.platform.domain.business.BusinessIdentifierDefinition;
import com.securedoc.platform.domain.business.BusinessIdentifierValidationType;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.identity.TenantId;
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
import org.springframework.transaction.support.TransactionTemplate;

import java.io.PrintStream;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The LexaVault-only demo records of the demo step (task 0037): what {@code LocalDemoBootstrap} inserted at startup
 * until then, now written once, by a command, after setup. For the demo entity:
 * <ol>
 *   <li>LexaVault's own entity row ({@code tenants}, id = the entity UUID derived from the slot ID), ACTIVE;</li>
 *   <li>7 capabilities: DOCUMENT_UPLOAD, OCR_PROCESSING, SENSITIVE_DATA_DETECTION, REDACTION, HUMAN_REVIEW, VAULT,
 *       AUDIT;</li>
 *   <li>3 business identifier rules: Contract Number (primary, 12 digits), Member Number (11 digits), Service Entity
 *       (3 digits, 000 to 005);</li>
 *   <li>the default sensitive-data policy ({@link TenantRedactionPolicy#defaults});</li>
 *   <li>the "Local Scanner Bridge" ingestion source for the workstation Scanner Bridge.</li>
 * </ol>
 * Each item is created only when missing, and nothing that exists is changed, so a rerun changes nothing and a Business
 * Admin's own changes are kept. (The bootstrap's one-time upgrades of older demo rows are not carried over: they
 * repaired rows of the fixed demo tenant {@code 0000…0001}, which the derived demo entity never had.)
 */
final class DemoAppRecords {

    static final List<Capability> CAPABILITIES = List.of(Capability.DOCUMENT_UPLOAD, Capability.OCR_PROCESSING,
            Capability.SENSITIVE_DATA_DETECTION, Capability.REDACTION, Capability.HUMAN_REVIEW, Capability.VAULT,
            Capability.AUDIT);

    private final SpringDataTenantRepository tenants;
    private final SpringDataTenantCapabilityRepository capabilities;
    private final BusinessIdentifierDefinitionRepository identifiers;
    private final TenantRedactionPolicyRepository policies;
    private final IntegrationConnectionRepository integrations;
    private final TransactionTemplate tx;
    private final PrintStream out;

    DemoAppRecords(SpringDataTenantRepository tenants, SpringDataTenantCapabilityRepository capabilities,
                   BusinessIdentifierDefinitionRepository identifiers, TenantRedactionPolicyRepository policies,
                   IntegrationConnectionRepository integrations, TransactionTemplate tx, PrintStream out) {
        this.tenants = tenants;
        this.capabilities = capabilities;
        this.identifiers = identifiers;
        this.policies = policies;
        this.integrations = integrations;
        this.tx = tx;
        this.out = out;
    }

    /** Writes what is missing, in one transaction. */
    void ensure(UUID entity, String name, String slug, String scannerClientPrincipal) {
        tx.executeWithoutResult(status -> {
            TenantId tenantId = TenantId.of(entity);
            Instant now = Instant.now();
            if (tenants.findById(entity).isEmpty()) {
                TenantEntity tenant = new TenantEntity();
                tenant.setId(entity);
                tenant.setName(name);
                tenant.setSlug(slug);
                tenant.setStatus("ACTIVE");
                tenant.setCreatedAt(now);
                tenant.setUpdatedAt(now);
                tenants.save(tenant);
                for (Capability cap : CAPABILITIES) {
                    TenantCapabilityEntity row = new TenantCapabilityEntity();
                    row.setId(UUID.randomUUID());
                    row.setTenantId(entity);
                    row.setCapability(cap.name());
                    row.setStatus(CapabilityStatus.ENABLED.name());
                    row.setCreatedAt(now);
                    row.setUpdatedAt(now);
                    capabilities.save(row);
                }
                line("create", "entity row " + entity + " \"" + name + "\" with " + CAPABILITIES.size() + " capabilities");
            } else {
                line("unchanged", "entity row " + entity);
            }

            if (identifiers.findActiveByTenantId(tenantId).isEmpty()) {
                identifiers.saveAll(referenceIdentifiers(tenantId));
                line("create", "identifier rules: Contract Number (primary), Member Number, Service Entity");
            } else {
                line("unchanged", "identifier rules");
            }

            if (policies.findByTenantId(tenantId).isEmpty()) {
                policies.save(TenantRedactionPolicy.defaults(tenantId));
                line("create", "sensitive-data policy (defaults)");
            } else {
                line("unchanged", "sensitive-data policy");
            }

            String principal = scannerClientPrincipal == null ? "" : scannerClientPrincipal.trim();
            if (principal.isEmpty()) {
                line("skipped", "scanner source: no scanner client principal given");
            } else if (integrations.findEnabledScannerSourceByTenantIdAndClientPrincipal(tenantId, principal).isPresent()) {
                line("unchanged", "scanner source \"Local Scanner Bridge\"");
            } else if (integrations.findByTenantId(tenantId).stream().anyMatch(c -> principal.equals(c.clientPrincipal()))) {
                // Never rewrite an admin's integration: the principal is bound to something else.
                line("skipped", "scanner source: its client principal is already bound to another integration");
            } else {
                integrations.save(IntegrationConnection.create(tenantId, "Local Scanner Bridge",
                        "LOCAL/demo scanner source used by the workstation Scanner Bridge",
                        IntegrationPurpose.INGESTION_SOURCE, IntegrationDirection.INBOUND, IntegrationProviderType.SCANNER,
                        IntegrationAuthenticationType.NONE, principal, null, null, null, 60, 2, 24, 1,
                        2L * 1024L * 1024L * 1024L, CaseLimits.MAX_PAGES_PER_FILE, IntegrationAcknowledgementMode.NONE, true, Instant.now()));
                line("create", "scanner source \"Local Scanner Bridge\"");
            }
        });
    }

    static List<BusinessIdentifierDefinition> referenceIdentifiers(TenantId tenantId) {
        return List.of(
                BusinessIdentifierDefinition.create(tenantId, "contract_number", "Contract Number",
                        true, true, true, true, true, 1, BusinessIdentifierValidationType.NUMERIC,
                        12, null, null, BusinessIdentifierCaseNormalization.PRESERVE, false, Set.of(), null),
                BusinessIdentifierDefinition.create(tenantId, "member_number", "Member Number",
                        true, false, true, true, true, 2, BusinessIdentifierValidationType.NUMERIC,
                        11, null, null, BusinessIdentifierCaseNormalization.PRESERVE, false, Set.of(), null),
                BusinessIdentifierDefinition.create(tenantId, "service_entity", "Service Entity",
                        true, false, true, true, true, 3, BusinessIdentifierValidationType.NUMERIC,
                        3, null, null, BusinessIdentifierCaseNormalization.PRESERVE, false,
                        Set.of("000", "001", "002", "003", "004", "005"), null));
    }

    private void line(String action, String what) {
        out.println("  " + String.format("%-11s", action) + " " + what);
    }
}

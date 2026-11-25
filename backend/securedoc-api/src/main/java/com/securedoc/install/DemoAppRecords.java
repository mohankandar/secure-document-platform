package com.securedoc.install;

import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldScope;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.SearchMode;
import com.securedoc.platform.application.casefields.CaseFieldService;
import com.securedoc.platform.application.casefields.CaseFieldSettings;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.domain.document.CaseLimits;
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
    private final FieldDefinitions identifiers;
    private final TenantRedactionPolicyRepository policies;
    private final IntegrationConnectionRepository integrations;
    private final TransactionTemplate tx;
    private final PrintStream out;

    DemoAppRecords(SpringDataTenantRepository tenants, SpringDataTenantCapabilityRepository capabilities,
                   FieldDefinitions identifiers, TenantRedactionPolicyRepository policies,
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
                // Flushed: what follows is partly written over JDBC and points to it.
                tenants.saveAndFlush(tenant);
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

            FieldScope scope = CaseFieldService.scope(tenantId);
            if (identifiers.list(scope).isEmpty()) {
                for (FieldSpec field : referenceFields()) {
                    identifiers.create(scope, field, "demo-step");
                    identifiers.putExtension(scope, field.key(), CaseFieldSettings.EXTRACTABLE, "true");
                }
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

    /** Digits with their leading zeros; a part of a number finds its cases. */
    static List<FieldSpec> referenceFields() {
        return List.of(
                FieldSpec.builder("contract_number", "Contract Number", FieldType.NUMBER).pattern("[0-9]+").exactLength(12)
                        .required(true).primary(true).searchable(SearchMode.PARTIAL).displayable(true).displayOrder(1).build(),
                FieldSpec.builder("member_number", "Member Number", FieldType.NUMBER).pattern("[0-9]+").exactLength(11)
                        .required(true).searchable(SearchMode.PARTIAL).displayable(true).displayOrder(2).build(),
                FieldSpec.builder("service_entity", "Service Entity", FieldType.DROPDOWN)
                        .options("000", "001", "002", "003", "004", "005")
                        .required(true).searchable(SearchMode.EXACT).displayable(true).displayOrder(3).build());
    }

    private void line(String action, String what) {
        out.println("  " + String.format("%-11s", action) + " " + what);
    }
}

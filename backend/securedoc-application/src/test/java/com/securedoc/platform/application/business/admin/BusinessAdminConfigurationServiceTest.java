package com.securedoc.platform.application.business.admin;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.*;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.business.*;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BusinessAdminConfigurationServiceTest {

    @Test
    void legacyAdminPayloadPreservesExistingValidationConfiguration() {
        TenantId tenant = TenantId.generate();
        BusinessIdentifierDefinition contract = numericContract(tenant);
        DefinitionRepo definitions = new DefinitionRepo(List.of(contract));
        BusinessAdminConfigurationService service = service(definitions);

        var result = service.updateIdentifiers(tenant, ActorId.generate(), CorrelationId.generate(), List.of(
                new BusinessAdminConfigurationService.IdentifierConfigurationInput(
                        contract.id().value(), "Contract Number", true, true, true, true, true,
                        null, null, null, null, null, null, null, null)
        ));

        assertEquals(1, result.size());
        BusinessIdentifierDefinition saved = result.getFirst();
        assertEquals(BusinessIdentifierValidationType.NUMERIC, saved.validationType());
        assertEquals(12, saved.exactLength());
        assertEquals(BusinessIdentifierCaseNormalization.PRESERVE, saved.caseNormalization());
    }

    @Test
    void canonicalizationCannotBeChangedInPlaceForExistingDefinition() {
        TenantId tenant = TenantId.generate();
        BusinessIdentifierDefinition contract = numericContract(tenant);
        DefinitionRepo definitions = new DefinitionRepo(List.of(contract));
        BusinessAdminConfigurationService service = service(definitions);

        ValidationException error = assertThrows(ValidationException.class, () -> service.updateIdentifiers(
                tenant, ActorId.generate(), CorrelationId.generate(), List.of(
                        new BusinessAdminConfigurationService.IdentifierConfigurationInput(
                                contract.id().value(), "Contract Number", true, true, true, true, true,
                                "NUMERIC", 12, null, null, "UPPER", false, Set.of(), null)
                )));

        assertTrue(error.getMessage().contains("Canonical normalization"));
    }


    @Test
    void reportingDimensionsRequireLowCardinalityTenantControlledValues() {
        TenantId tenant = TenantId.generate();
        BusinessIdentifierDefinition contract = numericContract(tenant);
        BusinessIdentifierDefinition serviceEntity = BusinessIdentifierDefinition.create(tenant, "service_entity", "Service Entity",
                false, false, true, false, true, 2, BusinessIdentifierValidationType.NUMERIC,
                3, null, null, BusinessIdentifierCaseNormalization.PRESERVE, false, Set.of("000", "001"), null);
        DefinitionRepo definitions = new DefinitionRepo(List.of(contract, serviceEntity));
        BusinessAdminConfigurationService service = service(definitions);

        var saved = service.updateReportingConfiguration(tenant, ActorId.generate(), CorrelationId.generate(),
                List.of(serviceEntity.id().value()), 240, 95.0, 90.0, 60);
        assertEquals(1, saved.dimensions().size());
        assertEquals("service_entity", saved.dimensions().getFirst().key());
        assertEquals("Service Entity", saved.dimensions().getFirst().label());

        // High-cardinality identifiers such as Contract Number remain ineligible because they do not
        // define a bounded tenant-controlled value set.
        assertThrows(ValidationException.class, () -> service.updateReportingConfiguration(tenant, ActorId.generate(),
                CorrelationId.generate(), List.of(contract.id().value()), 240, 95.0, 90.0, 60));
    }

    private BusinessIdentifierDefinition numericContract(TenantId tenant) {
        return BusinessIdentifierDefinition.create(tenant, "contract_number", "Contract Number",
                true, true, true, true, true, 1, BusinessIdentifierValidationType.NUMERIC,
                12, null, null, BusinessIdentifierCaseNormalization.PRESERVE, false, Set.of(), null);
    }

    private BusinessAdminConfigurationService service(DefinitionRepo definitions) {
        TenantRedactionPolicyRepository policies = new TenantRedactionPolicyRepository() {
            public Optional<TenantRedactionPolicy> findByTenantId(TenantId tenantId) { return Optional.empty(); }
            public void save(TenantRedactionPolicy policy) { }
        };
        TenantLifecyclePolicyRepository lifecycle = new TenantLifecyclePolicyRepository() {
            public Optional<TenantLifecyclePolicy> findByTenantId(TenantId tenantId) { return Optional.empty(); }
            public void save(TenantLifecyclePolicy policy) { }
        };
        ReportingConfigurationRepository reporting = new ReportingConfigurationRepository() {
            private ReportingConfiguration value = ReportingConfiguration.defaults();
            public ReportingConfiguration findByTenantId(TenantId tenantId) { return value; }
            public void save(TenantId tenantId, ReportingConfiguration configuration) { value = configuration; }
        };
        return new BusinessAdminConfigurationService(definitions, policies, lifecycle, reporting, new AuditService(record -> { }));
    }

    private static final class DefinitionRepo implements BusinessIdentifierDefinitionRepository {
        private List<BusinessIdentifierDefinition> values;
        DefinitionRepo(List<BusinessIdentifierDefinition> values) { this.values = new ArrayList<>(values); }
        public List<BusinessIdentifierDefinition> findActiveByTenantId(TenantId tenantId) {
            return values.stream().filter(BusinessIdentifierDefinition::active).toList();
        }
        public List<BusinessIdentifierDefinition> findAllByTenantId(TenantId tenantId) { return List.copyOf(values); }
        public void save(BusinessIdentifierDefinition definition) { values = List.of(definition); }
        public void saveAll(List<BusinessIdentifierDefinition> definitions) { values = new ArrayList<>(definitions); }
    }
}

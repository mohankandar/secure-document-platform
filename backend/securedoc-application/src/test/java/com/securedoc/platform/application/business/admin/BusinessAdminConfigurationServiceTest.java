package com.securedoc.platform.application.business.admin;

import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.Normalisation;
import com.securedoc.platform.application.casefields.CaseFieldSettings;
import com.securedoc.platform.application.casefields.TestCaseFields;
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
        TestCaseFields definitions = new TestCaseFields();
        FieldDefinition contract = numericContract(definitions, tenant);
        BusinessAdminConfigurationService service = service(definitions);

        var result = service.updateIdentifiers(tenant, ActorId.generate(), CorrelationId.generate(), List.of(
                new CaseFieldSettings.Input(
                        contract.id(), "Contract Number", true, true, true, true, true,
                        null, null, null, null, null, null, null, null)
        ));

        assertEquals(1, result.size());
        FieldDefinition saved = result.getFirst();
        assertEquals(FieldType.NUMBER, saved.type());
        assertEquals("[0-9]+", saved.spec().pattern());
        assertEquals(12, saved.spec().exactLength());
        assertEquals(Normalisation.LetterCase.PRESERVE, saved.spec().normalisation().letterCase());
    }

    @Test
    void canonicalizationCannotBeChangedInPlaceForExistingDefinition() {
        TenantId tenant = TenantId.generate();
        TestCaseFields definitions = new TestCaseFields();
        FieldDefinition contract = numericContract(definitions, tenant);
        BusinessAdminConfigurationService service = service(definitions);

        ValidationException error = assertThrows(ValidationException.class, () -> service.updateIdentifiers(
                tenant, ActorId.generate(), CorrelationId.generate(), List.of(
                        new CaseFieldSettings.Input(
                                contract.id(), "Contract Number", true, true, true, true, true,
                                "NUMBER", 12, null, null, "UPPER", false, Set.of(), "[0-9]+")
                )));

        assertTrue(error.getMessage().contains("Canonical normalization"));
    }

    @Test
    void anInvalidPatternIsNamedWithoutQuotingThePatternCompiler() {
        // Task 0023 B7 (#286): only LexaVault's own text follows "Invalid validation configuration for <label>:".
        TenantId tenant = TenantId.generate();
        TestCaseFields definitions = new TestCaseFields();
        FieldDefinition contract = numericContract(definitions, tenant);
        BusinessAdminConfigurationService service = service(definitions);

        ValidationException error = assertThrows(ValidationException.class, () -> service.updateIdentifiers(
                tenant, ActorId.generate(), CorrelationId.generate(), List.of(
                        new CaseFieldSettings.Input(
                                contract.id(), "Contract Number", true, true, true, true, true,
                                null, null, null, null, null, null, null, null),
                        new CaseFieldSettings.Input(
                                null, "Branch", false, false, true, true, true,
                                "CUSTOM_PATTERN", null, null, null, "UPPER", false, Set.of(), "(unclosed[secret"))));

        assertEquals("Invalid validation configuration for Branch: the pattern is not a valid regular expression",
                error.getMessage());
        assertFalse(error.getMessage().contains("unclosed"));
    }


    @Test
    void reportingDimensionsRequireLowCardinalityTenantControlledValues() {
        TenantId tenant = TenantId.generate();
        TestCaseFields definitions = new TestCaseFields();
        FieldDefinition contract = numericContract(definitions, tenant);
        FieldDefinition serviceEntity = definitions.add(tenant, FieldSpec.builder("service_entity", "Service Entity",
                FieldType.DROPDOWN).options("000", "001").displayOrder(2));
        BusinessAdminConfigurationService service = service(definitions);

        var saved = service.updateReportingConfiguration(tenant, ActorId.generate(), CorrelationId.generate(),
                List.of(serviceEntity.id()), 240, 95.0, 90.0, 60, 80, null);
        assertEquals(1, saved.dimensions().size());
        assertEquals("service_entity", saved.dimensions().getFirst().key());
        assertEquals("Service Entity", saved.dimensions().getFirst().label());

        // High-cardinality identifiers such as Contract Number remain ineligible because they do not
        // define a bounded tenant-controlled value set.
        assertThrows(ValidationException.class, () -> service.updateReportingConfiguration(tenant, ActorId.generate(),
                CorrelationId.generate(), List.of(contract.id()), 240, 95.0, 90.0, 60, 80, null));
    }

    @Test
    void reviewPickupTargetPercentDefaultsTo80AndIsSaved() {
        TenantId tenant = TenantId.generate();
        TestCaseFields definitions = new TestCaseFields();
        numericContract(definitions, tenant);
        BusinessAdminConfigurationService service = service(definitions);
        assertEquals(80, ReportingConfiguration.defaults().reviewWaitTargetPercent());

        var saved = service.updateReportingConfiguration(tenant, ActorId.generate(), CorrelationId.generate(),
                List.of(), 240, 95.0, 70.0, 60, 85, null);
        assertEquals(85, saved.reviewWaitTargetPercent());
        assertEquals(85, service.getConfiguration(tenant).reportingConfiguration().reviewWaitTargetPercent());

        // 0 and 100 are allowed.
        assertEquals(0, service.updateReportingConfiguration(tenant, ActorId.generate(), CorrelationId.generate(),
                List.of(), 240, 95.0, 70.0, 60, 0, null).reviewWaitTargetPercent());
        assertEquals(100, service.updateReportingConfiguration(tenant, ActorId.generate(), CorrelationId.generate(),
                List.of(), 240, 95.0, 70.0, 60, 100, null).reviewWaitTargetPercent());
    }

    @Test
    void reviewPickupTargetPercentOutside0To100IsRefusedAndNothingIsSaved() {
        TenantId tenant = TenantId.generate();
        TestCaseFields definitions = new TestCaseFields();
        numericContract(definitions, tenant);
        BusinessAdminConfigurationService service = service(definitions);
        service.updateReportingConfiguration(tenant, ActorId.generate(), CorrelationId.generate(), List.of(), 240, 95.0, 70.0, 60, 85, null);

        for (int bad : new int[]{-1, 101}) {
            ValidationException error = assertThrows(ValidationException.class, () -> service.updateReportingConfiguration(tenant,
                    ActorId.generate(), CorrelationId.generate(), List.of(), 240, 95.0, 70.0, 60, bad, null));
            assertTrue(error.getMessage().contains("reviewWaitTargetPercent"));
        }
        assertEquals(85, service.getConfiguration(tenant).reportingConfiguration().reviewWaitTargetPercent());
    }

    @Test
    void aCallerThatLeavesOutTheReviewPercentKeepsTheStoredValue() {
        TenantId tenant = TenantId.generate();
        TestCaseFields definitions = new TestCaseFields();
        numericContract(definitions, tenant);
        BusinessAdminConfigurationService service = service(definitions);
        service.updateReportingConfiguration(tenant, ActorId.generate(), CorrelationId.generate(), List.of(), 240, 95.0, 70.0, 60, 65, null);

        var saved = service.updateReportingConfiguration(tenant, ActorId.generate(), CorrelationId.generate(),
                List.of(), 120, 95.0, 70.0, 30, null, null);
        assertEquals(65, saved.reviewWaitTargetPercent());
        assertEquals(30, saved.reviewWaitTargetMinutes());
        assertEquals(120, saved.deliveryTargetMinutes());
    }

    private FieldDefinition numericContract(TestCaseFields definitions, TenantId tenant) {
        return definitions.add(tenant, FieldSpec.builder("contract_number", "Contract Number", FieldType.NUMBER)
                .pattern("[0-9]+").exactLength(12).required(true).primary(true).searchable(true).displayOrder(1));
    }

    private BusinessAdminConfigurationService service(TestCaseFields definitions) {
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
        return new BusinessAdminConfigurationService(definitions.settings(), policies, lifecycle, reporting, new AuditService(record -> { }));
    }

}

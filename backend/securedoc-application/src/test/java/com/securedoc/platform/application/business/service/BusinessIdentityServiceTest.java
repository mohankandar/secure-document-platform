package com.securedoc.platform.application.business.service;

import com.securedoc.platform.application.exception.IntakeValidationException;
import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import com.securedoc.platform.application.port.out.BusinessIdentifierDefinitionRepository;
import com.securedoc.platform.application.port.out.BusinessRecordRepository;
import com.securedoc.platform.domain.business.BusinessIdentifier;
import com.securedoc.platform.domain.business.BusinessIdentifierDefinition;
import com.securedoc.platform.domain.business.BusinessRecord;
import com.securedoc.platform.domain.identity.BusinessIdentifierDefinitionId;
import com.securedoc.platform.domain.identity.BusinessRecordId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class BusinessIdentityServiceTest {

    @Test
    void newPrimaryCreatesNewRecordWhenSecondaryIdentifiersRepeat() {
        Fixture fixture = new Fixture();
        fixture.seed("C-100", "M-200", "000");

        var result = fixture.service.resolveOrCreate(fixture.tenant,
                Map.of("contract_number", "C-101", "member_number", "M-200", "service_entity", "000"));

        assertTrue(result.createdNew());
        assertEquals(2, fixture.records.values.size());
        assertEquals("C-101", value(result, "contract_number"));
        assertEquals("M-200", value(result, "member_number"));
        assertEquals("000", value(result, "service_entity"));
    }

    @Test
    void samePrimaryReusesExistingBusinessRecordWhenSecondaryIdentifiersMatch() {
        Fixture fixture = new Fixture();
        BusinessRecord existing = fixture.seed("C-100", "M-200", "000");

        var result = fixture.service.resolveOrCreate(fixture.tenant,
                Map.of("contract_number", "C-100", "member_number", "M-200", "service_entity", "000"));

        assertFalse(result.createdNew());
        assertEquals(existing.id(), result.businessRecordId());
        assertEquals(1, fixture.records.values.size());
    }

    @Test
    void samePrimaryRejectsConflictingSecondaryIdentifier() {
        Fixture fixture = new Fixture();
        fixture.seed("C-100", "M-200", "000");

        IntakeValidationException error = assertThrows(IntakeValidationException.class,
                () -> fixture.service.resolveOrCreate(fixture.tenant,
                        Map.of("contract_number", "C-100", "member_number", "M-DIFFERENT", "service_entity", "000")));

        assertEquals(IntakeRejectionCode.IDENTIFIER_CONFLICT, error.rejectionCode());
        assertTrue(error.getMessage().contains("Member Number"));
    }

    private static String value(com.securedoc.platform.application.business.model.BusinessIdentitySnapshot snapshot,
                                String key) {
        return snapshot.identifiers().stream().filter(i -> i.key().equals(key)).findFirst().orElseThrow().value();
    }

    private static final class Fixture {
        final TenantId tenant = TenantId.generate();
        final BusinessIdentifierDefinition contract = BusinessIdentifierDefinition.create(
                tenant, "contract_number", "Contract Number", true, true, true, false, true, 1);
        final BusinessIdentifierDefinition member = BusinessIdentifierDefinition.create(
                tenant, "member_number", "Member Number", true, false, true, false, true, 2);
        final BusinessIdentifierDefinition serviceEntity = BusinessIdentifierDefinition.create(
                tenant, "service_entity", "Service Entity", true, false, true, false, true, 3);
        final FakeDefinitionRepository definitions = new FakeDefinitionRepository(List.of(contract, member, serviceEntity));
        final FakeRecordRepository records = new FakeRecordRepository();
        final BusinessIdentityService service = new BusinessIdentityService(definitions, records);

        BusinessRecord seed(String contractValue, String memberValue, String serviceValue) {
            BusinessRecord record = BusinessRecord.create(tenant, List.of(
                    BusinessIdentifier.from(contract, contractValue),
                    BusinessIdentifier.from(member, memberValue),
                    BusinessIdentifier.from(serviceEntity, serviceValue)));
            records.save(record);
            return record;
        }
    }

    private static final class FakeDefinitionRepository implements BusinessIdentifierDefinitionRepository {
        private final List<BusinessIdentifierDefinition> values;
        FakeDefinitionRepository(List<BusinessIdentifierDefinition> values) { this.values = new ArrayList<>(values); }
        @Override public List<BusinessIdentifierDefinition> findActiveByTenantId(TenantId tenantId) {
            return values.stream().filter(v -> v.tenantId().equals(tenantId) && v.active()).toList();
        }
        @Override public List<BusinessIdentifierDefinition> findAllByTenantId(TenantId tenantId) {
            return values.stream().filter(v -> v.tenantId().equals(tenantId)).toList();
        }
        @Override public void save(BusinessIdentifierDefinition definition) { }
        @Override public void saveAll(List<BusinessIdentifierDefinition> definitions) { }
    }

    private static final class FakeRecordRepository implements BusinessRecordRepository {
        final List<BusinessRecord> values = new ArrayList<>();
        @Override public void save(BusinessRecord record) {
            values.removeIf(v -> v.id().equals(record.id()));
            values.add(record);
        }
        @Override public Optional<BusinessRecord> findByIdAndTenantId(BusinessRecordId id, TenantId tenantId) {
            return values.stream().filter(v -> v.id().equals(id) && v.tenantId().equals(tenantId)).findFirst();
        }
        @Override public Optional<BusinessRecord> findByPrimaryIdentifier(TenantId tenantId,
                                                                   BusinessIdentifierDefinitionId definitionId,
                                                                   String normalizedValue) {
            return values.stream()
                    .filter(v -> v.tenantId().equals(tenantId))
                    .filter(v -> v.identifiers().stream().anyMatch(i -> i.definitionId().equals(definitionId)
                            && i.normalizedValue().equals(normalizedValue)))
                    .findFirst();
        }
    }
}

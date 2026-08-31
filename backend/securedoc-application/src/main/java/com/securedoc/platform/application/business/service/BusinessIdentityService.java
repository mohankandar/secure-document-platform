package com.securedoc.platform.application.business.service;

import com.securedoc.platform.application.business.model.BusinessIdentitySnapshot;
import com.securedoc.platform.application.exception.IntakeValidationException;
import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import com.securedoc.platform.application.port.out.BusinessIdentifierDefinitionRepository;
import com.securedoc.platform.application.port.out.BusinessRecordRepository;
import com.securedoc.platform.domain.business.*;
import com.securedoc.platform.domain.identity.BusinessIdentifierDefinitionId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolves required tenant-configured identifiers to one long-lived BusinessRecord. */
public final class BusinessIdentityService {
    private final BusinessIdentifierDefinitionRepository definitionRepository;
    private final BusinessRecordRepository recordRepository;

    public BusinessIdentityService(BusinessIdentifierDefinitionRepository definitionRepository,
                                   BusinessRecordRepository recordRepository) {
        this.definitionRepository = Objects.requireNonNull(definitionRepository);
        this.recordRepository = Objects.requireNonNull(recordRepository);
    }

    public BusinessIdentitySnapshot resolveOrCreate(TenantId tenantId, Map<String,String> supplied) {
        List<BusinessIdentifierDefinition> definitions = definitionRepository.findActiveByTenantId(tenantId);
        validateConfiguration(definitions);
        Map<String,String> input = normalizeInput(supplied);
        Map<String,BusinessIdentifierDefinition> byKey = definitions.stream()
                .collect(Collectors.toMap(BusinessIdentifierDefinition::key, Function.identity()));

        for (String key : input.keySet()) {
            if (!byKey.containsKey(key)) {
                throw new IntakeValidationException(IntakeRejectionCode.UNKNOWN_IDENTIFIER,
                        "Unknown business identifier: " + key);
            }
        }
        for (BusinessIdentifierDefinition definition : definitions) {
            if (definition.required() && !input.containsKey(definition.key())) {
                throw new IntakeValidationException(IntakeRejectionCode.MISSING_REQUIRED_IDENTIFIER,
                        "Missing required business identifier: " + definition.label());
            }
        }
        if (input.isEmpty()) {
            throw new IntakeValidationException(IntakeRejectionCode.MISSING_REQUIRED_IDENTIFIER,
                    "At least one business identifier is required");
        }

        List<BusinessIdentifier> requested = input.entrySet().stream()
                .map(e -> BusinessIdentifier.from(byKey.get(e.getKey()), e.getValue()))
                .sorted(Comparator.comparingInt(BusinessIdentifier::displayOrder))
                .toList();

        Map<com.securedoc.platform.domain.identity.BusinessRecordId, BusinessRecord> matches = new LinkedHashMap<>();
        for (BusinessIdentifier identifier : requested) {
            recordRepository.findByIdentifier(tenantId, identifier.definitionId(), identifier.normalizedValue())
                    .ifPresent(record -> matches.put(record.id(), record));
        }
        if (matches.size() > 1) {
            throw new IntakeValidationException(IntakeRejectionCode.IDENTIFIER_CONFLICT,
                    "Provided identifiers resolve to different business records");
        }

        BusinessRecord record;
        boolean createdNew = matches.isEmpty();
        if (createdNew) {
            record = BusinessRecord.create(tenantId, requested);
        } else {
            record = matches.values().iterator().next();
            Map<BusinessIdentifierDefinitionId,BusinessIdentifier> existing = record.identifiers().stream()
                    .collect(Collectors.toMap(BusinessIdentifier::definitionId, Function.identity()));
            for (BusinessIdentifier identifier : requested) {
                BusinessIdentifier current = existing.get(identifier.definitionId());
                if (current == null) {
                    record.addIdentifier(identifier);
                } else if (!current.normalizedValue().equals(identifier.normalizedValue())) {
                    throw new IntakeValidationException(IntakeRejectionCode.IDENTIFIER_CONFLICT,
                            "Business identifier conflicts with the existing record: " + identifier.label());
                }
            }
        }
        recordRepository.save(record);
        return snapshot(record, createdNew);
    }

    public BusinessIdentitySnapshot get(com.securedoc.platform.domain.identity.BusinessRecordId id, TenantId tenantId) {
        BusinessRecord record = recordRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new com.securedoc.platform.application.exception.NotFoundException("BusinessRecord", id.toString()));
        return snapshot(record, false);
    }

    public List<BusinessIdentifierDefinition> definitions(TenantId tenantId) {
        List<BusinessIdentifierDefinition> definitions = definitionRepository.findActiveByTenantId(tenantId);
        validateConfiguration(definitions);
        return definitions;
    }

    private void validateConfiguration(List<BusinessIdentifierDefinition> definitions) {
        if (definitions.isEmpty()) {
            throw new IntakeValidationException(IntakeRejectionCode.IDENTIFIER_CONFIGURATION_MISSING,
                    "Business identifier configuration is required before intake");
        }
        long primaryCount = definitions.stream().filter(BusinessIdentifierDefinition::primary).count();
        if (primaryCount != 1) {
            throw new IntakeValidationException(IntakeRejectionCode.IDENTIFIER_CONFIGURATION_INVALID,
                    "Exactly one active business identifier must be Primary");
        }
    }

    private Map<String,String> normalizeInput(Map<String,String> supplied) {
        if (supplied == null) return Map.of();
        Map<String,String> normalized = new LinkedHashMap<>();
        supplied.forEach((k,v) -> {
            if (k == null || v == null || v.isBlank()) return;
            normalized.put(BusinessIdentifierDefinition.normalizeKey(k), v.trim());
        });
        return Map.copyOf(normalized);
    }

    private BusinessIdentitySnapshot snapshot(BusinessRecord record, boolean createdNew) {
        return new BusinessIdentitySnapshot(record.id(), createdNew, record.identifiers().stream()
                .map(i -> new BusinessIdentitySnapshot.IdentifierValue(i.key(), i.label(), i.value(), i.primary(),
                        i.searchable(), i.displayable(), i.displayOrder()))
                .toList());
    }
}

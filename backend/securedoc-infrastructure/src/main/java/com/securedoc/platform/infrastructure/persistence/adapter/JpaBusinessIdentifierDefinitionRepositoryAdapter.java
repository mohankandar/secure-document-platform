package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.BusinessIdentifierDefinitionRepository;
import com.securedoc.platform.domain.business.BusinessIdentifierDefinition;
import com.securedoc.platform.domain.business.BusinessIdentifierValidationType;
import com.securedoc.platform.domain.business.BusinessIdentifierCaseNormalization;
import com.securedoc.platform.domain.identity.BusinessIdentifierDefinitionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.entity.BusinessIdentifierDefinitionEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataBusinessIdentifierDefinitionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class JpaBusinessIdentifierDefinitionRepositoryAdapter implements BusinessIdentifierDefinitionRepository {
    private final SpringDataBusinessIdentifierDefinitionRepository repo;
    public JpaBusinessIdentifierDefinitionRepositoryAdapter(SpringDataBusinessIdentifierDefinitionRepository repo){this.repo=repo;}

    @Override public List<BusinessIdentifierDefinition> findActiveByTenantId(TenantId tenantId){
        return repo.findByTenantIdAndActiveTrueOrderByDisplayOrderAsc(tenantId.value()).stream().map(this::toDomain).toList();
    }

    @Override public List<BusinessIdentifierDefinition> findAllByTenantId(TenantId tenantId) {
        return repo.findByTenantIdOrderByDisplayOrderAsc(tenantId.value()).stream().map(this::toDomain).toList();
    }

    @Override public void save(BusinessIdentifierDefinition d){
        var existing = repo.findByIdAndTenantId(d.id().value(), d.tenantId().value());
        if (existing.isEmpty() && repo.existsById(d.id().value())) {
            throw new IllegalStateException("Identifier definition is not owned by the current tenant");
        }
        BusinessIdentifierDefinitionEntity e = existing.orElseGet(BusinessIdentifierDefinitionEntity::new);
        apply(e, d);
        repo.save(e);
    }

    /**
     * Replaces the tenant configuration transactionally. Existing primary flags are cleared and
     * flushed before the final state is applied so switching Primary identifiers cannot collide
     * with the PostgreSQL partial unique index midway through the update.
     */
    @Override
    @Transactional
    public void saveAll(List<BusinessIdentifierDefinition> definitions) {
        if (definitions == null || definitions.isEmpty()) return;
        UUID tenantId = definitions.get(0).tenantId().value();
        Map<UUID, BusinessIdentifierDefinitionEntity> existing = repo.findByTenantIdOrderByDisplayOrderAsc(tenantId)
                .stream().collect(Collectors.toMap(BusinessIdentifierDefinitionEntity::getId, Function.identity()));

        List<BusinessIdentifierDefinitionEntity> clearPrimary = new ArrayList<>(existing.values());
        clearPrimary.forEach(entity -> entity.setPrimary(false));
        if (!clearPrimary.isEmpty()) {
            repo.saveAll(clearPrimary);
            repo.flush();
        }

        List<BusinessIdentifierDefinitionEntity> finalEntities = new ArrayList<>();
        for (BusinessIdentifierDefinition definition : definitions) {
            if (!definition.tenantId().value().equals(tenantId)) {
                throw new IllegalArgumentException("All identifier definitions must belong to the same tenant");
            }
            BusinessIdentifierDefinitionEntity entity = existing.get(definition.id().value());
            if (entity == null) {
                if (repo.existsById(definition.id().value())) {
                    throw new IllegalStateException("Identifier definition is not owned by the current tenant");
                }
                entity = new BusinessIdentifierDefinitionEntity();
            }
            apply(entity, definition);
            finalEntities.add(entity);
        }
        repo.saveAll(finalEntities);
        repo.flush();
    }

    private void apply(BusinessIdentifierDefinitionEntity e, BusinessIdentifierDefinition d) {
        e.setId(d.id().value()); e.setTenantId(d.tenantId().value()); e.setIdentifierKey(d.key()); e.setLabel(d.label());
        e.setRequired(d.required()); e.setPrimary(d.primary()); e.setSearchable(d.searchable()); e.setExtractable(d.extractable());
        e.setDisplayable(d.displayable()); e.setDisplayOrder(d.displayOrder()); e.setActive(d.active());
        e.setValidationType(d.validationType().name()); e.setExactLength(d.exactLength()); e.setMinLength(d.minLength()); e.setMaxLength(d.maxLength());
        e.setCaseNormalization(d.caseNormalization().name()); e.setStripSeparators(d.stripSeparators());
        e.setAllowedValues(encodeAllowedValues(d.allowedValues())); e.setCustomPattern(d.customPattern());
        e.setCreatedAt(d.createdAt()); e.setUpdatedAt(d.updatedAt());
    }

    private BusinessIdentifierDefinition toDomain(BusinessIdentifierDefinitionEntity e){
        return new BusinessIdentifierDefinition(BusinessIdentifierDefinitionId.of(e.getId()), TenantId.of(e.getTenantId()),
                e.getIdentifierKey(), e.getLabel(), e.isRequired(), e.isPrimary(), e.isSearchable(), e.isExtractable(),
                e.isDisplayable(), e.getDisplayOrder(), e.isActive(),
                parseValidationType(e.getValidationType()), e.getExactLength(), e.getMinLength(), e.getMaxLength(),
                parseCaseNormalization(e.getCaseNormalization()), e.isStripSeparators(), decodeAllowedValues(e.getAllowedValues()),
                e.getCustomPattern(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private BusinessIdentifierValidationType parseValidationType(String value) {
        return value == null || value.isBlank() ? BusinessIdentifierValidationType.TEXT : BusinessIdentifierValidationType.valueOf(value);
    }

    private BusinessIdentifierCaseNormalization parseCaseNormalization(String value) {
        return value == null || value.isBlank() ? BusinessIdentifierCaseNormalization.UPPER : BusinessIdentifierCaseNormalization.valueOf(value);
    }

    private String encodeAllowedValues(Set<String> values) {
        if (values == null || values.isEmpty()) return null;
        return String.join("\n", values);
    }

    private Set<String> decodeAllowedValues(String value) {
        if (value == null || value.isBlank()) return Set.of();
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (String item : value.split("\n", -1)) {
            if (!item.isBlank()) values.add(item);
        }
        return Set.copyOf(values);
    }
}

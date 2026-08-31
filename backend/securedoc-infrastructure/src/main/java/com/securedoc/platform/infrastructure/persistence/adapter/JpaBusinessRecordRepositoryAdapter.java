package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.BusinessRecordRepository;
import com.securedoc.platform.domain.business.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.infrastructure.persistence.entity.*;
import com.securedoc.platform.infrastructure.persistence.repository.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

@Component
public class JpaBusinessRecordRepositoryAdapter implements BusinessRecordRepository {
    private final SpringDataBusinessRecordRepository records;
    private final SpringDataBusinessRecordIdentifierRepository identifiers;
    private final SpringDataBusinessIdentifierDefinitionRepository definitions;
    public JpaBusinessRecordRepositoryAdapter(SpringDataBusinessRecordRepository records,
            SpringDataBusinessRecordIdentifierRepository identifiers,
            SpringDataBusinessIdentifierDefinitionRepository definitions){
        this.records=records; this.identifiers=identifiers; this.definitions=definitions;
    }
    @Override @Transactional public void save(BusinessRecord record){
        BusinessRecordEntity entity=records.findByIdAndTenantId(record.id().value(), record.tenantId().value()).orElseGet(BusinessRecordEntity::new);
        if(entity.getId()==null){entity.setId(record.id().value()); entity.setTenantId(record.tenantId().value()); entity.setCreatedAt(record.createdAt());}
        entity.setUpdatedAt(record.updatedAt()); records.save(entity);
        Map<UUID,BusinessRecordIdentifierEntity> existing=new HashMap<>();
        for(BusinessRecordIdentifierEntity e:identifiers.findByBusinessRecordIdAndTenantId(record.id().value(), record.tenantId().value())) existing.put(e.getDefinitionId(),e);
        for(BusinessIdentifier i:record.identifiers()){
            BusinessRecordIdentifierEntity e=existing.getOrDefault(i.definitionId().value(), new BusinessRecordIdentifierEntity());
            if(e.getId()==null){e.setId(UUID.randomUUID());e.setTenantId(record.tenantId().value());e.setBusinessRecordId(record.id().value());e.setDefinitionId(i.definitionId().value());e.setCreatedAt(Instant.now());}
            e.setIdentifierValue(i.value());e.setNormalizedValue(i.normalizedValue());identifiers.save(e);
        }
    }
    @Override public Optional<BusinessRecord> findByIdAndTenantId(BusinessRecordId id,TenantId tenant){
        return records.findByIdAndTenantId(id.value(),tenant.value()).map(this::toDomain);
    }
    @Override public Optional<BusinessRecord> findByIdentifier(TenantId tenant, BusinessIdentifierDefinitionId definitionId,String normalizedValue){
        return identifiers.findByTenantIdAndDefinitionIdAndNormalizedValue(tenant.value(), definitionId.value(), normalizedValue)
                .flatMap(e->records.findByIdAndTenantId(e.getBusinessRecordId(),tenant.value())).map(this::toDomain);
    }
    private BusinessRecord toDomain(BusinessRecordEntity r){
        Map<UUID,BusinessIdentifierDefinitionEntity> defs=new HashMap<>();
        for(BusinessIdentifierDefinitionEntity d:definitions.findByTenantIdAndActiveTrueOrderByDisplayOrderAsc(r.getTenantId())) defs.put(d.getId(),d);
        List<BusinessIdentifier> values=new ArrayList<>();
        for(BusinessRecordIdentifierEntity e:identifiers.findByBusinessRecordIdAndTenantId(r.getId(),r.getTenantId())){
            BusinessIdentifierDefinitionEntity d=defs.get(e.getDefinitionId()); if(d==null) continue;
            values.add(new BusinessIdentifier(BusinessIdentifierDefinitionId.of(d.getId()),d.getIdentifierKey(),d.getLabel(),e.getIdentifierValue(),e.getNormalizedValue(),d.isPrimary(),d.isSearchable(),d.isDisplayable(),d.getDisplayOrder()));
        }
        return BusinessRecord.reconstitute(BusinessRecordId.of(r.getId()),TenantId.of(r.getTenantId()),r.getCreatedAt(),r.getUpdatedAt(),values);
    }
}

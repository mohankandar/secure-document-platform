package com.securedoc.platform.infrastructure.persistence.mapper;

import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.infrastructure.persistence.entity.AuditRecordEntity;

import java.time.Instant;

public final class AuditRecordMapper {

    private AuditRecordMapper() {
    }

    public static AuditRecordEntity toEntity(AuditRecord record) {
        AuditRecordEntity entity = new AuditRecordEntity();
        entity.setId(record.id());
        entity.setTenantId(record.tenantId().value());
        entity.setActorId(record.actorId().value());
        entity.setAction(record.action().name());
        entity.setResourceType(record.resourceType());
        entity.setResourceId(record.resourceId());
        entity.setOutcome(record.outcome().name());
        entity.setCorrelationId(record.correlationId().value());
        entity.setDetail(record.detail());
        entity.setOccurredAt(record.timestamp());
        entity.setCreatedAt(Instant.now());
        return entity;
    }
}

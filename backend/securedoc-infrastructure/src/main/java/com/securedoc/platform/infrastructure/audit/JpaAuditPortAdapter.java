package com.securedoc.platform.infrastructure.audit;

import com.securedoc.platform.application.port.out.AuditPort;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.infrastructure.persistence.mapper.AuditRecordMapper;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataAuditRecordRepository;
import org.springframework.stereotype.Component;

/**
 * Persists audit records to the database via JPA.
 * Append-oriented — audit records are never updated or deleted.
 */
@Component
public class JpaAuditPortAdapter implements AuditPort {

    private final SpringDataAuditRecordRepository auditRepo;

    public JpaAuditPortAdapter(SpringDataAuditRecordRepository auditRepo) {
        this.auditRepo = auditRepo;
    }

    @Override
    public void record(AuditRecord auditRecord) {
        auditRepo.save(AuditRecordMapper.toEntity(auditRecord));
    }
}

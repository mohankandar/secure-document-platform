package com.securedoc.platform.infrastructure.audit;

import com.securedoc.platform.application.port.out.AuditPort;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.infrastructure.persistence.mapper.AuditRecordMapper;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataAuditRecordRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Persists audit records to the database via JPA.
 * Append-oriented — audit records are never updated or deleted.
 */
@Component
public class JpaAuditPortAdapter implements AuditPort {

    private final SpringDataAuditRecordRepository auditRepo;
    private final TransactionTemplate independentTransaction;

    public JpaAuditPortAdapter(SpringDataAuditRecordRepository auditRepo, PlatformTransactionManager transactionManager) {
        this.auditRepo = auditRepo;
        this.independentTransaction = new TransactionTemplate(transactionManager);
        this.independentTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public void record(AuditRecord auditRecord) {
        auditRepo.save(AuditRecordMapper.toEntity(auditRecord));
    }

    /** Task 0023 C6: REQUIRES_NEW, so a refusal record is committed even when the request then rolls back. */
    @Override
    public void recordIndependently(AuditRecord auditRecord) {
        independentTransaction.executeWithoutResult(status -> auditRepo.save(AuditRecordMapper.toEntity(auditRecord)));
    }
}

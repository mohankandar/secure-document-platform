package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.ExternalIntakeRepository;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.intake.*;
import com.securedoc.platform.infrastructure.persistence.entity.*;
import com.securedoc.platform.infrastructure.persistence.repository.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Component
public class JpaExternalIntakeRepositoryAdapter implements ExternalIntakeRepository {
    private final SpringDataExternalIntakeRepository intakeRepository;
    private final SpringDataExternalIntakeDocumentRepository documentRepository;

    public JpaExternalIntakeRepositoryAdapter(SpringDataExternalIntakeRepository intakeRepository,
                                              SpringDataExternalIntakeDocumentRepository documentRepository) {
        this.intakeRepository = intakeRepository;
        this.documentRepository = documentRepository;
    }

    @Override @Transactional(readOnly = true)
    public Optional<ExternalIntake> findByTenantIdAndPackageId(TenantId tenantId, PackageId packageId) {
        return intakeRepository.findByPackageIdAndTenantId(packageId.value(), tenantId.value()).map(this::toDomain);
    }

    @Override @Transactional
    public Optional<ExternalIntake> findByTenantIdAndPackageIdForUpdate(TenantId tenantId, PackageId packageId) {
        return intakeRepository.findForUpdate(packageId.value(), tenantId.value()).map(this::toDomain);
    }

    @Override @Transactional(readOnly = true)
    public Optional<ExternalIntake> findBySourceIdentity(TenantId tenantId, IntegrationConnectionId connectionId,
                                                         String externalSourcePackageId, String sourceRevision) {
        return intakeRepository.findFirstByTenantIdAndIntegrationConnectionIdAndExternalSourcePackageIdAndSourceRevisionOrderByAttemptNumberDesc(
                tenantId.value(), connectionId.value(), externalSourcePackageId, sourceRevision).map(this::toDomain);
    }

    @Override @Transactional(readOnly = true)
    public List<ExternalIntake> findAwaitingExpiredBefore(Instant now, int limit) {
        int bounded = Math.max(1, Math.min(500, limit));
        return intakeRepository.findByStatusAndExpiresAtLessThanEqualOrderByExpiresAtAsc(
                        ExternalIntakeStatus.AWAITING_DOCUMENTS.name(), now, PageRequest.of(0, bounded))
                .stream().map(this::toDomain).toList();
    }

    @Override @Transactional(readOnly = true)
    public List<ExternalIntake> findExpiredCleanupPending(int limit) {
        int bounded = Math.max(1, Math.min(500, limit));
        return intakeRepository.findByStatusAndCleanupCompletedAtIsNullOrderByExpiredAtAsc(
                        ExternalIntakeStatus.INCOMPLETE_EXPIRED.name(), PageRequest.of(0, bounded))
                .stream().map(this::toDomain).toList();
    }

    @Override @Transactional
    public void save(ExternalIntake intake) {
        ExternalIntakeEntity entity = intakeRepository.findById(intake.packageId().value()).orElseGet(ExternalIntakeEntity::new);
        entity.setPackageId(intake.packageId().value());
        entity.setTenantId(intake.tenantId().value());
        entity.setIntegrationConnectionId(intake.integrationConnectionId().value());
        entity.setChannel(intake.channel().name());
        entity.setExternalSourcePackageId(intake.externalSourcePackageId());
        entity.setSourceRevision(intake.sourceRevision());
        entity.setAttemptNumber(intake.attemptNumber());
        entity.setRequestFingerprint(intake.requestFingerprint());
        entity.setStatus(intake.status().name());
        entity.setCreatedAt(intake.createdAt());
        entity.setExpiresAt(intake.expiresAt());
        entity.setAcceptedAt(intake.acceptedAt());
        entity.setExpiredAt(intake.expiredAt());
        entity.setCleanupCompletedAt(intake.cleanupCompletedAt());
        entity.setUpdatedAt(intake.updatedAt());
        intakeRepository.save(entity);

        for (ExternalManifestDocument document : intake.documents()) {
            ExternalIntakeDocumentEntity row = documentRepository
                    .findByPackageIdAndExternalSourceDocumentId(intake.packageId().value(), document.externalSourceDocumentId())
                    .orElseGet(ExternalIntakeDocumentEntity::new);
            if (row.getId() == null) row.setId(UUID.randomUUID());
            row.setPackageId(intake.packageId().value());
            row.setExternalSourceDocumentId(document.externalSourceDocumentId());
            row.setOriginalFileName(document.originalFileName());
            row.setDeclaredSha256(document.declaredSha256());
            row.setReceivedDocumentId(document.receivedDocumentId() == null ? null : document.receivedDocumentId().value());
            row.setReceivedSha256(document.receivedSha256());
            row.setIdempotencyKey(document.idempotencyKey());
            row.setReceivedAt(document.receivedAt());
            documentRepository.save(row);
        }
    }

    private ExternalIntake toDomain(ExternalIntakeEntity entity) {
        List<ExternalManifestDocument> documents = documentRepository.findByPackageIdOrderByIdAsc(entity.getPackageId())
                .stream().map(row -> new ExternalManifestDocument(
                        row.getExternalSourceDocumentId(), row.getOriginalFileName(), row.getDeclaredSha256(),
                        row.getReceivedDocumentId() == null ? null : DocumentId.of(row.getReceivedDocumentId()),
                        row.getReceivedSha256(), row.getIdempotencyKey(), row.getReceivedAt())).toList();
        return ExternalIntake.reconstitute(PackageId.of(entity.getPackageId()), TenantId.of(entity.getTenantId()),
                IntegrationConnectionId.of(entity.getIntegrationConnectionId()), ExternalIntakeChannel.valueOf(entity.getChannel()),
                entity.getExternalSourcePackageId(), entity.getSourceRevision(), entity.getAttemptNumber(), entity.getRequestFingerprint(), entity.getCreatedAt(), entity.getExpiresAt(),
                documents, ExternalIntakeStatus.valueOf(entity.getStatus()), entity.getAcceptedAt(), entity.getExpiredAt(),
                entity.getCleanupCompletedAt(), entity.getUpdatedAt());
    }
}

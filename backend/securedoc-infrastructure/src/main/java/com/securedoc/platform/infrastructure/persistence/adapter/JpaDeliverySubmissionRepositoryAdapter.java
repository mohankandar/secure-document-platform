package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.DeliverySubmissionRepository;
import com.securedoc.platform.domain.delivery.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.infrastructure.persistence.entity.*;
import com.securedoc.platform.infrastructure.persistence.repository.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Component
public class JpaDeliverySubmissionRepositoryAdapter implements DeliverySubmissionRepository {
    private final SpringDataDeliverySubmissionRepository submissions;
    private final SpringDataDeliverySubmissionDocumentRepository documents;
    private final SpringDataDeliveryAttemptRepository attempts;
    public JpaDeliverySubmissionRepositoryAdapter(SpringDataDeliverySubmissionRepository submissions,
                                                   SpringDataDeliverySubmissionDocumentRepository documents,
                                                   SpringDataDeliveryAttemptRepository attempts) {
        this.submissions=submissions; this.documents=documents; this.attempts=attempts;
    }
    @Override public Optional<DeliverySubmission> findByTenantIdAndPackageIdAndExportCycle(TenantId tenantId, PackageId packageId, int cycle) {
        return submissions.findByTenantIdAndPackageIdAndExportCycle(tenantId.value(), packageId.value(), cycle).map(this::toDomain);
    }
    @Override @Transactional public void save(DeliverySubmission s) {
        DeliverySubmissionEntity e=submissions.findById(s.id()).orElseGet(DeliverySubmissionEntity::new);
        e.setId(s.id()); e.setTenantId(s.tenantId().value()); e.setPackageId(s.packageId().value()); e.setExportCycle(s.exportCycle());
        e.setSourceSubmissionId(s.sourceSubmissionId()); e.setDestinationType(s.destinationType()); e.setStatus(s.status().name());
        e.setAttemptCount(s.attemptCount()); e.setDestinationSubmissionReference(s.destinationSubmissionReference()); e.setLastFailureCategory(s.lastFailureCategory());
        e.setCreatedAt(s.createdAt()); e.setUpdatedAt(s.updatedAt()); e.setCompletedAt(s.completedAt()); submissions.save(e);
        Map<UUID,DeliverySubmissionDocumentEntity> existing=new HashMap<>();
        for(var d:documents.findBySubmissionIdOrderByFileNameAsc(s.id())) existing.put(d.getDocumentId(),d);
        for(var d:s.documents()){
            DeliverySubmissionDocumentEntity de=existing.getOrDefault(d.documentId().value(),new DeliverySubmissionDocumentEntity());
            if(de.getId()==null) de.setId(UUID.randomUUID());
            de.setSubmissionId(s.id()); de.setTenantId(s.tenantId().value()); de.setPackageId(s.packageId().value()); de.setDocumentId(d.documentId().value());
            de.setSourceDocumentId(d.sourceDocumentId()); de.setFileName(d.fileName()); de.setSizeBytes(d.sizeBytes());
            de.setChecksumAlgorithm(d.checksum().algorithm()); de.setChecksumValue(d.checksum().value()); de.setAcknowledged(d.acknowledged());
            de.setDestinationDocumentReference(d.destinationDocumentReference()); de.setDestinationLocation(d.destinationLocation()); de.setAcknowledgedAt(d.acknowledgedAt()); documents.save(de);
        }
    }
    @Override public void saveAttempt(DeliveryAttempt a){
        DeliveryAttemptEntity e=new DeliveryAttemptEntity(); e.setId(a.id()); e.setSubmissionId(a.submissionId()); e.setAttemptNumber(a.attemptNumber());
        e.setConnectionId(a.connectionId()==null?null:a.connectionId().value()); e.setConnectionConfigurationVersion(a.connectionConfigurationVersion());
        e.setStatus(a.status().name()); e.setFailureCategory(a.failureCategory()); e.setStartedAt(a.startedAt()); e.setCompletedAt(a.completedAt()); attempts.save(e);
    }
    private DeliverySubmission toDomain(DeliverySubmissionEntity e){
        List<DeliverySubmission.DocumentState> docs=documents.findBySubmissionIdOrderByFileNameAsc(e.getId()).stream().map(d->new DeliverySubmission.DocumentState(
                DocumentId.of(d.getDocumentId()), d.getSourceDocumentId(), d.getFileName(), d.getSizeBytes(),
                Checksum.of(d.getChecksumAlgorithm(),d.getChecksumValue()), d.isAcknowledged(), d.getDestinationDocumentReference(), d.getDestinationLocation(), d.getAcknowledgedAt())).toList();
        return new DeliverySubmission(e.getId(),TenantId.of(e.getTenantId()),PackageId.of(e.getPackageId()),e.getExportCycle(),e.getSourceSubmissionId(),e.getDestinationType(),
                DeliverySubmissionStatus.valueOf(e.getStatus()),e.getAttemptCount(),e.getDestinationSubmissionReference(),e.getLastFailureCategory(),e.getCreatedAt(),e.getUpdatedAt(),e.getCompletedAt(),docs);
    }
}

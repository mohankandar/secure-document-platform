package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.domain.finding.DetectorSource;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.finding.FindingStatus;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Confidence;
import com.securedoc.platform.infrastructure.persistence.entity.FindingEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataFindingRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class JpaFindingRepositoryAdapter implements FindingRepository {

    private final SpringDataFindingRepository jpaRepo;

    public JpaFindingRepositoryAdapter(SpringDataFindingRepository jpaRepo) {
        this.jpaRepo = jpaRepo;
    }

    @Override
    public void save(Finding finding) {
        Optional<FindingEntity> existing = jpaRepo.findByIdAndTenantId(
                finding.id().value(), finding.tenantId().value());
        if (existing.isPresent()) {
            updateEntity(finding, existing.get());
            jpaRepo.save(existing.get());
        } else {
            jpaRepo.save(toNewEntity(finding));
        }
    }

    @Override
    public void saveAll(List<Finding> findings) {
        List<FindingEntity> entities = findings.stream()
                .map(this::toNewEntity)
                .toList();
        jpaRepo.saveAll(entities);
    }

    @Override
    public Optional<Finding> findByIdAndTenantId(FindingId id, TenantId tenantId) {
        return jpaRepo.findByIdAndTenantId(id.value(), tenantId.value())
                .map(this::toDomain);
    }

    @Override
    public List<Finding> findByDocumentIdAndTenantId(DocumentId documentId, TenantId tenantId) {
        return jpaRepo.findByDocumentIdAndTenantId(documentId.value(), tenantId.value())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    private FindingEntity toNewEntity(Finding finding) {
        FindingEntity entity = new FindingEntity();
        entity.setId(finding.id().value());
        entity.setTenantId(finding.tenantId().value());
        entity.setDocumentId(finding.documentId().value());
        entity.setEntityType(finding.entityType().name());
        entity.setPageNumber(finding.location().pageNumber());
        entity.setStartOffset(finding.location().startOffset());
        entity.setEndOffset(finding.location().endOffset());
        entity.setConfidence(finding.confidence().value());
        entity.setDetectorName(finding.detectorSource().detectorName());
        entity.setDetectorVersion(finding.detectorSource().version());
        entity.setSnippet(finding.snippet());
        persistBoundingBox(finding.location(), entity);
        entity.setStatus(finding.status().name());
        entity.setDetectedAt(finding.detectedAt());
        entity.setUpdatedAt(finding.updatedAt());
        return entity;
    }

    private void updateEntity(Finding finding, FindingEntity entity) {
        entity.setStatus(finding.status().name());
        entity.setUpdatedAt(finding.updatedAt());
        entity.setSnippet(finding.snippet());
        persistBoundingBox(finding.location(), entity);
    }

    private void persistBoundingBox(FindingLocation location, FindingEntity entity) {
        if (location.hasBoundingBox()) {
            FindingLocation.BoundingBox box = location.boundingBox();
            entity.setBboxLeft(box.left());
            entity.setBboxTop(box.top());
            entity.setBboxWidth(box.width());
            entity.setBboxHeight(box.height());
        } else {
            entity.setBboxLeft(null);
            entity.setBboxTop(null);
            entity.setBboxWidth(null);
            entity.setBboxHeight(null);
        }
    }

    private Finding toDomain(FindingEntity entity) {
        FindingLocation location = rehydrateLocation(entity);

        return Finding.reconstitute(
                FindingId.of(entity.getId()),
                TenantId.of(entity.getTenantId()),
                DocumentId.of(entity.getDocumentId()),
                EntityType.valueOf(entity.getEntityType()),
                location,
                Confidence.of(entity.getConfidence()),
                new DetectorSource(entity.getDetectorName(), entity.getDetectorVersion()),
                entity.getSnippet(),
                entity.getDetectedAt(),
                FindingStatus.valueOf(entity.getStatus()),
                List.of(), // evidence history not persisted in this table
                entity.getUpdatedAt()
        );
    }

    private FindingLocation rehydrateLocation(FindingEntity entity) {
        if (entity.getBboxLeft() != null && entity.getBboxTop() != null
                && entity.getBboxWidth() != null && entity.getBboxHeight() != null) {
            FindingLocation.BoundingBox box = FindingLocation.BoundingBox.of(
                    entity.getBboxLeft(), entity.getBboxTop(),
                    entity.getBboxWidth(), entity.getBboxHeight());
            return FindingLocation.withBounds(
                    entity.getPageNumber(), entity.getStartOffset(), entity.getEndOffset(), box);
        }
        return FindingLocation.textOnly(entity.getPageNumber(), entity.getStartOffset(), entity.getEndOffset());
    }
}

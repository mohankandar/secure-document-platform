package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.ReviewTaskRepository;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.ReviewTaskId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.review.ReviewStatus;
import com.securedoc.platform.domain.review.ReviewTask;
import com.securedoc.platform.infrastructure.persistence.entity.ReviewTaskEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataReviewTaskRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class JpaReviewTaskRepositoryAdapter implements ReviewTaskRepository {

    private static final List<String> OPEN_STATUSES = List.of(
            ReviewStatus.PENDING.name(),
            ReviewStatus.ASSIGNED.name(),
            ReviewStatus.IN_PROGRESS.name()
    );

    private final SpringDataReviewTaskRepository jpaRepo;

    public JpaReviewTaskRepositoryAdapter(SpringDataReviewTaskRepository jpaRepo) {
        this.jpaRepo = jpaRepo;
    }

    @Override
    public void save(ReviewTask task) {
        Optional<ReviewTaskEntity> existing = jpaRepo.findByIdAndTenantId(
                task.id().value(), task.tenantId().value());
        if (existing.isPresent()) {
            updateEntity(task, existing.get());
            jpaRepo.save(existing.get());
        } else {
            jpaRepo.save(toNewEntity(task));
        }
    }

    @Override
    public Optional<ReviewTask> findByIdAndTenantId(ReviewTaskId id, TenantId tenantId) {
        return jpaRepo.findByIdAndTenantId(id.value(), tenantId.value())
                .map(this::toDomain);
    }

    @Override
    public List<ReviewTask> findByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
        return jpaRepo.findByPackageIdAndTenantId(packageId.value(), tenantId.value())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public List<ReviewTask> findByStatusAndTenantId(ReviewStatus status, TenantId tenantId) {
        return jpaRepo.findByStatusAndTenantId(status.name(), tenantId.value())
                .stream()
                .map(this::toDomain)
                .toList();
    }

    @Override
    public Optional<ReviewTask> findOpenByPackageIdAndTenantId(PackageId packageId, TenantId tenantId) {
        return jpaRepo.findFirstByPackageIdAndTenantIdAndStatusIn(
                        packageId.value(), tenantId.value(), OPEN_STATUSES)
                .map(this::toDomain);
    }

    private ReviewTaskEntity toNewEntity(ReviewTask task) {
        ReviewTaskEntity entity = new ReviewTaskEntity();
        entity.setId(task.id().value());
        entity.setTenantId(task.tenantId().value());
        entity.setPackageId(task.packageId().value());
        entity.setStatus(task.status().name());
        entity.setAssignedTo(task.assignedTo().map(ActorId::value).orElse(null));
        entity.setCreatedAt(task.createdAt());
        entity.setUpdatedAt(task.updatedAt());
        return entity;
    }

    private void updateEntity(ReviewTask task, ReviewTaskEntity entity) {
        entity.setStatus(task.status().name());
        entity.setAssignedTo(task.assignedTo().map(ActorId::value).orElse(null));
        entity.setUpdatedAt(task.updatedAt());
    }

    private ReviewTask toDomain(ReviewTaskEntity entity) {
        return ReviewTask.reconstitute(
                ReviewTaskId.of(entity.getId()),
                TenantId.of(entity.getTenantId()),
                PackageId.of(entity.getPackageId()),
                entity.getCreatedAt(),
                ReviewStatus.valueOf(entity.getStatus()),
                entity.getAssignedTo() != null ? ActorId.of(entity.getAssignedTo()) : null,
                List.of(), // feedback not persisted in this table for now
                entity.getUpdatedAt()
        );
    }
}

package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.ExternalIntakeDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SpringDataExternalIntakeDocumentRepository extends JpaRepository<ExternalIntakeDocumentEntity, UUID> {
    List<ExternalIntakeDocumentEntity> findByPackageIdOrderByIdAsc(UUID packageId);
    Optional<ExternalIntakeDocumentEntity> findByPackageIdAndExternalSourceDocumentId(UUID packageId, String externalSourceDocumentId);
}

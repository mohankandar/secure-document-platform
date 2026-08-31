package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.DocumentExportRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SpringDataDocumentExportRecordRepository extends JpaRepository<DocumentExportRecordEntity, UUID> {
    List<DocumentExportRecordEntity> findByExportRecordId(UUID exportRecordId);
}

package com.securedoc.platform.infrastructure.persistence.repository;

import com.securedoc.platform.infrastructure.persistence.entity.DocumentIntelligenceBundleEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SpringDataDocumentIntelligenceBundleRepository extends JpaRepository<DocumentIntelligenceBundleEntity, String> {
}

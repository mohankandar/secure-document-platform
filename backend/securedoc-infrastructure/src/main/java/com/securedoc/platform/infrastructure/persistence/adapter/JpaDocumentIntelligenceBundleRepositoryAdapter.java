package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.DocumentIntelligenceBundleRepository;
import com.securedoc.platform.domain.intelligence.DocumentIntelligenceBundle;
import com.securedoc.platform.domain.intelligence.IntelligenceBundleStatus;
import com.securedoc.platform.infrastructure.persistence.entity.DocumentIntelligenceBundleEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataDocumentIntelligenceBundleRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class JpaDocumentIntelligenceBundleRepositoryAdapter implements DocumentIntelligenceBundleRepository {
    private final SpringDataDocumentIntelligenceBundleRepository repository;

    public JpaDocumentIntelligenceBundleRepositoryAdapter(SpringDataDocumentIntelligenceBundleRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<DocumentIntelligenceBundle> findByVersion(String version) {
        if (version == null || version.isBlank()) return Optional.empty();
        return repository.findById(version.trim()).map(this::toDomain);
    }

    @Override
    public List<DocumentIntelligenceBundle> findAll() {
        return repository.findAll().stream().map(this::toDomain).toList();
    }

    private DocumentIntelligenceBundle toDomain(DocumentIntelligenceBundleEntity entity) {
        return new DocumentIntelligenceBundle(entity.getVersion(), IntelligenceBundleStatus.valueOf(entity.getStatus()),
                entity.getModelVersion(), entity.getPromptVersion(), entity.getDetectorVersion(), entity.getOcrRulesVersion(),
                entity.getProfileVersion(), entity.getValidationRulesVersion(), entity.getThresholdsVersion(), entity.getDescription(),
                entity.getCreatedAt(), entity.getPromotedAt());
    }
}

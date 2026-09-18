package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.intelligence.DocumentIntelligenceBundle;

import java.util.List;
import java.util.Optional;

public interface DocumentIntelligenceBundleRepository {
    Optional<DocumentIntelligenceBundle> findByVersion(String version);
    List<DocumentIntelligenceBundle> findAll();
}

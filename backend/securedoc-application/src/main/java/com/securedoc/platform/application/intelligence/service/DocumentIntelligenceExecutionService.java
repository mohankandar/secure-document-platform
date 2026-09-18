package com.securedoc.platform.application.intelligence.service;

import com.securedoc.platform.application.port.out.DocumentIntelligenceBundleRepository;
import com.securedoc.platform.application.port.out.PackageProcessingIntelligenceSnapshotRepository;
import com.securedoc.platform.application.port.out.TenantDocumentIntelligencePolicyRepository;
import com.securedoc.platform.application.observability.StructuredLogContext;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.intelligence.DocumentIntelligenceBundle;
import com.securedoc.platform.domain.intelligence.PackageProcessingIntelligenceSnapshot;
import com.securedoc.platform.domain.intelligence.TenantDocumentIntelligencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/** Captures one immutable intelligence configuration snapshot per package processing cycle. */
public final class DocumentIntelligenceExecutionService {
    private static final Logger log = LoggerFactory.getLogger(DocumentIntelligenceExecutionService.class);

    private final TenantDocumentIntelligencePolicyRepository policyRepository;
    private final DocumentIntelligenceBundleRepository bundleRepository;
    private final PackageProcessingIntelligenceSnapshotRepository snapshotRepository;

    public DocumentIntelligenceExecutionService(TenantDocumentIntelligencePolicyRepository policyRepository,
                                                DocumentIntelligenceBundleRepository bundleRepository,
                                                PackageProcessingIntelligenceSnapshotRepository snapshotRepository) {
        this.policyRepository = Objects.requireNonNull(policyRepository);
        this.bundleRepository = Objects.requireNonNull(bundleRepository);
        this.snapshotRepository = Objects.requireNonNull(snapshotRepository);
    }

    public PackageProcessingIntelligenceSnapshot ensureSnapshot(TenantId tenantId,
                                                                 PackageId packageId,
                                                                 int processingCycle,
                                                                 CorrelationId correlationId) {
        return snapshotRepository.findByTenantIdAndPackageIdAndProcessingCycle(tenantId, packageId, processingCycle)
                .orElseGet(() -> capture(tenantId, packageId, processingCycle, correlationId));
    }

    private PackageProcessingIntelligenceSnapshot capture(TenantId tenantId,
                                                           PackageId packageId,
                                                           int processingCycle,
                                                           CorrelationId correlationId) {
        TenantDocumentIntelligencePolicy policy = policyRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantDocumentIntelligencePolicy.defaults(tenantId, Instant.now()));
        boolean activeBundleExecutable = bundleRepository.findByVersion(policy.activeBundleVersion())
                .map(DocumentIntelligenceBundle::executableForPinnedTenant)
                .orElse(false);
        PackageProcessingIntelligenceSnapshot candidate = PackageProcessingIntelligenceSnapshot.capture(
                tenantId, packageId, processingCycle, policy, activeBundleExecutable, Instant.now());
        PackageProcessingIntelligenceSnapshot stored = snapshotRepository.saveIfAbsent(candidate);

        Map<String, Object> fields = new java.util.LinkedHashMap<>();
        fields.put("tenantId", tenantId);
        fields.put("packageId", packageId);
        fields.put("correlationId", correlationId);
        fields.put("processingCycle", processingCycle);
        fields.put("intelligenceRequestedMode", stored.requestedMode());
        fields.put("intelligenceEffectiveMode", stored.effectiveMode());
        fields.put("intelligenceBundleVersion", stored.bundleVersion());
        fields.put("intelligencePolicyVersion", stored.policyConfigurationVersion());
        fields.put("intelligenceExecutionAllowed", stored.intelligenceExecutionAllowed());
        fields.put("intelligenceInfluenceAllowed", stored.decisionInfluenceAllowed());
        fields.put("baselineDiscoveryRequired", stored.baselineDiscoveryRequired());
        fields.put("intelligenceFallbackToBaseline", stored.fallbackToBaseline());
        StructuredLogContext.info(log, "DOCUMENT_INTELLIGENCE_CONTEXT_CAPTURED", fields,
                "Document intelligence processing context captured");

        if (stored.fallbackToBaseline()) {
            StructuredLogContext.warn(log, "DOCUMENT_INTELLIGENCE_BASELINE_FALLBACK", Map.of(
                    "tenantId", tenantId,
                    "packageId", packageId,
                    "correlationId", correlationId,
                    "processingCycle", processingCycle,
                    "intelligenceRequestedMode", stored.requestedMode(),
                    "intelligenceEffectiveMode", stored.effectiveMode(),
                    "intelligenceBundleVersion", stored.bundleVersion()),
                    "Document intelligence bundle unavailable; frozen baseline processing preserved");
        }
        return stored;
    }
}

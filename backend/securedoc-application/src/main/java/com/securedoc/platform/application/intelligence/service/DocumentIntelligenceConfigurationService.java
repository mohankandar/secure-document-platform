package com.securedoc.platform.application.intelligence.service;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.DocumentIntelligenceBundleRepository;
import com.securedoc.platform.application.port.out.TenantDocumentIntelligencePolicyRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.intelligence.DocumentIntelligenceBundle;
import com.securedoc.platform.domain.intelligence.DocumentIntelligenceMode;
import com.securedoc.platform.domain.intelligence.IntelligenceBundleStatus;
import com.securedoc.platform.domain.intelligence.TenantDocumentIntelligencePolicy;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Tenant-facing configuration for optional document intelligence.
 *
 * <p>Only PROMOTED bundles can be newly selected. Existing last-known-good versions remain
 * rollback eligible even if later RETIRED. The frozen baseline is always available through
 * DISABLED mode independently of any adaptive profile.</p>
 */
public final class DocumentIntelligenceConfigurationService {
    private final DocumentIntelligenceBundleRepository bundleRepository;
    private final TenantDocumentIntelligencePolicyRepository policyRepository;
    private final AuditService auditService;

    public DocumentIntelligenceConfigurationService(DocumentIntelligenceBundleRepository bundleRepository,
                                                    TenantDocumentIntelligencePolicyRepository policyRepository,
                                                    AuditService auditService) {
        this.bundleRepository = Objects.requireNonNull(bundleRepository);
        this.policyRepository = Objects.requireNonNull(policyRepository);
        this.auditService = Objects.requireNonNull(auditService);
    }

    public Configuration getConfiguration(TenantId tenantId) {
        TenantDocumentIntelligencePolicy policy = currentPolicy(tenantId);
        List<DocumentIntelligenceBundle> bundles = bundleRepository.findAll().stream()
                // Candidate releases remain an internal regression/promotion concern and are not
                // exposed to tenant Business Admins before promotion.
                .filter(bundle -> bundle.status() != IntelligenceBundleStatus.CANDIDATE)
                .sorted(Comparator.comparing(DocumentIntelligenceBundle::version))
                .toList();
        return new Configuration(policy, bundles);
    }

    public TenantDocumentIntelligencePolicy currentPolicy(TenantId tenantId) {
        Objects.requireNonNull(tenantId);
        return policyRepository.findByTenantId(tenantId)
                .orElseGet(() -> TenantDocumentIntelligencePolicy.defaults(tenantId, Instant.now()));
    }

    public TenantDocumentIntelligencePolicy update(TenantId tenantId,
                                                    ActorId actorId,
                                                    CorrelationId correlationId,
                                                    DocumentIntelligenceMode mode,
                                                    String activeBundleVersion) {
        Objects.requireNonNull(mode, "Document intelligence mode is required");
        String version = normalizeVersion(activeBundleVersion);
        if (mode == DocumentIntelligenceMode.DISABLED) {
            // Turning intelligence off is a safety action and must not be blocked merely because
            // the tenant's remembered pin has since been retired. The pin is metadata only while
            // DISABLED; re-enabling still requires a PROMOTED bundle.
            requireKnown(version);
        } else {
            requireSelectable(version);
        }
        TenantDocumentIntelligencePolicy updated = currentPolicy(tenantId).reconfigure(mode, version, Instant.now());
        policyRepository.save(updated);
        auditService.recordSuccess(tenantId, actorId, AuditAction.DOCUMENT_INTELLIGENCE_CONFIGURATION_CHANGED,
                "DocumentIntelligencePolicy", tenantId.toString(), correlationId,
                "Document intelligence configuration changed: mode=" + mode
                        + ", activeBundle=" + version
                        + ", configurationVersion=" + updated.configurationVersion());
        return updated;
    }

    public TenantDocumentIntelligencePolicy markCurrentAsLastKnownGood(TenantId tenantId,
                                                                        ActorId actorId,
                                                                        CorrelationId correlationId) {
        TenantDocumentIntelligencePolicy current = currentPolicy(tenantId);
        requireSelectable(current.activeBundleVersion());
        TenantDocumentIntelligencePolicy updated = current.markCurrentAsLastKnownGood(Instant.now());
        policyRepository.save(updated);
        auditService.recordSuccess(tenantId, actorId, AuditAction.DOCUMENT_INTELLIGENCE_LAST_KNOWN_GOOD_CHANGED,
                "DocumentIntelligencePolicy", tenantId.toString(), correlationId,
                "Document intelligence last-known-good updated: bundle=" + updated.lastKnownGoodBundleVersion()
                        + ", configurationVersion=" + updated.configurationVersion());
        return updated;
    }

    public TenantDocumentIntelligencePolicy rollback(TenantId tenantId,
                                                      ActorId actorId,
                                                      CorrelationId correlationId) {
        TenantDocumentIntelligencePolicy current = currentPolicy(tenantId);
        requireRollbackEligible(current.lastKnownGoodBundleVersion());
        TenantDocumentIntelligencePolicy updated = current.rollback(Instant.now());
        policyRepository.save(updated);
        auditService.recordSuccess(tenantId, actorId, AuditAction.DOCUMENT_INTELLIGENCE_ROLLED_BACK,
                "DocumentIntelligencePolicy", tenantId.toString(), correlationId,
                "Document intelligence rolled back: activeBundle=" + updated.activeBundleVersion()
                        + ", mode=" + updated.mode()
                        + ", configurationVersion=" + updated.configurationVersion());
        return updated;
    }


    private void requireKnown(String version) {
        bundleRepository.findByVersion(version)
                .orElseThrow(() -> new ValidationException("Selected document intelligence bundle does not exist"));
    }

    private void requireSelectable(String version) {
        DocumentIntelligenceBundle bundle = bundleRepository.findByVersion(version)
                .orElseThrow(() -> new ValidationException("Selected document intelligence bundle does not exist"));
        if (!bundle.selectableForTenant()) {
            throw new ValidationException("Selected document intelligence bundle is not promoted for tenant use");
        }
    }

    private void requireRollbackEligible(String version) {
        DocumentIntelligenceBundle bundle = bundleRepository.findByVersion(version)
                .orElseThrow(() -> new ValidationException("Last-known-good document intelligence bundle is unavailable"));
        if (bundle.status() == IntelligenceBundleStatus.CANDIDATE) {
            throw new ValidationException("Last-known-good document intelligence bundle is not eligible for rollback");
        }
    }

    private String normalizeVersion(String version) {
        if (version == null || version.trim().isEmpty()) {
            throw new ValidationException("Document intelligence bundle version is required");
        }
        String normalized = version.trim();
        if (normalized.length() > 80) throw new ValidationException("Document intelligence bundle version is too long");
        return normalized;
    }

    public record Configuration(TenantDocumentIntelligencePolicy policy,
                                List<DocumentIntelligenceBundle> availableBundles) {
        public Configuration {
            Objects.requireNonNull(policy);
            availableBundles = List.copyOf(availableBundles == null ? List.of() : availableBundles);
        }
    }
}

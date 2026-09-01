package com.securedoc.platform.application.resilience;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Map;
import java.util.Objects;

/**
 * Routes semantic-analysis requests to a deployment-configured tenant AI profile.
 *
 * <p>Provider credentials/endpoints remain deployment/Platform concerns. Business redaction
 * policy travels independently in the analysis request. Deployments may require every tenant
 * to have an explicit AI profile; in that mode missing profiles fail closed and document text
 * is never sent to the default provider accidentally.
 */
public final class TenantRoutingSensitiveContentAnalysisPort implements SensitiveContentAnalysisPort {

    private final SensitiveContentAnalysisPort defaultProvider;
    private final Map<TenantId, SensitiveContentAnalysisPort> tenantProviders;
    private final boolean requireExplicitTenantProfile;

    public TenantRoutingSensitiveContentAnalysisPort(
            SensitiveContentAnalysisPort defaultProvider,
            Map<TenantId, SensitiveContentAnalysisPort> tenantProviders) {
        this(defaultProvider, tenantProviders, false);
    }

    public TenantRoutingSensitiveContentAnalysisPort(
            SensitiveContentAnalysisPort defaultProvider,
            Map<TenantId, SensitiveContentAnalysisPort> tenantProviders,
            boolean requireExplicitTenantProfile) {
        this.defaultProvider = Objects.requireNonNull(defaultProvider, "defaultProvider must not be null");
        this.tenantProviders = tenantProviders == null ? Map.of() : Map.copyOf(tenantProviders);
        this.requireExplicitTenantProfile = requireExplicitTenantProfile;
    }

    @Override
    public AnalysisOutput analyze(AnalysisRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        if (request.tenantId() == null) {
            return defaultProvider.analyze(request);
        }
        SensitiveContentAnalysisPort tenantProvider = tenantProviders.get(request.tenantId());
        if (tenantProvider != null) {
            return tenantProvider.analyze(request);
        }
        if (requireExplicitTenantProfile) {
            throw new TechnicalDependencyException(
                    TechnicalDependency.AI,
                    TechnicalFailureCode.AI_UNAVAILABLE,
                    false,
                    "No AI provider profile is configured for this tenant");
        }
        return defaultProvider.analyze(request);
    }
}

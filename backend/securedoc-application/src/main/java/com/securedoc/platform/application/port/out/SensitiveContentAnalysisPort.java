package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Outbound port for AI-first semantic discovery of sensitive content.
 *
 * <p>The analyzer receives normalized page text for every processed document and
 * independently discovers sensitive content. It MUST NOT require a deterministic
 * detector to pre-identify candidates. Deterministic recognizers are a separate,
 * supporting signal that the application merges with this output.
 *
 * <p>The application supplies tenant policy as part of the request. Provider adapters
 * must not hard-code which categories a customer wants redacted. Implementations may
 * use a local/on-prem model or another approved provider. Raw matched text is transient
 * only: callers use it to resolve offsets/locations and must not persist or audit it.
 */
public interface SensitiveContentAnalysisPort {

    AnalysisOutput analyze(AnalysisRequest request);

    /**
     * Compatibility helper for focused adapter tests and development utilities. Production
     * document processing always supplies tenant identity and policy through {@link AnalysisRequest}.
     */
    default AnalysisOutput analyze(List<PageContent> pages) {
        return analyze(new AnalysisRequest(null, pages, AnalysisPolicy.v1Defaults()));
    }

    /**
     * Provider-neutral semantic-analysis request.
     *
     * @param tenantId tenant whose provider profile/policy applies; null only for focused tests
     * @param pages normalized page text
     * @param policy categories/options requested by tenant policy
     */
    record AnalysisRequest(TenantId tenantId, List<PageContent> pages, AnalysisPolicy policy) {
        public AnalysisRequest {
            pages = pages == null ? List.of() : List.copyOf(pages);
            policy = Objects.requireNonNull(policy, "policy must not be null");
        }
    }

    /** Tenant-controlled semantic categories/options; independent of AI provider/model. */
    record AnalysisPolicy(Set<EntityType> enabledEntityTypes, boolean maskSsnLastFour) {
        private static final Set<EntityType> V1_TYPES = Set.of(
                EntityType.SSN,
                EntityType.DATE_OF_BIRTH,
                EntityType.CREDIT_CARD_NUMBER,
                EntityType.CREDIT_SCORE);

        public AnalysisPolicy {
            Objects.requireNonNull(enabledEntityTypes, "enabledEntityTypes must not be null");
            if (!V1_TYPES.containsAll(enabledEntityTypes)) {
                throw new IllegalArgumentException("Analysis policy contains an unsupported V1 entity type");
            }
            enabledEntityTypes = Set.copyOf(enabledEntityTypes);
        }

        public static AnalysisPolicy v1Defaults() {
            return new AnalysisPolicy(EnumSet.copyOf(V1_TYPES), true);
        }
    }

    /** Normalized text for one document page. */
    record PageContent(int pageNumber, String text) {
        public PageContent {
            if (pageNumber < 1) {
                throw new IllegalArgumentException("Page number must be >= 1");
            }
            if (text == null) {
                text = "";
            }
        }
    }

    /**
     * A semantic sensitive-content discovery.
     *
     * @param entityType      normalized/domain-compatible type name
     * @param pageNumber     page containing the sensitive content
     * @param matchedText    exact text as it appears on the normalized page; transient only
     * @param contextText    optional exact surrounding quote used only to resolve the correct occurrence
     * @param confidence     semantic confidence from 0.0 to 1.0
     * @param analyzerName   provider/analyzer identifier for traceability
     * @param analyzerVersion provider/analyzer version when available
     */
    record DiscoveredSensitiveContent(
            String entityType,
            int pageNumber,
            String matchedText,
            String contextText,
            double confidence,
            String analyzerName,
            String analyzerVersion
    ) {
        public DiscoveredSensitiveContent {
            if (entityType == null || entityType.isBlank()) {
                throw new IllegalArgumentException("EntityType must not be blank");
            }
            if (pageNumber < 1) {
                throw new IllegalArgumentException("Page number must be >= 1");
            }
            if (matchedText == null || matchedText.isBlank()) {
                throw new IllegalArgumentException("Matched text must not be blank");
            }
            if (contextText == null) {
                contextText = "";
            }
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("Confidence must be between 0.0 and 1.0");
            }
            if (analyzerName == null || analyzerName.isBlank()) {
                analyzerName = "semantic-analyzer";
            }
        }
    }

    record AnalysisOutput(List<DiscoveredSensitiveContent> findings) {
        public AnalysisOutput {
            findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }
}

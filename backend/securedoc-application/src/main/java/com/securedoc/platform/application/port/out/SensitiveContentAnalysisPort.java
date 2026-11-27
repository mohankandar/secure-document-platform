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
     * Task 0052: whether one request to the AI provider may cover several pages for this tenant, with any provider the
     * tenant may use (failover included). Page batches share pages only then. An implementation that does not say is
     * taken to send several pages (the batches keep their shared pages).
     */
    default boolean mayCoverSeveralPages(TenantId tenantId) {
        return true;
    }

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

    /**
     * Tenant-controlled semantic categories/options; independent of AI provider/model.
     *
     * @param ask what is asked: the kinds (Find), or one of the other questions, each with its own prompt
     */
    record AnalysisPolicy(Set<EntityType> enabledEntityTypes, boolean maskSsnLastFour, Ask ask) {
        public enum Ask {
            KINDS,
            /**
             * Task 0083: the page is of a finished file; one page at a time, and the kinds are the names of
             * {@code VisionPageResult.Kind}.
             */
            SECOND_CHECK,
            /** Task 0065: the one page is the beginning of a document; its people and their other names are asked for. */
            SUMMARY,
            /**
             * Task 0065: each "page" is one sentence with the word asked about; a finding (kind PERSON_NAME, the page's
             * number) means it names a person there, or was not answered.
             */
            KNOWN_NAMES
        }

        /** Task 0065: a person of the summary, and another name of the person before it. */
        public static final String SUMMARY_PERSON = "SUMMARY_PERSON";
        public static final String SUMMARY_ALSO_CALLED = "SUMMARY_ALSO_CALLED";

        public boolean secondCheck() {
            return ask == Ask.SECOND_CHECK;
        }

        public static AnalysisPolicy of(Ask ask) {
            return new AnalysisPolicy(Set.of(), false, ask);
        }

        public AnalysisPolicy(Set<EntityType> enabledEntityTypes, boolean maskSsnLastFour) {
            this(enabledEntityTypes, maskSsnLastFour, Ask.KINDS);
        }

        public static AnalysisPolicy ofSecondCheck() {
            return new AnalysisPolicy(V1_TYPES, true, Ask.SECOND_CHECK);
        }

        private static final Set<EntityType> V1_TYPES = Set.of(
                EntityType.SSN,
                EntityType.DATE_OF_BIRTH,
                EntityType.CREDIT_CARD_NUMBER,
                EntityType.CREDIT_SCORE);

        /** Task 0072: asked for only where the entity turned it on. Task 0103: every kind found in text. */
        private static final Set<EntityType> KNOWN = com.securedoc.platform.domain.business.RedactionCatalog.askedOfAi();

        public AnalysisPolicy {
            Objects.requireNonNull(enabledEntityTypes, "enabledEntityTypes must not be null");
            if (!KNOWN.containsAll(enabledEntityTypes)) {
                throw new IllegalArgumentException("Analysis policy contains an unsupported entity type");
            }
            Objects.requireNonNull(ask, "ask must not be null");
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

        /** Page number and length only: page text holds the values being redacted (task 0015). */
        @Override
        public String toString() {
            return "PageContent[pageNumber=" + pageNumber + ", characters=" + text.length() + "]";
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

        /** Type and page only: a stray log line must never print the found value (task 0015). */
        @Override
        public String toString() {
            return "DiscoveredSensitiveContent[entityType=" + entityType + ", pageNumber=" + pageNumber + "]";
        }
    }

    /** @param models task 0064: the AI model(s) that answered; empty where none did (no text, the heuristic) */
    record AnalysisOutput(List<DiscoveredSensitiveContent> findings,
                          List<com.securedoc.platform.domain.finding.AiModel> models) {
        public AnalysisOutput {
            findings = findings == null ? List.of() : List.copyOf(findings);
            models = models == null ? List.of() : List.copyOf(models);
        }

        public AnalysisOutput(List<DiscoveredSensitiveContent> findings) {
            this(findings, List.of());
        }
    }
}

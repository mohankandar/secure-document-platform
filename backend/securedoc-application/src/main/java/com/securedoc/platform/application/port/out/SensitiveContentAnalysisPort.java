package com.securedoc.platform.application.port.out;

import java.util.List;

/**
 * Outbound port for AI-first semantic discovery of sensitive content.
 *
 * <p>The analyzer receives normalized page text for every processed document and
 * independently discovers sensitive content. It MUST NOT require a deterministic
 * detector to pre-identify candidates. Deterministic recognizers are a separate,
 * supporting signal that the application merges with this output.
 *
 * <p>Implementations may use a local/on-prem model or another approved provider.
 * Raw matched text is transient only: callers use it to resolve offsets/locations
 * and must not persist or audit it.
 */
public interface SensitiveContentAnalysisPort {

    AnalysisOutput analyze(List<PageContent> pages);

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

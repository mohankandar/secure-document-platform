package com.securedoc.platform.application.port.out;

import java.util.List;

/**
 * Outbound port for deterministic/rule-based sensitive-data recognition and validation.
 * This port runs in parallel with the AI-first {@link SensitiveContentAnalysisPort}; it is
 * supporting evidence and must not gate semantic AI discovery.
 *
 * <p>The application layer depends only on this interface — never on regex implementation details.
 */
public interface SensitiveDataDetectionPort {

    /**
     * Detects sensitive data in the given text pages.
     *
     * @param pages the extracted text pages to scan
     * @return detection results with per-finding metadata (never raw matched values)
     */
    DetectionOutput detect(List<PageInput> pages);

    /**
     * Input representing a single page of extracted text.
     */
    record PageInput(int pageNumber, String text) {
        public PageInput {
            if (pageNumber < 1) {
                throw new IllegalArgumentException("Page number must be >= 1");
            }
            if (text == null) {
                text = "";
            }
        }
    }

    /**
     * Output of the detection process.
     */
    record DetectionOutput(List<DetectedEntity> entities) {
        public DetectionOutput {
            if (entities == null) {
                entities = List.of();
            } else {
                entities = List.copyOf(entities);
            }
        }

        public int totalCount() {
            return entities.size();
        }

        public boolean hasFindings() {
            return !entities.isEmpty();
        }
    }

    /**
     * A single detected sensitive entity.
     * Contains safe metadata only — maskedPreview is a masked representation, never the raw value.
     */
    record DetectedEntity(
            String entityType,
            int pageNumber,
            int startOffset,
            int endOffset,
            double confidence,
            String maskedPreview,
            String detectorName,
            String detectorVersion
    ) {
        public DetectedEntity {
            if (entityType == null || entityType.isBlank()) {
                throw new IllegalArgumentException("EntityType must not be blank");
            }
            if (pageNumber < 1) {
                throw new IllegalArgumentException("Page number must be >= 1");
            }
            if (confidence < 0.0 || confidence > 1.0) {
                throw new IllegalArgumentException("Confidence must be between 0.0 and 1.0");
            }
            if (maskedPreview == null) {
                maskedPreview = "***";
            }
        }
    }
}


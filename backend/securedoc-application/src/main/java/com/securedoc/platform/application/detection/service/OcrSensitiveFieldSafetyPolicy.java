package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.DetectedEntity;
import com.securedoc.platform.application.port.out.TextExtractionPort.ExtractionSource;
import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.domain.finding.EntityType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fail-safe for structured sensitive fields recovered from OCR.
 *
 * <p>This is intentionally scoped to OCR pages and to strong field-label syntax. It does not
 * replace AI or deterministic discovery. Instead it protects the V1 workflow from a silent miss
 * when OCR clearly recovered a sensitive field label but the downstream detector did not create
 * a finding. A confidently recognized credit-score value is converted into a normal located
 * finding so it can be auto-redacted. A non-empty but unresolved value becomes an unlocated
 * review signal so the package cannot silently export.</p>
 */
final class OcrSensitiveFieldSafetyPolicy {

    private static final String DETECTOR_NAME = "ocr-sensitive-field-guard";
    private static final String DETECTOR_VERSION = "1.0.0";
    private static final double LOCATED_SCORE_CONFIDENCE = 0.92;
    private static final double REVIEW_SIGNAL_CONFIDENCE = 0.50;

    // Requiring an explicit field delimiter avoids turning privacy prose such as
    // "Social Security number and income" into a review item.
    private static final Pattern FIELD_LINE = Pattern.compile(
            "(?im)^[ \t]*(social[ \t]+security(?:[ \t]+number)?|ssn|date[ \t]+of[ \t]+birth|dob|"
                    + "credit[ \t]+card(?:[ \t]+number)?|card[ \t]+number|credit[ \t]+score)"
                    + "[ \t]*([:#-])[ \t]*(.*?)[ \t]*$");

    private static final Pattern EXACT_CREDIT_SCORE = Pattern.compile("^(\\d{3})$");
    private static final Pattern FULL_SSN = Pattern.compile("^\\d{3}[- ]\\d{2}[- ]\\d{4}$");
    private static final Pattern DOB = Pattern.compile(
            "^(?:0?[1-9]|1[0-2])[/-](?:0?[1-9]|[12]\\d|3[01])[/-](?:19|20)\\d{2}$");
    private static final Pattern FULL_PAN = Pattern.compile("^(?:\\d[ -]?){13,19}$");
    private static final Pattern MASKED_CARD = Pattern.compile(
            "(?i)^(?=.*[x*])(?:[x*0-9][ -]?){4,25}$");
    private static final Pattern SAFE_EMPTY_VALUE = Pattern.compile(
            "(?i)^(?:n/?a|none|not\\s+applicable|blank|unknown|not\\s+provided)?$");

    Evaluation evaluate(Map<Integer, PageText> pagesByNumber,
                        List<DetectedEntity> existingEntities,
                        Set<EntityType> enabledTypes) {
        List<DetectedEntity> located = new ArrayList<>();
        Set<ReviewSignal> reviewSignals = new LinkedHashSet<>();

        for (PageText page : pagesByNumber.values()) {
            if (page.source() != ExtractionSource.OCR || page.text().isBlank()) {
                continue;
            }

            Matcher matcher = FIELD_LINE.matcher(page.text());
            while (matcher.find()) {
                EntityType type = fieldType(matcher.group(1));
                if (type == null || !enabledTypes.contains(type)) {
                    continue;
                }

                String rawValue = matcher.group(3) == null ? "" : matcher.group(3).trim();
                if (rawValue.isBlank() || SAFE_EMPTY_VALUE.matcher(rawValue).matches()) {
                    continue;
                }

                int valueStart = matcher.start(3);
                int valueEnd = matcher.end(3);

                if (hasExistingCoverage(existingEntities, located, type,
                        page.pageNumber(), valueStart, valueEnd)) {
                    continue;
                }

                if (type == EntityType.CREDIT_SCORE) {
                    Matcher score = EXACT_CREDIT_SCORE.matcher(rawValue);
                    if (score.matches()) {
                        int numeric = Integer.parseInt(score.group(1));
                        if (numeric >= 300 && numeric <= 850) {
                            int scoreStart = valueStart + score.start(1);
                            int scoreEnd = valueStart + score.end(1);
                            located.add(new DetectedEntity(
                                    EntityType.CREDIT_SCORE.name(), page.pageNumber(),
                                    scoreStart, scoreEnd, LOCATED_SCORE_CONFIDENCE,
                                    "***", DETECTOR_NAME, DETECTOR_VERSION));
                            continue;
                        }
                    }
                }

                // Known, already-safe representations should not create review noise. These checks
                // are only exemptions; actual discovery remains owned by the AI/deterministic path.
                if (type == EntityType.CREDIT_CARD_NUMBER && MASKED_CARD.matcher(rawValue).matches()) {
                    continue;
                }

                if (isStructurallyRecognizable(type, rawValue)) {
                    // The normal detectors should have found a structurally clear value. If they did
                    // not, fail safe to review rather than silently allowing export.
                    reviewSignals.add(new ReviewSignal(type, page.pageNumber(), REVIEW_SIGNAL_CONFIDENCE));
                    continue;
                }

                // A strong OCR field label with a non-empty but ambiguous value (for example
                // "Credit Score: 76B") is exactly the case that must pause for human review.
                reviewSignals.add(new ReviewSignal(type, page.pageNumber(), REVIEW_SIGNAL_CONFIDENCE));
            }
        }

        return new Evaluation(located, List.copyOf(reviewSignals));
    }

    private boolean isStructurallyRecognizable(EntityType type, String rawValue) {
        return switch (type) {
            case SSN -> FULL_SSN.matcher(rawValue).matches();
            case DATE_OF_BIRTH -> DOB.matcher(rawValue).matches();
            case CREDIT_CARD_NUMBER -> FULL_PAN.matcher(rawValue).matches();
            case CREDIT_SCORE -> EXACT_CREDIT_SCORE.matcher(rawValue).matches();
            default -> false;
        };
    }

    private boolean hasExistingCoverage(List<DetectedEntity> existing,
                                        List<DetectedEntity> newlyLocated,
                                        EntityType type,
                                        int pageNumber,
                                        int valueStart,
                                        int valueEnd) {
        return existing.stream().anyMatch(entity -> overlaps(entity, type, pageNumber, valueStart, valueEnd))
                || newlyLocated.stream().anyMatch(entity -> overlaps(entity, type, pageNumber, valueStart, valueEnd));
    }

    private boolean overlaps(DetectedEntity entity,
                             EntityType type,
                             int pageNumber,
                             int valueStart,
                             int valueEnd) {
        if (entity.pageNumber() != pageNumber || parseType(entity.entityType()) != type) {
            return false;
        }
        return entity.startOffset() < valueEnd && valueStart < entity.endOffset();
    }

    private EntityType fieldType(String label) {
        String normalized = label.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
        if (normalized.equals("ssn") || normalized.startsWith("social security")) {
            return EntityType.SSN;
        }
        if (normalized.equals("dob") || normalized.equals("date of birth")) {
            return EntityType.DATE_OF_BIRTH;
        }
        if (normalized.contains("credit card") || normalized.equals("card number")) {
            return EntityType.CREDIT_CARD_NUMBER;
        }
        if (normalized.equals("credit score")) {
            return EntityType.CREDIT_SCORE;
        }
        return null;
    }

    private EntityType parseType(String raw) {
        if (raw == null) {
            return EntityType.CUSTOM;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        if ("CREDIT_CARD".equals(normalized)) {
            normalized = "CREDIT_CARD_NUMBER";
        }
        try {
            return EntityType.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return EntityType.CUSTOM;
        }
    }

    record ReviewSignal(EntityType entityType, int pageNumber, double confidence) {
    }

    record Evaluation(List<DetectedEntity> locatedEntities, List<ReviewSignal> reviewSignals) {
        Evaluation {
            locatedEntities = List.copyOf(locatedEntities == null ? List.of() : locatedEntities);
            reviewSignals = List.copyOf(reviewSignals == null ? List.of() : reviewSignals);
        }
    }
}

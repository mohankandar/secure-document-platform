package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.DetectedEntity;
import com.securedoc.platform.application.port.out.TextExtractionPort.ExtractionSource;
import com.securedoc.platform.application.port.out.TextExtractionPort.PageText;
import com.securedoc.platform.application.port.out.TextExtractionPort.TextSpan;
import com.securedoc.platform.domain.finding.EntityType;

import java.util.ArrayList;
import java.util.Comparator;
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
 * a usable finding. A confidently recognized credit-score value is converted into a normal
 * located finding so it can be auto-redacted. A non-empty but unresolved field becomes an
 * unlocated review signal so the package cannot silently export.</p>
 *
 * <p>Important: OCR engines do not guarantee logical text order. On forms they may emit labels
 * in one block and values in another block even when the words occupy the same visual row. The
 * credit-score guard therefore has a geometry-based fallback using OCR spans in addition to the
 * normalized text-line path.</p>
 */
final class OcrSensitiveFieldSafetyPolicy {

    private static final String DETECTOR_NAME = "ocr-sensitive-field-guard";
    private static final String DETECTOR_VERSION = "1.2.0";
    private static final double LOCATED_SCORE_CONFIDENCE = 0.94;
    private static final double REVIEW_SIGNAL_CONFIDENCE = 0.50;

    // Requiring an explicit field delimiter avoids turning privacy prose such as
    // "Social Security number and income" into a review item.
    private static final Pattern FIELD_LINE = Pattern.compile(
            "(?im)^[ \\t]*(social[ \\t]+security(?:[ \\t]+number)?|ssn|date[ \\t]+of[ \\t]+birth|dob|"
                    + "credit[ \\t]+card(?:[ \\t]+number)?|card[ \\t]+number|credit[ \\t]+score)"
                    + "[ \\t]*([:#-])[ \\t]*(.*?)[ \\t]*$");

    private static final Pattern EXACT_CREDIT_SCORE = Pattern.compile("^(\\d{3})$");
    private static final Pattern FULL_SSN = Pattern.compile("^\\d{3}[- ]\\d{2}[- ]\\d{4}$");
    private static final Pattern DOB = Pattern.compile(
            "^(?:0?[1-9]|1[0-2])[/-](?:0?[1-9]|[12]\\d|3[01])[/-](?:19|20)\\d{2}$");
    private static final Pattern FULL_PAN = Pattern.compile("^(?:\\d[ -]?){13,19}$");
    private static final Pattern MASKED_CARD = Pattern.compile(
            "(?i)^(?=.*[x*])(?:[x*0-9][ -]?){4,25}$");
    private static final Pattern SAFE_EMPTY_VALUE = Pattern.compile(
            "(?i)^(?:n/?a|none|not\\s+applicable|blank|unknown|not\\s+provided)?$");

    // Geometry fallback thresholds. Normal prose word spacing is much tighter than a form's
    // label/value gap. The minimum gap keeps "credit score and payment history" from looking like
    // a field. The maximum gap prevents binding a label to an unrelated value across the page.
    private static final double MAX_FIELD_VALUE_GAP = 0.60;
    private static final double SAME_ROW_CENTER_TOLERANCE = 0.018;
    private static final double VALUE_ROW_CENTER_TOLERANCE = 0.030;

    Evaluation evaluate(Map<Integer, PageText> pagesByNumber,
                        List<DetectedEntity> existingEntities,
                        Set<EntityType> enabledTypes) {
        List<DetectedEntity> located = new ArrayList<>();
        Set<ReviewSignal> reviewSignals = new LinkedHashSet<>();
        int strongLabelCount = 0;
        int spatialScoreCandidateCount = 0;

        for (PageText page : pagesByNumber.values()) {
            if (page.source() != ExtractionSource.OCR || page.text().isBlank()) {
                continue;
            }

            Set<EntityType> unresolvedBlankLabels = new LinkedHashSet<>();

            // First use the normalized textual representation. This remains the preferred path
            // for ordinary OCR forms where label and value were reconstructed onto one line.
            Matcher matcher = FIELD_LINE.matcher(page.text());
            while (matcher.find()) {
                EntityType type = fieldType(matcher.group(1));
                if (type == null || !enabledTypes.contains(type)) {
                    continue;
                }
                strongLabelCount++;

                String rawValue = matcher.group(3) == null ? "" : matcher.group(3).trim();
                if (rawValue.isBlank()) {
                    // OCR may separate a visually adjacent value onto another logical line. A strong
                    // sensitive field label with no already-resolved value on this page is therefore
                    // unresolved, not safe. Defer the score decision until the geometry fallback has
                    // a chance to recover it; otherwise create a review signal below.
                    if (!hasTypeOnPage(existingEntities, located, type, page.pageNumber())) {
                        unresolvedBlankLabels.add(type);
                    }
                    continue;
                }
                if (SAFE_EMPTY_VALUE.matcher(rawValue).matches()) {
                    continue;
                }

                int valueStart = matcher.start(3);
                int valueEnd = matcher.end(3);

                if (type == EntityType.CREDIT_SCORE) {
                    DetectedEntity scoreEntity = exactScoreEntity(page.pageNumber(), rawValue, valueStart);
                    if (scoreEntity != null) {
                        // Do not suppress the guard merely because another detector already reported
                        // the same score. mergeEvidence() will deduplicate it and independent guard
                        // evidence can raise an otherwise-low-confidence OCR score above the automatic
                        // redaction threshold. This was a hole in R7.
                        if (!hasNewCoverage(located, EntityType.CREDIT_SCORE,
                                page.pageNumber(), scoreEntity.startOffset(), scoreEntity.endOffset())) {
                            located.add(scoreEntity);
                        }
                        unresolvedBlankLabels.remove(EntityType.CREDIT_SCORE);
                        continue;
                    }
                }

                if (hasExistingCoverage(existingEntities, located, type,
                        page.pageNumber(), valueStart, valueEnd)) {
                    continue;
                }

                // Known, already-safe representations should not create review noise. These checks
                // are only exemptions; actual discovery remains owned by the AI/deterministic path.
                if (type == EntityType.CREDIT_CARD_NUMBER && MASKED_CARD.matcher(rawValue).matches()) {
                    continue;
                }

                // Any explicit non-empty sensitive field that is not safely resolved must pause.
                reviewSignals.add(new ReviewSignal(type, page.pageNumber(), REVIEW_SIGNAL_CONFIDENCE));
            }

            // Second, resolve a credit-score field from OCR geometry. This intentionally does not
            // depend on newline placement, block ordering, or whether Tesseract preserved the colon.
            if (enabledTypes.contains(EntityType.CREDIT_SCORE)) {
                SpatialScoreResult spatial = resolveSpatialCreditScore(page);
                if (spatial.strongLabelSeen()) {
                    strongLabelCount++;
                }
                if (spatial.candidateSeen()) {
                    spatialScoreCandidateCount++;
                }
                if (spatial.locatedEntity() != null) {
                    DetectedEntity scoreEntity = spatial.locatedEntity();
                    if (!hasNewCoverage(located, EntityType.CREDIT_SCORE,
                            page.pageNumber(), scoreEntity.startOffset(), scoreEntity.endOffset())) {
                        located.add(scoreEntity);
                    }
                    unresolvedBlankLabels.remove(EntityType.CREDIT_SCORE);
                } else if (spatial.requiresReview()) {
                    reviewSignals.add(new ReviewSignal(
                            EntityType.CREDIT_SCORE, page.pageNumber(), REVIEW_SIGNAL_CONFIDENCE));
                    unresolvedBlankLabels.remove(EntityType.CREDIT_SCORE);
                }
            }

            // Final fail-safe: a strong OCR field label must never silently disappear. If its
            // value was blank in logical OCR text and no detector/geometry fallback resolved that
            // entity type anywhere on this page, force Manual Review instead of allowing export.
            for (EntityType unresolvedType : unresolvedBlankLabels) {
                if (!hasTypeOnPage(existingEntities, located, unresolvedType, page.pageNumber())) {
                    reviewSignals.add(new ReviewSignal(
                            unresolvedType, page.pageNumber(), REVIEW_SIGNAL_CONFIDENCE));
                }
            }
        }

        return new Evaluation(located, List.copyOf(reviewSignals), strongLabelCount, spatialScoreCandidateCount);
    }

    private DetectedEntity exactScoreEntity(int pageNumber, String rawValue, int valueStart) {
        Matcher score = EXACT_CREDIT_SCORE.matcher(rawValue);
        if (!score.matches()) {
            return null;
        }
        int numeric = Integer.parseInt(score.group(1));
        if (numeric < 300 || numeric > 850) {
            return null;
        }
        int scoreStart = valueStart + score.start(1);
        int scoreEnd = valueStart + score.end(1);
        return new DetectedEntity(
                EntityType.CREDIT_SCORE.name(), pageNumber,
                scoreStart, scoreEnd, LOCATED_SCORE_CONFIDENCE,
                "***", DETECTOR_NAME, DETECTOR_VERSION);
    }

    /**
     * Finds a visual "Credit Score" label and the nearest OCR token to its right on the same row.
     * This survives Tesseract output such as labels-first/values-second block ordering because the
     * OCR spans retain the real page coordinates and offsets into the normalized text.
     */
    private SpatialScoreResult resolveSpatialCreditScore(PageText page) {
        if (page.spans().isEmpty()) {
            return SpatialScoreResult.none();
        }

        List<OcrToken> tokens = page.spans().stream()
                .map(span -> token(page.text(), span))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator
                        .comparingDouble(OcrToken::verticalCenter)
                        .thenComparingDouble(OcrToken::left))
                .toList();

        boolean strongLabelSeen = false;
        boolean candidateSeen = false;
        boolean unresolvedCandidate = false;

        for (int i = 0; i < tokens.size() - 1; i++) {
            OcrToken credit = tokens.get(i);
            if (!"credit".equals(credit.normalized())) {
                continue;
            }

            OcrToken score = findScoreTokenSameRow(tokens, i + 1, credit);
            if (score == null) {
                continue;
            }
            strongLabelSeen = true;

            OcrToken candidate = nearestValueTokenToRight(tokens, score);
            if (candidate == null) {
                continue;
            }
            candidateSeen = true;

            Matcher exact = EXACT_CREDIT_SCORE.matcher(candidate.normalizedNumeric());
            if (exact.matches()) {
                int numeric = Integer.parseInt(exact.group(1));
                if (numeric >= 300 && numeric <= 850) {
                    // Candidate token may include harmless punctuation. Resolve the digit offsets
                    // inside the original OCR token so redaction targets only the value glyphs.
                    String original = candidate.raw();
                    Matcher digits = Pattern.compile("\\d{3}").matcher(original);
                    if (digits.find()) {
                        return new SpatialScoreResult(
                                new DetectedEntity(
                                        EntityType.CREDIT_SCORE.name(), page.pageNumber(),
                                        candidate.startOffset() + digits.start(),
                                        candidate.startOffset() + digits.end(),
                                        LOCATED_SCORE_CONFIDENCE,
                                        "***", DETECTOR_NAME, DETECTOR_VERSION),
                                true, true, false);
                    }
                }
            }

            // A non-empty, form-like value exists beside a strong Credit Score label but cannot be
            // safely interpreted (e.g. 76B). It must block export and be reviewed.
            unresolvedCandidate = true;
        }

        return new SpatialScoreResult(null, strongLabelSeen, candidateSeen, unresolvedCandidate);
    }

    private OcrToken findScoreTokenSameRow(List<OcrToken> tokens, int startIndex, OcrToken credit) {
        for (int j = startIndex; j < tokens.size(); j++) {
            OcrToken candidate = tokens.get(j);
            if (candidate.verticalCenter() - credit.verticalCenter() > SAME_ROW_CENTER_TOLERANCE) {
                break;
            }
            if (!sameVisualRow(credit, candidate) || candidate.left() <= credit.left()) {
                continue;
            }
            if (candidate.left() - credit.right() > 0.12) {
                break;
            }
            if ("score".equals(candidate.normalized())) {
                return candidate;
            }
        }
        return null;
    }

    private OcrToken nearestValueTokenToRight(List<OcrToken> tokens, OcrToken labelEnd) {
        OcrToken best = null;
        double bestScore = Double.MAX_VALUE;
        for (OcrToken candidate : tokens) {
            if (candidate.left() <= labelEnd.right()) {
                continue;
            }
            double gap = candidate.left() - labelEnd.right();
            if (gap > MAX_FIELD_VALUE_GAP) {
                continue;
            }

            double centerGap = Math.abs(candidate.verticalCenter() - labelEnd.verticalCenter());
            double adaptiveTolerance = Math.max(
                    VALUE_ROW_CENTER_TOLERANCE,
                    2.5 * Math.max(labelEnd.height(), candidate.height()));
            if (!sameVisualRow(labelEnd, candidate) && centerGap > adaptiveTolerance) {
                continue;
            }

            // Prefer the closest visual row first and horizontal distance second. This is more
            // tolerant of small Windows/Tesseract box shifts while still keeping adjacent form
            // rows separate.
            double score = (centerGap * 10.0) + gap;
            if (score < bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    private boolean sameVisualRow(OcrToken left, OcrToken right) {
        double overlap = Math.max(0.0,
                Math.min(left.bottom(), right.bottom()) - Math.max(left.top(), right.top()));
        double minHeight = Math.max(0.0001, Math.min(left.height(), right.height()));
        double overlapRatio = overlap / minHeight;
        double centerGap = Math.abs(left.verticalCenter() - right.verticalCenter());
        return overlapRatio >= 0.30 || centerGap <= SAME_ROW_CENTER_TOLERANCE;
    }

    private OcrToken token(String pageText, TextSpan span) {
        if (span.startOffset() < 0 || span.endOffset() > pageText.length()
                || span.endOffset() <= span.startOffset()) {
            return null;
        }
        String raw = pageText.substring(span.startOffset(), span.endOffset()).trim();
        if (raw.isBlank()) {
            return null;
        }
        String normalized = raw.toLowerCase(Locale.ROOT)
                .replaceAll("^[^a-z0-9]+|[^a-z0-9]+$", "");
        String normalizedNumeric = raw.replaceAll("[^0-9]", "");
        return new OcrToken(raw, normalized, normalizedNumeric,
                span.startOffset(), span.endOffset(),
                span.left(), span.top(), span.width(), span.height());
    }


    private boolean hasTypeOnPage(List<DetectedEntity> existing,
                                  List<DetectedEntity> newlyLocated,
                                  EntityType type,
                                  int pageNumber) {
        return existing.stream().anyMatch(entity ->
                entity.pageNumber() == pageNumber && parseType(entity.entityType()) == type)
                || newlyLocated.stream().anyMatch(entity ->
                entity.pageNumber() == pageNumber && parseType(entity.entityType()) == type);
    }

    private boolean hasExistingCoverage(List<DetectedEntity> existing,
                                        List<DetectedEntity> newlyLocated,
                                        EntityType type,
                                        int pageNumber,
                                        int valueStart,
                                        int valueEnd) {
        return existing.stream().anyMatch(entity -> overlaps(entity, type, pageNumber, valueStart, valueEnd))
                || hasNewCoverage(newlyLocated, type, pageNumber, valueStart, valueEnd);
    }

    private boolean hasNewCoverage(List<DetectedEntity> newlyLocated,
                                   EntityType type,
                                   int pageNumber,
                                   int valueStart,
                                   int valueEnd) {
        return newlyLocated.stream().anyMatch(entity -> overlaps(entity, type, pageNumber, valueStart, valueEnd));
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

    private record OcrToken(String raw, String normalized, String normalizedNumeric,
                            int startOffset, int endOffset,
                            double left, double top, double width, double height) {
        double right() {
            return left + width;
        }

        double bottom() {
            return top + height;
        }

        double verticalCenter() {
            return top + (height / 2.0);
        }
    }

    private record SpatialScoreResult(DetectedEntity locatedEntity,
                                      boolean strongLabelSeen,
                                      boolean candidateSeen,
                                      boolean requiresReview) {
        static SpatialScoreResult none() {
            return new SpatialScoreResult(null, false, false, false);
        }
    }

    record ReviewSignal(EntityType entityType, int pageNumber, double confidence) {
    }

    record Evaluation(List<DetectedEntity> locatedEntities,
                      List<ReviewSignal> reviewSignals,
                      int strongLabelCount,
                      int spatialScoreCandidateCount) {
        Evaluation {
            locatedEntities = List.copyOf(locatedEntities == null ? List.of() : locatedEntities);
            reviewSignals = List.copyOf(reviewSignals == null ? List.of() : reviewSignals);
        }
    }
}

package com.securedoc.platform.domain.document;

/**
 * How sensitive a case or document is, as set by the source system (task 0021). Listed lowest to highest.
 *
 * <p>LexaVault never decides or changes a level: it keeps what it was given and passes it on unchanged when it
 * exports. The level limits nothing inside LexaVault.
 */
public enum SensitivityLevel {
    STANDARD,
    CONFIDENTIAL,
    HIGHLY_CONFIDENTIAL;

    /** Warning recorded when a source sends a value that is not one of the exact names. */
    public static final String UNKNOWN_LEVEL_WARNING = "LV-INT-005";

    /** Plain words for the warning, as the error code list will carry them. */
    public static final String UNKNOWN_LEVEL_WARNING_TEXT = "Unknown sensitivity level, taken as Standard";

    /**
     * Reads a level as a source sent it. Nothing sent ({@code null}) is {@link #STANDARD}. Only an exact name counts:
     * anything else (unknown, wrongly cased, padded, blank) is never refused; it is taken as {@link #STANDARD} and
     * flagged so the caller can record {@link #UNKNOWN_LEVEL_WARNING}.
     */
    public static Received fromSource(String sent) {
        if (sent == null) return new Received(STANDARD, false);
        for (SensitivityLevel level : values()) {
            if (level.name().equals(sent)) return new Received(level, false);
        }
        return new Received(STANDARD, true);
    }

    /**
     * The effective level of a document: the higher of its own level and its case's level. A missing level counts as
     * {@link #STANDARD}. This is the one place for the rule.
     */
    public static SensitivityLevel effective(SensitivityLevel documentLevel, SensitivityLevel caseLevel) {
        SensitivityLevel document = documentLevel == null ? STANDARD : documentLevel;
        SensitivityLevel kase = caseLevel == null ? STANDARD : caseLevel;
        return document.compareTo(kase) >= 0 ? document : kase;
    }

    /**
     * The effective level of a whole case: the highest of the case's own level and the effective level of each of its
     * documents (which, by {@link #effective}, is the highest of the case level and every document level).
     */
    public static SensitivityLevel effectiveForCase(SensitivityLevel caseLevel,
                                                    java.util.Collection<SensitivityLevel> documentLevels) {
        SensitivityLevel highest = caseLevel == null ? STANDARD : caseLevel;
        if (documentLevels != null) {
            for (SensitivityLevel documentLevel : documentLevels) highest = effective(documentLevel, highest);
        }
        return highest;
    }

    /** A level as read from a source, and whether the value sent was unknown (and so taken as Standard). */
    public record Received(SensitivityLevel level, boolean unknown) {
        public Received {
            if (level == null) throw new IllegalArgumentException("Sensitivity level must not be null");
        }

        public static Received standard() {
            return new Received(STANDARD, false);
        }

        public static Received of(SensitivityLevel level) {
            return new Received(level == null ? STANDARD : level, false);
        }
    }
}

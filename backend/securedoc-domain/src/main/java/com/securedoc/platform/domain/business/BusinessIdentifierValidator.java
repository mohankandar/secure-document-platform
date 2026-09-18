package com.securedoc.platform.domain.business;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Validates and canonicalizes one identifier value according to its tenant definition.
 * The original display value is kept separately by {@link BusinessIdentifier}; this class
 * returns the canonical value used for matching/searching and therefore preserves leading zeroes.
 */
public final class BusinessIdentifierValidator {
    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\t]]");

    private BusinessIdentifierValidator() {}

    public static String validateAndNormalize(BusinessIdentifierDefinition definition, String rawValue) {
        if (definition == null) throw new IllegalArgumentException("identifier definition is required");
        String display = requireValue(rawValue);
        if (CONTROL.matcher(display).find()) {
            throw invalid(definition, "must not contain control characters");
        }

        String canonical = display.replaceAll("\\s+", " ");
        if (definition.stripSeparators()) {
            canonical = canonical.replace("-", "").replace(" ", "");
        }
        canonical = switch (definition.caseNormalization()) {
            case PRESERVE -> canonical;
            case UPPER -> canonical.toUpperCase(Locale.ROOT);
            case LOWER -> canonical.toLowerCase(Locale.ROOT);
        };
        if (canonical.length() > 512) {
            throw invalid(definition, "must contain no more than 512 characters");
        }

        validateShape(definition, canonical);
        validateLength(definition, canonical);
        validateAllowedValues(definition, canonical);
        return canonical;
    }

    public static void validateConfiguration(BusinessIdentifierValidationType validationType,
                                             Integer exactLength,
                                             Integer minLength,
                                             Integer maxLength,
                                             Set<String> allowedValues,
                                             String customPattern) {
        BusinessIdentifierValidationType type = validationType == null ? BusinessIdentifierValidationType.TEXT : validationType;
        Set<String> allowed = allowedValues == null ? Set.of() : allowedValues;
        Integer exact = exactLength;
        Integer min = minLength;
        Integer max = maxLength;
        if (exact != null && exact <= 0) throw new IllegalArgumentException("exactLength must be > 0");
        if (min != null && min <= 0) throw new IllegalArgumentException("minLength must be > 0");
        if (max != null && max <= 0) throw new IllegalArgumentException("maxLength must be > 0");
        if (exact != null && (min != null || max != null)) {
            throw new IllegalArgumentException("exactLength cannot be combined with minLength/maxLength");
        }
        if (min != null && max != null && min > max) {
            throw new IllegalArgumentException("minLength must be <= maxLength");
        }
        if ((exact != null && exact > 512) || (min != null && min > 512) || (max != null && max > 512)) {
            throw new IllegalArgumentException("identifier length constraints cannot exceed 512");
        }
        if (type == BusinessIdentifierValidationType.ALLOW_LIST && allowed.isEmpty()) {
            throw new IllegalArgumentException("allowedValues are required for ALLOW_LIST validation");
        }
        if (type == BusinessIdentifierValidationType.CUSTOM_PATTERN) {
            if (customPattern == null || customPattern.isBlank()) {
                throw new IllegalArgumentException("customPattern is required for CUSTOM_PATTERN validation");
            }
            if (customPattern.length() > 512) {
                throw new IllegalArgumentException("customPattern must be 512 characters or fewer");
            }
            try {
                Pattern.compile(customPattern);
            } catch (PatternSyntaxException e) {
                throw new IllegalArgumentException("customPattern is not a valid regular expression");
            }
        }
        if (allowed.size() > 100) {
            throw new IllegalArgumentException("allowedValues cannot contain more than 100 values");
        }
        for (String value : allowed) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("allowedValues must not contain blank values");
            }
            if (value.length() > 512 || value.contains("\n") || value.contains("\r")) {
                throw new IllegalArgumentException("allowedValues entries must be single-line values up to 512 characters");
            }
        }
    }

    private static void validateShape(BusinessIdentifierDefinition definition, String canonical) {
        boolean matches = switch (definition.validationType()) {
            case TEXT -> true;
            case NUMERIC -> canonical.matches("[0-9]+");
            case ALPHANUMERIC -> canonical.matches("[A-Za-z0-9]+");
            case EMAIL -> EMAIL.matcher(canonical).matches();
            case UUID -> isUuid(canonical);
            case ALLOW_LIST -> true;
            case CUSTOM_PATTERN -> Pattern.compile(definition.customPattern()).matcher(canonical).matches();
        };
        if (!matches) {
            String expectation = switch (definition.validationType()) {
                case TEXT -> "has an invalid value";
                case NUMERIC -> "must contain digits only";
                case ALPHANUMERIC -> "must contain letters and digits only";
                case EMAIL -> "must be a valid email address";
                case UUID -> "must be a valid UUID";
                case ALLOW_LIST -> "must be one of the configured allowed values";
                case CUSTOM_PATTERN -> "does not match the configured format";
            };
            throw invalid(definition, expectation);
        }
    }

    private static void validateLength(BusinessIdentifierDefinition definition, String canonical) {
        int length = canonical.length();
        if (definition.exactLength() != null && length != definition.exactLength()) {
            throw invalid(definition, "must contain exactly " + definition.exactLength() + " characters");
        }
        if (definition.minLength() != null && length < definition.minLength()) {
            throw invalid(definition, "must contain at least " + definition.minLength() + " characters");
        }
        if (definition.maxLength() != null && length > definition.maxLength()) {
            throw invalid(definition, "must contain no more than " + definition.maxLength() + " characters");
        }
    }

    private static void validateAllowedValues(BusinessIdentifierDefinition definition, String canonical) {
        Set<String> allowed = definition.allowedValues();
        if (allowed.isEmpty()) return;
        boolean matched = allowed.stream()
                .map(value -> normalizeAllowedValue(definition, value))
                .anyMatch(canonical::equals);
        if (!matched) throw invalid(definition, "must be one of the configured allowed values");
    }

    private static String normalizeAllowedValue(BusinessIdentifierDefinition definition, String value) {
        String canonical = value.trim().replaceAll("\\s+", " ");
        if (definition.stripSeparators()) canonical = canonical.replace("-", "").replace(" ", "");
        return switch (definition.caseNormalization()) {
            case PRESERVE -> canonical;
            case UPPER -> canonical.toUpperCase(Locale.ROOT);
            case LOWER -> canonical.toLowerCase(Locale.ROOT);
        };
    }

    private static boolean isUuid(String value) {
        try {
            return UUID.fromString(value).toString().equalsIgnoreCase(value);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static String requireValue(String value) {
        if (value == null) throw new IllegalArgumentException("identifier value must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) throw new IllegalArgumentException("identifier value must not be blank");
        return trimmed;
    }

    private static IllegalArgumentException invalid(BusinessIdentifierDefinition definition, String message) {
        return new IllegalArgumentException(definition.label() + " " + message);
    }
}

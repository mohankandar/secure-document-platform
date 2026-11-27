package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.application.port.out.SensitiveDataValidationPort.ValidationOutcome;
import com.securedoc.platform.domain.finding.EntityType;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Type-specific plausibility guardrail for candidates that were already discovered by AI.
 *
 * <p>This class is intentionally not a discovery engine. The AI still receives every text-bearing
 * document first and may propose a candidate without any regex seed. This policy only prevents a
 * structurally impossible or contextually contradictory AI classification from being persisted as
 * a finding/review item. Plausible uncertainty continues through normal confidence/review policy.
 */
final class SensitiveCandidatePlausibilityPolicy {

    private static final Pattern BIRTH_CONTEXT = Pattern.compile(
            "(?i)\\b(?:date\\s+of\\s+birth|birth\\s*date|dob|born(?:\\s+on)?|birthday)\\b");

    private static final Pattern NON_BIRTH_DATE_CONTEXT = Pattern.compile(
            "(?i)\\b(?:(?:contract|sale|closing|effective|first\\s+payment|loan\\s+payment|"
                    + "signature|authorization|issue|report|servicing|expiration)\\s+date|date\\s+of\\s+sale|date)\\b");

    private static final Pattern SSN_CONTEXT = Pattern.compile(
            "(?i)\\b(?:ssn|social\\s+security(?:\\s+number)?)\\b");

    /**
     * Labels for tax/business identifiers that can also contain nine digits but are not SSNs.
     * These are intentionally separate from the generic business-identifier pattern because
     * government tax forms frequently present "Federal ID Number OR Social Security Number"
     * in the same visual cell. The candidate must be bound to the nearest applicable label.
     */
    private static final Pattern NON_SSN_TAX_ID_CONTEXT = Pattern.compile(
            "(?i)\\b(?:federal\\s+(?:employer\\s+)?id(?:entification)?(?:\\s+number)?|"
                    + "employer\\s+identification(?:\\s+number)?|e\\.?i\\.?n\\.?|f\\.?e\\.?i\\.?n\\.?|"
                    + "business\\s+(?:tax\\s+)?id(?:entification)?(?:\\s+number)?|"
                    + "taxpayer\\s+identification(?:\\s+number)?|tax\\s+id(?:entification)?(?:\\s+number)?)\\b");

    private static final Pattern CARD_CONTEXT = Pattern.compile(
            "(?i)\\b(?:credit\\s+card|debit\\s+card|card\\s*(?:number|no\\.?|#)|payment\\s+card|"
                    + "pan|visa|master\\s*card|mastercard|amex|american\\s+express|discover)\\b");

    private static final Pattern SCORE_CONTEXT = Pattern.compile(
            "(?i)\\b(?:your\\s+credit\\s+score|credit\\s+score|fico(?:\\s+score)?|"
                    + "vantage(?:score)?(?:\\s+score)?|beacon\\s+score)\\b");

    private static final Pattern SCORE_RANGE_CONTEXT = Pattern.compile(
            "(?i)(?:range\\s+of\\s+scores|scores?\\s+range|range\\s+from|low\\s+of\\s+300|"
                    + "high\\s+of\\s+850|300\\s*(?:to|-)\\s*850|example|illustrative)");

    private static final Pattern BUSINESS_IDENTIFIER_CONTEXT = Pattern.compile(
            "(?i)\\b(?:contract|member|account|loan|reference|ref\\.?|form|document|invoice|order|"
                    + "routing|phone|telephone|servicing|confirmation|reservation)\\s*"
                    + "(?:number|no\\.?|#|id)?\\b");

    private static final List<DateTimeFormatter> DOB_FORMATTERS = List.of(
            strict("M/d/uuuu"),
            strict("M-d-uuuu"),
            strict("uuuu-M-d"),
            textFormatter("MMM d, uuuu"),
            textFormatter("MMMM d, uuuu"),
            textFormatter("d MMM uuuu"),
            textFormatter("d MMMM uuuu")
    );

    Evaluation evaluate(EntityType type,
                        String matchedText,
                        String trustedContext,
                        boolean maskSsnLastFour,
                        ValidationOutcome deterministicValidation) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(deterministicValidation, "deterministicValidation");
        String value = matchedText == null ? "" : matchedText.trim();
        String context = trustedContext == null ? "" : trustedContext;

        return switch (type) {
            case DATE_OF_BIRTH -> evaluateDob(value, context);
            case SSN -> evaluateSsn(value, context, maskSsnLastFour, deterministicValidation);
            case CREDIT_CARD_NUMBER -> evaluateCreditCard(value, context, deterministicValidation);
            case CREDIT_SCORE -> evaluateCreditScore(value, context);
            case PERSON_NAME -> evaluateName(value);
            case BANK_ROUTING_NUMBER, BANK_ACCOUNT_NUMBER, ITIN, EIN, DRIVERS_LICENSE_NUMBER, PASSPORT_NUMBER, CVV, PIN ->
                    evaluateShape(type, value);
            default -> Evaluation.accept();
        };
    }

    /**
     * Task 0103: the kinds the AI is now asked for too. Only the shape is checked (the digits a value of the kind can
     * have); what it is in the sentence is the AI's reading, and an AI-only finding goes to a person anyway.
     */
    private static Evaluation evaluateShape(EntityType type, String value) {
        int digits = digits(value).length();
        boolean onlyDigits = value.matches("[0-9 \\-]+");
        boolean fits = switch (type) {
            case BANK_ROUTING_NUMBER -> digits == 9 && onlyDigits;
            case BANK_ACCOUNT_NUMBER -> digits >= 4 && digits <= 17 && onlyDigits;
            case ITIN -> digits == 9 && onlyDigits && digits(value).startsWith("9");
            case EIN -> digits == 9 && onlyDigits;
            case DRIVERS_LICENSE_NUMBER, PASSPORT_NUMBER -> digits >= 4 && value.length() <= 20
                    && value.matches("[A-Za-z0-9 \\-]+");
            case CVV -> digits >= 3 && digits <= 4 && onlyDigits;
            case PIN -> digits >= 4 && digits <= 6 && onlyDigits;
            default -> true;
        };
        return fits ? Evaluation.accept() : Evaluation.reject(type.name() + "_SHAPE");
    }

    /** Task 0072: a form of a name: letters, no digits, not a sentence. What it means is the AI's and the person's. */
    private static Evaluation evaluateName(String value) {
        boolean name = value.length() >= 2 && value.length() <= 80 && value.chars().noneMatch(Character::isDigit)
                && value.chars().filter(Character::isLetter).count() >= 2 && value.split("\\s+").length <= 8;
        return name ? Evaluation.accept() : Evaluation.reject("NAME_NOT_A_NAME");
    }

    private Evaluation evaluateDob(String value, String context) {
        LocalDate parsed = parsePlausibleBirthDate(value);
        if (parsed == null) {
            return Evaluation.reject("DOB_VALUE_NOT_DATE");
        }
        int birthGap = nearestContextGap(BIRTH_CONTEXT, value, context);
        int nonBirthDateGap = nearestContextGap(NON_BIRTH_DATE_CONTEXT, value, context);
        if (nonBirthDateGap >= 0 && nonBirthDateGap <= 60
                && (birthGap < 0 || nonBirthDateGap <= birthGap)) {
            // The document names the date as another date ("First Payment Date"): clearly something else.
            return Evaluation.reject("DOB_CONTEXT_NOT_BIRTH");
        }
        if (birthGap < 0 || birthGap > 80) {
            // Task 0103: no label either way is not "something else": a person decides.
            return Evaluation.review("DOB_NO_BIRTH_LABEL");
        }
        return Evaluation.accept();
    }

    private Evaluation evaluateSsn(String value,
                                   String context,
                                   boolean maskSsnLastFour,
                                   ValidationOutcome deterministicValidation) {
        String digits = digits(value);
        int ssnGap = nearestContextGap(SSN_CONTEXT, value, context);
        int nonSsnTaxIdGap = nearestContextGap(NON_SSN_TAX_ID_CONTEXT, value, context);
        int businessIdentifierGap = nearestContextGap(BUSINESS_IDENTIFIER_CONTEXT, value, context);
        boolean explicitSsnContext = ssnGap >= 0 && ssnGap <= 80;
        boolean nonSsnTaxIdContext = nonSsnTaxIdGap >= 0 && nonSsnTaxIdGap <= 80;
        boolean businessIdentifierContext = businessIdentifierGap >= 0 && businessIdentifierGap <= 80;

        if (digits.length() == 4) {
            if (!maskSsnLastFour) {
                return Evaluation.reject("SSN_LAST4_POLICY_DISABLED");
            }
            return explicitSsnContext
                    ? Evaluation.accept()
                    : Evaluation.review("SSN_LAST4_WITHOUT_SSN_CONTEXT");
        }

        if (digits.length() != 9) {
            return Evaluation.reject("SSN_VALUE_NOT_NINE_DIGITS");
        }

        // EIN/FEIN and similar business tax IDs are nine digits too. Government forms can place
        // "Federal ID Number" and "Social Security Number" in the same cell, so the presence of
        // SSN wording somewhere nearby is not sufficient. When this exact value is closer to a
        // non-SSN tax-ID label, reject it as an SSN. The common EIN 2-7 rendering is an additional
        // strong signal, but proximity is what keeps a real 3-2-4 SSN below the SSN label valid.
        boolean einShaped = value.matches("\\d{2}[- ]\\d{7}");
        if (einShaped) {
            return Evaluation.reject("SSN_EIN_SHAPE");
        }
        if (nonSsnTaxIdContext
                && (!explicitSsnContext || nonSsnTaxIdGap <= ssnGap)) {
            return Evaluation.review("SSN_NON_PERSON_TAX_ID_CONTEXT");
        }
        if (deterministicValidation == ValidationOutcome.CONTRADICTS) {
            return Evaluation.reject("SSN_INVALID_STRUCTURE");
        }
        if (businessIdentifierContext
                && (!explicitSsnContext || businessIdentifierGap < ssnGap)) {
            return Evaluation.review("SSN_BUSINESS_IDENTIFIER_CONTEXT");
        }
        if (explicitSsnContext) {
            return Evaluation.accept();
        }

        // A classic 3-2-4 rendering is still plausible enough for review even when the page has
        // weak semantic context. A bare nine-digit business identifier is not.
        boolean classicSsnShape = value.matches("\\d{3}[- ]\\d{2}[- ]\\d{4}");
        return classicSsnShape
                ? Evaluation.accept()
                : Evaluation.review("SSN_UNLABELED_COMPACT_NUMBER");
    }

    private Evaluation evaluateCreditCard(String value,
                                           String context,
                                           ValidationOutcome deterministicValidation) {
        String digits = digits(value);
        if (digits.length() < 13 || digits.length() > 19) {
            // V1 policy: last-four-only/masked PAN display is not independently redacted.
            return Evaluation.reject("CARD_NOT_FULL_PAN");
        }

        int cardGap = nearestContextGap(CARD_CONTEXT, value, context);
        int businessIdentifierGap = nearestContextGap(BUSINESS_IDENTIFIER_CONTEXT, value, context);
        boolean cardContext = cardGap >= 0 && cardGap <= 100;
        boolean businessIdentifierContext = businessIdentifierGap >= 0 && businessIdentifierGap <= 80;
        if (businessIdentifierContext && (!cardContext || businessIdentifierGap < cardGap)) {
            return Evaluation.review("CARD_BUSINESS_IDENTIFIER_CONTEXT");
        }
        if (cardContext) {
            // Luhn remains supporting evidence only. A model-found, properly grounded full PAN in
            // explicit card context may still reach review if Luhn contradicts it.
            return Evaluation.accept();
        }
        return deterministicValidation == ValidationOutcome.SUPPORTS
                ? Evaluation.accept()
                : Evaluation.review("CARD_NO_PAYMENT_CONTEXT_OR_LUHN_SUPPORT");
    }

    private Evaluation evaluateCreditScore(String value, String context) {
        if (!value.matches("\\d{3}")) {
            return Evaluation.reject("SCORE_NOT_THREE_DIGITS");
        }
        int score;
        try {
            score = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return Evaluation.reject("SCORE_NOT_NUMERIC");
        }
        if (score < 300 || score > 850) {
            return Evaluation.reject("SCORE_OUT_OF_RANGE");
        }
        // A range the page explains (300 to 850) is clearly not a person's score.
        if (isExplanatoryScoreValue(score, value, context)) {
            return Evaluation.reject("SCORE_EXPLANATORY_RANGE");
        }
        if (!hasAssociatedContext(SCORE_CONTEXT, value, context, 120)) {
            return Evaluation.review("SCORE_NO_CREDIT_CONTEXT");
        }
        return Evaluation.accept();
    }


    private boolean isExplanatoryScoreValue(int score, String value, String context) {
        if (!SCORE_RANGE_CONTEXT.matcher(context).find()) {
            return false;
        }

        // The common explanatory endpoints must never become a customer's score merely because
        // the same page also contains a real credit score.
        if (score == 300 || score == 850) {
            return true;
        }

        String quoted = Pattern.quote(value);
        Pattern candidateInRange = Pattern.compile(
                "(?i)(?:\\b" + quoted + "\\b\\s*(?:to|through|-)\\s*\\d{3}\\b"
                        + "|\\b\\d{3}\\b\\s*(?:to|through|-)\\s*\\b" + quoted + "\\b"
                        + "|(?:low|high)\\s+of\\s+\\b" + quoted + "\\b)");
        return candidateInRange.matcher(context).find();
    }

    /**
     * Returns the smallest character gap between this exact grounded value and a semantic label.
     * A broad page window can contain multiple unrelated fields, so simple label presence is not
     * enough to establish that the label describes this candidate.
     */
    private int nearestContextGap(Pattern labelPattern, String value, String context) {
        if (value == null || value.isBlank() || context == null || context.isBlank()) {
            return -1;
        }

        int nearest = Integer.MAX_VALUE;
        int valueStart = context.indexOf(value);
        while (valueStart >= 0) {
            int valueEnd = valueStart + value.length();
            var labels = labelPattern.matcher(context);
            while (labels.find()) {
                int gap;
                if (labels.end() <= valueStart) {
                    gap = valueStart - labels.end();
                } else if (valueEnd <= labels.start()) {
                    gap = labels.start() - valueEnd;
                } else {
                    gap = 0;
                }
                nearest = Math.min(nearest, gap);
            }
            valueStart = context.indexOf(value, valueStart + 1);
        }
        return nearest == Integer.MAX_VALUE ? -1 : nearest;
    }

    private boolean hasAssociatedContext(Pattern labelPattern,
                                         String value,
                                         String context,
                                         int maxGapChars) {
        int gap = nearestContextGap(labelPattern, value, context);
        return gap >= 0 && gap <= maxGapChars;
    }

    private LocalDate parsePlausibleBirthDate(String value) {
        LocalDate today = LocalDate.now();
        for (DateTimeFormatter formatter : DOB_FORMATTERS) {
            try {
                LocalDate date = LocalDate.parse(value, formatter);
                if (date.isAfter(today) || date.isBefore(today.minusYears(125))) {
                    return null;
                }
                return date;
            } catch (DateTimeException ignored) {
                // Try next supported representation.
            }
        }
        return null;
    }

    private static DateTimeFormatter strict(String pattern) {
        return DateTimeFormatter.ofPattern(pattern, Locale.US).withResolverStyle(ResolverStyle.STRICT);
    }

    private static DateTimeFormatter textFormatter(String pattern) {
        return new DateTimeFormatterBuilder()
                .parseCaseInsensitive()
                .appendPattern(pattern)
                .toFormatter(Locale.US)
                .withResolverStyle(ResolverStyle.STRICT);
    }

    private static String digits(String value) {
        return value == null ? "" : value.replaceAll("[^0-9]", "");
    }

    /**
     * @param review task 0103: plausible by its shape, but the words around it are missing or point elsewhere; the
     *               finding is kept and goes to a person (a missing label never drops a value)
     */
    record Evaluation(boolean plausible, String reasonCode, boolean review) {
        Evaluation {
            reasonCode = reasonCode == null || reasonCode.isBlank() ? "UNSPECIFIED" : reasonCode;
        }

        Evaluation(boolean plausible, String reasonCode) {
            this(plausible, reasonCode, false);
        }

        static Evaluation accept() {
            return new Evaluation(true, "PLAUSIBLE");
        }

        static Evaluation reject(String reasonCode) {
            return new Evaluation(false, reasonCode);
        }

        /** Task 0103: kept, for a person. */
        static Evaluation review(String reasonCode) {
            return new Evaluation(true, reasonCode, true);
        }
    }
}

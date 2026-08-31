package com.securedoc.platform.infrastructure.detection;

import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort;
import com.securedoc.platform.application.port.out.SensitiveDataValidationPort;
import com.securedoc.platform.application.port.out.SensitiveDataValidationPort.Candidate;
import com.securedoc.platform.application.port.out.SensitiveDataValidationPort.ValidationEvidence;
import org.springframework.stereotype.Component;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local deterministic recognizer/validator that runs in parallel with AI semantic discovery.
 * It provides supporting structured evidence; it does not gate whether AI is allowed to find
 * sensitive content. Detects SSN, email, phone number, date-of-birth, credit card number
 * (Luhn-validated), and contextual credit-score patterns.
 *
 * <p>The same component also validates AI-discovered structured candidates. Validation can
 * support, contradict, or be not-applicable. It never discovers a candidate on behalf of AI.
 */
@Component
public class LocalRuleBasedDetectionAdapter implements SensitiveDataDetectionPort, SensitiveDataValidationPort {

    private static final String DETECTOR_NAME = "local-rule-based";
    private static final String DETECTOR_VERSION = "1.2.0";

    // SSN pattern: 3 digits, separator, 2 digits, separator, 4 digits
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b(\\d{3})[- ](\\d{2})[- ](\\d{4})\\b");

    // Email pattern
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    // Phone number pattern: various US formats
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "\\b(?:\\+1[- ]?)?\\(?\\d{3}\\)?[- ]?\\d{3}[- ]?\\d{4}\\b");

    // DOB discovery requires explicit birth context. A syntactically valid date alone is not
    // evidence that the date is a person's date of birth. Group 1 is the value to redact.
    private static final Pattern DOB_CONTEXT_PATTERN = Pattern.compile(
            "(?i)\\b(?:date\\s+of\\s+birth|birth\\s*date|dob|born(?:\\s+on)?)\\b\\s*[:#-]?\\s*"
                    + "((?:0?[1-9]|1[0-2])[/-](?:0?[1-9]|[12]\\d|3[01])[/-](?:19|20)\\d{2})\\b");

    // PAN candidates may be 13-19 digits, optionally grouped by spaces or dashes.
    // Structural match only — confirmed via Luhn before being reported.
    private static final Pattern CREDIT_CARD_PATTERN = Pattern.compile(
            "\\b\\d(?:[ -]?\\d){12,18}\\b");

    // Credit score: a number in the valid FICO/VantageScore range (300-850) that appears
    // in a credit-score context. Context is required so an arbitrary "720" is not flagged.
    private static final Pattern CREDIT_SCORE_PATTERN = Pattern.compile(
            "(?i)\\b(?:credit\\s*score|fico(?:\\s*score)?|vantage\\s*score|score)\\b[^0-9]{0,15}(\\d{3})\\b");

    private static final Pattern SCORE_CONTEXT_PATTERN = Pattern.compile(
            "(?i)\\b(?:credit\\s*score|fico(?:\\s*score)?|vantage(?:score)?(?:\\s*score)?|beacon\\s*score)\\b");

    @Override
    public DetectionOutput detect(List<PageInput> pages) {
        List<DetectedEntity> entities = new ArrayList<>();

        for (PageInput page : pages) {
            if (page.text().isBlank()) {
                continue;
            }
            detectInPage(page, entities);
        }

        return new DetectionOutput(entities);
    }

    @Override
    public ValidationEvidence validate(Candidate candidate) {
        String type = candidate.entityType().trim().toUpperCase(Locale.ROOT);
        return switch (type) {
            case "SSN", "SOCIAL_SECURITY_NUMBER" -> validateSsn(candidate.matchedText(), candidate.contextText());
            case "CREDIT_CARD", "CREDIT_CARD_NUMBER", "FINANCIAL_ACCOUNT" ->
                    validateCreditCard(candidate.matchedText());
            case "DATE_OF_BIRTH", "DOB" -> validateDob(candidate.matchedText(), candidate.contextText());
            case "CREDIT_SCORE", "FICO_SCORE" -> validateCreditScore(candidate.matchedText(), candidate.contextText());
            default -> ValidationEvidence.notApplicable(DETECTOR_NAME, DETECTOR_VERSION);
        };
    }

    private ValidationEvidence validateSsn(String raw, String contextText) {
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() == 4 && hasExplicitSsnContext(contextText)) {
            // Last-four-only SSN is semantically valid in explicit SSN context. It is not a
            // structurally complete SSN, so this validator supports the contextual classification
            // rather than requiring a nine-digit format.
            return ValidationEvidence.supports(DETECTOR_NAME, DETECTOR_VERSION);
        }
        if (digits.length() != 9) {
            return ValidationEvidence.contradicts(DETECTOR_NAME, DETECTOR_VERSION);
        }
        int area = Integer.parseInt(digits.substring(0, 3));
        int group = Integer.parseInt(digits.substring(3, 5));
        int serial = Integer.parseInt(digits.substring(5));
        boolean structurallyValid = area != 0 && area != 666 && area < 900 && group != 0 && serial != 0;
        return structurallyValid
                ? ValidationEvidence.supports(DETECTOR_NAME, DETECTOR_VERSION)
                : ValidationEvidence.contradicts(DETECTOR_NAME, DETECTOR_VERSION);
    }

    private boolean hasExplicitSsnContext(String contextText) {
        if (contextText == null || contextText.isBlank()) {
            return false;
        }
        return Pattern.compile("(?i)\\b(?:ssn|social\\s+security(?:\\s+number)?)\\b")
                .matcher(contextText).find();
    }

    private ValidationEvidence validateCreditCard(String raw) {
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19) {
            return ValidationEvidence.contradicts(DETECTOR_NAME, DETECTOR_VERSION);
        }
        return isLuhnValid(digits)
                ? ValidationEvidence.supports(DETECTOR_NAME, DETECTOR_VERSION)
                : ValidationEvidence.contradicts(DETECTOR_NAME, DETECTOR_VERSION);
    }

    private ValidationEvidence validateDob(String raw, String contextText) {
        String value = raw.trim();
        if (!value.matches("\\d{1,2}[/-]\\d{1,2}[/-]\\d{4}")) {
            // Textual or otherwise non-standard dates can still be semantically valid; this
            // validator has no reliable opinion rather than falsely contradicting the AI.
            return ValidationEvidence.notApplicable(DETECTOR_NAME, DETECTOR_VERSION);
        }
        String normalized = value.replace('-', '/');
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("M/d/uuuu").withResolverStyle(ResolverStyle.STRICT);
        try {
            LocalDate date = LocalDate.parse(normalized, formatter);
            LocalDate today = LocalDate.now();
            if (date.isAfter(today) || date.isBefore(today.minusYears(125))) {
                return ValidationEvidence.contradicts(DETECTOR_NAME, DETECTOR_VERSION);
            }
            // Structural date validity is only supporting evidence for DOB when the supplied
            // semantic context actually ties the date to birth. Otherwise it is just a date.
            if (!hasExplicitDobContext(contextText)) {
                return ValidationEvidence.notApplicable(DETECTOR_NAME, DETECTOR_VERSION);
            }
            return ValidationEvidence.supports(DETECTOR_NAME, DETECTOR_VERSION);
        } catch (DateTimeException e) {
            return ValidationEvidence.contradicts(DETECTOR_NAME, DETECTOR_VERSION);
        }
    }

    private boolean hasExplicitDobContext(String contextText) {
        if (contextText == null || contextText.isBlank()) {
            return false;
        }
        return Pattern.compile("(?i)\\b(?:date\\s+of\\s+birth|birth\\s*date|dob|born|birthday|birth)\\b")
                .matcher(contextText).find();
    }

    private ValidationEvidence validateCreditScore(String raw, String context) {
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() != 3) {
            return ValidationEvidence.contradicts(DETECTOR_NAME, DETECTOR_VERSION);
        }
        int score;
        try {
            score = Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return ValidationEvidence.contradicts(DETECTOR_NAME, DETECTOR_VERSION);
        }
        if (score < 300 || score > 850) {
            return ValidationEvidence.contradicts(DETECTOR_NAME, DETECTOR_VERSION);
        }
        if (context != null && SCORE_CONTEXT_PATTERN.matcher(context).find()) {
            return ValidationEvidence.supports(DETECTOR_NAME, DETECTOR_VERSION);
        }
        // A valid-range number without our known keywords is not enough to contradict semantic
        // understanding; AI may recognize other industry-specific score labels.
        return ValidationEvidence.notApplicable(DETECTOR_NAME, DETECTOR_VERSION);
    }

    private void detectInPage(PageInput page, List<DetectedEntity> results) {
        String text = page.text();

        detectPattern(text, SSN_PATTERN, "SSN", 0.95, page.pageNumber(), results, this::maskSsn);
        detectPattern(text, EMAIL_PATTERN, "EMAIL_ADDRESS", 0.90, page.pageNumber(), results, this::maskEmail);
        detectPattern(text, PHONE_PATTERN, "PHONE_NUMBER", 0.85, page.pageNumber(), results, this::maskPhone);
        detectDatesOfBirth(text, page.pageNumber(), results);
        detectCreditCards(text, page.pageNumber(), results);
        detectCreditScores(text, page.pageNumber(), results);
    }

    private void detectDatesOfBirth(String text, int pageNumber, List<DetectedEntity> results) {
        Matcher matcher = DOB_CONTEXT_PATTERN.matcher(text);
        while (matcher.find()) {
            String rawMatch = matcher.group(1);
            results.add(new DetectedEntity(
                    "DATE_OF_BIRTH", pageNumber, matcher.start(1), matcher.end(1), 0.90,
                    maskDob(rawMatch), DETECTOR_NAME, DETECTOR_VERSION));
        }
    }

    private void detectCreditCards(String text, int pageNumber, List<DetectedEntity> results) {
        Matcher matcher = CREDIT_CARD_PATTERN.matcher(text);
        while (matcher.find()) {
            String rawMatch = matcher.group();
            String digits = rawMatch.replaceAll("[^0-9]", "");
            if (digits.length() < 13 || digits.length() > 19 || !isLuhnValid(digits)) {
                continue;
            }
            results.add(new DetectedEntity(
                    "CREDIT_CARD_NUMBER", pageNumber, matcher.start(), matcher.end(), 0.95,
                    maskCreditCard(digits), DETECTOR_NAME, DETECTOR_VERSION));
        }
    }

    private void detectCreditScores(String text, int pageNumber, List<DetectedEntity> results) {
        Matcher matcher = CREDIT_SCORE_PATTERN.matcher(text);
        while (matcher.find()) {
            String scoreStr = matcher.group(1);
            int score;
            try {
                score = Integer.parseInt(scoreStr);
            } catch (NumberFormatException e) {
                continue;
            }
            if (score < 300 || score > 850) {
                continue;
            }
            results.add(new DetectedEntity(
                    "CREDIT_SCORE", pageNumber, matcher.start(1), matcher.end(1), 0.85,
                    "***", DETECTOR_NAME, DETECTOR_VERSION));
        }
    }

    private boolean isLuhnValid(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) {
                    n -= 9;
                }
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    private void detectPattern(String text, Pattern pattern, String entityType, double confidence,
                               int pageNumber, List<DetectedEntity> results, MaskFunction maskFunction) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            String rawMatch = matcher.group();
            results.add(new DetectedEntity(
                    entityType, pageNumber, matcher.start(), matcher.end(), confidence,
                    maskFunction.mask(rawMatch), DETECTOR_NAME, DETECTOR_VERSION));
        }
    }

    private String maskSsn(String raw) {
        if (raw.length() >= 4) {
            return "***-**-" + raw.substring(raw.length() - 4);
        }
        return "***-**-****";
    }

    private String maskEmail(String raw) {
        int atIndex = raw.indexOf('@');
        if (atIndex > 0) {
            String localPart = raw.substring(0, atIndex);
            String domainPart = raw.substring(atIndex + 1);
            String maskedLocal = localPart.charAt(0) + "***";
            int dotIndex = domainPart.lastIndexOf('.');
            if (dotIndex > 0) {
                String maskedDomain = domainPart.charAt(0) + "***" + domainPart.substring(dotIndex);
                return maskedLocal + "@" + maskedDomain;
            }
            return maskedLocal + "@***";
        }
        return "***@***";
    }

    private String maskPhone(String raw) {
        String digits = raw.replaceAll("[^0-9]", "");
        if (digits.length() >= 4) {
            return "***-***-" + digits.substring(digits.length() - 4);
        }
        return "***-***-****";
    }

    private String maskDob(String raw) {
        if (raw.length() >= 4) {
            return "**/**/" + raw.substring(raw.length() - 4);
        }
        return "**/**/****";
    }

    private String maskCreditCard(String digits) {
        if (digits.length() >= 4) {
            return "****-****-****-" + digits.substring(digits.length() - 4);
        }
        return "****-****-****-****";
    }

    @FunctionalInterface
    private interface MaskFunction {
        String mask(String raw);
    }
}

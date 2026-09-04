package com.securedoc.platform.infrastructure.detection;

import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort;
import com.securedoc.platform.application.port.out.SensitiveDataValidationPort;
import com.securedoc.platform.application.port.out.SensitiveDataValidationPort.Candidate;
import com.securedoc.platform.application.port.out.SensitiveDataValidationPort.ValidationEvidence;
import com.securedoc.platform.application.port.out.SensitiveDataValidationPort.ValidationOutcome;
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
    private static final String DETECTOR_VERSION = "1.5.0";

    // SSN pattern: 3 digits, separator, 2 digits, separator, 4 digits
    private static final Pattern SSN_PATTERN = Pattern.compile("\\b(\\d{3})[- ](\\d{2})[- ](\\d{4})\\b");

    // Email pattern
    private static final Pattern EMAIL_PATTERN = Pattern.compile(
            "\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b");

    // Phone number pattern: various US formats
    private static final Pattern PHONE_PATTERN = Pattern.compile(
            "\\b(?:\\+1[- ]?)?\\(?\\d{3}\\)?[- ]?\\d{3}[- ]?\\d{4}\\b");

    // DOB discovery is context-bound rather than assuming every date is a birth date. A single
    // table row label can apply to multiple owner columns, so candidate dates are bound to the
    // nearest birth label instead of requiring the label to be repeated before every value.
    private static final Pattern DOB_LABEL_PATTERN = Pattern.compile(
            "(?i)\\b(?:date\\s+of\\s+birth|birth\\s*date|dob|born(?:\\s+on)?)\\b");
    private static final Pattern DATE_VALUE_PATTERN = Pattern.compile(
            "\\b((?:0?[1-9]|1[0-2])[/-](?:0?[1-9]|[12]\\d|3[01])[/-](?:19|20)\\d{2})\\b");
    private static final Pattern NON_BIRTH_DATE_LABEL_PATTERN = Pattern.compile(
            "(?i)\\b(?:date\\s+of\\s+sale|sale\\s+date|issue\\s+date|first\\s+payment\\s+date|"
                    + "payment\\s+date|closing\\s+date|effective\\s+date|authorization\\s+date|"
                    + "expiration\\s+date|report\\s+date|signature\\s+date|contract\\s+date|"
                    + "servicing\\s+date|processing\\s+date|statement\\s+date)\\b");
    private static final int DOB_CONTEXT_MAX_GAP = 100;

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

    private static final Pattern SCORE_RANGE_CONTEXT_PATTERN = Pattern.compile(
            "(?i)(?:range\\s+of\\s+scores|scores?\\s+range|range\\s+from|low\\s+of\\s+300|"
                    + "high\\s+of\\s+850|300\\s*(?:to|-)\\s*850|example|illustrative)");

    private static final Pattern SSN_CONTEXT_PATTERN = Pattern.compile(
            "(?i)\\b(?:ssn|social\\s+security(?:\\s+number)?)\\b");

    private static final Pattern BUSINESS_IDENTIFIER_CONTEXT_PATTERN = Pattern.compile(
            "(?i)\\b(?:contract|member|account|loan|reference|ref\\.?|form|document|invoice|order|"
                    + "routing|phone|telephone|servicing|confirmation|reservation)\\s*"
                    + "(?:number|no\\.?|#|id)?\\b");

    private static final Pattern SCORE_TABLE_HEADER_PATTERN = Pattern.compile(
            "(?is)\\bapplicant\\b.{0,160}\\byour\\s+credit\\s+score\\b");

    private static final Pattern SCORE_TABLE_END_PATTERN = Pattern.compile(
            "(?i)\\b(?:what\\s+you\\s+should\\s+know|the\\s+range\\s+of\\s+scores|key\\s+factors|"
                    + "how\\s+can\\s+you\\s+get\\s+more\\s+information)\\b");

    private static final Pattern THREE_DIGIT_NUMBER_PATTERN = Pattern.compile("(?<!\\d)(\\d{3})(?!\\d)");

    private static final Pattern CARD_CONTEXT_PATTERN = Pattern.compile(
            "(?i)\\b(?:credit\\s+card|debit\\s+card|card\\s*(?:number|no\\.?|#)|payment\\s+card|"
                    + "pan|visa|master\\s*card|mastercard|amex|american\\s+express|discover)\\b");

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

        // For redaction, the relevant question is whether this exposed value is presented as an
        // SSN in the document, not whether SSA could historically have issued it. Synthetic/test
        // values intentionally use non-assignable ranges (for example 987-xx-xxxx) and still need
        // protection when explicitly labeled as Social Security Numbers.
        return hasExplicitSsnContext(contextText)
                ? ValidationEvidence.supports(DETECTOR_NAME, DETECTOR_VERSION)
                : ValidationEvidence.notApplicable(DETECTOR_NAME, DETECTOR_VERSION);
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

        detectSsns(text, page.pageNumber(), results);
        detectPattern(text, EMAIL_PATTERN, "EMAIL_ADDRESS", 0.90, page.pageNumber(), results, this::maskEmail);
        detectPattern(text, PHONE_PATTERN, "PHONE_NUMBER", 0.85, page.pageNumber(), results, this::maskPhone);
        detectDatesOfBirth(text, page.pageNumber(), results);
        detectCreditCards(text, page.pageNumber(), results);
        detectCreditScores(text, page.pageNumber(), results);
    }

    private void detectDatesOfBirth(String text, int pageNumber, List<DetectedEntity> results) {
        Matcher matcher = DATE_VALUE_PATTERN.matcher(text);
        while (matcher.find()) {
            int dobGap = nearestContextGap(
                    DOB_LABEL_PATTERN, text, matcher.start(1), matcher.end(1), 180);
            if (dobGap < 0 || dobGap > DOB_CONTEXT_MAX_GAP) {
                continue;
            }

            int competingDateLabelGap = nearestContextGap(
                    NON_BIRTH_DATE_LABEL_PATTERN, text, matcher.start(1), matcher.end(1), 180);
            if (competingDateLabelGap >= 0
                    && competingDateLabelGap <= DOB_CONTEXT_MAX_GAP
                    && competingDateLabelGap <= dobGap) {
                // Bind dates to their nearest semantic field label. This keeps an Issue Date or
                // Payment Date near a DOB row from being promoted while allowing one DOB label to
                // safely cover multiple owner values in the same table row.
                continue;
            }

            boolean duplicate = results.stream().anyMatch(existing ->
                    "DATE_OF_BIRTH".equals(existing.entityType())
                            && existing.pageNumber() == pageNumber
                            && existing.startOffset() == matcher.start(1)
                            && existing.endOffset() == matcher.end(1));
            if (duplicate) {
                continue;
            }

            String rawMatch = matcher.group(1);
            results.add(new DetectedEntity(
                    "DATE_OF_BIRTH", pageNumber, matcher.start(1), matcher.end(1), 0.90,
                    maskDob(rawMatch), DETECTOR_NAME, DETECTOR_VERSION));
        }
    }

    private void detectSsns(String text, int pageNumber, List<DetectedEntity> results) {
        Matcher matcher = SSN_PATTERN.matcher(text);
        while (matcher.find()) {
            String context = contextWindow(text, matcher.start(), matcher.end(), 180);
            String rawMatch = matcher.group();
            int ssnGap = nearestContextGap(
                    SSN_CONTEXT_PATTERN, text, matcher.start(), matcher.end(), 180);
            int businessGap = nearestContextGap(
                    BUSINESS_IDENTIFIER_CONTEXT_PATTERN, text, matcher.start(), matcher.end(), 180);
            boolean explicitSsnContext = ssnGap >= 0 && ssnGap <= 90;
            boolean closerBusinessIdentifier = businessGap >= 0 && businessGap <= 90
                    && (!explicitSsnContext || businessGap <= ssnGap);

            if (!explicitSsnContext || closerBusinessIdentifier) {
                // Bind a 3-2-4 value to its nearest semantic label. This prevents a nearby phrase
                // such as "not a Social Security Number" from turning a Form/Reference Number into
                // an SSN finding while still protecting explicitly labeled synthetic SSNs.
                continue;
            }
            ValidationEvidence evidence = validateSsn(rawMatch, context);
            if (evidence.outcome() != ValidationOutcome.SUPPORTS) {
                continue;
            }
            results.add(new DetectedEntity(
                    "SSN", pageNumber, matcher.start(), matcher.end(), 0.95,
                    maskSsn(rawMatch), DETECTOR_NAME, DETECTOR_VERSION));
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
            String context = contextWindow(text, matcher.start(), matcher.end(), 160);
            boolean cardContext = CARD_CONTEXT_PATTERN.matcher(context).find();
            if (!cardContext) {
                // Luhn alone is supporting evidence, not sufficient semantic meaning. This keeps a
                // Luhn-valid contract/member/reference number from becoming a deterministic finding.
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
            addCreditScoreIfPlausible(
                    text, pageNumber, matcher.start(1), matcher.end(1), 0.85, results);
        }

        // In a table the semantic label is often a column header and is not repeated beside each
        // row value. Detect valid three-digit values only inside the bounded score-table region;
        // stop before explanatory score-range prose so 300/850 remain non-findings.
        String flattened = text.replace('\n', ' ');
        Matcher header = SCORE_TABLE_HEADER_PATTERN.matcher(flattened);
        if (!header.find()) {
            return;
        }

        // The flattened header offsets correspond 1:1 to the original string because only newline
        // characters were replaced, so the end offset is safe to use against the original text.
        int tableStart = Math.min(text.length(), header.end());
        Matcher endMatcher = SCORE_TABLE_END_PATTERN.matcher(text);
        int tableEnd = text.length();
        if (endMatcher.find(tableStart)) {
            tableEnd = endMatcher.start();
        } else {
            tableEnd = Math.min(text.length(), tableStart + 800);
        }

        Matcher tableScores = THREE_DIGIT_NUMBER_PATTERN.matcher(text);
        tableScores.region(tableStart, tableEnd);
        while (tableScores.find()) {
            addCreditScoreIfPlausible(
                    text, pageNumber, tableScores.start(1), tableScores.end(1), 0.90, results);
        }
    }

    private void addCreditScoreIfPlausible(String text,
                                            int pageNumber,
                                            int start,
                                            int end,
                                            double confidence,
                                            List<DetectedEntity> results) {
        String scoreStr = text.substring(start, end);
        int score;
        try {
            score = Integer.parseInt(scoreStr);
        } catch (NumberFormatException e) {
            return;
        }
        if (score < 300 || score > 850) {
            return;
        }
        String context = contextWindow(text, start, end, 120);
        if (isExplanatoryScoreValue(score, scoreStr, context)) {
            return;
        }

        boolean duplicate = results.stream().anyMatch(existing ->
                "CREDIT_SCORE".equals(existing.entityType())
                        && existing.pageNumber() == pageNumber
                        && existing.startOffset() == start
                        && existing.endOffset() == end);
        if (!duplicate) {
            results.add(new DetectedEntity(
                    "CREDIT_SCORE", pageNumber, start, end, confidence,
                    "***", DETECTOR_NAME, DETECTOR_VERSION));
        }
    }

    private boolean isExplanatoryScoreValue(int score, String value, String context) {
        if (!SCORE_RANGE_CONTEXT_PATTERN.matcher(context).find()) {
            return false;
        }
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

    private int nearestContextGap(Pattern labelPattern,
                                  String text,
                                  int candidateStart,
                                  int candidateEnd,
                                  int radius) {
        if (text == null || text.isBlank()) {
            return -1;
        }
        int windowStart = Math.max(0, candidateStart - radius);
        int windowEnd = Math.min(text.length(), candidateEnd + radius);
        String context = text.substring(windowStart, windowEnd);
        int relativeStart = candidateStart - windowStart;
        int relativeEnd = candidateEnd - windowStart;
        int best = Integer.MAX_VALUE;
        Matcher label = labelPattern.matcher(context);
        while (label.find()) {
            int gap;
            if (label.end() <= relativeStart) {
                gap = relativeStart - label.end();
            } else if (label.start() >= relativeEnd) {
                gap = label.start() - relativeEnd;
            } else {
                gap = 0;
            }
            best = Math.min(best, gap);
        }
        return best == Integer.MAX_VALUE ? -1 : best;
    }

    private String contextWindow(String text, int startOffset, int endOffset, int radius) {
        int start = Math.max(0, startOffset - radius);
        int end = Math.min(text.length(), endOffset + radius);
        return text.substring(start, end);
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

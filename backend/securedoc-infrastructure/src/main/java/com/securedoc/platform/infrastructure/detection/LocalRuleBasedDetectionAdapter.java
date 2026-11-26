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
    private static final String DETECTOR_VERSION = "1.8.0";

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

    private static final Pattern NON_SSN_TAX_ID_CONTEXT_PATTERN = Pattern.compile(
            "(?i)\\b(?:federal\\s+(?:employer\\s+)?id(?:entification)?(?:\\s+number)?|"
                    + "employer\\s+identification(?:\\s+number)?|e\\.?i\\.?n\\.?|f\\.?e\\.?i\\.?n\\.?|"
                    + "business\\s+(?:tax\\s+)?id(?:entification)?(?:\\s+number)?|"
                    + "taxpayer\\s+identification(?:\\s+number)?|tax\\s+id(?:entification)?(?:\\s+number)?)\\b");

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

    /**
     * Task 0099 (R1): on the number's own line, "card" alone ("paid by card", "Card 4111 ..."), an expiry or a card
     * security code label also say it is a card.
     */
    private static final Pattern CARD_WORD_PATTERN = Pattern.compile(
            "(?i)(?<![a-z])(?:cards?|exp(?:iry|ires|iration)?\\.?|valid\\s+thru|cvv2?|cvc2?)(?![a-z])");

    /** Task 0099: 4-4-4-4 (also 4-4-4-1..7), 4-6-5 or 4-6-4, with spaces or dashes. */
    private static final Pattern CARD_GROUPING_PATTERN = Pattern.compile(
            "\\d{4}([ -])\\d{4}\\1\\d{4}\\1\\d{1,7}|\\d{4}([ -])\\d{6}\\2\\d{4,5}");

    /** Task 0099 (R2): a card number as OCR may read it, with l, I, | for 1 and O, o for 0. */
    private static final Pattern OCR_CARD_PATTERN = Pattern.compile(
            "(?<![A-Za-z0-9])[0-9lIO|o](?:[ -]?[0-9lIO|o]){12,18}(?![A-Za-z0-9])");

    private final OptInPatternDetector optInPatterns = new OptInPatternDetector();
    private final LockedItemDetector lockedItems = new LockedItemDetector();

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
            case "CREDIT_CARD", "CREDIT_CARD_NUMBER" ->
                    validateCreditCard(candidate.matchedText());
            case "DATE_OF_BIRTH", "DOB" -> validateDob(candidate.matchedText(), candidate.contextText());
            case "CREDIT_SCORE", "FICO_SCORE" -> validateCreditScore(candidate.matchedText(), candidate.contextText());
            default -> ValidationEvidence.notApplicable(DETECTOR_NAME, DETECTOR_VERSION);
        };
    }

    private ValidationEvidence validateSsn(String raw, String contextText) {
        String digits = raw.replaceAll("[^0-9]", "");
        if (raw.trim().matches("\\d{2}[- ]\\d{7}")) {
            return ValidationEvidence.contradicts(DETECTOR_NAME, DETECTOR_VERSION);
        }
        if (digits.length() == 9 && isCloserNonSsnTaxIdentifier(raw, contextText)) {
            return ValidationEvidence.contradicts(DETECTOR_NAME, DETECTOR_VERSION);
        }
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
        return SSN_CONTEXT_PATTERN.matcher(contextText).find();
    }

    private boolean isCloserNonSsnTaxIdentifier(String raw, String contextText) {
        if (contextText == null || contextText.isBlank() || raw == null || raw.isBlank()) {
            return false;
        }
        String value = raw.trim();
        int candidateStart = contextText.indexOf(value);
        if (candidateStart < 0) {
            return value.matches("\\d{2}[- ]\\d{7}")
                    && NON_SSN_TAX_ID_CONTEXT_PATTERN.matcher(contextText).find();
        }
        int candidateEnd = candidateStart + value.length();
        int taxIdGap = nearestContextGap(
                NON_SSN_TAX_ID_CONTEXT_PATTERN, contextText, candidateStart, candidateEnd, contextText.length());
        int ssnGap = nearestContextGap(
                SSN_CONTEXT_PATTERN, contextText, candidateStart, candidateEnd, contextText.length());
        boolean einShaped = value.matches("\\d{2}[- ]\\d{7}");
        return taxIdGap >= 0 && (einShaped || ssnGap < 0 || taxIdGap <= ssnGap);
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
        detectPattern(text, EMAIL_PATTERN, "EMAIL_ADDRESS", 0.90, page.pageNumber(), results);
        detectPattern(text, PHONE_PATTERN, "PHONE_NUMBER", 0.85, page.pageNumber(), results);
        detectDatesOfBirth(text, page.pageNumber(), results);
        detectCreditCards(text, page.pageNumber(), results);
        detectCreditScores(text, page.pageNumber(), results);
        // Task 0043: the opt-in pattern kinds, after the V1 detectors and independent of them. Their findings carry
        // their own detector name, and the detection service drops them unless the entity switched the kind on.
        optInPatterns.detect(text, page.pageNumber(), results);
        // Task 0062: card security codes and PINs, dropped the same way for an entity without the locked items.
        lockedItems.detect(text, page.pageNumber(), results);
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

            results.add(new DetectedEntity(
                    "DATE_OF_BIRTH", pageNumber, matcher.start(1), matcher.end(1), 0.90,
                    DETECTOR_NAME, DETECTOR_VERSION));
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
            // Task 0099 (R3): "SSN 512-34-8876 account 4820193746": the label just before the value is its own; a label
            // after it starts the next field, unless no SSN label stands before the value closer than another label.
            int ssnBefore = precedingGap(SSN_CONTEXT_PATTERN, text, matcher.start(), 90);
            int businessBefore = precedingGap(BUSINESS_IDENTIFIER_CONTEXT_PATTERN, text, matcher.start(), 90);
            boolean ownLabelBefore = ssnBefore >= 0 && (businessBefore < 0 || ssnBefore < businessBefore);
            boolean closerBusinessIdentifier = !ownLabelBefore && businessGap >= 0 && businessGap <= 90
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
            // Task 0069: a number no SSN can have (area 000, 666 or 9NN, group 00, serial 0000) is still covered next to
            // an SSN label (a misread digit, a test value), but with less confidence, so a person looks at it.
            double confidence = isIssuable(matcher.group(1), matcher.group(2), matcher.group(3)) ? 0.95 : 0.75;
            results.add(new DetectedEntity(
                    "SSN", pageNumber, matcher.start(), matcher.end(), confidence,
                    DETECTOR_NAME, DETECTOR_VERSION));
        }
    }

    /** The ranges the SSA never issues. */
    static boolean isIssuable(String area, String group, String serial) {
        return !area.equals("000") && !area.equals("666") && area.charAt(0) != '9'
                && !group.equals("00") && !serial.equals("0000");
    }

    private void detectCreditCards(String text, int pageNumber, List<DetectedEntity> results) {
        Matcher matcher = CREDIT_CARD_PATTERN.matcher(text);
        while (matcher.find()) {
            addCardIfPlausible(text, pageNumber, matcher.start(), matcher.end(), matcher.group(), results);
        }
        // Task 0099 (R2): OCR reads a 1 as l, I or |, a 0 as O or o. A run with enough real digits is read again with
        // those letters as digits; the place is the run's own, so nothing is guessed about where it stands.
        Matcher misread = OCR_CARD_PATTERN.matcher(text);
        while (misread.find()) {
            String raw = misread.group();
            long letters = raw.chars().filter(Character::isLetter).count();
            if (letters == 0 || letters > 3 || raw.chars().filter(Character::isDigit).count() < 10) continue;
            addCardIfPlausible(text, pageNumber, misread.start(), misread.end(), ocrDigits(raw), results);
        }
    }

    /** Task 0099: what a misread letter stands for; anything else is not a digit. */
    static String ocrDigits(String raw) {
        StringBuilder digits = new StringBuilder(raw.length());
        for (char c : raw.toCharArray()) {
            switch (c) {
                case 'l', 'I', '|' -> digits.append('1');
                case 'O', 'o' -> digits.append('0');
                default -> digits.append(c);
            }
        }
        return digits.toString();
    }

    private void addCardIfPlausible(String text, int pageNumber, int start, int end, String value,
                                    List<DetectedEntity> results) {
        String digits = value.replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19 || !isLuhnValid(digits)) {
            return;
        }
        boolean known = results.stream().anyMatch(e -> "CREDIT_CARD_NUMBER".equals(e.entityType())
                && e.pageNumber() == pageNumber && e.startOffset() < end && start < e.endOffset());
        if (known) {
            return;
        }
        String context = contextWindow(text, start, end, 160);
        double confidence;
        if (CARD_CONTEXT_PATTERN.matcher(context).find() || CARD_WORD_PATTERN.matcher(sameLine(text, start, end)).find()) {
            confidence = 0.95;
        } else if (CARD_GROUPING_PATTERN.matcher(value.trim()).matches() && hasCardIssuerPrefix(digits)
                && !BUSINESS_IDENTIFIER_CONTEXT_PATTERN.matcher(sameLine(text, start, end)).find()) {
            // Task 0099: a locked item. Grouped like a card, with a card issuer's first digits and a valid check digit,
            // it is a card even without a label; with less confidence, so a person decides.
            confidence = 0.75;
        } else {
            // Luhn alone is supporting evidence, not sufficient semantic meaning. This keeps a
            // Luhn-valid contract/member/reference number from becoming a deterministic finding.
            return;
        }
        results.add(new DetectedEntity(
                "CREDIT_CARD_NUMBER", pageNumber, start, end, confidence,
                DETECTOR_NAME, DETECTOR_VERSION));
    }

    /** The line around a place, up to 60 characters on each side. */
    private static String sameLine(String text, int start, int end) {
        int from = Math.max(Math.max(0, start - 60), text.lastIndexOf('\n', Math.max(0, start - 1)) + 1);
        int lineEnd = text.indexOf('\n', end);
        int to = Math.min(lineEnd < 0 ? text.length() : lineEnd, Math.min(text.length(), end + 60));
        return text.substring(from, Math.max(from, to));
    }

    /** Visa, Mastercard, American Express, Discover, JCB, Diners Club, UnionPay: the first digits and the length. */
    static boolean hasCardIssuerPrefix(String digits) {
        int length = digits.length();
        int two = Integer.parseInt(digits.substring(0, 2));
        int four = Integer.parseInt(digits.substring(0, 4));
        if (digits.charAt(0) == '4') return length == 13 || length == 16 || length == 19;
        if ((two >= 51 && two <= 55) || (four >= 2221 && four <= 2720)) return length == 16;
        if (two == 34 || two == 37) return length == 15;
        if (four == 6011 || two == 65 || (four >= 6440 && four <= 6499) || two == 62) return length >= 16;
        if (four >= 3528 && four <= 3589) return length >= 16;
        if (two == 36 || two == 38 || (four >= 3000 && four <= 3059)) return length == 14;
        return false;
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
                    DETECTOR_NAME, DETECTOR_VERSION));
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

    /** Task 0099: how far before {@code start} the nearest label of the pattern ends; -1 when none is that close. */
    private static int precedingGap(Pattern labelPattern, String text, int start, int radius) {
        int from = Math.max(0, start - radius);
        Matcher label = labelPattern.matcher(text);
        label.useTransparentBounds(true).region(from, start);
        int best = -1;
        while (label.find()) best = start - label.end();
        return best;
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

    /** Only the match offsets are kept; the matched text itself is never carried out (task 0015). */
    private void detectPattern(String text, Pattern pattern, String entityType, double confidence,
                               int pageNumber, List<DetectedEntity> results) {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            results.add(new DetectedEntity(
                    entityType, pageNumber, matcher.start(), matcher.end(), confidence,
                    DETECTOR_NAME, DETECTOR_VERSION));
        }
    }
}

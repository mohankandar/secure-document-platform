package com.securedoc.platform.infrastructure.detection;

import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.DetectedEntity;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 0062 (R1): the locked items that had no detector: card security codes (CVV) and PINs. A value counts only next
 * to its label: after it on the same line with only filler between, alone on the line under it, or (CVV) the one 3 or
 * 4 digit number on the line under a header that has the label. Offsets only; nothing is kept or logged.
 */
final class LockedItemDetector {

    static final String DETECTOR_NAME = "local-locked-items";
    static final String DETECTOR_VERSION = "1.1.0"; // 1.1.0: task 0103 (any wording, near a card, stray spaces)

    private static final String FILLER =
            "[\\s:#.()=\\-]*(?:(?i:number|no|code|is|digits)[\\s:#.()=\\-]+)*";

    private static final String CVV_LABEL =
            "(?<![A-Za-z])(?i:cvv2?|cvc2?|cv2|csc|cvn|card\\s+(?:security|verification)\\s+(?:code|value|number)|"
                    + "card\\s+identification\\s+number|card\\s+code|c[oó]digo\\s+de\\s+seguridad|"
                    + "c[oó]d\\.?\\s+(?:de\\s+)?seg(?:uridad|\\.)?)(?![A-Za-z0-9])";
    /** "Security code" alone is also an alarm or a login code: only with card wording near it. */
    private static final String SECURITY_CODE_LABEL =
            "(?<![A-Za-z])(?i:sec(?:urity|\\.)?\\s*code|verification\\s+code|cid)(?![A-Za-z0-9])";
    private static final Pattern CARD_WORDS = Pattern.compile(
            "(?i)(?<![a-z])(?:card|tarjeta|visa|master\\s*card|amex|american\\s+express|discover)(?![a-z])");

    /** "PIN" in capitals (a pin is also an object), "Pin #", the Spanish NIP, or written out. */
    private static final String PIN_LABEL =
            "(?<![A-Za-z])(?:PIN|NIP|P\\.I\\.N\\.?|(?i:personal\\s+identification\\s+number|pin\\s*(?:number|code|no\\.?|#)|"
                    + "n[uú]mero\\s+de\\s+identificaci[oó]n\\s+personal|clave\\s+personal))"
                    + "(?![A-Za-z0-9])";

    /** A code OCR split with one stray space ("7 34"), next to its label only. */
    private static final Pattern CVV = labelled(CVV_LABEL, "\\d(?: ?\\d){2,3}");
    private static final Pattern SECURITY_CODE = labelled(SECURITY_CODE_LABEL, "\\d(?: ?\\d){2,3}");
    private static final Pattern PIN = labelled(PIN_LABEL, "\\d(?: ?\\d){3,5}");
    /** Task 0103: the label earlier in a sentence on the value's line ("the PIN we have is 8264"). */
    private static final Pattern CVV_IN_LINE = Pattern.compile(CVV_LABEL + "|" + SECURITY_CODE_LABEL);
    private static final Pattern PIN_IN_LINE = Pattern.compile(PIN_LABEL);
    /** Task 0103: a lone 3 or 4 digit number; not a part of a date, an amount, a time, a phone number or a range. */
    private static final Pattern LONE_CODE = Pattern.compile("(?<![\\w/.,$:()\\-])(\\d{3,4})(?![\\w/:%)]|[.,]\\d|-\\d)");
    /** How far from a card number a lone code is held for a person: lines before and after. */
    private static final int NEAR_CARD_LINES = 6;
    private static final double NEAR_CARD = 0.70;
    private static final Pattern CVV_HEADER = Pattern.compile(CVV_LABEL);
    private static final Pattern PIN_HEADER = Pattern.compile("\\s*" + PIN_LABEL + "[\\s:#]*");
    private static final Pattern PIN_ALONE = Pattern.compile("\\s*(\\d{4,6})\\s*");
    /** Standing alone: not part of a longer number, a date, an amount or a range. */
    private static final Pattern THREE_OR_FOUR = Pattern.compile("(?<![\\w/.,$\\-])(\\d{3,4})(?![\\w/\\-]|[.,]\\d)");
    private static final Pattern CARD_NUMBER = Pattern.compile(
            "(?<!\\d)(?:\\d{4}[ -]?\\d{6}[ -]?\\d{5}|(?:\\d{4}[ -]?){3}\\d{4})(?!\\d)");

    private static Pattern labelled(String label, String value) {
        return Pattern.compile(label + FILLER + "(?<![\\d$/])(" + value + ")(?![\\w/]|-\\d|[.,]\\d)");
    }

    void detect(String text, int pageNumber, List<DetectedEntity> results) {
        sameLine(text, pageNumber, CVV, "CVV", false, results);
        sameLine(text, pageNumber, SECURITY_CODE, "CVV", true, results);
        sameLine(text, pageNumber, PIN, "PIN", false, results);
        int lineStart = 0;
        String header = null;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = text.length();
            String line = text.substring(lineStart, lineEnd);
            if (header != null) underHeader(header, line, lineStart, pageNumber, results);
            header = line;
            lineStart = lineEnd + 1;
        }
        inLine(text, pageNumber, CVV_IN_LINE, "CVV", 3, 4, results);
        inLine(text, pageNumber, PIN_IN_LINE, "PIN", 4, 6, results);
        nearCards(text, pageNumber, results);
    }

    /**
     * Task 0103: the label earlier on the value's line with words between ("the security code on the back is 734"): the
     * first lone number of the right length after it, within 60 characters. Covered (0.80): a label raises confidence.
     */
    private static void inLine(String text, int pageNumber, Pattern label, String kind, int min, int max,
                               List<DetectedEntity> results) {
        Matcher m = label.matcher(text);
        while (m.find()) {
            int lineEnd = text.indexOf('\n', m.end());
            int to = Math.min(lineEnd < 0 ? text.length() : lineEnd, m.end() + 60);
            if (kind.equals("CVV") && m.group().toLowerCase(java.util.Locale.ROOT).matches("(?s).*(sec|verif|cid).*")
                    && !CARD_WORDS.matcher(text.substring(Math.max(0, m.start() - 300),
                    Math.min(text.length(), m.end() + 300))).find()) continue;
            Matcher code = LONE_CODE.matcher(text);
            code.region(m.end(), to);
            while (code.find()) {
                int length = code.group(1).length();
                if (length < min || length > max) continue;
                add(kind, pageNumber, code.start(1), code.end(1), 0.80, results);
                break;
            }
        }
    }

    /**
     * Task 0103 (owner's rule 4): codes look like anything, so where a card number is on the page, every lone 3 or 4
     * digit number within {@value #NEAR_CARD_LINES} lines of it is held for a person, labelled or not. A person clears
     * a false alarm with one click; a code is never delivered on a guess.
     */
    private static void nearCards(String text, int pageNumber, List<DetectedEntity> results) {
        List<DetectedEntity> cards = results.stream().filter(e -> "CREDIT_CARD_NUMBER".equals(e.entityType())
                && e.pageNumber() == pageNumber).toList();
        if (cards.isEmpty()) return;
        int[] lineOf = lineNumbers(text);
        for (DetectedEntity card : cards) {
            int cardLine = lineOf[Math.min(card.startOffset(), text.length() - 1)];
            Matcher code = LONE_CODE.matcher(text);
            while (code.find()) {
                int start = code.start(1);
                int end = code.end(1);
                if (Math.abs(lineOf[start] - cardLine) > NEAR_CARD_LINES) continue;
                // Inside a card number (its groups are 4 digits) or another finding: not a code of its own.
                boolean inside = results.stream().anyMatch(e -> e.pageNumber() == pageNumber
                        && e.startOffset() < end && start < e.endOffset());
                if (inside || partOfGroupedDigits(text, start, end)) continue;
                add("CVV", pageNumber, start, end, NEAR_CARD, results);
            }
        }
    }

    /** "4111 1111 1111 1111": a 4-digit group with digit groups beside it is a part of a longer number. */
    private static boolean partOfGroupedDigits(String text, int start, int end) {
        boolean before = start >= 2 && text.charAt(start - 1) == ' ' && Character.isDigit(text.charAt(start - 2));
        boolean after = end + 1 < text.length() && text.charAt(end) == ' ' && Character.isDigit(text.charAt(end + 1));
        return before || after;
    }

    private static int[] lineNumbers(String text) {
        int[] lines = new int[Math.max(1, text.length())];
        int line = 0;
        for (int i = 0; i < text.length(); i++) {
            lines[i] = line;
            if (text.charAt(i) == '\n') line++;
        }
        return lines;
    }

    private static void sameLine(String text, int pageNumber, Pattern pattern, String kind, boolean cardWordsNeeded,
                                 List<DetectedEntity> results) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            // The filler allows line breaks in the pattern language; the value must be on the label's line.
            if (text.substring(m.start(), m.start(1)).indexOf('\n') >= 0) continue;
            if (cardWordsNeeded && !CARD_WORDS.matcher(text.substring(Math.max(0, m.start() - 160),
                    Math.min(text.length(), m.end() + 160))).find()) continue;
            add(kind, pageNumber, m.start(1), m.end(1), 0.95, results);
        }
    }

    private static void underHeader(String header, String line, int lineStart, int pageNumber,
                                    List<DetectedEntity> results) {
        if (PIN_HEADER.matcher(header).matches()) {
            Matcher alone = PIN_ALONE.matcher(line);
            if (alone.matches()) {
                add("PIN", pageNumber, lineStart + alone.start(1), lineStart + alone.end(1), 0.90, results);
            }
        }
        // Task 0099: a line that holds its own code is no header ("... CVV 123" over "Credit score: 742").
        if (!CVV_HEADER.matcher(header).find() || CVV.matcher(header).find()) return;
        char[] rest = line.toCharArray();
        Matcher card = CARD_NUMBER.matcher(line);
        // 'x' is a word character, so a blanked card number can't lend its digits or its ends to a value.
        while (card.find()) java.util.Arrays.fill(rest, card.start(), card.end(), 'x');
        Matcher m = THREE_OR_FOUR.matcher(new String(rest));
        if (!m.find()) return;
        int start = m.start(1);
        int end = m.end(1);
        if (m.find()) return; // more than one candidate: which one is the code can't be told
        add("CVV", pageNumber, lineStart + start, lineStart + end, 0.85, results);
    }

    private static void add(String kind, int pageNumber, int start, int end, double confidence,
                            List<DetectedEntity> results) {
        boolean known = results.stream().anyMatch(e -> e.entityType().equals(kind) && e.pageNumber() == pageNumber
                && e.startOffset() < end && start < e.endOffset());
        if (!known) {
            results.add(new DetectedEntity(kind, pageNumber, start, end, confidence, DETECTOR_NAME, DETECTOR_VERSION));
        }
    }
}

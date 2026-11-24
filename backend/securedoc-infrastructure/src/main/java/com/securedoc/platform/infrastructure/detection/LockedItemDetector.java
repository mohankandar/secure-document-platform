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
    static final String DETECTOR_VERSION = "1.0.0";

    private static final String FILLER =
            "[\\s:#.()=\\-]*(?:(?i:number|no|code|is|digits)[\\s:#.()=\\-]+)*";

    private static final String CVV_LABEL =
            "(?<![A-Za-z])(?i:cvv2?|cvc2?|cv2|card\\s+(?:security|verification)\\s+(?:code|value|number)|"
                    + "card\\s+identification\\s+number)(?![A-Za-z0-9])";
    /** "Security code" alone is also an alarm or a login code: only with card wording near it. */
    private static final String SECURITY_CODE_LABEL = "(?<![A-Za-z])(?i:security\\s+code|cid)(?![A-Za-z0-9])";
    private static final Pattern CARD_WORDS = Pattern.compile(
            "(?i)(?<![a-z])(?:card|visa|master\\s*card|amex|american\\s+express|discover)(?![a-z])");

    /** "PIN" in capitals (a pin is also an object), or written out. */
    private static final String PIN_LABEL =
            "(?<![A-Za-z])(?:PIN|P\\.I\\.N\\.?|(?i:personal\\s+identification\\s+number|pin\\s+(?:number|code|no\\.?)))"
                    + "(?![A-Za-z0-9])";

    private static final Pattern CVV = labelled(CVV_LABEL, "\\d{3,4}");
    private static final Pattern SECURITY_CODE = labelled(SECURITY_CODE_LABEL, "\\d{3,4}");
    private static final Pattern PIN = labelled(PIN_LABEL, "\\d{4,6}");
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
        if (!CVV_HEADER.matcher(header).find()) return;
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

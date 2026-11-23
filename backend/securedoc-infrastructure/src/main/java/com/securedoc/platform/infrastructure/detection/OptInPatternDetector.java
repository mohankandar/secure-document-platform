package com.securedoc.platform.infrastructure.detection;

import com.securedoc.platform.application.port.out.SensitiveDataDetectionPort.DetectedEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 0043 (R1 part 1): deterministic detectors for the opt-in pattern kinds. Every kind here is off by default for
 * every entity; the detection service keeps a finding only when the entity's policy has the kind switched on.
 *
 * <ul>
 *   <li>{@code BANK_ROUTING_NUMBER}: 9 digits with the ABA 3-7-1 checksum, bound to a routing label, or on a check's
 *       MICR line (transit symbols, or the routing / account / check-number pattern).</li>
 *   <li>{@code BANK_ACCOUNT_NUMBER}: 4-17 digits bound to an account label, or the account field of a MICR line;
 *       never a phone, date, amount, ZIP+4 or another kind's value.</li>
 *   <li>{@code ITIN}: 9NN-NN-NNNN with the group in 70-88, 90-92 or 94-99, bound to an ITIN label (an SSN label
 *       nearer the value wins, so an SSN stays an SSN).</li>
 *   <li>{@code EIN}: NN-NNNNNNN with a valid IRS prefix, bound to an EIN or tax-ID label.</li>
 *   <li>{@code DRIVERS_LICENSE_NUMBER} and {@code PASSPORT_NUMBER}: a plausible number, only with its label.</li>
 * </ul>
 *
 * <p>"Bound to a label": the label is on the same line with only filler between ("number", "no.", "#", ":"), or the
 * label is on the line above (a form's header row) and, of all the labels before the value that could own it, it is
 * the nearest. Nothing here keeps or logs a value: findings are offsets only.
 */
final class OptInPatternDetector {

    static final String DETECTOR_NAME = "local-pattern-kinds";
    static final String DETECTOR_VERSION = "1.0.0";

    static final String ROUTING = "BANK_ROUTING_NUMBER";
    static final String ACCOUNT = "BANK_ACCOUNT_NUMBER";
    static final String ITIN = "ITIN";
    static final String EIN = "EIN";
    static final String DRIVERS_LICENSE = "DRIVERS_LICENSE_NUMBER";
    static final String PASSPORT = "PASSPORT_NUMBER";

    /** How far before a value a label is looked for. */
    private static final int LABEL_WINDOW = 100;
    /** A corrected OCR misread (O for 0, l for 1, ...) lowers confidence so the finding goes to a person. */
    private static final double MISREAD_PENALTY = 0.20;

    // ---------------------------------------------------------------- labels

    private static final Pattern ROUTING_LABEL = Pattern.compile(
            "(?i)(?<![a-z])(?:rout(?:ing|1ng|lng|mg|in9)|aba|rtn|r/t|transit\\s+(?:number|no\\.?|#))(?![a-z])");
    private static final Pattern ACCOUNT_LABEL = Pattern.compile(
            "(?i)(?<![a-z])(?:account|acc[o0]unt|acct|acc't|a/c|acc(?=\\.?\\s*(?:no\\b|#|number)))(?![a-z])");
    private static final Pattern ITIN_LABEL = Pattern.compile(
            "(?i)(?<![a-z])(?:itin|individual\\s+taxpayer\\s+identification(?:\\s+number)?)(?![a-z])");
    private static final Pattern EIN_LABEL = Pattern.compile(
            "(?i)(?<![a-z])(?:e\\.?i\\.?n\\.?|f\\.?e\\.?i\\.?n\\.?|employer\\s+identification(?:\\s+number)?|"
                    + "federal\\s+(?:tax|employer)\\s+id(?:entification)?(?:\\s+number)?|federal\\s+id(?:\\s+number)?|"
                    + "tax\\s+id(?:entification)?(?:\\s+number)?|t\\.?i\\.?n\\.?)(?![a-z])");
    private static final Pattern DRIVERS_LICENSE_LABEL = Pattern.compile(
            "(?i)(?<![a-z])(?:driver'?s?\\s*(?:licen[cs]e|lic\\.?)|driving\\s+licen[cs]e|operator'?s?\\s+licen[cs]e|"
                    + "d\\.?l\\.?(?:\\s*(?:n[o0]\\.?|#|number))?|licen[cs]e[\\s-]*(?:number|n[o0]\\.?|#))(?![a-z])");
    private static final Pattern PASSPORT_LABEL = Pattern.compile("(?i)(?<![a-z])passport(?![a-z])");

    private static final Pattern SSN_LABEL = Pattern.compile("(?i)(?<![a-z])(?:ssn|social\\s+security)(?![a-z])");
    private static final Pattern CARD_LABEL = Pattern.compile(
            "(?i)(?<![a-z])(?:card|visa|master\\s*card|amex|discover)(?![a-z])");
    private static final Pattern PHONE_LABEL = Pattern.compile(
            "(?i)(?<![a-z])(?:phone|telephone|tel|fax|mobile|cell)(?![a-z])");
    private static final Pattern DATE_LABEL = Pattern.compile("(?i)(?<![a-z])(?:date|dob|birth|born|expires?|issued?)(?![a-z])");
    private static final Pattern SCORE_LABEL = Pattern.compile("(?i)(?<![a-z])(?:score|fico)(?![a-z])");
    private static final Pattern ZIP_LABEL = Pattern.compile("(?i)(?<![a-z])(?:zip|postal\\s+code)(?![a-z])");
    /** Other identifiers that own any value next to them (a loan number is not a bank account). */
    private static final Pattern OTHER_ID_LABEL = Pattern.compile(
            "(?i)(?<![a-z])(?:loan|order|invoice|reference|ref|member|policy|claim|case|customer|confirmation|tracking|"
                    + "transaction|serial|contract|application|file|check|cheque|receipt|ticket|employee|student|"
                    + "patient|medical\\s+record|vin)\\s*(?:number|no\\.?|num|#|id)(?![a-z])");

    /** Only filler between a label and its value on the same line. */
    private static final Pattern FILLER = Pattern.compile(
            "(?i)[\\s:#.\\-()=]*(?:(?:number|numbers|n[o0]|num|nbr|#|is|id)[\\s:#.\\-()=]*)*");

    // ---------------------------------------------------------------- values

    private static final Pattern NINE_DIGIT_LIKE = Pattern.compile("(?<![A-Za-z0-9])([0-9OoIlSB]{9})(?![A-Za-z0-9])");
    private static final Pattern ACCOUNT_VALUE = Pattern.compile(
            "(?<![A-Za-z0-9$.,/\\-])(\\d(?:-?\\d){3,20})(?![A-Za-z0-9/]|[.,]\\d|-\\d)");
    private static final Pattern ITIN_VALUE = Pattern.compile("(?<![\\w-])(9\\d{2})([- ]?)(\\d{2})\\2(\\d{4})(?![\\w-])");
    private static final Pattern EIN_VALUE = Pattern.compile("(?<![\\w-])(\\d{2})(-?)(\\d{7})(?![\\w-])");
    private static final Pattern DRIVERS_LICENSE_VALUE = Pattern.compile(
            "(?<![A-Za-z0-9-])([A-Z]{0,3}\\d[A-Z0-9\\-]{3,18}[A-Z0-9])(?![A-Za-z0-9-])");
    private static final Pattern PASSPORT_VALUE = Pattern.compile("(?<![A-Za-z0-9])([A-Z]{0,2}\\d{6,9})(?![A-Za-z0-9])");

    private static final Pattern DATE_SHAPE = Pattern.compile("\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{1,4}");
    private static final Pattern PHONE_SHAPE = Pattern.compile("1?-?\\d{3}-\\d{3}-\\d{4}");
    private static final Pattern ZIP4_SHAPE = Pattern.compile("\\d{5}-\\d{4}");
    private static final Pattern SSN_SHAPE = Pattern.compile("\\d{3}-\\d{2}-\\d{4}");
    private static final Pattern EIN_SHAPE = Pattern.compile("\\d{2}-\\d{7}");

    /** MICR symbols as printed (U+2446..U+2449) and as OCR commonly reads them. */
    private static final String MICR_DELIMITERS = "\u2446\u2447\u2448\u2449:;|[]!\"'";
    /** A digit group on a MICR line, misreads included; kept only when most of it is real digits. */
    private static final Pattern DIGIT_GROUP = Pattern.compile("[0-9OoIlSBb]{3,}");
    /** Words printed on a check; with them, a transit-delimited routing number counts even on a noisy OCR line. */
    private static final Pattern CHECK_PAGE_WORDS = Pattern.compile(
            "(?i)(?<![a-z])(?:pay\\s+to\\s+the\\s+order\\s+of|order\\s+of|dollars|memo|check|cheque)(?![a-z])");

    // ---------------------------------------------------------------- label ownership

    private record LabelKind(String kind, Pattern label, Predicate<String> claims) {
    }

    private static final List<LabelKind> LABELS = List.of(
            new LabelKind(ROUTING, ROUTING_LABEL, v -> isAbaValid(digits(v))),
            // A checksum-valid 9-digit value is left to a routing label ("Routing number Account number" header rows).
            new LabelKind(ACCOUNT, ACCOUNT_LABEL, v -> v.matches("[\\d-]+") && between(digits(v).length(), 4, 17)
                    && !isAbaValid(digits(v))),
            new LabelKind(ITIN, ITIN_LABEL, OptInPatternDetector::isItinShaped),
            new LabelKind(EIN, EIN_LABEL, v -> digits(v).length() == 9 && v.matches("[\\d-]+")),
            new LabelKind(DRIVERS_LICENSE, DRIVERS_LICENSE_LABEL, v -> plausibleLicense(v)),
            new LabelKind(PASSPORT, PASSPORT_LABEL, v -> plausiblePassport(v)),
            new LabelKind("SSN", SSN_LABEL, v -> digits(v).length() == 9 && v.matches("[\\d -]+")),
            new LabelKind("CARD", CARD_LABEL, v -> v.matches("[\\d -]+") && between(digits(v).length(), 13, 19)
                    && isLuhnValid(digits(v))),
            new LabelKind("PHONE", PHONE_LABEL, v -> v.matches("[\\d ()+.-]+") && between(digits(v).length(), 10, 11)),
            new LabelKind("DATE", DATE_LABEL, v -> DATE_SHAPE.matcher(v).matches()
                    || (v.matches("\\d{8}") && plausibleCompactDate(v))),
            new LabelKind("SCORE", SCORE_LABEL, v -> v.matches("\\d{3}")),
            new LabelKind("ZIP", ZIP_LABEL, v -> v.matches("\\d{5}(?:-?\\d{4})?")),
            new LabelKind("OTHER_ID", OTHER_ID_LABEL, v -> true));

    // ---------------------------------------------------------------- detection

    void detect(String text, int pageNumber, List<DetectedEntity> results) {
        List<DetectedEntity> found = new ArrayList<>();
        detectCheckLines(text, pageNumber, found);
        detectRoutingNumbers(text, pageNumber, found);
        detectItins(text, pageNumber, found);
        detectEins(text, pageNumber, found);
        detectAccountNumbers(text, pageNumber, found);
        detectLabelled(text, pageNumber, DRIVERS_LICENSE_VALUE, DRIVERS_LICENSE, DRIVERS_LICENSE_LABEL,
                OptInPatternDetector::plausibleLicense, found);
        detectLabelled(text, pageNumber, PASSPORT_VALUE, PASSPORT, PASSPORT_LABEL,
                OptInPatternDetector::plausiblePassport, found);
        results.addAll(found);
    }

    private void detectRoutingNumbers(String text, int pageNumber, List<DetectedEntity> found) {
        Matcher m = NINE_DIGIT_LIKE.matcher(text);
        while (m.find()) {
            String token = m.group(1);
            MappedDigits mapped = mapMisreads(token);
            if (mapped == null || mapped.substitutions() > 2 || !isAbaValid(mapped.digits())) continue;
            int start = m.start(1), end = m.end(1);
            if (overlaps(found, start, end)) continue;
            // Ownership is decided on the corrected digits.
            Binding binding = bind(text, start, end, mapped.digits(), ROUTING, ROUTING_LABEL);
            if (binding == Binding.NONE) continue;
            double confidence = binding == Binding.SAME_LINE ? 0.95 : 0.90;
            add(found, ROUTING, pageNumber, start, end, confidence - mapped.substitutions() * MISREAD_PENALTY);
        }
    }

    private void detectAccountNumbers(String text, int pageNumber, List<DetectedEntity> found) {
        Matcher m = ACCOUNT_VALUE.matcher(text);
        while (m.find()) {
            String value = m.group(1);
            int start = m.start(1), end = m.end(1);
            int count = digits(value).length();
            if (!between(count, 4, 17) || overlaps(found, start, end)) continue;
            if (DATE_SHAPE.matcher(value).matches() || PHONE_SHAPE.matcher(value).matches()
                    || ZIP4_SHAPE.matcher(value).matches() || SSN_SHAPE.matcher(value).matches()
                    || EIN_SHAPE.matcher(value).matches()) continue;
            if (isAmount(text, start, end) || partOfGroupedNumber(text, start, end)) continue;
            Binding binding = bind(text, start, end, value, ACCOUNT, ACCOUNT_LABEL);
            // A 4- or 5-digit value only directly after its label: a year or a count in a table is not an account.
            if (binding == Binding.NONE || (binding == Binding.HEADER_ROW && count < 6)) continue;
            add(found, ACCOUNT, pageNumber, start, end, binding == Binding.SAME_LINE ? 0.90 : 0.85);
        }
    }

    private void detectItins(String text, int pageNumber, List<DetectedEntity> found) {
        Matcher m = ITIN_VALUE.matcher(text);
        while (m.find()) {
            String value = m.group();
            if (!isItinShaped(value)) continue;
            int start = m.start(), end = m.end();
            if (overlaps(found, start, end)) continue;
            Binding binding = bindOrFollowing(text, start, end, value, ITIN, ITIN_LABEL);
            if (binding == Binding.NONE) continue;
            add(found, ITIN, pageNumber, start, end, binding == Binding.SAME_LINE ? 0.95 : 0.90);
        }
    }

    private void detectEins(String text, int pageNumber, List<DetectedEntity> found) {
        Matcher m = EIN_VALUE.matcher(text);
        while (m.find()) {
            String value = m.group();
            if (!isValidEinPrefix(Integer.parseInt(m.group(1)))) continue;
            int start = m.start(), end = m.end();
            if (overlaps(found, start, end)) continue;
            Binding binding = bindOrFollowing(text, start, end, value, EIN, EIN_LABEL);
            // Nine digits without the dash only directly after an EIN label: otherwise it could be anything.
            if (binding == Binding.NONE || (m.group(2).isEmpty() && binding != Binding.SAME_LINE)) continue;
            add(found, EIN, pageNumber, start, end, binding == Binding.SAME_LINE ? 0.90 : 0.85);
        }
    }

    private void detectLabelled(String text, int pageNumber, Pattern valuePattern, String kind, Pattern label,
                                Predicate<String> plausible, List<DetectedEntity> found) {
        Matcher m = valuePattern.matcher(text);
        while (m.find()) {
            String value = m.group(1);
            int start = m.start(1), end = m.end(1);
            if (!plausible.test(value) || overlaps(found, start, end)) continue;
            Binding binding = bind(text, start, end, value, kind, label);
            if (binding == Binding.NONE) continue;
            add(found, kind, pageNumber, start, end, binding == Binding.SAME_LINE ? 0.90 : 0.85);
        }
    }

    // ---------------------------------------------------------------- check (MICR) line

    private void detectCheckLines(String text, int pageNumber, List<DetectedEntity> found) {
        boolean checkPage = CHECK_PAGE_WORDS.matcher(text).find();
        int lineStart = 0;
        while (lineStart <= text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            if (lineEnd < 0) lineEnd = text.length();
            detectCheckLine(text, lineStart, lineEnd, pageNumber, checkPage, found);
            lineStart = lineEnd + 1;
        }
    }

    /**
     * A MICR line: either mostly digits and MICR symbols (by itself), or, on a page that reads like a check, any line
     * with a transit-delimited checksum-valid routing number (OCR often adds letter noise to the MICR line).
     */
    private void detectCheckLine(String text, int lineStart, int lineEnd, int pageNumber, boolean checkPage,
                                 List<DetectedEntity> found) {
        String line = text.substring(lineStart, lineEnd);
        int nonSpace = 0, digitsOrSymbols = 0, digitCount = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (Character.isWhitespace(c)) continue;
            nonSpace++;
            if (Character.isDigit(c)) digitCount++;
            if (Character.isDigit(c) || c == 'O' || MICR_DELIMITERS.indexOf(c) >= 0) digitsOrSymbols++;
        }
        boolean micrLike = nonSpace > 0 && digitCount >= 15 && digitsOrSymbols >= 0.85 * nonSpace;
        if (!micrLike && !checkPage) return;

        List<int[]> groups = new ArrayList<>();
        Matcher g = DIGIT_GROUP.matcher(line);
        while (g.find()) {
            String token = line.substring(g.start(), g.end());
            long real = token.chars().filter(Character::isDigit).count();
            if (real >= 3 && real * 10 >= token.length() * 7L) groups.add(new int[]{g.start(), g.end()});
        }
        if (groups.size() < 2 && !checkPage) return;

        boolean anySymbol = line.chars().anyMatch(c -> MICR_DELIMITERS.indexOf(c) >= 0);
        for (int i = 0; i < groups.size(); i++) {
            int[] group = groups.get(i);
            String token = line.substring(group[0], group[1]);
            MappedDigits mapped = mapMisreads(token);
            if (mapped == null || mapped.substitutions() > 2 || mapped.digits().length() != 9
                    || !isAbaValid(mapped.digits())) continue;
            boolean transit = touchesSymbol(line, group[0], group[1]);
            // Without symbols, only the plain "routing account check-number" line counts.
            boolean plainTriple = micrLike && !anySymbol && groups.size() == 3 && i == 0;
            if (!(transit && (micrLike || checkPage)) && !plainTriple) continue;
            double base = transit ? 0.90 : 0.85;
            add(found, ROUTING, pageNumber, lineStart + group[0], lineStart + group[1],
                    base - mapped.substitutions() * MISREAD_PENALTY);
            if (i + 1 < groups.size()) {
                int[] account = groups.get(i + 1);
                String accountToken = line.substring(account[0], account[1]);
                MappedDigits accountDigits = mapMisreads(accountToken);
                if (accountDigits == null || accountDigits.substitutions() > 2
                        || !between(accountDigits.digits().length(), 4, 17)) return;
                // A short last group is usually the check number; take it as the account only with its on-us symbol.
                boolean last = i + 2 == groups.size();
                if (accountDigits.digits().length() < 6 && last && !touchesSymbol(line, account[0], account[1])) return;
                add(found, ACCOUNT, pageNumber, lineStart + account[0], lineStart + account[1],
                        base - 0.05 - accountDigits.substitutions() * MISREAD_PENALTY);
            }
            return;
        }
    }

    private static boolean touchesSymbol(String line, int start, int end) {
        return (start > 0 && MICR_DELIMITERS.indexOf(line.charAt(start - 1)) >= 0)
                || (end < line.length() && MICR_DELIMITERS.indexOf(line.charAt(end)) >= 0);
    }

    // ---------------------------------------------------------------- binding a value to its label

    private enum Binding { NONE, SAME_LINE, HEADER_ROW }

    /**
     * SAME_LINE: the kind's label is before the value on its line with only filler between. HEADER_ROW: the kind's
     * label is on the line above and is the nearest label before the value that could own it.
     */
    private Binding bind(String text, int start, int end, String value, String kind, Pattern ownLabel) {
        int lineStart = text.lastIndexOf('\n', start - 1) + 1;
        Matcher same = ownLabel.matcher(text);
        same.region(lineStart, start);
        int lastEnd = -1;
        while (same.find()) lastEnd = same.end();
        if (lastEnd >= 0 && FILLER.matcher(text.substring(lastEnd, start)).matches()) return Binding.SAME_LINE;

        String owner = nearestOwner(text, start, value);
        if (!kind.equals(owner)) return Binding.NONE;
        int windowStart = Math.max(0, start - LABEL_WINDOW);
        Matcher label = ownLabel.matcher(text);
        label.region(windowStart, start);
        int nearestEnd = -1;
        while (label.find()) nearestEnd = label.end();
        if (nearestEnd < 0) return Binding.NONE;
        long newlines = text.substring(nearestEnd, start).chars().filter(c -> c == '\n').count();
        // The label is on the line right above (a form's header row). A label earlier on the same line with words
        // between ("keep your account details safe. 4410...") does not own the value.
        return newlines == 1 ? Binding.HEADER_ROW : Binding.NONE;
    }

    /** As {@link #bind}, and also a label right after the value on its line ("12-3456789 (EIN)"). */
    private Binding bindOrFollowing(String text, int start, int end, String value, String kind, Pattern ownLabel) {
        Binding binding = bind(text, start, end, value, kind, ownLabel);
        if (binding != Binding.NONE) return binding;
        int lineEnd = text.indexOf('\n', end);
        if (lineEnd < 0) lineEnd = text.length();
        Matcher after = ownLabel.matcher(text);
        after.region(end, Math.min(lineEnd, end + 20));
        if (after.find() && FILLER.matcher(text.substring(end, after.start())).matches()) {
            String owner = nearestOwner(text, start, value);
            if (owner == null || owner.equals(kind)) return Binding.SAME_LINE;
        }
        return Binding.NONE;
    }

    /** The kind of the nearest label before the value (within the window) that could own this value, or null. */
    private String nearestOwner(String text, int start, String value) {
        int windowStart = Math.max(0, start - LABEL_WINDOW);
        String owner = null;
        int bestGap = Integer.MAX_VALUE;
        for (LabelKind kind : LABELS) {
            if (!kind.claims().test(value)) continue;
            Matcher label = kind.label().matcher(text);
            label.region(windowStart, start);
            while (label.find()) {
                int gap = start - label.end();
                if (gap < bestGap || (gap == bestGap && owner != null && isMoreSpecific(kind.kind(), owner))) {
                    bestGap = gap;
                    owner = kind.kind();
                }
            }
        }
        return owner;
    }

    /** On a tie ("SSN or ITIN"), the specific kind of this task wins over the generic labels. */
    private static boolean isMoreSpecific(String candidate, String current) {
        return !candidate.equals("OTHER_ID") && current.equals("OTHER_ID");
    }

    // ---------------------------------------------------------------- value rules

    static boolean isAbaValid(String digits) {
        if (digits == null || digits.length() != 9 || digits.chars().distinct().count() == 1) return false;
        int[] d = digits.chars().map(c -> c - '0').toArray();
        int sum = 3 * (d[0] + d[3] + d[6]) + 7 * (d[1] + d[4] + d[7]) + (d[2] + d[5] + d[8]);
        return sum % 10 == 0;
    }

    static boolean isItinShaped(String value) {
        Matcher m = ITIN_VALUE.matcher(value);
        if (!m.matches()) return false;
        int group = Integer.parseInt(m.group(3));
        return (group >= 70 && group <= 88) || (group >= 90 && group <= 92) || (group >= 94 && group <= 99);
    }

    /** IRS campus and online prefixes; 00, 07-09, 17-19, 28-29, 49, 69-70, 78-79, 89, 96-97 are never issued. */
    static boolean isValidEinPrefix(int prefix) {
        return (prefix >= 1 && prefix <= 6) || (prefix >= 10 && prefix <= 16) || (prefix >= 20 && prefix <= 27)
                || (prefix >= 30 && prefix <= 48) || (prefix >= 50 && prefix <= 68) || (prefix >= 71 && prefix <= 77)
                || (prefix >= 80 && prefix <= 88) || (prefix >= 90 && prefix <= 95) || prefix == 98 || prefix == 99;
    }

    static boolean plausibleLicense(String value) {
        if (!DRIVERS_LICENSE_VALUE.matcher(value).matches()) return false;
        String alnum = value.replace("-", "");
        long digitCount = alnum.chars().filter(Character::isDigit).count();
        long letters = alnum.length() - digitCount;
        if (alnum.length() < 5 || alnum.length() > 16 || digitCount < 4 || letters > 3) return false;
        return !DATE_SHAPE.matcher(value).matches() && !SSN_SHAPE.matcher(value).matches()
                && !PHONE_SHAPE.matcher(value).matches() && !ZIP4_SHAPE.matcher(value).matches();
    }

    static boolean plausiblePassport(String value) {
        return PASSPORT_VALUE.matcher(value).matches() && value.length() >= 6 && value.length() <= 11;
    }

    private static boolean plausibleCompactDate(String v) {
        int month = Integer.parseInt(v.substring(0, 2));
        int day = Integer.parseInt(v.substring(2, 4));
        int year = Integer.parseInt(v.substring(4));
        return month >= 1 && month <= 12 && day >= 1 && day <= 31 && year >= 1900 && year <= 2100;
    }

    private static boolean isAmount(String text, int start, int end) {
        int before = start - 1;
        while (before >= 0 && text.charAt(before) == ' ') before--;
        if (before >= 0 && (text.charAt(before) == '$' || text.charAt(before) == '\u20ac' || text.charAt(before) == '\u00a3')) {
            return true;
        }
        String after = text.substring(end, Math.min(text.length(), end + 4)).trim().toLowerCase();
        return after.startsWith("usd") || after.startsWith("dollars");
    }

    /**
     * True when the value is one group of a number written in short groups ("4000 0012 3456 7899", "555 123 4567"):
     * three or more space-separated digit groups of at most four digits each.
     */
    private static boolean partOfGroupedNumber(String text, int start, int end) {
        int left = start;
        while (left >= 2 && text.charAt(left - 1) == ' ' && Character.isDigit(text.charAt(left - 2))) {
            left -= 2;
            while (left > 0 && Character.isDigit(text.charAt(left - 1))) left--;
        }
        int right = end;
        while (right + 1 < text.length() && text.charAt(right) == ' ' && Character.isDigit(text.charAt(right + 1))) {
            right += 2;
            while (right < text.length() && Character.isDigit(text.charAt(right))) right++;
        }
        String[] groups = text.substring(left, right).split(" ");
        if (groups.length < 3) return false;
        for (String group : groups) {
            if (group.length() > 4) return false;
        }
        return true;
    }

    static boolean isLuhnValid(String digits) {
        int sum = 0;
        boolean alternate = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int n = digits.charAt(i) - '0';
            if (alternate) {
                n *= 2;
                if (n > 9) n -= 9;
            }
            sum += n;
            alternate = !alternate;
        }
        return sum % 10 == 0;
    }

    private record MappedDigits(String digits, int substitutions) {
    }

    /** Common OCR misreads in a digit field: O/o for 0, I/l for 1, S for 5, b for 6, B for 8. Null if another letter. */
    private static MappedDigits mapMisreads(String token) {
        StringBuilder out = new StringBuilder(token.length());
        int substitutions = 0;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            char d;
            switch (c) {
                case 'O', 'o' -> d = '0';
                case 'I', 'l' -> d = '1';
                case 'S' -> d = '5';
                case 'b' -> d = '6';
                case 'B' -> d = '8';
                default -> d = c;
            }
            if (d != c) substitutions++;
            else if (!Character.isDigit(c)) return null;
            out.append(d);
        }
        return new MappedDigits(out.toString(), substitutions);
    }

    private static String digits(String value) {
        return value.replaceAll("[^0-9]", "");
    }

    private static boolean between(int value, int min, int max) {
        return value >= min && value <= max;
    }

    private static boolean overlaps(List<DetectedEntity> found, int start, int end) {
        return found.stream().anyMatch(e -> e.startOffset() < end && start < e.endOffset());
    }

    private static void add(List<DetectedEntity> found, String kind, int pageNumber, int start, int end,
                            double confidence) {
        if (overlaps(found, start, end)) return;
        found.add(new DetectedEntity(kind, pageNumber, start, end, Math.max(0.5, Math.min(1.0, confidence)),
                DETECTOR_NAME, DETECTOR_VERSION));
    }
}

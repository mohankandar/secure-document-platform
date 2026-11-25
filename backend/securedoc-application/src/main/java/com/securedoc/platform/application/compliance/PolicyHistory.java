package com.securedoc.platform.application.compliance;

import com.securedoc.platform.domain.business.PolicyPreset;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.VisionSettings;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Reads the policy values out of {@code SENSITIVE_DATA_POLICY_CHANGED} and {@code LIFECYCLE_POLICY_CHANGED} audit
 * details (task 0016 format) and words the changes for the compliance report (task 0017 B2). Only the known keys
 * are read; anything else in a detail is ignored, and a value that cannot be read is left unknown, never guessed.
 */
public final class PolicyHistory {

    private static final String SENSITIVE_PREFIX = "Sensitive-data policy changed: ";
    private static final String LIFECYCLE_PREFIX = "Lifecycle policy changed: ";

    private PolicyHistory() {
    }

    /** Sensitive-data policy values; {@code types} is null when the record did not hold the names (before 0016). */
    public record SensitivePolicy(Set<String> types, Integer enabledCount, Double threshold, String reviewMode,
                                  Boolean maskSsnLastFour, String preset, Set<String> packs,
                                  Set<String> keepLastFour, VisionSettings vision, boolean secondCheck,
                                  boolean documentContext) {

        /** Without packs or styles (before task 0062: none). */
        public SensitivePolicy(Set<String> types, Integer enabledCount, Double threshold, String reviewMode,
                               Boolean maskSsnLastFour, String preset) {
            this(types, enabledCount, threshold, reviewMode, maskSsnLastFour, preset, Set.of(), Set.of(), VisionSettings.DEFAULTS,
                    false, false);
        }

        public SensitivePolicy with(Set<String> packNames, Set<String> lastFour) {
            return new SensitivePolicy(types, enabledCount, threshold, reviewMode, maskSsnLastFour, preset,
                    new TreeSet<>(packNames), new TreeSet<>(lastFour), vision, secondCheck, documentContext);
        }

        /** Task 0064. */
        public SensitivePolicy withVision(VisionSettings settings) {
            return new SensitivePolicy(types, enabledCount, threshold, reviewMode, maskSsnLastFour, preset, packs,
                    keepLastFour, settings, secondCheck, documentContext);
        }

        /** Task 0083. */
        public SensitivePolicy withSecondCheck(boolean on) {
            return new SensitivePolicy(types, enabledCount, threshold, reviewMode, maskSsnLastFour, preset, packs,
                    keepLastFour, vision, on, documentContext);
        }

        /** Task 0065. */
        public SensitivePolicy withDocumentContext(boolean on) {
            return new SensitivePolicy(types, enabledCount, threshold, reviewMode, maskSsnLastFour, preset, packs,
                    keepLastFour, vision, secondCheck, on);
        }

        public static SensitivePolicy of(Set<String> types, double threshold, ReviewMode mode, boolean mask) {
            Set<String> names = new TreeSet<>(types);
            return new SensitivePolicy(names, names.size(), threshold, mode.name(), mask,
                    PolicyPreset.of(mode, threshold).name());
        }
    }

    /** A parsed change: the new values and, when recorded, the values before it. */
    public record SensitiveChange(SensitivePolicy now, SensitivePolicy previous, boolean previousRecorded) { }

    public record LifecyclePolicy(int originalRetentionHours, int redactedCacheHours, int neverExportedHoldHours,
                                  boolean finalApprovalRequired) { }

    /** Null when the detail is not a sensitive-data policy change. */
    public static SensitiveChange parseSensitive(String detail) {
        if (detail == null || !detail.startsWith(SENSITIVE_PREFIX)) return null;
        String body = detail.substring(SENSITIVE_PREFIX.length());
        int prevAt = body.indexOf(", previous=");
        String nowPart = prevAt < 0 ? body : body.substring(0, prevAt);
        SensitivePolicy now = sensitive(keys(nowPart));
        if (now == null) return null;
        if (prevAt < 0) return new SensitiveChange(now, null, false);
        String prev = body.substring(prevAt + ", previous=".length()).trim();
        if (prev.equals("none")) return new SensitiveChange(now, null, true);
        if (prev.startsWith("[") && prev.endsWith("]")) {
            return new SensitiveChange(now, sensitive(keys(prev.substring(1, prev.length() - 1))), true);
        }
        return new SensitiveChange(now, null, false);
    }

    /**
     * A parsed lifecycle change (task 0018 A4). {@code previousRecorded} is false for records written before 0018
     * (no {@code previous=} key); {@code previous} is null when it was {@code previous=none} or not recorded.
     */
    public record LifecycleChange(LifecyclePolicy now, LifecyclePolicy previous, boolean previousRecorded) { }

    /** Null when the detail is not a lifecycle policy change with all four values. */
    public static LifecyclePolicy parseLifecycle(String detail) {
        LifecycleChange change = parseLifecycleChange(detail);
        return change == null ? null : change.now();
    }

    /** Null when the detail is not a lifecycle policy change with all four new values. */
    public static LifecycleChange parseLifecycleChange(String detail) {
        if (detail == null || !detail.startsWith(LIFECYCLE_PREFIX)) return null;
        String body = detail.substring(LIFECYCLE_PREFIX.length());
        int prevAt = body.indexOf(", previous=");
        LifecyclePolicy now = lifecycle(keys(prevAt < 0 ? body : body.substring(0, prevAt)));
        if (now == null) return null;
        if (prevAt < 0) return new LifecycleChange(now, null, false);
        String prev = body.substring(prevAt + ", previous=".length()).trim();
        if (prev.equals("none")) return new LifecycleChange(now, null, true);
        if (prev.startsWith("[") && prev.endsWith("]")) {
            LifecyclePolicy before = lifecycle(keys(prev.substring(1, prev.length() - 1)));
            if (before != null) return new LifecycleChange(now, before, true);
        }
        // An unreadable previous= is treated as not recorded: the caller falls back, never guesses.
        return new LifecycleChange(now, null, false);
    }

    /**
     * The plain-words lines for one lifecycle change from {@code before} to {@code after}, worded like section 1
     * ("Originals kept for: 72 → 48 hours"). {@code before} null means the policy was saved for the first time.
     */
    public static List<String> lifecycleChangeLines(LifecyclePolicy before, LifecyclePolicy after) {
        List<String> lines = new ArrayList<>();
        if (before == null) {
            lines.add("Policy saved for the first time");
            return lines;
        }
        if (before.originalRetentionHours() != after.originalRetentionHours()) {
            lines.add("Originals kept for: " + before.originalRetentionHours() + " → "
                    + after.originalRetentionHours() + " hours");
        }
        if (before.redactedCacheHours() != after.redactedCacheHours()) {
            lines.add("Redacted copies kept for: " + hoursWords(before.redactedCacheHours()) + " → "
                    + hoursWords(after.redactedCacheHours()));
        }
        if (before.neverExportedHoldHours() != after.neverExportedHoldHours()) {
            lines.add("Stopped cases kept for: " + hoursWords(before.neverExportedHoldHours()) + " → "
                    + hoursWords(after.neverExportedHoldHours()));
        }
        if (before.finalApprovalRequired() != after.finalApprovalRequired()) {
            lines.add("Supervisor approval before delivery: " + requiredWords(before.finalApprovalRequired()) + " → "
                    + requiredWords(after.finalApprovalRequired()));
        }
        if (lines.isEmpty()) lines.add("Saved with no change");
        return lines;
    }

    public static String requiredWords(boolean required) {
        return required ? "Required" : "Not required";
    }

    private static LifecyclePolicy lifecycle(Map<String, String> k) {
        try {
            return new LifecyclePolicy(Integer.parseInt(k.get("originalRetentionHours")),
                    Integer.parseInt(k.get("redactedCacheHours")), Integer.parseInt(k.get("neverExportedHoldHours")),
                    parseBoolean(k.get("finalApprovalRequired")));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The plain-words lines for one recorded sensitive-data policy change, matching the canvas wording. */
    public static List<String> changeLines(SensitiveChange change) {
        List<String> lines = new ArrayList<>();
        if (!change.previousRecorded()) {
            lines.add("Changed (this record does not hold the earlier values)");
            return lines;
        }
        if (change.previous() == null) {
            lines.add("Policy saved for the first time");
            return lines;
        }
        SensitivePolicy before = change.previous();
        SensitivePolicy after = change.now();
        if (before.preset() != null && after.preset() != null && !before.preset().equals(after.preset())) {
            lines.add("Preset: " + presetWords(before.preset()) + " → " + presetWords(after.preset()));
        }
        if (before.reviewMode() != null && after.reviewMode() != null && !before.reviewMode().equals(after.reviewMode())) {
            lines.add("Sent to a person: " + reviewModeWords(before.reviewMode()) + " → "
                    + reviewModeWords(after.reviewMode()));
        }
        if (before.threshold() != null && after.threshold() != null
                && Math.abs(before.threshold() - after.threshold()) > 1e-9) {
            lines.add("Redacted without review at: " + percent(before.threshold()) + " → " + percent(after.threshold()));
        }
        if (before.types() != null && after.types() != null) {
            after.types().stream().filter(t -> !before.types().contains(t)).sorted(SensitiveTypeNames.order())
                    .forEach(t -> lines.add(SensitiveTypeNames.name(t) + " turned on"));
            before.types().stream().filter(t -> !after.types().contains(t)).sorted(SensitiveTypeNames.order())
                    .forEach(t -> lines.add(SensitiveTypeNames.name(t) + " turned off"));
        }
        if (before.maskSsnLastFour() != null && after.maskSsnLastFour() != null
                && !before.maskSsnLastFour().equals(after.maskSsnLastFour())) {
            lines.add("Labeled last-four SSN treated as sensitive: " + yesNo(before.maskSsnLastFour()) + " → "
                    + yesNo(after.maskSsnLastFour()));
        }
        after.packs().stream().filter(p -> !before.packs().contains(p))
                .forEach(p -> lines.add("Regulation pack " + packName(p) + " turned on"));
        before.packs().stream().filter(p -> !after.packs().contains(p))
                .forEach(p -> lines.add("Regulation pack " + packName(p) + " turned off"));
        after.keepLastFour().stream().filter(t -> !before.keepLastFour().contains(t)).sorted(SensitiveTypeNames.order())
                .forEach(t -> lines.add(SensitiveTypeNames.name(t) + ": remove fully → keep the last 4"));
        before.keepLastFour().stream().filter(t -> !after.keepLastFour().contains(t)).sorted(SensitiveTypeNames.order())
                .forEach(t -> lines.add(SensitiveTypeNames.name(t) + ": keep the last 4 → remove fully"));
        if (before.vision().enabled() != after.vision().enabled()) {
            lines.add("Hard pages read by the vision model: " + yesNo(before.vision().enabled()) + " → "
                    + yesNo(after.vision().enabled()));
        }
        if (Math.abs(before.vision().ocrThreshold() - after.vision().ocrThreshold()) > 1e-9) {
            lines.add("Pages are hard below text-reading confidence: " + percent(before.vision().ocrThreshold()) + " → "
                    + percent(after.vision().ocrThreshold()));
        }
        if (before.documentContext() != after.documentContext()) {
            lines.add("Document summary and case instructions: " + yesNo(before.documentContext()) + " → "
                    + yesNo(after.documentContext()));
        }
        if (before.secondCheck() != after.secondCheck()) {
            lines.add("Second check of the finished file: " + yesNo(before.secondCheck()) + " → "
                    + yesNo(after.secondCheck()));
        }
        if (lines.isEmpty()) lines.add("Saved with no change");
        return lines;
    }

    private static final java.util.regex.Pattern MINUTES_PER_PAGE =
            java.util.regex.Pattern.compile("\\bminutesPerPage=([0-9]+(?:\\.[0-9]+)?)");

    /**
     * Task 0020 B4: the minutes per page in a {@code REPORTING_CONFIGURATION_CHANGED} detail: the value saved
     * ({@code previous=false}) or the one in {@code previous=[...]} ({@code previous=true}). Null when that part does
     * not hold it (records from before task 0020, or {@code previous=none}).
     */
    public static Double minutesPerPage(String detail, boolean previous) {
        if (detail == null) return null;
        int split = detail.indexOf("previous=");
        String part = previous ? (split < 0 ? "" : detail.substring(split)) : (split < 0 ? detail : detail.substring(0, split));
        java.util.regex.Matcher m = MINUTES_PER_PAGE.matcher(part);
        if (!m.find()) return null;
        try {
            return Double.valueOf(m.group(1));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String reviewModeWords(String mode) {
        if (mode == null) return "Not recorded";
        return switch (mode) {
            case "REVIEW_ALL" -> "Every finding"; // task 0020 A3: the words of the Fine-tune button
            case "UNCERTAIN_ONLY" -> "Only uncertain findings";
            default -> SensitiveTypeNames.sentenceCase(mode);
        };
    }

    public static String presetWords(String preset) {
        return preset == null ? "Not recorded" : SensitiveTypeNames.sentenceCase(preset);
    }

    public static String percent(double threshold) {
        return Math.round(threshold * 100) + "%";
    }

    public static String yesNo(boolean value) {
        return value ? "Yes" : "No";
    }

    /** "72 hours", "7 days" (whole days from 2 days up), "24 hours". */
    public static String hoursWords(int hours) {
        if (hours >= 48 && hours % 24 == 0) return (hours / 24) + " days";
        return hours == 1 ? "1 hour" : hours + " hours";
    }

    private static SensitivePolicy sensitive(Map<String, String> k) {
        if (!k.containsKey("threshold") && !k.containsKey("reviewMode")) return null;
        Set<String> types = null;
        if (k.containsKey("enabledTypes")) {
            types = new TreeSet<>();
            for (String t : k.get("enabledTypes").split(",")) {
                String v = t.trim();
                if (v.matches("[A-Z_]+")) types.add(v);
            }
        }
        return new SensitivePolicy(types, parseInt(k.get("enabledCount")), parseDouble(k.get("threshold")),
                enumWord(k.get("reviewMode")), k.containsKey("maskSsnLastFour") ? parseBoolean(k.get("maskSsnLastFour")) : null,
                enumWord(k.get("preset"))).with(list(k.get("packs")), list(k.get("keepLastFour")))
                .withVision(vision(k)).withSecondCheck("true".equals(k.get("secondCheck")))
                .withDocumentContext("true".equals(k.get("documentContext")));
    }

    private static Set<String> list(String value) {
        Set<String> out = new TreeSet<>();
        if (value != null) {
            for (String v : value.split(",")) {
                if (v.matches("[A-Z0-9_]+")) out.add(v);
            }
        }
        return out;
    }

    public static String packName(String key) {
        try {
            return com.securedoc.platform.domain.business.RegulationPack.valueOf(key).displayName();
        } catch (IllegalArgumentException unknown) {
            return SensitiveTypeNames.sentenceCase(key);
        }
    }

    /** Task 0064: the keys are written only when not the default. */
    private static VisionSettings vision(Map<String, String> k) {
        Double threshold = parseDouble(k.get("visionOcrThreshold"));
        try {
            return new VisionSettings(!"false".equals(k.get("visionEnabled")),
                    threshold == null ? VisionSettings.DEFAULTS.ocrThreshold() : threshold);
        } catch (IllegalArgumentException unreadable) {
            return VisionSettings.DEFAULTS;
        }
    }

    /** "a=1, b=X,Y, c=true" → {a:1, b:"X,Y", c:true}. Keys are split on ", " only; lists use "," with no space. */
    private static Map<String, String> keys(String part) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String pair : part.split(", ")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            out.putIfAbsent(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
        }
        return out;
    }

    private static String enumWord(String v) {
        return v != null && v.matches("[A-Z_]+") ? v : null;
    }

    private static Integer parseInt(String v) {
        try {
            return v == null ? null : Integer.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String v) {
        try {
            return v == null ? null : Double.valueOf(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean parseBoolean(String v) {
        if ("true".equals(v)) return true;
        if ("false".equals(v)) return false;
        throw new IllegalArgumentException("not a boolean");
    }
}

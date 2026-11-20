package com.securedoc.platform.application.compliance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Every value the compliance report prints (task 0017 B3), already in words. The PDF adapter only lays it out.
 *
 * <p>{@link #fingerprint()} is the SHA-256 of {@link #canonicalText()}: one {@code key=value} line per printed value,
 * in print order, including the created-at time, the creator and the edition. Page numbers and the fingerprint
 * itself are not part of it (they are layout, and a text cannot hold its own hash).
 */
public record ComplianceReportContent(
        ComplianceEdition edition,
        String customerName,
        String entityName,
        String periodWords,
        String periodEndWords,
        String createdBy,
        String createdAt,
        Policy policy,
        Retention retention,
        Cases cases,
        List<CountRow> activity,
        List<AppendixRow> appendix
) {

    public ComplianceReportContent {
        Objects.requireNonNull(edition);
        Objects.requireNonNull(entityName);
        Objects.requireNonNull(periodWords);
        Objects.requireNonNull(periodEndWords);
        Objects.requireNonNull(createdBy);
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(retention);
        Objects.requireNonNull(cases);
        activity = List.copyOf(activity);
        appendix = appendix == null ? List.of() : List.copyOf(appendix);
        if (edition == ComplianceEdition.SUMMARY && !appendix.isEmpty()) {
            throw new IllegalArgumentException("The summary edition has no appendix");
        }
    }

    public record LabelValue(String label, String value) { }

    public record ChangeRow(String when, String who, String what) { }

    /** Section 1. {@code unchangedSinceBefore}: no change record at or before the period end. */
    public record Policy(boolean unchangedSinceBefore, List<LabelValue> atEnd, List<ChangeRow> changes) {
        public Policy {
            atEnd = List.copyOf(atEnd);
            changes = List.copyOf(changes);
        }
    }

    /** Section 2. */
    public record Retention(boolean unchangedSinceBefore, List<LabelValue> settings, long due, long onTime,
                            long lateOrFailed, String note) {
        public Retention {
            settings = List.copyOf(settings);
            Objects.requireNonNull(note);
        }
    }

    /** Section 3. {@code share} is a whole percent ("95%"). */
    public record CountRow(String label, long count, String share) { }

    public record Cases(List<CountRow> outcomes, List<CountRow> ofDelivered) {
        public Cases {
            outcomes = List.copyOf(outcomes);
            ofDelivered = List.copyOf(ofDelivered);
        }
    }

    /** One appendix row; {@code detail} is "—" unless the action is on {@link AppendixDetailAllowList}. */
    public record AppendixRow(String time, String action, String person, String result, String caseId,
                              String detail) { }

    public String fingerprint() {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonicalText().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** The canonical, ordered text form of every printed value. */
    public String canonicalText() {
        StringBuilder out = new StringBuilder(4096 + appendix.size() * 160);
        line(out, "format", "lexavault-compliance-report/1");
        line(out, "edition", edition.name());
        line(out, "customer", customerName == null ? "" : customerName);
        line(out, "entity", entityName);
        line(out, "period", periodWords);
        line(out, "periodEnd", periodEndWords);
        line(out, "createdBy", createdBy);
        line(out, "createdAt", createdAt);
        line(out, "policy.unchangedSinceBefore", Boolean.toString(policy.unchangedSinceBefore()));
        for (LabelValue v : policy.atEnd()) line(out, "policy.atEnd", v.label() + "|" + v.value());
        for (ChangeRow c : policy.changes()) line(out, "policy.change", c.when() + "|" + c.who() + "|" + c.what());
        line(out, "retention.unchangedSinceBefore", Boolean.toString(retention.unchangedSinceBefore()));
        for (LabelValue v : retention.settings()) line(out, "retention.setting", v.label() + "|" + v.value());
        line(out, "retention.due", Long.toString(retention.due()));
        line(out, "retention.onTime", Long.toString(retention.onTime()));
        line(out, "retention.lateOrFailed", Long.toString(retention.lateOrFailed()));
        line(out, "retention.note", retention.note());
        for (CountRow r : cases.outcomes()) line(out, "cases.outcome", count(r));
        for (CountRow r : cases.ofDelivered()) line(out, "cases.ofDelivered", count(r));
        for (CountRow r : activity) line(out, "activity", count(r));
        for (AppendixRow r : appendix) {
            line(out, "appendix", r.time() + "|" + r.action() + "|" + r.person() + "|" + r.result() + "|"
                    + r.caseId() + "|" + r.detail());
        }
        return out.toString();
    }

    private static String count(CountRow r) {
        return r.label() + "|" + r.count() + "|" + (r.share() == null ? "" : r.share());
    }

    private static void line(StringBuilder out, String key, String value) {
        out.append(key).append('=').append(escape(value)).append('\n');
    }

    /** Backslash, newline and carriage return are escaped so every value stays on one line. */
    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r");
    }
}

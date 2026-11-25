package com.securedoc.platform.application.operations.daily;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Task 0081: whether a source system is silent for clearly longer than usual. Its receipts of the same weekday in the four
 * weeks before say what is usual; it warns only when at least three of those days have four receipts or more, each of them
 * with one before this time of day, and today's silence is over twice the longest of theirs at this time (and the usual
 * gap), and over 30 minutes.
 */
final class QuietSources {
    static final int DAYS = 3;
    static final int RECEIPTS = 4;
    private static final Duration SHORTEST = Duration.ofMinutes(30);

    private QuietSources() { }

    /** The usual minutes between two receipts, when the source is quiet now. */
    static Optional<Long> quiet(ZonedDateTime now, Collection<ZonedDateTime> today, Collection<List<ZonedDateTime>> days) {
        LocalTime time = now.toLocalTime();
        List<Duration> silences = new ArrayList<>();
        List<Duration> gaps = new ArrayList<>();
        Duration latestStart = Duration.ZERO;
        int enough = 0;
        for (List<ZonedDateTime> receipts : days) {
            List<LocalTime> day = receipts.stream().map(ZonedDateTime::toLocalTime).sorted().toList();
            if (day.size() < RECEIPTS) continue;
            enough++;
            LocalTime before = day.stream().filter(t -> !t.isAfter(time)).max(Comparator.naturalOrder()).orElse(null);
            if (before == null) return Optional.empty();
            silences.add(Duration.between(before, time));
            Duration start = Duration.between(LocalTime.MIN, day.getFirst());
            if (start.compareTo(latestStart) > 0) latestStart = start;
            for (int i = 1; i < day.size(); i++) gaps.add(Duration.between(day.get(i - 1), day.get(i)));
        }
        if (enough < DAYS) return Optional.empty();
        Collections.sort(gaps);
        Duration usual = gaps.get(gaps.size() / 2);
        Duration longest = Collections.max(silences);
        Duration limit = Collections.max(List.of(longest, usual)).multipliedBy(2);
        if (limit.compareTo(SHORTEST) < 0) limit = SHORTEST;
        Duration silent = today.stream().filter(t -> !t.isAfter(now)).max(Comparator.naturalOrder())
                .map(last -> Duration.between(last, now))
                .orElse(Duration.between(LocalTime.MIN, time).minus(latestStart));
        return silent.compareTo(limit) > 0 ? Optional.of(Math.max(1, usual.toMinutes())) : Optional.empty();
    }
}

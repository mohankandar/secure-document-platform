package com.securedoc.platform.domain.pipeline;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * Task 0079: when the cases of a Bulk source may be worked on, in the entity's time zone. From {@code from} to {@code to}
 * every night (over midnight when {@code to} is earlier), and all of Saturday and Sunday with {@code weekends}.
 */
public record BulkWindow(LocalTime from, LocalTime to, boolean weekends) {
    /** Weeknights 7 PM to 6 AM, plus weekends. */
    public static final BulkWindow DEFAULT = new BulkWindow(LocalTime.of(19, 0), LocalTime.of(6, 0), true);

    public BulkWindow {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (from.equals(to)) throw new IllegalArgumentException("The window's start and end must differ");
    }

    public boolean open(ZonedDateTime at) {
        if (weekends && (at.getDayOfWeek() == DayOfWeek.SATURDAY || at.getDayOfWeek() == DayOfWeek.SUNDAY)) return true;
        LocalTime time = at.toLocalTime();
        return from.isBefore(to) ? !time.isBefore(from) && time.isBefore(to)
                : !time.isBefore(from) || time.isBefore(to);
    }

    /** How long the window was open between the two, in steps of five minutes. */
    public Duration openTime(ZonedDateTime start, ZonedDateTime end) {
        Duration sum = Duration.ZERO;
        for (ZonedDateTime at = start; at.isBefore(end); at = at.plusMinutes(5)) {
            if (open(at)) sum = sum.plus(at.plusMinutes(5).isAfter(end) ? Duration.between(at, end) : Duration.ofMinutes(5));
        }
        return sum;
    }

    /** When that much open time has passed from {@code start}; empty after two years. */
    public Optional<ZonedDateTime> after(ZonedDateTime start, Duration needed) {
        Duration left = needed;
        ZonedDateTime at = start;
        ZonedDateTime last = start.plusYears(2);
        while (at.isBefore(last)) {
            if (open(at)) {
                left = left.minusMinutes(5);
                if (left.isNegative() || left.isZero()) return Optional.of(at.plusMinutes(5));
            }
            at = at.plusMinutes(5);
        }
        return Optional.empty();
    }
}

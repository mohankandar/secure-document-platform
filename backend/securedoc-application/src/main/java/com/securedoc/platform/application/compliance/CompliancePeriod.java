package com.securedoc.platform.application.compliance;

import com.securedoc.platform.application.exception.ValidationException;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.IsoFields;
import java.util.Locale;

/**
 * The report period (task 0017 B1), in the requested time zone. {@code from} and {@code to} are inclusive dates;
 * {@link #start()} is the start of {@code from} and {@link #endExclusive()} the start of the day after {@code to},
 * both in that zone.
 *
 * <ul>
 *   <li>{@code LAST_MONTH}, {@code LAST_QUARTER}, {@code LAST_YEAR}: the last complete calendar month, quarter or
 *       year before today in that zone.</li>
 *   <li>{@code CUSTOM}: must end before today and be 1 to 366 days long.</li>
 * </ul>
 * Anything else is refused, never guessed.
 */
public record CompliancePeriod(Type type, LocalDate from, LocalDate to, ZoneId zone) {

    public static final int MAX_CUSTOM_DAYS = 366;

    public enum Type { LAST_MONTH, LAST_QUARTER, LAST_YEAR, CUSTOM }

    public Instant start() {
        return from.atStartOfDay(zone).toInstant();
    }

    public Instant endExclusive() {
        return to.plusDays(1).atStartOfDay(zone).toInstant();
    }

    /** A validated IANA zone name; blank means UTC. Offsets ("+02:00") and short ids ("EST") are refused. */
    public static ZoneId zone(String timeZone) {
        if (timeZone == null || timeZone.isBlank()) return ZoneId.of("UTC");
        String name = timeZone.trim();
        if (!name.equals("UTC") && !name.matches("[A-Za-z]+(?:/[A-Za-z0-9_+\\-]+)+")) {
            throw new ValidationException("timeZone must be an IANA time zone name, for example America/New_York");
        }
        try {
            return ZoneId.of(name);
        } catch (DateTimeException e) {
            throw new ValidationException("timeZone must be an IANA time zone name, for example America/New_York");
        }
    }

    public static Type type(String period) {
        if (period == null || period.isBlank()) throw new ValidationException("period is required");
        try {
            return Type.valueOf(period.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("period must be LAST_MONTH, LAST_QUARTER, LAST_YEAR or CUSTOM");
        }
    }

    public static CompliancePeriod resolve(Type type, LocalDate customFrom, LocalDate customTo, ZoneId zone,
                                           Clock clock) {
        if (type == null) throw new ValidationException("period is required");
        if (zone == null) throw new ValidationException("timeZone is required");
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (type != Type.CUSTOM && (customFrom != null || customTo != null)) {
            throw new ValidationException("from and to are only accepted with period CUSTOM");
        }
        return switch (type) {
            case LAST_MONTH -> {
                LocalDate first = today.withDayOfMonth(1).minusMonths(1);
                yield new CompliancePeriod(type, first, first.plusMonths(1).minusDays(1), zone);
            }
            case LAST_QUARTER -> {
                int quarter = today.get(IsoFields.QUARTER_OF_YEAR);
                LocalDate thisQuarter = LocalDate.of(today.getYear(), (quarter - 1) * 3 + 1, 1);
                LocalDate first = thisQuarter.minusMonths(3);
                yield new CompliancePeriod(type, first, thisQuarter.minusDays(1), zone);
            }
            case LAST_YEAR -> new CompliancePeriod(type, LocalDate.of(today.getYear() - 1, 1, 1),
                    LocalDate.of(today.getYear() - 1, 12, 31), zone);
            case CUSTOM -> {
                if (customFrom == null || customTo == null) {
                    throw new ValidationException("from and to are required for period CUSTOM");
                }
                if (customTo.isBefore(customFrom)) throw new ValidationException("to must not be before from");
                if (!customTo.isBefore(today)) throw new ValidationException("A custom period must end before today");
                long days = ChronoUnit.DAYS.between(customFrom, customTo) + 1;
                if (days > MAX_CUSTOM_DAYS) {
                    throw new ValidationException("A custom period must be 1 to " + MAX_CUSTOM_DAYS + " days long");
                }
                yield new CompliancePeriod(type, customFrom, customTo, zone);
            }
        };
    }
}

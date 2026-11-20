package com.securedoc.platform.application.compliance;

import com.securedoc.platform.application.compliance.CompliancePeriod.Type;
import com.securedoc.platform.application.exception.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Task 0017 C1: the period maths, quarter edges and the time zone. */
class CompliancePeriodTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static Clock at(String instant) {
        return Clock.fixed(Instant.parse(instant), ZoneOffset.UTC);
    }

    private static CompliancePeriod resolve(Type type, String now, ZoneId zone) {
        return CompliancePeriod.resolve(type, null, null, zone, at(now));
    }

    @Test
    void lastMonthIsTheLastCompleteCalendarMonth() {
        CompliancePeriod p = resolve(Type.LAST_MONTH, "2026-11-19T12:00:00Z", UTC);
        assertEquals(LocalDate.of(2026, 10, 1), p.from());
        assertEquals(LocalDate.of(2026, 10, 31), p.to());
        CompliancePeriod march = resolve(Type.LAST_MONTH, "2024-03-01T00:00:00Z", UTC);
        assertEquals(LocalDate.of(2024, 2, 29), march.to(), "leap February");
        CompliancePeriod january = resolve(Type.LAST_MONTH, "2026-01-15T00:00:00Z", UTC);
        assertEquals(LocalDate.of(2025, 12, 1), january.from());
    }

    @Test
    void lastQuarterEdges() {
        assertEquals(LocalDate.of(2026, 7, 1), resolve(Type.LAST_QUARTER, "2026-11-19T12:00:00Z", UTC).from());
        assertEquals(LocalDate.of(2026, 9, 30), resolve(Type.LAST_QUARTER, "2026-11-19T12:00:00Z", UTC).to());
        // First day of a quarter: the quarter just ended.
        CompliancePeriod april1 = resolve(Type.LAST_QUARTER, "2026-04-01T00:00:00Z", UTC);
        assertEquals(LocalDate.of(2026, 1, 1), april1.from());
        assertEquals(LocalDate.of(2026, 3, 31), april1.to());
        // Last day of a quarter: the quarter before it, across the year.
        CompliancePeriod march31 = resolve(Type.LAST_QUARTER, "2026-03-31T23:59:59Z", UTC);
        assertEquals(LocalDate.of(2025, 10, 1), march31.from());
        assertEquals(LocalDate.of(2025, 12, 31), march31.to());
    }

    @Test
    void lastYear() {
        CompliancePeriod p = resolve(Type.LAST_YEAR, "2026-01-01T00:00:00Z", UTC);
        assertEquals(LocalDate.of(2025, 1, 1), p.from());
        assertEquals(LocalDate.of(2025, 12, 31), p.to());
    }

    @Test
    void todayIsTakenInTheRequestedTimeZone() {
        // 03:00 UTC on Jan 1 is still Dec 31 in New York.
        String now = "2026-01-01T03:00:00Z";
        assertEquals(LocalDate.of(2025, 12, 1), resolve(Type.LAST_MONTH, now, UTC).from());
        assertEquals(LocalDate.of(2025, 11, 1), resolve(Type.LAST_MONTH, now, NEW_YORK).from());
        assertEquals(LocalDate.of(2025, 7, 1), resolve(Type.LAST_QUARTER, now, NEW_YORK).from());
        assertEquals(LocalDate.of(2024, 1, 1), resolve(Type.LAST_YEAR, now, NEW_YORK).from());
    }

    @Test
    void startAndEndAreTheDayBoundariesInTheZone() {
        CompliancePeriod p = resolve(Type.LAST_QUARTER, "2026-11-19T12:00:00Z", NEW_YORK);
        assertEquals(Instant.parse("2026-07-01T04:00:00Z"), p.start(), "midnight EDT");
        assertEquals(Instant.parse("2026-10-01T04:00:00Z"), p.endExclusive(), "to is inclusive: the end is the next midnight");
        CompliancePeriod winter = resolve(Type.LAST_MONTH, "2026-02-10T12:00:00Z", NEW_YORK);
        assertEquals(Instant.parse("2026-01-01T05:00:00Z"), winter.start(), "midnight EST");
    }

    @Test
    void customPeriodRules() {
        Clock clock = at("2026-11-19T12:00:00Z");
        CompliancePeriod oneDay = CompliancePeriod.resolve(Type.CUSTOM, LocalDate.of(2026, 11, 18),
                LocalDate.of(2026, 11, 18), UTC, clock);
        assertEquals(LocalDate.of(2026, 11, 18), oneDay.to());
        CompliancePeriod longest = CompliancePeriod.resolve(Type.CUSTOM, LocalDate.of(2025, 11, 18),
                LocalDate.of(2026, 11, 18), UTC, clock);
        assertEquals(366, java.time.temporal.ChronoUnit.DAYS.between(longest.from(), longest.to()) + 1);

        assertThrows(ValidationException.class, () -> CompliancePeriod.resolve(Type.CUSTOM,
                LocalDate.of(2025, 11, 17), LocalDate.of(2026, 11, 18), UTC, clock), "367 days");
        assertThrows(ValidationException.class, () -> CompliancePeriod.resolve(Type.CUSTOM,
                LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 19), UTC, clock), "must end before today");
        assertThrows(ValidationException.class, () -> CompliancePeriod.resolve(Type.CUSTOM,
                LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 9), UTC, clock), "to before from");
        assertThrows(ValidationException.class, () -> CompliancePeriod.resolve(Type.CUSTOM,
                null, LocalDate.of(2026, 11, 9), UTC, clock));
        assertThrows(ValidationException.class, () -> CompliancePeriod.resolve(Type.LAST_MONTH,
                LocalDate.of(2026, 10, 1), null, UTC, clock), "dates only with CUSTOM");
        // "Before today" is in the zone: 02:00 UTC on Nov 20 is still Nov 19 in New York.
        Clock early = at("2026-11-20T02:00:00Z");
        assertThrows(ValidationException.class, () -> CompliancePeriod.resolve(Type.CUSTOM,
                LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 19), NEW_YORK, early));
        assertEquals(LocalDate.of(2026, 11, 19), CompliancePeriod.resolve(Type.CUSTOM,
                LocalDate.of(2026, 11, 19), LocalDate.of(2026, 11, 19), UTC, early).to());
    }

    @Test
    void timeZoneAndPeriodNamesAreValidated() {
        assertEquals(UTC, CompliancePeriod.zone(null));
        assertEquals(UTC, CompliancePeriod.zone(" "));
        assertEquals(NEW_YORK, CompliancePeriod.zone("America/New_York"));
        assertEquals(ZoneId.of("America/Argentina/Buenos_Aires"), CompliancePeriod.zone("America/Argentina/Buenos_Aires"));
        for (String bad : new String[]{"Mars/Base", "+02:00", "EST", "UTC+1", "America/New_York;drop", "../etc"}) {
            assertThrows(ValidationException.class, () -> CompliancePeriod.zone(bad), bad);
        }
        assertEquals(Type.LAST_QUARTER, CompliancePeriod.type("last_quarter"));
        assertThrows(ValidationException.class, () -> CompliancePeriod.type("LAST_WEEK"));
        assertThrows(ValidationException.class, () -> CompliancePeriod.type(null));
    }
}

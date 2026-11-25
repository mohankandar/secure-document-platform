package com.securedoc.platform.application.operations.daily;

import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Task 0081: a source system is quiet only when its own history says so. */
class QuietSourcesTest {
    private static final ZonedDateTime TUESDAY = ZonedDateTime.of(2026, 11, 24, 0, 0, 0, 0, ZoneId.of("America/New_York"));

    private static List<ZonedDateTime> day(int weeksAgo, String... times) {
        return Stream.of(times).map(t -> TUESDAY.minusWeeks(weeksAgo).with(LocalTime.parse(t))).toList();
    }

    private static List<List<ZonedDateTime>> hourly(int days) {
        List<List<ZonedDateTime>> out = new ArrayList<>();
        for (int week = 1; week <= days; week++) out.add(day(week, "08:00", "09:00", "10:00", "11:00", "12:00", "13:00", "14:00"));
        return out;
    }

    private static ZonedDateTime now(String time) {
        return TUESDAY.with(LocalTime.parse(time));
    }

    @Test
    void silentForOverTwiceItsLongestSilenceAtThisTime() {
        assertEquals(Optional.of(60L), QuietSources.quiet(now("11:30"), day(0, "08:00", "09:12"), hourly(4)));
        assertEquals(Optional.empty(), QuietSources.quiet(now("11:10"), day(0, "09:12"), hourly(4)), "not yet two hours");
    }

    @Test
    void neverWithoutEnoughOfTheSameWeekday() {
        assertEquals(Optional.empty(), QuietSources.quiet(now("13:00"), day(0, "09:12"), hourly(2)));
        assertEquals(Optional.empty(), QuietSources.quiet(now("13:00"), List.of(),
                List.of(day(1, "08:00", "09:00"), day(2, "08:00", "09:00"), day(3, "08:00"), day(4, "08:00", "09:00"))));
        assertEquals(Optional.empty(), QuietSources.quiet(now("13:00"), List.of(), List.of()));
    }

    @Test
    void aSourceThatHasNotStartedByItsUsualTimeIsQuietToo() {
        assertEquals(Optional.of(60L), QuietSources.quiet(now("11:00"), List.of(), hourly(4)));
        assertEquals(Optional.empty(), QuietSources.quiet(now("09:30"), List.of(), hourly(4)));
        assertEquals(Optional.empty(), QuietSources.quiet(now("07:00"), List.of(), hourly(4)), "it never sends this early");
    }

    @Test
    void notAfterTheHoursItSendsIn() {
        assertEquals(Optional.empty(), QuietSources.quiet(now("20:00"), day(0, "13:30"), hourly(4)));
    }
}

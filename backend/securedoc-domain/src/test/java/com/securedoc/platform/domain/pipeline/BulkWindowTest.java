package com.securedoc.platform.domain.pipeline;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0079: weeknights 7 PM to 6 AM, plus weekends. */
class BulkWindowTest {
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    @Test
    void theDefaultIsWeeknightsAndWeekends() {
        BulkWindow w = BulkWindow.DEFAULT;
        assertFalse(w.open(at("2026-11-24T18:59")), "Tuesday");
        assertTrue(w.open(at("2026-11-24T19:00")));
        assertTrue(w.open(at("2026-11-25T05:59")));
        assertFalse(w.open(at("2026-11-25T06:00")));
        assertTrue(w.open(at("2026-11-28T12:00")), "Saturday noon");
        assertTrue(w.open(at("2026-11-29T18:00")), "Sunday evening");
        assertTrue(w.open(at("2026-11-30T05:00")), "Monday before six");
        assertFalse(w.open(at("2026-11-30T09:00")));
    }

    @Test
    void aDaytimeWindowWithoutWeekends() {
        BulkWindow w = new BulkWindow(LocalTime.of(12, 0), LocalTime.of(13, 0), false);
        assertTrue(w.open(at("2026-11-24T12:30")));
        assertFalse(w.open(at("2026-11-24T13:00")));
        assertFalse(w.open(at("2026-11-28T15:00")));
        assertThrows(IllegalArgumentException.class, () -> new BulkWindow(LocalTime.NOON, LocalTime.NOON, true));
    }

    @Test
    void onlyOpenTimeCounts() {
        assertEquals(Duration.ofHours(11), BulkWindow.DEFAULT.openTime(at("2026-11-24T12:00"), at("2026-11-25T12:00")));
        assertEquals(at("2026-11-25T20:00").toInstant(), BulkWindow.DEFAULT.after(at("2026-11-24T12:00"), Duration.ofHours(12))
                .orElseThrow().toInstant(), "eleven hours of one night and one of the next");
        assertTrue(BulkWindow.DEFAULT.after(at("2026-11-24T12:00"), Duration.ofDays(900)).isEmpty());
    }

    @Test
    void theNightTheClocksChange() {
        // 2026-11-01: 25 hours in the Sunday; it is a weekend, so all of it is open.
        assertEquals(Duration.ofHours(25), BulkWindow.DEFAULT.openTime(at("2026-11-01T00:00"), at("2026-11-02T00:00")));
    }

    private static ZonedDateTime at(String local) {
        return java.time.LocalDateTime.parse(local).atZone(NEW_YORK);
    }
}

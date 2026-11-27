package com.securedoc.platform.application.operations.automation;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.AutomationReportPort;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0105: Reports, Automation. */
class AutomationReportServiceTest {

    private AutomationReportPort.Counts counts = new AutomationReportPort.Counts(197, 2, 1, 180, 20);
    // Thursday 2026-11-26, 23:30 in New York (Friday in UTC).
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-27T04:30:00Z"), ZoneOffset.UTC);
    private final AutomationReportService service = new AutomationReportService((entity, from, to) -> counts,
            entity -> Optional.of(ZoneId.of("America/New_York")), clock);

    @Test
    void thePeriodsAreTheEntitysDayWeekAndMonth() {
        TenantId e = TenantId.generate();
        var today = service.report(e, "today");
        assertEquals(Instant.parse("2026-11-26T05:00:00Z"), today.from());
        assertEquals(Instant.parse("2026-11-27T05:00:00Z"), today.to());
        assertEquals(Instant.parse("2026-11-23T05:00:00Z"), service.report(e, "WEEK").from(), "from Monday");
        assertEquals(Instant.parse("2026-11-01T04:00:00Z"), service.report(e, "MONTH").from());
    }

    @Test
    void theHeadlineIsTheAutomaticShareOfAllRedacted() {
        var r = service.report(TenantId.generate(), "TODAY");
        assertEquals(98, r.automaticPercent(), "197 of 200, rounded down");
        assertEquals(2, r.placedByPerson());
        assertEquals(1, r.drawnByPerson());
        counts = new AutomationReportPort.Counts(0, 0, 0, 0, 0);
        assertNull(service.report(TenantId.generate(), "TODAY").automaticPercent(), "nothing redacted: no share");
    }

    @Test
    void anUnknownPeriodIsRefused() {
        assertThrows(ValidationException.class, () -> service.report(TenantId.generate(), "YEAR"));
    }
}

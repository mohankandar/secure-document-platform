package com.securedoc.platform.application.operations.automation;

import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.AutomationReportPort;
import com.securedoc.platform.application.port.out.EntityTimeZonePort;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;
import java.util.Objects;

/**
 * Task 0105: Reports › Automation. For today, this week or this month (in the entity's time zone, UTC without one): the
 * share of redacted findings LexaVault found and placed with no person, and the three counts under it.
 */
public final class AutomationReportService {

    public enum Period { TODAY, WEEK, MONTH }

    /**
     * @param automaticPercent whole percent of all; null when nothing was redacted in the period
     */
    public record Report(Period period, Instant from, Instant to, Integer automaticPercent, long automatic,
                         long placedByPerson, long drawnByPerson, long byText, long byVision) { }

    private final AutomationReportPort port;
    private final EntityTimeZonePort zones;
    private final Clock clock;

    public AutomationReportService(AutomationReportPort port, EntityTimeZonePort zones, Clock clock) {
        this.port = Objects.requireNonNull(port);
        this.zones = Objects.requireNonNull(zones);
        this.clock = Objects.requireNonNull(clock);
    }

    /** @throws ValidationException LV-CFG-001 for a period that is not one of the three */
    public Report report(TenantId entity, String period) {
        Period chosen;
        try {
            chosen = Period.valueOf(period == null ? "TODAY" : period.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException(LvErrorCode.CFG_001, "period: one of TODAY, WEEK, MONTH");
        }
        ZoneId zone = zones.effective(entity).orElse(ZoneOffset.UTC);
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate first = switch (chosen) {
            case TODAY -> today;
            case WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> today.withDayOfMonth(1);
        };
        Instant from = first.atStartOfDay(zone).toInstant();
        Instant to = today.plusDays(1).atStartOfDay(zone).toInstant();
        AutomationReportPort.Counts c = port.counts(entity, from, to);
        long all = c.automatic() + c.placedByPerson() + c.drawnByPerson();
        Integer percent = all == 0 ? null : (int) Math.floor(100.0 * c.automatic() / all);
        return new Report(chosen, from, to, percent, c.automatic(), c.placedByPerson(), c.drawnByPerson(), c.byText(),
                c.byVision());
    }
}

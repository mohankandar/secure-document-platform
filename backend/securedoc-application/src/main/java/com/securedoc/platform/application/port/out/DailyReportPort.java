package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.operations.daily.DailyReport;
import com.securedoc.platform.application.operations.model.ReportScope;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Task 0081: the counts of one entity behind "Daily intake and delivery". Every query is of that entity only. */
public interface DailyReportPort {

    /** Everything that is counted straight from the records; usual ranges and quiet sources are the caller's. */
    record Counts(DailyReport.Received received, DailyReport.Exported exported, DailyReport.NotExported notExported,
                  DailyReport.Handled handled, List<DailyReport.Level> levels, List<DailyReport.Destination> destinations,
                  List<SourceCounts> sources, List<Refused> refused, Map<String, Long> rejected, List<Stopped> stopped,
                  Map<String, Long> exportFailed, long waitingForTrigger) { }

    record SourceCounts(String key, long received, long exported, long inProgress, long stopped) { }

    /** @param reason an LV code, or the name of an intake refusal of the watched folder */
    record Refused(String sourceKey, boolean duplicate, String reason, long count) { }

    /** Cases stopped now, by what their latest problem says. @param delivery it stopped in the export */
    record Stopped(String lvCode, String reason, boolean delivery, long cases) { }

    /** A receipt of one source system. */
    record Receipt(String sourceKey, Instant at) { }

    /** A connection that brings cases in, or the watched folder. */
    record KnownSource(String key, String name, boolean enabled) { }

    /** A span of time, from inclusive, to exclusive. */
    record Span(Instant from, Instant to) { }

    Counts counts(TenantId tenantId, Instant from, Instant to, ReportScope scope, Instant now);

    List<Long> receivedIn(TenantId tenantId, List<Span> spans, ReportScope scope);

    List<Long> exportedIn(TenantId tenantId, List<Span> spans, ReportScope scope);

    /** When the entity's first case came in; null before any. */
    Instant firstReceived(TenantId tenantId);

    List<Receipt> receipts(TenantId tenantId, List<Span> spans);

    Map<String, Instant> lastReceived(TenantId tenantId);

    List<KnownSource> knownSources(TenantId tenantId);
}

package com.securedoc.platform.application.operations.daily;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.intake.CasePriorityService;
import com.securedoc.platform.application.intake.watched.WatchedFolderSettingsService;
import com.securedoc.platform.application.operations.model.ReportScope;
import com.securedoc.platform.application.port.out.DailyReportPort;
import com.securedoc.platform.application.port.out.DailyReportPort.Counts;
import com.securedoc.platform.application.port.out.DailyReportPort.KnownSource;
import com.securedoc.platform.application.port.out.DailyReportPort.Receipt;
import com.securedoc.platform.application.port.out.DailyReportPort.Refused;
import com.securedoc.platform.application.port.out.DailyReportPort.SourceCounts;
import com.securedoc.platform.application.port.out.DailyReportPort.Span;
import com.securedoc.platform.application.port.out.DailyReportPort.Stopped;
import com.securedoc.platform.application.port.out.EntityTimeZonePort;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import java.util.stream.Stream;

/** Task 0081: "Daily intake and delivery" of the caller's own entity. */
public final class DailyReportService {
    private static final int WEEKS = 4;
    private static final String FOLDER = CasePriorityService.folder(WatchedFolderSettingsService.INSTALLED_FOLDER);
    private static final Duration LONGEST = Duration.ofDays(366);

    private final DailyReportPort port;
    private final EntityTimeZonePort zones;
    private final BiPredicate<TenantId, String> sourceOpen;
    private final Clock clock;
    private Predicate<TenantId> watchedFolder = tenant -> false;

    /** @param sourceOpen false for a Bulk source whose window is closed */
    public DailyReportService(DailyReportPort port, EntityTimeZonePort zones, BiPredicate<TenantId, String> sourceOpen,
                              Clock clock) {
        this.port = Objects.requireNonNull(port);
        this.zones = Objects.requireNonNull(zones);
        this.sourceOpen = Objects.requireNonNull(sourceOpen);
        this.clock = Objects.requireNonNull(clock);
    }

    /** @param folder the entity whose watched folder is read on its own */
    public DailyReportService withWatchedFolder(Predicate<TenantId> folder) {
        this.watchedFolder = Objects.requireNonNull(folder);
        return this;
    }

    private List<KnownSource> known(TenantId tenant) {
        List<KnownSource> known = new ArrayList<>(port.knownSources(tenant));
        known.add(new KnownSource(FOLDER, "Watched folder", watchedFolder.test(tenant)));
        return known;
    }

    /** The source systems the filter offers: those that can send, and those that have. */
    public List<DailyReport.SourceName> sources(AuthenticatedPrincipal principal) {
        List<KnownSource> known = known(principal.tenantId());
        Set<String> keys = new LinkedHashSet<>();
        known.stream().filter(KnownSource::enabled).map(KnownSource::key).forEach(keys::add);
        keys.addAll(port.lastReceived(principal.tenantId()).keySet());
        return keys.stream().map(key -> new DailyReport.SourceName(key, name(key, known)))
                .sorted(Comparator.comparing(DailyReport.SourceName::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    public DailyReport report(AuthenticatedPrincipal principal, Instant from, Instant to, ReportScope scope) {
        Objects.requireNonNull(principal);
        Instant now = clock.instant();
        Instant safeTo = to == null ? now : to;
        Instant safeFrom = from == null ? safeTo.minus(Duration.ofDays(1)) : from;
        if (!safeFrom.isBefore(safeTo)) throw new IllegalArgumentException("Report start must be before end");
        if (safeFrom.isBefore(safeTo.minus(LONGEST))) throw new IllegalArgumentException("Report range cannot exceed 366 days");
        TenantId tenant = principal.tenantId();
        ZoneId zone = zones.effective(tenant).orElse(ZoneId.systemDefault());
        boolean includesNow = !now.isBefore(safeFrom) && now.isBefore(safeTo);
        Counts counts = port.counts(tenant, safeFrom, safeTo, scope, now);
        List<KnownSource> known = known(tenant);
        Map<String, Instant> last = port.lastReceived(tenant);
        List<DailyReport.Quiet> quiet = includesNow ? quiet(tenant, now.atZone(zone), known, last, scope) : List.of();

        List<Span> sameWeekday = sameWeekday(tenant, safeFrom, safeTo, zone);
        DailyReport.Received r = counts.received();
        DailyReport.Exported e = counts.exported();
        return new DailyReport(now, safeFrom, safeTo, includesNow, scope.everyLevel(), quiet,
                new DailyReport.Received(r.cases(), r.documents(), r.pages(), r.urgent(),
                        usual(sameWeekday.isEmpty() ? List.of() : port.receivedIn(tenant, sameWeekday, scope))),
                new DailyReport.Exported(e.cases(), e.documents(), e.pages(), e.splitDocuments(), e.urgent(),
                        usual(sameWeekday.isEmpty() ? List.of() : port.exportedIn(tenant, sameWeekday, scope))),
                counts.notExported(), attention(counts), reasons(counts), counts.destinations(),
                sources(counts, known, last, quiet, scope), counts.handled(), counts.levels(), counts.waitingForTrigger());
    }

    /** The same weekday of the four weeks before, for a single day; only days since the entity's first case. */
    private List<Span> sameWeekday(TenantId tenant, Instant from, Instant to, ZoneId zone) {
        long hours = Duration.between(from, to).toHours();
        if (hours < 23 || hours > 25) return List.of();
        Instant first = port.firstReceived(tenant);
        if (first == null) return List.of();
        List<Span> spans = new ArrayList<>();
        for (int week = 1; week <= WEEKS; week++) {
            Span span = new Span(from.atZone(zone).minusWeeks(week).toInstant(), to.atZone(zone).minusWeeks(week).toInstant());
            if (first.isBefore(span.to())) spans.add(span);
        }
        return spans;
    }

    private static DailyReport.Usual usual(List<Long> days) {
        if (days.isEmpty()) return null;
        return new DailyReport.Usual(days.stream().mapToLong(Long::longValue).min().orElse(0),
                days.stream().mapToLong(Long::longValue).max().orElse(0));
    }

    private List<DailyReport.Quiet> quiet(TenantId tenant, ZonedDateTime now, List<KnownSource> known,
                                          Map<String, Instant> last, ReportScope scope) {
        LocalDate today = now.toLocalDate();
        List<Span> spans = new ArrayList<>();
        for (int week = 0; week <= WEEKS; week++) {
            LocalDate day = today.minusWeeks(week);
            spans.add(new Span(day.atStartOfDay(now.getZone()).toInstant(), day.plusDays(1).atStartOfDay(now.getZone()).toInstant()));
        }
        Map<String, Map<LocalDate, List<ZonedDateTime>>> receipts = new HashMap<>();
        for (Receipt receipt : port.receipts(tenant, spans)) {
            ZonedDateTime at = receipt.at().atZone(now.getZone());
            receipts.computeIfAbsent(receipt.sourceKey(), k -> new HashMap<>())
                    .computeIfAbsent(at.toLocalDate(), k -> new ArrayList<>()).add(at);
        }
        List<DailyReport.Quiet> quiet = new ArrayList<>();
        for (KnownSource source : known) {
            if (!source.enabled() || !(scope.everySource() || scope.sourceSystem().equals(source.key()))
                    || !sourceOpen.test(tenant, source.key())) continue;
            Map<LocalDate, List<ZonedDateTime>> days = receipts.getOrDefault(source.key(), Map.of());
            QuietSources.quiet(now, days.getOrDefault(today, List.of()),
                            Stream.of(1, 2, 3, 4).map(w -> days.getOrDefault(today.minusWeeks(w), List.of())).toList())
                    .ifPresent(minutes -> quiet.add(new DailyReport.Quiet(source.key(), source.name(),
                            last.get(source.key()), minutes)));
        }
        return quiet;
    }

    private static DailyReport.Attention attention(Counts counts) {
        long refused = counts.refused().stream().filter(r -> !r.duplicate()).mapToLong(Refused::count).sum();
        long stopped = counts.stopped().stream().filter(s -> !s.delivery()).mapToLong(Stopped::cases).sum();
        long failed = counts.exportFailed().values().stream().mapToLong(Long::longValue).sum()
                + counts.stopped().stream().filter(Stopped::delivery).mapToLong(Stopped::cases).sum();
        return new DailyReport.Attention(refused, stopped, failed,
                counts.rejected().values().stream().mapToLong(Long::longValue).sum());
    }

    private static List<DailyReport.Reason> reasons(Counts counts) {
        Map<DailyReport.Where, Map<String, Long>> by = new EnumMap<>(DailyReport.Where.class);
        for (DailyReport.Where where : DailyReport.Where.values()) by.put(where, new LinkedHashMap<>());
        counts.refused().stream().filter(r -> !r.duplicate()).forEach(r ->
                by.get(DailyReport.Where.REFUSED_AT_INTAKE).merge(code(r.reason(), null), r.count(), Long::sum));
        counts.stopped().forEach(s -> by.get(s.delivery() ? DailyReport.Where.EXPORT_FAILED : DailyReport.Where.STOPPED_IN_PROCESSING)
                .merge(code(s.lvCode(), s.reason()), s.cases(), Long::sum));
        counts.exportFailed().forEach((code, cases) -> by.get(DailyReport.Where.EXPORT_FAILED).merge(code(code, null), cases, Long::sum));
        counts.rejected().forEach((code, cases) -> by.get(DailyReport.Where.REJECTED).merge(code(code, null), cases, Long::sum));
        List<DailyReport.Reason> out = new ArrayList<>();
        by.forEach((where, codes) -> codes.forEach((code, cases) -> out.add(new DailyReport.Reason(code, where, cases))));
        out.sort(Comparator.comparing(DailyReport.Reason::where).thenComparing(Comparator.comparingLong(DailyReport.Reason::cases).reversed())
                .thenComparing(DailyReport.Reason::code));
        return out;
    }

    /** Only a code LexaVault knows; anything else is the fallback. */
    private static String code(String code, String reason) {
        return Stream.of(code, reason).filter(Objects::nonNull).map(v -> LvErrorCodes.forDetail(v).orElse(null))
                .filter(Objects::nonNull).findFirst().orElse(LvErrorCode.FALLBACK).code();
    }

    private List<DailyReport.Source> sources(Counts counts, List<KnownSource> known, Map<String, Instant> last,
                                             List<DailyReport.Quiet> quiet, ReportScope scope) {
        Map<String, SourceCounts> received = new HashMap<>();
        counts.sources().forEach(s -> received.put(s.key(), s));
        Set<String> keys = new LinkedHashSet<>();
        known.stream().filter(KnownSource::enabled).map(KnownSource::key).forEach(keys::add);
        keys.addAll(received.keySet());
        counts.refused().forEach(r -> keys.add(r.sourceKey()));
        Set<String> silent = new LinkedHashSet<>();
        quiet.forEach(q -> silent.add(q.sourceKey()));
        return keys.stream().filter(key -> scope.everySource() || scope.sourceSystem().equals(key)).map(key -> {
            SourceCounts c = received.getOrDefault(key, new SourceCounts(key, 0, 0, 0, 0));
            return new DailyReport.Source(key, name(key, known), last.get(key), c.received(), refused(counts, key, true),
                    refused(counts, key, false), c.exported(), c.inProgress(), c.stopped(), silent.contains(key));
        }).sorted(Comparator.comparing(DailyReport.Source::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }

    private static long refused(Counts counts, String key, boolean duplicate) {
        return counts.refused().stream().filter(r -> r.sourceKey().equals(key) && r.duplicate() == duplicate)
                .mapToLong(Refused::count).sum();
    }

    private static String name(String key, List<KnownSource> known) {
        return known.stream().filter(k -> k.key().equals(key)).map(KnownSource::name).findFirst().orElseGet(() -> {
            if (key.startsWith("folder:")) return "Watched folder";
            return switch (key) {
                case "type:MANUAL_UPLOAD" -> "Manual upload";
                case "type:API_SUBMISSION" -> "API (no connection)";
                case "type:SCANNER_INTAKE" -> "Scanner (no connection)";
                default -> "A removed connection";
            };
        });
    }
}

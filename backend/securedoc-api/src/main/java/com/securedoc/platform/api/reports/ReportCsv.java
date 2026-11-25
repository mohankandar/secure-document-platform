package com.securedoc.platform.api.reports;

import com.securedoc.platform.api.csv.CsvCells;
import com.securedoc.platform.api.platform.PipelineQueueController.Queue;
import com.securedoc.platform.application.operations.daily.DailyReport;
import com.securedoc.platform.application.operations.model.OperationalReportSnapshot;
import com.securedoc.platform.application.operations.model.ReportScope;

import java.time.Instant;
import java.util.List;

/** Task 0081: the CSV of each Reports tab. Counts, LV codes and connection names; every cell neutralised. */
final class ReportCsv {
    private final StringBuilder out = new StringBuilder("\"section\",\"item\",\"measure\",\"value\"\r\n");

    private ReportCsv() { }

    private ReportCsv row(String section, String item, String measure, Object value) {
        out.append(String.join(",", List.of(CsvCells.quoted(section), CsvCells.quoted(item), CsvCells.quoted(measure),
                CsvCells.quoted(value == null ? "" : value.toString())))).append("\r\n");
        return this;
    }

    private ReportCsv head(String report, Instant generatedAt, Instant from, Instant to, String zone, ReportScope scope, String sourceName) {
        return row("Report", report, "Generated at", generatedAt).row("Report", report, "Period from", from)
                .row("Report", report, "Period to", to).row("Report", report, "Time zone of the days", zone)
                .row("Report", report, "Source system", scope.everySource() ? "All" : sourceName)
                .row("Report", report, "Level", scope.everyLevel() ? "All" : level(scope.level()));
    }

    static String overview(OperationalReportSnapshot r, ReportScope scope, String zone, String sourceName) {
        ReportCsv csv = new ReportCsv().head("Overview", r.generatedAt(), r.period().from(), r.period().to(), zone, scope, sourceName);
        var t = r.timeSaved();
        csv.row("What LexaVault did for you", "", "Cases delivered", t.casesDelivered())
                .row("What LexaVault did for you", "", "Cases handled without a person", t.casesNoPerson())
                .row("What LexaVault did for you", "", "Pages checked", t.pagesChecked())
                .row("What LexaVault did for you", "", "Documents checked", t.documentsChecked())
                .row("What LexaVault did for you", "", "Sensitive items redacted", t.itemsRedacted())
                .row("What LexaVault did for you", "", "Review minutes saved (estimate)", t.minutesSaved());
        var e = r.executive();
        csv.row("Headline", "", "Cases received", e.batches()).row("Headline", "", "Documents", e.documents())
                .row("Headline", "", "Pages", e.pages()).row("Headline", "", "Delivered", e.delivered())
                .row("Headline", "", "Typical hours to delivery", e.medianDeliveryHours())
                .row("Headline", "", "Slowest 5% hours to delivery", e.p95DeliveryHours())
                .row("Headline", "", "No review needed %", e.straightThroughRate())
                .row("Headline", "", "Needed a person %", e.reviewRate())
                .row("Headline", "", "Open now", e.currentBacklog()).row("Headline", "", "Stopped or waiting for a person now", e.currentAttention());
        e.trend().forEach(p -> csv.row("Per day", String.valueOf(p.bucket()), "Received", p.received())
                .row("Per day", String.valueOf(p.bucket()), "Delivered", p.delivered()));
        var s = r.serviceLevel();
        csv.row("Service levels", "Delivery", "Within target %", s.deliveryWithinTargetRate())
                .row("Service levels", "Delivery", "Target %", s.deliveryTargetPercent())
                .row("Service levels", "Delivery", "Target minutes", s.deliveryTargetMinutes())
                .row("Service levels", "Delivery", "Missed", s.deliveryBreaches())
                .row("Service levels", "No review needed", "Actual %", s.straightThroughActualPercent())
                .row("Service levels", "No review needed", "Target %", s.straightThroughTargetPercent())
                .row("Service levels", "Review pickup", "Within target %", s.reviewWithinTargetRate())
                .row("Service levels", "Review pickup", "Target %", s.reviewWaitTargetPercent())
                .row("Service levels", "Review pickup", "Target minutes", s.reviewWaitTargetMinutes())
                .row("Service levels", "Review pickup", "Waited longer", s.reviewBreaches());
        return csv.out.toString();
    }

    static String operations(OperationalReportSnapshot r, Queue queue, ReportScope scope, String zone, String sourceName) {
        ReportCsv csv = new ReportCsv().head("Operations", r.generatedAt(), r.period().from(), r.period().to(), zone, scope, sourceName);
        // As the cards: the queue is the whole entity's.
        csv.row("Queue now, every source system", "", "Seconds left", queue.etaSeconds());
        queue.stages().forEach(q -> csv.row("Queue now, every source system", q.stage(), "Waiting", q.waiting())
                .row("Queue now, every source system", q.stage(), "Running", q.running())
                .row("Queue now, every source system", q.stage(), "Still to come", q.toCome())
                .row("Queue now, every source system", q.stage(), "On hold", q.held())
                .row("Queue now, every source system", q.stage(), "Seconds left", q.etaSeconds()));
        var p = r.processing();
        csv.row("Pipeline", "", "Redacting", p.processing()).row("Pipeline", "", "Needs review", p.needsReview())
                .row("Pipeline", "", "Awaiting approval", p.needsApproval()).row("Pipeline", "", "Delivered", p.completed())
                .row("Pipeline", "", "Stopped", p.technicalAttention()).row("Pipeline", "", "Rejected", p.rejected());
        var b = r.backlog();
        csv.row("Backlog age", "", "Under 1 hour", b.underOneHour()).row("Backlog age", "", "1-4 hours", b.oneToFourHours())
                .row("Backlog age", "", "4-24 hours", b.fourToTwentyFourHours()).row("Backlog age", "", "1-3 days", b.oneToThreeDays())
                .row("Backlog age", "", "Over 3 days", b.overThreeDays()).row("Backlog age", "", "Oldest hours", b.oldestHours());
        var k = r.retention();
        csv.row("Retention", "", "Originals due in 24h", k.originalsDueWithin24Hours())
                .row("Retention", "", "Redacted copies due in 24h", k.redactedCopiesDueWithin24Hours())
                .row("Retention", "", "Technical holds due in 24h", k.technicalHoldsDueWithin24Hours())
                .row("Retention", "", "Originals overdue", k.originalsOverdue())
                .row("Retention", "", "Redacted copies overdue", k.redactedCopiesOverdue())
                .row("Retention", "", "Cases purged", k.purgedPackages());
        var t = r.timing();
        var u = r.usage();
        csv.row("Timing and volume", "", "Typical turnaround hours", t.medianTurnaroundHours())
                .row("Timing and volume", "", "Slowest 5% turnaround hours", t.p95TurnaroundHours())
                .row("Timing and volume", "", "Typical active processing hours", t.medianActiveProcessingHours())
                .row("Timing and volume", "", "Slowest 5% active processing hours", t.p95ActiveProcessingHours())
                .row("Timing and volume", "", "Typical review wait hours", t.medianReviewWaitHours())
                .row("Timing and volume", "", "Typical review handling hours", t.medianReviewHandlingHours())
                .row("Timing and volume", "", "Documents per case", u.averageDocumentsPerPackage())
                .row("Timing and volume", "", "Pages per case", u.averagePagesPerPackage())
                .row("Timing and volume", "", "Reprocessed", u.reprocessedPackages());
        return csv.out.toString();
    }

    static String daily(DailyReport r, ReportScope scope, String zone, String sourceName) {
        ReportCsv csv = new ReportCsv().head("Daily intake and delivery", r.generatedAt(), r.from(), r.to(), zone, scope, sourceName);
        r.quiet().forEach(q -> csv.row("Quiet source systems", q.name(), "Last received", q.lastReceivedAt())
                .row("Quiet source systems", q.name(), "Usual minutes between cases", q.usualMinutes()));
        var in = r.received();
        csv.row("Received", "", "Cases", in.cases()).row("Received", "", "Documents", in.documents()).row("Received", "", "Pages", in.pages())
                .row("Received", "", "Urgent cases", in.urgent());
        usual(csv, "Received", in.usual());
        var ex = r.exported();
        csv.row("Exported", "", "Cases", ex.cases()).row("Exported", "", "Documents", ex.documents()).row("Exported", "", "Pages", ex.pages())
                .row("Exported", "", "Split documents", ex.splitDocuments()).row("Exported", "", "Urgent cases", ex.urgent());
        usual(csv, "Exported", ex.usual());
        var no = r.notExported();
        csv.row("Not exported yet", "", "Cases", no.cases()).row("Not exported yet", "", "Ready for export", no.ready())
                .row("Not exported yet", "", "Waiting to retry", no.waitingToRetry())
                .row("Not exported yet", "", "Destination paused", no.destinationPaused())
                .row("Not exported yet", "", "Waiting for you", no.waitingForYou()).row("Not exported yet", "", "Urgent cases", no.urgent())
                .row("Not exported yet", "", "Oldest waiting seconds", no.oldestWaitingSeconds());
        var a = r.attention();
        csv.row("Needs attention", "", "Total", a.total())
                .row("Needs attention", "", "Refused at intake", r.refusalsCounted() ? a.refused() : "not counted by level")
                .row("Needs attention", "", "Stopped in processing", a.stopped()).row("Needs attention", "", "Export failed", a.exportFailed())
                .row("Needs attention", "", "Rejected (not in the total)", a.rejected());
        r.reasons().forEach(x -> csv.row("Needs attention by reason", x.code(), where(x.where()), x.cases()));
        r.destinations().forEach(d -> {
            String kind = d.split() ? "Exported by split destination" : "Exported by destination";
            csv.row(kind, d.name(), d.split() ? "Exported documents" : "Exported cases", d.exported())
                    .row(kind, d.name(), "Documents", d.documents()).row(kind, d.name(), "Pages", d.pages())
                    .row(kind, d.name(), "Failed", d.failed()).row(kind, d.name(), "Waiting to retry", d.waitingToRetry());
        });
        r.sources().forEach(x -> csv.row("Received by source system", x.name(), "Last received", x.lastReceivedAt())
                .row("Received by source system", x.name(), "Received", x.received())
                .row("Received by source system", x.name(), "Duplicates", r.refusalsCounted() ? x.duplicates() : "not counted by level")
                .row("Received by source system", x.name(), "Refused", r.refusalsCounted() ? x.refused() : "not counted by level")
                .row("Received by source system", x.name(), "Exported", x.exported())
                .row("Received by source system", x.name(), "In progress", x.inProgress())
                .row("Received by source system", x.name(), "Stopped", x.stopped()));
        var h = r.handled();
        csv.row("How the cases were handled", "", "Finished with no person", h.noPerson())
                .row("How the cases were handled", "", "Finished after review", h.afterReview())
                .row("How the cases were handled", "", "Finished by hand after a stop", h.byHand())
                .row("How the cases were handled", "", "Still in progress", h.inProgress())
                .row("How the cases were handled", "", "Stopped", h.stopped()).row("How the cases were handled", "", "Rejected", h.rejected());
        r.levels().forEach(l -> csv.row("Documents by sensitivity level", level(l.level()), "Received", l.received())
                .row("Documents by sensitivity level", level(l.level()), "Exported", l.exported()));
        return csv.out.toString();
    }

    private static void usual(ReportCsv csv, String section, DailyReport.Usual usual) {
        if (usual == null) return;
        csv.row(section, "Same weekday, last 4 weeks", "Lowest", usual.low()).row(section, "Same weekday, last 4 weeks", "Highest", usual.high());
    }

    private static String where(DailyReport.Where where) {
        return switch (where) {
            case REFUSED_AT_INTAKE -> "Refused at intake";
            case STOPPED_IN_PROCESSING -> "Stopped in processing";
            case EXPORT_FAILED -> "Export failed";
            case REJECTED -> "Rejected";
        };
    }

    private static String level(String level) {
        return switch (level) {
            case "CONFIDENTIAL" -> "Confidential";
            case "HIGHLY_CONFIDENTIAL" -> "Highly Confidential";
            default -> "Standard";
        };
    }
}

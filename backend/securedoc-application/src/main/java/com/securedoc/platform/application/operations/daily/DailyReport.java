package com.securedoc.platform.application.operations.daily;

import java.time.Instant;
import java.util.List;

/**
 * Task 0081: "Daily intake and delivery". Counts, LV codes and the names IT gave to connections only: never a person, a file
 * name or content.
 */
public record DailyReport(Instant generatedAt, Instant from, Instant to, boolean includesNow, boolean refusalsCounted,
                          List<Quiet> quiet, Received received, Exported exported, NotExported notExported,
                          Attention attention, List<Reason> reasons, List<Destination> destinations,
                          List<Source> sources, Handled handled, List<Level> levels, long waitingForTrigger) {

    /** The lowest and highest count of the same weekday in the four weeks before; null when there is none to tell. */
    public record Usual(long low, long high) { }

    /** A source system that usually sends by now and has not. */
    public record Quiet(String sourceKey, String name, Instant lastReceivedAt, long usualMinutes) { }

    public record Received(long cases, long documents, long pages, long urgent, Usual usual) { }

    public record Exported(long cases, long documents, long pages, long splitDocuments, long urgent, Usual usual) { }

    /** Cases whose export is open now, whenever they were received. */
    public record NotExported(long cases, long ready, long waitingToRetry, long destinationPaused, long waitingForYou,
                              long urgent, Long oldestWaitingSeconds) { }

    /** Refused and rejected: of the period. Stopped and export failed: as they are now. */
    public record Attention(long refused, long stopped, long exportFailed, long rejected) {
        public long total() {
            return refused + stopped + exportFailed;
        }
    }

    public enum Where { REFUSED_AT_INTAKE, STOPPED_IN_PROCESSING, EXPORT_FAILED, REJECTED }

    public record Reason(String code, Where where, long cases) { }

    /** A split destination counts documents, another one cases. */
    public record Destination(String key, String name, boolean split, boolean paused, long exported, long documents,
                              long pages, long failed, long waitingToRetry) { }

    public record Source(String key, String name, Instant lastReceivedAt, long received, long duplicates, long refused,
                         long exported, long inProgress, long stopped, boolean quiet) { }

    public record Handled(long noPerson, long afterReview, long byHand, long inProgress, long stopped, long rejected) { }

    /** Documents. */
    public record Level(String level, long received, long exported) { }

    /** A source system as the filter offers it. */
    public record SourceName(String key, String name) { }
}

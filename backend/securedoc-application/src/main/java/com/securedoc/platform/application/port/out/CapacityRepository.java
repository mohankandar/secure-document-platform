package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.pipeline.PipelineStage;

import java.util.Map;
import java.util.OptionalInt;

/** Task 0067: what is kept about capacity (for the whole installation), and how much work waits. */
public interface CapacityRepository {

    /** The requests at once learned for this AI server. */
    OptionalInt learnedLimit(String server);

    void saveLearnedLimit(String server, int limit);

    /** IT's own maximums by name (a stage's name, or AI). */
    Map<String, Integer> maximums();

    /** Null removes the maximum. */
    void setMaximum(String name, Integer maximum);

    /** Task 0079: due BULK jobs per stage of these sources, counted up to {@code most} each. */
    Map<PipelineStage, Integer> dueBulkJobs(int most, java.util.Set<String> openBulkSources);

    /** Due jobs per stage but BULK ones, counted up to {@code most} each (more makes no difference). A stage without any is absent. */
    Map<PipelineStage, Integer> dueJobs(int most);

    /** Cases submitted and not started yet, counted up to {@code most}. */
    int waitingCases(int most);

    /** The bytes of the database; PostgreSQL does not tell how much its disk still has. */
    long databaseBytes();
}

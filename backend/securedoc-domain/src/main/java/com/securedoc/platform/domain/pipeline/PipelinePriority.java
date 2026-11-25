package com.securedoc.platform.domain.pipeline;

/** Job priority (design section 7). Task 0073: a case is URGENT, NORMAL or LOW; BULK (task 0079) is for the cases of a Bulk source. */
public enum PipelinePriority {
    URGENT(1),
    NORMAL(2),
    LOW(3),
    BULK(4);

    private final int rank;

    PipelinePriority(int rank) { this.rank = rank; }

    /** Lower runs first. */
    public int rank() { return rank; }
}

package com.securedoc.platform.domain.pipeline;

/** Job priority (design section 7). Stored from task 0027; NORMAL for every job until P4. */
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

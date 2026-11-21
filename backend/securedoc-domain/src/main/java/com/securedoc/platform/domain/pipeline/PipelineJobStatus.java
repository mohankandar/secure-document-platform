package com.securedoc.platform.domain.pipeline;

/** A pipeline job's status (design section 10). */
public enum PipelineJobStatus {
    READY,
    RUNNING,
    WAITING_RETRY,
    /** Reserved: not set by task 0027. A paused destination's jobs stay READY or WAITING_RETRY. */
    PAUSED,
    WAITING_FOR_YOU,
    NEEDS_IT,
    DONE,
    STOPPED;

    /** Still to be done: anything but DONE and STOPPED. */
    public boolean open() {
        return this != DONE && this != STOPPED;
    }
}

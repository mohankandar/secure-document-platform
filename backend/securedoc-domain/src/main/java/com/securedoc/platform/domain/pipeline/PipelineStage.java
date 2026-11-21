package com.securedoc.platform.domain.pipeline;

/**
 * The stages of the staged pipeline (design: docs/work/staged-pipeline-design.md, section 3). Task 0027 (P1) uses only
 * {@link #EXPORT} and {@link #SPLIT_EXPORT}; the others are for P2.
 */
public enum PipelineStage {
    TEXT,
    FIND,
    REDACT,
    /** The full case to one destination. */
    EXPORT,
    /** One split (derived) document to one destination. */
    SPLIT_EXPORT;

    public boolean isExport() {
        return this == EXPORT || this == SPLIT_EXPORT;
    }
}

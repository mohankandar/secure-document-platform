package com.securedoc.platform.domain.artifact;

/**
 * Lifecycle status of a processing artifact.
 */
public enum ArtifactStatus {

    /** Artifact is being generated. */
    GENERATING,

    /** Artifact is stored and available. */
    AVAILABLE,

    /** Artifact generation failed. */
    FAILED,

    /** Artifact has been deleted/purged. */
    DELETED
}

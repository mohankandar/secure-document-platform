package com.securedoc.platform.domain.storage;

/** Lifecycle of a processing-storage definition. Activation is represented separately by an assignment. */
public enum ProcessingStorageProviderStatus {
    CANDIDATE,
    AVAILABLE,
    RETIRED
}

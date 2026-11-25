package com.securedoc.platform.application.export.service;

import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;

import java.io.InputStream;
import java.util.Objects;

/**
 * Task 0060: the stored copies of one delivery. A copy that can't be opened (storage, key service) is remembered, so a
 * delivery adapter that reports it as its own failure never blames the destination for it.
 */
public final class DeliverySource {
    private final StoragePort storage;
    private volatile TechnicalDependencyException failure;

    public DeliverySource(StoragePort storage) {
        this.storage = Objects.requireNonNull(storage);
    }

    public InputStream open(ArtifactStorageReference reference) {
        try {
            return storage.retrieve(reference);
        } catch (TechnicalDependencyException notOpened) {
            failure = notOpened;
            throw notOpened;
        }
    }

    /** The failure to open the stored copy when there was one, else the delivery's own. */
    public TechnicalDependencyException failureOr(TechnicalDependencyException reported) {
        TechnicalDependencyException own = failure;
        return own != null ? own : reported;
    }
}

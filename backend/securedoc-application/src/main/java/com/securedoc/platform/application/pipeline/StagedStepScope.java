package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.domain.artifact.ArtifactStorageReference;

import java.util.List;
import java.util.function.Supplier;

/**
 * Task 0048: the files a staged step stores while it runs. A step's database writes are rolled back when its claim is
 * no longer current; the files it stored are then deleted too, so no file is left that no record points to. Only the
 * staged workers open a scope; everywhere else {@link #recordStored} does nothing.
 */
public final class StagedStepScope {
    private static final ThreadLocal<List<ArtifactStorageReference>> STORED = new ThreadLocal<>();

    private StagedStepScope() {
    }

    /** Runs the work with {@code stored} collecting every file stored on this thread meanwhile. */
    public static <T> T tracking(List<ArtifactStorageReference> stored, Supplier<T> work) {
        List<ArtifactStorageReference> previous = STORED.get();
        STORED.set(stored);
        try {
            return work.get();
        } finally {
            if (previous == null) STORED.remove(); else STORED.set(previous);
        }
    }

    /** Called by the storage adapter after a file is stored. */
    public static void recordStored(ArtifactStorageReference reference) {
        List<ArtifactStorageReference> stored = STORED.get();
        if (stored != null && reference != null) stored.add(reference);
    }
}

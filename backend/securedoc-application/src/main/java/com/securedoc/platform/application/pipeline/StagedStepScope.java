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

    private static final ThreadLocal<List<ArtifactStorageReference>> DISCARDED = new ThreadLocal<>();

    /** Task 0049: as {@link #tracking}, also collecting the files to delete once the step's result is saved. */
    public static <T> T tracking(List<ArtifactStorageReference> stored, List<ArtifactStorageReference> discarded,
                                 Supplier<T> work) {
        List<ArtifactStorageReference> previous = DISCARDED.get();
        DISCARDED.set(discarded);
        try {
            return tracking(stored, work);
        } finally {
            if (previous == null) DISCARDED.remove(); else DISCARDED.set(previous);
        }
    }

    /**
     * Task 0049: a file whose record this step marks deleted. The worker deletes the file only after the step is
     * saved, so a step that is not saved leaves it in place. Outside a staged step nothing happens.
     */
    public static void deleteWhenSaved(ArtifactStorageReference reference) {
        List<ArtifactStorageReference> discarded = DISCARDED.get();
        if (discarded != null && reference != null) discarded.add(reference);
    }

    /** Called by the storage adapter after a file is stored. */
    public static void recordStored(ArtifactStorageReference reference) {
        List<ArtifactStorageReference> stored = STORED.get();
        if (stored != null && reference != null) stored.add(reference);
    }
}

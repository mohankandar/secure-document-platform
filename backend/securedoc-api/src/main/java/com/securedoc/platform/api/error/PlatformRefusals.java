package com.securedoc.platform.api.error;

import com.documentplatform.errors.HasPlatformCode;
import com.documentplatform.errors.PlatformCode;

import java.util.Optional;

/** Task 0057: the shared platform's own code of a failure, if it carries one. Never its message. */
public final class PlatformRefusals {

    private static final int MAX_DEPTH = 16;

    private PlatformRefusals() {
    }

    /** The first platform code in the cause chain. */
    public static Optional<PlatformCode> codeOf(Throwable failure) {
        int depth = 0;
        for (Throwable t = failure; t != null && depth < MAX_DEPTH; t = t.getCause(), depth++) {
            if (t instanceof HasPlatformCode coded && coded.platformCode() != null) return Optional.of(coded.platformCode());
            if (t.getCause() == t) break;
        }
        return Optional.empty();
    }
}

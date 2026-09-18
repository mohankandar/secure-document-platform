package com.securedoc.platform.application.port.out;

import java.io.InputStream;

/**
 * Replayable, bounded-memory content produced by infrastructure adapters.
 * Implementations may use temporary disk storage; callers must close the content
 * when all reads are complete so temporary resources can be released.
 */
public interface ReplayableContent extends AutoCloseable {

    /** Opens a fresh stream positioned at the beginning of the content. */
    InputStream openStream();

    /** Returns the exact content length in bytes. */
    long contentLength();

    /** Releases any temporary backing resources. */
    @Override
    void close();
}

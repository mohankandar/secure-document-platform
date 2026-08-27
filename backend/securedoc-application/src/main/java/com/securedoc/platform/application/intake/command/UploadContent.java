package com.securedoc.platform.application.intake.command;

import java.io.InputStream;

/**
 * Framework-neutral abstraction for upload content that supports multiple reads.
 * Each call to openStream() returns a fresh InputStream, allowing
 * checksum computation and storage to use separate streams.
 */
public interface UploadContent {

    /**
     * Opens a fresh InputStream for reading the content.
     * Each invocation must return a new stream positioned at the beginning.
     */
    InputStream openStream();

    /**
     * Returns the content length in bytes.
     */
    long contentLength();
}

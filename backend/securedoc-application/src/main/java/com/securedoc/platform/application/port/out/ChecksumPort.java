package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.vo.Checksum;

import java.io.InputStream;

/**
 * Outbound port for computing content checksums.
 */
public interface ChecksumPort {

    /**
     * Computes a checksum for the given content stream.
     * The stream will be fully consumed; caller must not reuse it.
     */
    Checksum compute(InputStream content);
}

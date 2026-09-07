package com.securedoc.platform.application.port.out;

import java.util.Map;

/** Supplies the current privacy-safe processing/intelligence version fingerprint. */
@FunctionalInterface
public interface ProcessingFingerprintProvider {
    Map<String, String> currentFingerprint();

    static ProcessingFingerprintProvider empty() {
        return Map::of;
    }
}

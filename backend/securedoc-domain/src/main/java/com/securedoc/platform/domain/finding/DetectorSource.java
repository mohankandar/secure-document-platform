package com.securedoc.platform.domain.finding;

import java.util.Objects;

/**
 * Identifies what detector produced a finding.
 * Captures both the detector type and version for traceability.
 */
public record DetectorSource(String detectorName, String version) {

    public DetectorSource {
        Objects.requireNonNull(detectorName, "Detector name must not be null");
        if (detectorName.isBlank()) {
            throw new IllegalArgumentException("Detector name must not be blank");
        }
    }

    public static DetectorSource of(String name, String version) {
        return new DetectorSource(name, version);
    }

    public static DetectorSource of(String name) {
        return new DetectorSource(name, null);
    }

    @Override
    public String toString() {
        if (version == null) {
            return detectorName;
        }
        return detectorName + ":" + version;
    }
}

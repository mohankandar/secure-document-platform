package com.securedoc.platform.domain.tenant;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Defines which capabilities are enabled for a tenant and their status.
 * Immutable — modifications return a new instance.
 */
public final class TenantCapabilityProfile {

    private final Map<Capability, CapabilityStatus> capabilities;

    private TenantCapabilityProfile(Map<Capability, CapabilityStatus> capabilities) {
        this.capabilities = new EnumMap<>(capabilities);
    }

    /**
     * Creates a profile with no capabilities enabled.
     */
    public static TenantCapabilityProfile empty() {
        return new TenantCapabilityProfile(new EnumMap<>(Capability.class));
    }

    /**
     * Creates a profile from a given map.
     */
    public static TenantCapabilityProfile of(Map<Capability, CapabilityStatus> capabilities) {
        Objects.requireNonNull(capabilities, "Capabilities map must not be null");
        return new TenantCapabilityProfile(capabilities);
    }

    /**
     * Creates a default profile with core capabilities enabled.
     */
    public static TenantCapabilityProfile defaultProfile() {
        EnumMap<Capability, CapabilityStatus> defaults = new EnumMap<>(Capability.class);
        defaults.put(Capability.DOCUMENT_UPLOAD, CapabilityStatus.ENABLED);
        defaults.put(Capability.OCR_PROCESSING, CapabilityStatus.ENABLED);
        defaults.put(Capability.SENSITIVE_DATA_DETECTION, CapabilityStatus.ENABLED);
        defaults.put(Capability.REDACTION, CapabilityStatus.ENABLED);
        defaults.put(Capability.HUMAN_REVIEW, CapabilityStatus.ENABLED);
        defaults.put(Capability.VAULT, CapabilityStatus.ENABLED);
        defaults.put(Capability.AUDIT, CapabilityStatus.ENABLED);
        return new TenantCapabilityProfile(defaults);
    }

    public boolean isEnabled(Capability capability) {
        CapabilityStatus status = capabilities.get(capability);
        return status == CapabilityStatus.ENABLED || status == CapabilityStatus.TRIAL;
    }

    public CapabilityStatus statusOf(Capability capability) {
        return capabilities.getOrDefault(capability, CapabilityStatus.DISABLED);
    }

    public Set<Capability> enabledCapabilities() {
        return capabilities.entrySet().stream()
                .filter(e -> e.getValue() == CapabilityStatus.ENABLED || e.getValue() == CapabilityStatus.TRIAL)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Returns a new profile with the given capability set to the given status.
     */
    public TenantCapabilityProfile withCapability(Capability capability, CapabilityStatus status) {
        Objects.requireNonNull(capability, "Capability must not be null");
        Objects.requireNonNull(status, "Status must not be null");
        EnumMap<Capability, CapabilityStatus> updated = new EnumMap<>(capabilities);
        updated.put(capability, status);
        return new TenantCapabilityProfile(updated);
    }

    public Map<Capability, CapabilityStatus> asMap() {
        return Collections.unmodifiableMap(capabilities);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TenantCapabilityProfile that = (TenantCapabilityProfile) o;
        return capabilities.equals(that.capabilities);
    }

    @Override
    public int hashCode() {
        return capabilities.hashCode();
    }

    @Override
    public String toString() {
        return "TenantCapabilityProfile{" + capabilities + "}";
    }
}

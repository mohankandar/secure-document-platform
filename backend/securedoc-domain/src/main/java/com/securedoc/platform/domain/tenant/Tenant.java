package com.securedoc.platform.domain.tenant;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.Objects;

/**
 * Aggregate root representing a tenant in the platform.
 * Owns capability configuration and lifecycle state.
 */
public final class Tenant {

    private final TenantId id;
    private final String name;
    private final String slug;
    private final Instant createdAt;
    private TenantStatus status;
    private TenantCapabilityProfile capabilityProfile;
    private Instant updatedAt;

    private Tenant(TenantId id, String name, String slug, Instant createdAt,
                   TenantCapabilityProfile capabilityProfile) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.createdAt = createdAt;
        this.status = TenantStatus.PROVISIONING;
        this.capabilityProfile = capabilityProfile;
        this.updatedAt = createdAt;
    }

    public static Tenant create(String name, String slug) {
        Objects.requireNonNull(name, "Tenant name must not be null");
        Objects.requireNonNull(slug, "Tenant slug must not be null");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Tenant name must not be blank");
        }
        if (slug.isBlank()) {
            throw new IllegalArgumentException("Tenant slug must not be blank");
        }
        if (!slug.matches("^[a-z0-9\\-]+$")) {
            throw new IllegalArgumentException(
                    "Tenant slug must contain only lowercase alphanumeric and hyphens: " + slug);
        }

        return new Tenant(TenantId.generate(), name, slug, Instant.now(),
                TenantCapabilityProfile.defaultProfile());
    }

    public static Tenant reconstitute(TenantId id, String name, String slug, Instant createdAt,
                                       TenantStatus status, TenantCapabilityProfile capabilityProfile,
                                       Instant updatedAt) {
        Tenant tenant = new Tenant(id, name, slug, createdAt, capabilityProfile);
        tenant.status = status;
        tenant.updatedAt = updatedAt;
        return tenant;
    }

    public void activate() {
        if (status != TenantStatus.PROVISIONING && status != TenantStatus.SUSPENDED) {
            throw new IllegalStateException("Cannot activate tenant in status: " + status);
        }
        status = TenantStatus.ACTIVE;
        updatedAt = Instant.now();
    }

    public void suspend() {
        if (status != TenantStatus.ACTIVE) {
            throw new IllegalStateException("Cannot suspend tenant in status: " + status);
        }
        status = TenantStatus.SUSPENDED;
        updatedAt = Instant.now();
    }

    public void deactivate() {
        if (status == TenantStatus.DEACTIVATED) {
            throw new IllegalStateException("Tenant is already deactivated");
        }
        status = TenantStatus.DEACTIVATED;
        updatedAt = Instant.now();
    }

    public void updateCapabilityProfile(TenantCapabilityProfile profile) {
        Objects.requireNonNull(profile, "Capability profile must not be null");
        this.capabilityProfile = profile;
        this.updatedAt = Instant.now();
    }

    public boolean isCapabilityEnabled(Capability capability) {
        return capabilityProfile.isEnabled(capability);
    }

    // --- Accessors ---

    public TenantId id() {
        return id;
    }

    public String name() {
        return name;
    }

    public String slug() {
        return slug;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public TenantStatus status() {
        return status;
    }

    public TenantCapabilityProfile capabilityProfile() {
        return capabilityProfile;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean isActive() {
        return status == TenantStatus.ACTIVE;
    }
}

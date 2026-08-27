package com.securedoc.platform.infrastructure.persistence.mapper;

import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.tenant.Capability;
import com.securedoc.platform.domain.tenant.CapabilityStatus;
import com.securedoc.platform.domain.tenant.Tenant;
import com.securedoc.platform.domain.tenant.TenantCapabilityProfile;
import com.securedoc.platform.domain.tenant.TenantStatus;
import com.securedoc.platform.infrastructure.persistence.entity.TenantCapabilityEntity;
import com.securedoc.platform.infrastructure.persistence.entity.TenantEntity;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TenantMapper {

    private TenantMapper() {
    }

    /**
     * Converts a TenantEntity and its separately-loaded capabilities to a domain Tenant.
     */
    public static Tenant toDomain(TenantEntity entity, List<TenantCapabilityEntity> capabilities) {
        Map<Capability, CapabilityStatus> capMap = new EnumMap<>(Capability.class);
        for (TenantCapabilityEntity cap : capabilities) {
            capMap.put(Capability.valueOf(cap.getCapability()), CapabilityStatus.valueOf(cap.getStatus()));
        }
        TenantCapabilityProfile profile = TenantCapabilityProfile.of(capMap);

        return Tenant.reconstitute(
                TenantId.of(entity.getId()),
                entity.getName(),
                entity.getSlug(),
                entity.getCreatedAt(),
                TenantStatus.valueOf(entity.getStatus()),
                profile,
                entity.getUpdatedAt()
        );
    }

    public static TenantEntity toEntity(Tenant tenant) {
        TenantEntity entity = new TenantEntity();
        entity.setId(tenant.id().value());
        entity.setName(tenant.name());
        entity.setSlug(tenant.slug());
        entity.setStatus(tenant.status().name());
        entity.setCreatedAt(tenant.createdAt());
        entity.setUpdatedAt(tenant.updatedAt());
        return entity;
    }

    public static List<TenantCapabilityEntity> toCapabilityEntities(Tenant tenant) {
        return tenant.capabilityProfile().asMap().entrySet().stream()
                .map(e -> {
                    TenantCapabilityEntity cap = new TenantCapabilityEntity();
                    cap.setId(UUID.randomUUID());
                    cap.setTenantId(tenant.id().value());
                    cap.setCapability(e.getKey().name());
                    cap.setStatus(e.getValue().name());
                    cap.setCreatedAt(Instant.now());
                    cap.setUpdatedAt(Instant.now());
                    return cap;
                })
                .toList();
    }
}

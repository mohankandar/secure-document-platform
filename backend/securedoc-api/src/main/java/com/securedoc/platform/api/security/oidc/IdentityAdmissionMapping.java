package com.securedoc.platform.api.security.oidc;

import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.admission.IdentityAdmissionException;
import com.securedoc.platform.api.context.SecurityContextException;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The seam between Enterprise Identity 0.3.0 entity admission and LexaVault: id conversion, and every refusal
 * mapped to LexaVault's {@link SecurityContextException} (HTTP 403) with the library's message unchanged, so the
 * product keeps today's messages and status for every reason.
 */
public final class IdentityAdmissionMapping {

    private IdentityAdmissionMapping() {
    }

    public static EntityId toEntity(TenantId tenant) {
        return tenant == null ? null : EntityId.of(tenant.value());
    }

    public static TenantId toTenant(EntityId entity) {
        return entity == null ? null : TenantId.of(entity.value());
    }

    public static Set<EntityId> toEntities(Set<TenantId> tenants) {
        if (tenants == null) return null;
        Set<EntityId> out = new LinkedHashSet<>();
        for (TenantId tenant : tenants) out.add(toEntity(tenant));
        return out;
    }

    public static <V> Map<EntityId, V> toEntityKeys(Map<TenantId, V> byTenant) {
        if (byTenant == null) return null;
        Map<EntityId, V> out = new LinkedHashMap<>();
        byTenant.forEach((tenant, value) -> out.put(toEntity(tenant), value));
        return out;
    }

    public static <V> Map<TenantId, V> toTenantKeys(Map<EntityId, V> byEntity) {
        if (byEntity == null) return null;
        Map<TenantId, V> out = new LinkedHashMap<>();
        byEntity.forEach((entity, value) -> out.put(toTenant(entity), value));
        return out;
    }

    /** Runs a library call and maps any admission refusal to LexaVault's 403 refusal with the same message. */
    public static <T> T refusingAsSecurityContext(Supplier<T> call) {
        try {
            return call.get();
        } catch (IdentityAdmissionException refused) {
            throw new SecurityContextException(refused.getMessage(), refused.reason().name());
        }
    }

    public static void refusingAsSecurityContext(Runnable call) {
        refusingAsSecurityContext(() -> {
            call.run();
            return null;
        });
    }
}

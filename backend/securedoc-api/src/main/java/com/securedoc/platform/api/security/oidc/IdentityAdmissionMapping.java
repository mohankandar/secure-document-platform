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
 * The seam between platform-identity entity admission and LexaVault: id conversion, and every refusal
 * mapped to LexaVault's {@link SecurityContextException} (HTTP 403) in LexaVault's own words (see {@link #message}), so the
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

    /** Runs a library call and maps any admission refusal to LexaVault's 403 refusal, in LexaVault's own words. */
    public static <T> T refusingAsSecurityContext(Supplier<T> call) {
        try {
            return call.get();
        } catch (IdentityAdmissionException refused) {
            throw new SecurityContextException(message(refused.reason()), refused.reason().name());
        }
    }

    /**
     * LexaVault's words for each library reason (task 0037 step 0, A3 redone): platform-identity owns the reasons and
     * its messages are neutral; LexaVault owns the words. These are the texts LexaVault showed with Enterprise
     * Identity 0.3.0, unchanged, so its 403 messages and their LV codes (keyed on the reason) do not change.
     */
    public static String message(IdentityAdmissionException.Reason reason) {
        return switch (reason) {
            case NOT_ENABLED -> "Shared-issuer tenant selection is not enabled";
            case NO_VERIFIED_MEMBERSHIP -> "No verified ACTIVE tenant membership is available";
            case MEMBERSHIP_NOT_BACKED -> "Tenant membership is not backed by ACTIVE identity connections";
            case AMBIGUOUS_SELECTION -> "A unique authorized tenant context is required";
            case NOT_AUTHORIZED_FOR_ENTITY -> "Authenticated identity is not authorized for the requested tenant";
            case INVALID_SELECTION -> "Invalid selected tenant identifier";
            case NO_ROLE_FOR_ENTITY -> "Authenticated identity has no tenant-authorized role";
            case UNKNOWN_ROLE -> "Invalid tenant role configuration";
            case INVALID_ROLE_GRANT -> "Invalid tenant-scoped role grant";
            case PLATFORM_ROLE_IN_ENTITY_GRANT -> "Platform Operations cannot be granted through tenant IdP groups";
            case GROUPS_NOT_A_COLLECTION -> "Trusted IdP group collection is required";
            case NO_ADMISSION_POLICY -> "Tenant has no trusted group admission policy";
            case CONTEXT_UNAVAILABLE -> "Verified OIDC tenant membership context is unavailable";
            case POLICY_UNAVAILABLE -> "OIDC tenant admission policy or ACTIVE tenant bindings are unavailable";
            case MACHINE_BINDING_NOT_UNIQUE -> "Machine identity has no unique ACTIVE tenant integration binding";
        };
    }

    public static void refusingAsSecurityContext(Runnable call) {
        refusingAsSecurityContext(() -> {
            call.run();
            return null;
        });
    }
}

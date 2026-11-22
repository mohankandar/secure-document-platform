package com.securedoc.platform.api.security.oidc;

import com.documentplatform.identity.admission.EntityRoleCatalog;
import com.securedoc.platform.api.security.ProductRole;

import java.util.Optional;

/**
 * LexaVault's roles for platform-identity entity admission: role names are canonicalised by
 * {@link ProductRole#fromIdentityName}, and PLATFORM_OPERATIONS_ADMIN is the platform role, which an entity's groups
 * can never grant. (The same rules the R1B TenantGroupAuthorization and SharedIssuerActivationPreflight used.)
 */
public final class ProductEntityRoleCatalog implements EntityRoleCatalog {

    @Override
    public Optional<String> canonicalEntityRole(String configuredName) {
        return ProductRole.fromIdentityName(configuredName).map(Enum::name);
    }

    @Override
    public boolean isPlatformRole(String canonicalRole) {
        return ProductRole.PLATFORM_OPERATIONS_ADMIN.name().equals(canonicalRole);
    }
}

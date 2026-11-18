package com.securedoc.platform.infrastructure.identity;

import com.documentplatform.identity.admission.EntityId;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.mapping.MappingTargets;

import java.util.Optional;
import java.util.Set;

/**
 * What the role mapping loader reads and writes: tenants, their ACTIVE login-system record and their group grants
 * ({@code tenant_oidc_group_grants}). Callers run inside one transaction.
 */
public interface RoleMappingStore extends MappingTargets {

    /** Locks the tenant row until the transaction ends, so two loads of the same tenant cannot interleave. */
    void lock(EntityId tenant);

    /** The issuer of the tenant's ACTIVE login-system record, or empty when it has none. */
    Optional<String> activeIssuer(EntityId tenant);

    /** The tenant's grants for that issuer, exactly as stored. */
    Set<Grant> grants(EntityId tenant, String issuer);

    void add(EntityId tenant, String issuer, Grant grant);

    void remove(EntityId tenant, String issuer, Grant grant);
}

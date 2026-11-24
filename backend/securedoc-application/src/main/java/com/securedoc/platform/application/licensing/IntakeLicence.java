package com.securedoc.platform.application.licensing;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;

import java.util.Optional;

/**
 * Task 0057: whether new work may be taken in, on the word of the stored licence. Asked where new work starts (a case is
 * made, a document is added, a case is submitted, a folder or a source is read, a completed case is processed again),
 * never where work already taken in is processed, reviewed or exported: that is finished whatever the licence says.
 *
 * <p>A licence that cannot be read counts as "no" (fail closed). The old name {@code TenantLicensingService} and its own
 * signed tokens are gone.
 */
public interface IntakeLicence {

    /** Why new work is refused for the entity now; empty when it is allowed. Nothing is recorded. */
    Optional<LicenseEnforcementException> refusal(TenantId tenantId);

    /** Refuses new work, with an audit record that survives the refused request's rollback. */
    void requireIntake(TenantId tenantId, ActorId actorId, CorrelationId correlationId);

    /** The customer the licence in force is for (the compliance report's customer name); empty when it can't be read. */
    Optional<String> customerName();
}

package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.ZoneId;
import java.util.Optional;

/**
 * Task 0058: the time zone that applies to an entity (its own, else its customer's), for daily windows, reports and the
 * times people see. Empty when neither is set: everything then works as it did before entities had a zone.
 */
@FunctionalInterface
public interface EntityTimeZonePort {

    Optional<ZoneId> effective(TenantId entity);

    EntityTimeZonePort NONE = entity -> Optional.empty();
}

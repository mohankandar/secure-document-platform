package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.identity.TenantId;

import java.time.Duration;
import java.util.Map;

/** Task 0097: removes what a killed server left of a delivery to a folder. Only there when deliveries go to a folder. */
public interface DeliveryLeftoverPort {

    /**
     * Removes, through the erase log, at most {@code limit} unfinished deliveries in which nothing was written for
     * {@code quiet} and whose case is not being exported. Anything not certain is kept.
     *
     * @return how many, per entity
     */
    Map<TenantId, Integer> removeLeftovers(Duration quiet, int limit);
}

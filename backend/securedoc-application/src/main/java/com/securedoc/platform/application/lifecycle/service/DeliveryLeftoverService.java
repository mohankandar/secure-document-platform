package com.securedoc.platform.application.lifecycle.service;

import com.securedoc.platform.application.port.out.DeliveryLeftoverPort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Task 0097: a server killed while it writes a delivery to a folder leaves redacted copies in the export location. This
 * clean-up, part of the lifecycle maintenance (so also at the start), removes them. An entity with any removed gets one
 * audit record per run, with the count only.
 */
public class DeliveryLeftoverService {
    /** On top of the claim time, for clocks that differ between this server, the database and the export location. */
    public static final Duration MARGIN = Duration.ofMinutes(10);
    static final int MAX_PER_RUN = 1000;

    private final Optional<DeliveryLeftoverPort> leftovers;
    private final AuditService audit;
    private final Duration quiet;

    /**
     * @param leftovers empty when deliveries do not go to a folder: nothing is done
     * @param claimTime the longest time a claim on a case being exported lasts without renewal
     */
    public DeliveryLeftoverService(Optional<DeliveryLeftoverPort> leftovers, AuditService audit, Duration claimTime) {
        this.leftovers = Objects.requireNonNull(leftovers);
        this.audit = Objects.requireNonNull(audit);
        this.quiet = claimTime.plus(MARGIN);
    }

    /** The number of unfinished deliveries removed. */
    public int run(ActorId actorId, CorrelationId correlationId) {
        if (leftovers.isEmpty()) return 0;
        int total = 0;
        for (var entry : leftovers.get().removeLeftovers(quiet, MAX_PER_RUN).entrySet()) {
            if (entry.getValue() == 0) continue;
            total += entry.getValue();
            audit.recordSuccess(entry.getKey(), actorId, AuditAction.DELIVERY_LEFTOVERS_CLEANED, "Tenant",
                    entry.getKey().toString(), correlationId, "Folder delivery leftovers removed: deliveries=" + entry.getValue());
        }
        return total;
    }
}

package com.securedoc.platform.domain.event;

import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;

/**
 * Marker interface for all domain events.
 * Domain events represent something meaningful that happened in the domain.
 */
public interface DomainEvent {

    /**
     * When the event occurred.
     */
    Instant occurredAt();

    /**
     * The tenant context for this event.
     */
    TenantId tenantId();

    /**
     * Correlation ID for tracing related events.
     */
    CorrelationId correlationId();

    /**
     * Human-readable event type name.
     */
    String eventType();
}

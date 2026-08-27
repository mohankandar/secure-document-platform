package com.securedoc.platform.application.port.out;

import com.securedoc.platform.domain.event.DomainEvent;

/**
 * Outbound port for publishing domain events.
 * Implementation may be in-process, Kafka, outbox, etc.
 */
public interface DomainEventPublisher {

    void publish(DomainEvent event);
}

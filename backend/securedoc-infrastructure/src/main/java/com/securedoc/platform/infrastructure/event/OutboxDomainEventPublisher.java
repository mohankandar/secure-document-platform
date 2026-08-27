package com.securedoc.platform.infrastructure.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.domain.event.AccessRequestDecidedEvent;
import com.securedoc.platform.domain.event.ArtifactAccessedEvent;
import com.securedoc.platform.domain.event.DocumentDetectionCompletedEvent;
import com.securedoc.platform.domain.event.DocumentDetectionFailedEvent;
import com.securedoc.platform.domain.event.DocumentProcessingFailedEvent;
import com.securedoc.platform.domain.event.DocumentProcessingStartedEvent;
import com.securedoc.platform.domain.event.DocumentUploadedEvent;
import com.securedoc.platform.domain.event.DomainEvent;
import com.securedoc.platform.domain.event.FindingDetectedEvent;
import com.securedoc.platform.domain.event.PackageCreatedEvent;
import com.securedoc.platform.domain.event.PackageProcessingStartedEvent;
import com.securedoc.platform.domain.event.PackageSubmittedEvent;
import com.securedoc.platform.domain.event.PackageVaultedEvent;
import com.securedoc.platform.domain.event.RedactionCompletedEvent;
import com.securedoc.platform.domain.event.RedactionFailedEvent;
import com.securedoc.platform.domain.event.ReviewCompletedEvent;
import com.securedoc.platform.domain.event.ReviewTaskCreatedEvent;
import com.securedoc.platform.domain.event.TextExtractionCompletedEvent;
import com.securedoc.platform.domain.event.TextExtractionFailedEvent;
import com.securedoc.platform.domain.event.VaultEntryCreatedEvent;
import com.securedoc.platform.infrastructure.persistence.entity.OutboxEventEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataOutboxEventRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Persists domain events to the outbox table for reliable delivery.
 * No external messaging (Kafka) yet — events stay in outbox for future relay.
 * Payload contains only IDs and safe metadata, never raw document content.
 */
@Component
public class OutboxDomainEventPublisher implements DomainEventPublisher {

    private final SpringDataOutboxEventRepository outboxRepo;
    private final ObjectMapper objectMapper;

    public OutboxDomainEventPublisher(SpringDataOutboxEventRepository outboxRepo) {
        this.outboxRepo = outboxRepo;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public void publish(DomainEvent event) {
        OutboxEventEntity entity = new OutboxEventEntity();
        entity.setId(UUID.randomUUID());
        entity.setTenantId(event.tenantId().value());
        entity.setEventType(event.eventType());
        entity.setAggregateType(resolveAggregateType(event));
        entity.setAggregateId(resolveAggregateId(event));
        entity.setCorrelationId(event.correlationId().value());
        entity.setPayloadJson(serializePayload(event));
        entity.setStatus("PENDING");
        entity.setOccurredAt(event.occurredAt());
        entity.setCreatedAt(Instant.now());
        entity.setRetryCount(0);

        outboxRepo.save(entity);
    }

    private String serializePayload(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // Fallback: store event type and timestamp only
            return "{\"eventType\":\"" + event.eventType()
                    + "\",\"occurredAt\":\"" + event.occurredAt() + "\"}";
        }
    }

    private String resolveAggregateType(DomainEvent event) {
        if (event instanceof PackageCreatedEvent) return "DocumentPackage";
        if (event instanceof PackageSubmittedEvent) return "DocumentPackage";
        if (event instanceof PackageProcessingStartedEvent) return "DocumentPackage";
        if (event instanceof PackageVaultedEvent) return "DocumentPackage";
        if (event instanceof DocumentUploadedEvent) return "Document";
        if (event instanceof DocumentProcessingStartedEvent) return "Document";
        if (event instanceof DocumentProcessingFailedEvent) return "Document";
        if (event instanceof TextExtractionCompletedEvent) return "Document";
        if (event instanceof TextExtractionFailedEvent) return "Document";
        if (event instanceof DocumentDetectionCompletedEvent) return "Document";
        if (event instanceof DocumentDetectionFailedEvent) return "Document";
        if (event instanceof RedactionCompletedEvent) return "Document";
        if (event instanceof RedactionFailedEvent) return "Document";
        if (event instanceof FindingDetectedEvent) return "Finding";
        if (event instanceof ReviewTaskCreatedEvent) return "ReviewTask";
        if (event instanceof ReviewCompletedEvent) return "ReviewTask";
        if (event instanceof AccessRequestDecidedEvent) return "AccessRequest";
        if (event instanceof VaultEntryCreatedEvent) return "VaultEntry";
        if (event instanceof ArtifactAccessedEvent) return "VaultEntry";
        return "Unknown";
    }

    private String resolveAggregateId(DomainEvent event) {
        if (event instanceof PackageCreatedEvent e) return e.packageId().toString();
        if (event instanceof PackageSubmittedEvent e) return e.packageId().toString();
        if (event instanceof PackageProcessingStartedEvent e) return e.packageId().toString();
        if (event instanceof PackageVaultedEvent e) return e.packageId().toString();
        if (event instanceof DocumentUploadedEvent e) return e.documentId().toString();
        if (event instanceof DocumentProcessingStartedEvent e) return e.documentId().toString();
        if (event instanceof DocumentProcessingFailedEvent e) return e.documentId().toString();
        if (event instanceof TextExtractionCompletedEvent e) return e.documentId().toString();
        if (event instanceof TextExtractionFailedEvent e) return e.documentId().toString();
        if (event instanceof DocumentDetectionCompletedEvent e) return e.documentId().toString();
        if (event instanceof DocumentDetectionFailedEvent e) return e.documentId().toString();
        if (event instanceof RedactionCompletedEvent e) return e.documentId().toString();
        if (event instanceof RedactionFailedEvent e) return e.documentId().toString();
        if (event instanceof FindingDetectedEvent e) return e.findingId().toString();
        if (event instanceof ReviewTaskCreatedEvent e) return e.reviewTaskId().toString();
        if (event instanceof ReviewCompletedEvent e) return e.reviewTaskId().toString();
        if (event instanceof AccessRequestDecidedEvent e) return e.requestId().toString();
        if (event instanceof VaultEntryCreatedEvent e) return e.vaultEntryId().toString();
        if (event instanceof ArtifactAccessedEvent e) return e.vaultEntryId().toString();
        return event.correlationId().toString();
    }
}

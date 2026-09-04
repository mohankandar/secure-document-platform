package com.securedoc.platform.domain.routing;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable evidence for one derived-document delivery attempt. */
public record DerivedDeliveryAttempt(UUID id, UUID derivedDeliveryId, int attemptNumber,
                                     IntegrationConnectionId connectionId, int connectionConfigurationVersion,
                                     String status, String failureCategory, Instant startedAt, Instant completedAt) {
    public DerivedDeliveryAttempt {
        Objects.requireNonNull(id); Objects.requireNonNull(derivedDeliveryId); Objects.requireNonNull(connectionId);
        if(attemptNumber<1) throw new IllegalArgumentException("Attempt number must be >= 1");
        if(connectionConfigurationVersion<1) throw new IllegalArgumentException("Configuration version must be >= 1");
        Objects.requireNonNull(status); Objects.requireNonNull(startedAt);
    }
    public static DerivedDeliveryAttempt started(UUID deliveryId, int attemptNumber, IntegrationConnectionId connectionId, int configVersion, Instant now){
        return new DerivedDeliveryAttempt(UUID.randomUUID(),deliveryId,attemptNumber,connectionId,configVersion,"STARTED",null,now,null);
    }
    public DerivedDeliveryAttempt success(Instant now){return new DerivedDeliveryAttempt(id,derivedDeliveryId,attemptNumber,connectionId,connectionConfigurationVersion,"SUCCESS",null,startedAt,now);}
    public DerivedDeliveryAttempt failure(String category, Instant now){return new DerivedDeliveryAttempt(id,derivedDeliveryId,attemptNumber,connectionId,connectionConfigurationVersion,"FAILED",category,startedAt,now);}
}

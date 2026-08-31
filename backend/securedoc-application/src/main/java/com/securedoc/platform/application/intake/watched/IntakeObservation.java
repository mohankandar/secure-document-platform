package com.securedoc.platform.application.intake.watched;

import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import java.time.Instant;
import java.util.UUID;

public record IntakeObservation(UUID id, TenantId tenantId, String sourceReference, IntakeObservationStatus status,
                                IntakeRejectionCode reasonCode, String fingerprint, PackageId packageId,
                                Instant firstSeenAt, Instant visibleAfter, Instant updatedAt) {}

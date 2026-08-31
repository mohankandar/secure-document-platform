package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.intake.watched.IntakeObservation;
import com.securedoc.platform.domain.identity.TenantId;
import java.util.List;
import java.util.Optional;

public interface IntakeObservationRepository {
    Optional<IntakeObservation> findByTenantAndSource(TenantId tenantId, String sourceReference);
    void save(IntakeObservation observation);
    List<IntakeObservation> findRecentByTenant(TenantId tenantId, int limit);
}

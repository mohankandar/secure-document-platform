package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import com.securedoc.platform.application.intake.watched.*;
import com.securedoc.platform.application.port.out.IntakeObservationRepository;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.entity.IntakeObservationEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataIntakeObservationRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class JpaIntakeObservationRepositoryAdapter implements IntakeObservationRepository {
    private final SpringDataIntakeObservationRepository repo;
    public JpaIntakeObservationRepositoryAdapter(SpringDataIntakeObservationRepository repo){this.repo=repo;}
    @Override public Optional<IntakeObservation> findByTenantAndSource(TenantId tenantId,String source){return repo.findFirstByTenantIdAndSourceReferenceOrderByUpdatedAtDesc(tenantId.value(),source).map(this::toDomain);}
    @Override public void save(IntakeObservation o){
        var existing = repo.findByIdAndTenantId(o.id(), o.tenantId().value());
        if (existing.isEmpty() && repo.existsById(o.id())) {
            throw new IllegalStateException("Intake observation is not owned by the current tenant");
        }
        IntakeObservationEntity e=existing.orElseGet(IntakeObservationEntity::new);
        e.setId(o.id());e.setTenantId(o.tenantId().value());e.setSourceReference(o.sourceReference());e.setStatus(o.status().name());
        e.setReasonCode(o.reasonCode()==null?null:o.reasonCode().name());e.setFingerprint(o.fingerprint());e.setPackageId(o.packageId()==null?null:o.packageId().value());
        e.setFirstSeenAt(o.firstSeenAt());e.setVisibleAfter(o.visibleAfter());e.setUpdatedAt(o.updatedAt());repo.save(e);
    }
    @Override public List<IntakeObservation> findRecentByTenant(TenantId tenantId,int limit){return repo.findByTenantIdOrderByUpdatedAtDesc(tenantId.value(),PageRequest.of(0,Math.max(1,Math.min(limit,100)))).stream().map(this::toDomain).toList();}
    private IntakeObservation toDomain(IntakeObservationEntity e){return new IntakeObservation(e.getId(),TenantId.of(e.getTenantId()),e.getSourceReference(),IntakeObservationStatus.valueOf(e.getStatus()),e.getReasonCode()==null?null:IntakeRejectionCode.valueOf(e.getReasonCode()),e.getFingerprint(),e.getPackageId()==null?null:PackageId.of(e.getPackageId()),e.getFirstSeenAt(),e.getVisibleAfter(),e.getUpdatedAt());}
}

package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.port.out.DerivedDeliveryRepository;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.routing.*;
import com.securedoc.platform.infrastructure.persistence.entity.*;
import com.securedoc.platform.infrastructure.persistence.repository.*;
import org.springframework.stereotype.Component;
import java.util.*;

@Component
public class JpaDerivedDeliveryRepositoryAdapter implements DerivedDeliveryRepository {
    private final SpringDataDerivedDeliveryRepository repo; private final SpringDataDerivedDeliveryAttemptRepository attempts;
    public JpaDerivedDeliveryRepositoryAdapter(SpringDataDerivedDeliveryRepository repo,SpringDataDerivedDeliveryAttemptRepository attempts){this.repo=repo;this.attempts=attempts;}
    public List<DerivedDelivery> findByTenantIdAndPackageId(TenantId t,PackageId p){return repo.findByTenantIdAndPackageIdOrderByCreatedAtAsc(t.value(),p.value()).stream().map(this::toDomain).toList();}
    public Optional<DerivedDelivery> findByTenantIdAndId(TenantId t,UUID id){return repo.findByTenantIdAndId(t.value(),id).map(this::toDomain);}
    public List<DerivedDelivery> findAttentionByTenantId(TenantId t){return repo.findByTenantIdAndStatusInOrderByUpdatedAtDesc(t.value(),List.of("RETRY_PENDING","ATTENTION_REQUIRED")).stream().map(this::toDomain).toList();}
    public Optional<DerivedDelivery> findByTenantIdAndPackageIdAndExportCycleAndSourceDocumentIdAndRoutingRuleId(TenantId t,PackageId p,int cycle,DocumentId d,UUID rule){return repo.findByTenantIdAndPackageIdAndExportCycleAndSourceDocumentIdAndRoutingRuleId(t.value(),p.value(),cycle,d.value(),rule).map(this::toDomain);}
    public void save(DerivedDelivery d){DerivedDeliveryEntity e=repo.findById(d.id()).orElseGet(DerivedDeliveryEntity::new);e.setId(d.id());e.setTenantId(d.tenantId().value());e.setPackageId(d.packageId().value());e.setSourceDocumentId(d.sourceDocumentId().value());e.setExportCycle(d.exportCycle());e.setRoutingRuleId(d.routingRuleId());e.setDestinationConnectionId(d.destinationConnectionId()==null?null:d.destinationConnectionId().value());e.setConnectionConfigurationVersion(d.connectionConfigurationVersion());e.setDestinationLabel(d.destinationLabel());e.setMarkerType(d.markerType()==null?null:d.markerType().name());e.setMarkerValue(d.markerValue());e.setPageNumbers(join(d.pageNumbers()));e.setArtifactId(d.artifactId()==null?null:d.artifactId().value());e.setSourceDerivedDocumentId(d.sourceDerivedDocumentId());e.setFileName(d.fileName());e.setStatus(d.status().name());e.setAttemptCount(d.attemptCount());e.setLastFailureCategory(d.lastFailureCategory());e.setRetryable(d.retryable());e.setDestinationReference(d.destinationReference());e.setCreatedAt(d.createdAt());e.setUpdatedAt(d.updatedAt());e.setDeliveredAt(d.deliveredAt());repo.save(e);}
    public void saveAttempt(DerivedDeliveryAttempt a){DerivedDeliveryAttemptEntity e=new DerivedDeliveryAttemptEntity();e.setId(a.id());e.setDerivedDeliveryId(a.derivedDeliveryId());e.setAttemptNumber(a.attemptNumber());e.setConnectionId(a.connectionId().value());e.setConnectionConfigurationVersion(a.connectionConfigurationVersion());e.setStatus(a.status());e.setFailureCategory(a.failureCategory());e.setStartedAt(a.startedAt());e.setCompletedAt(a.completedAt());attempts.save(e);}
    private DerivedDelivery toDomain(DerivedDeliveryEntity e){return DerivedDelivery.reconstitute(e.getId(),TenantId.of(e.getTenantId()),PackageId.of(e.getPackageId()),DocumentId.of(e.getSourceDocumentId()),e.getExportCycle(),e.getRoutingRuleId(),e.getDestinationConnectionId()==null?null:IntegrationConnectionId.of(e.getDestinationConnectionId()),e.getConnectionConfigurationVersion(),e.getDestinationLabel(),e.getMarkerType()==null?null:RoutingMarkerType.valueOf(e.getMarkerType()),e.getMarkerValue(),parse(e.getPageNumbers()),e.getArtifactId()==null?null:ArtifactId.of(e.getArtifactId()),e.getSourceDerivedDocumentId(),e.getFileName(),DerivedDeliveryStatus.valueOf(e.getStatus()),e.getAttemptCount(),e.getLastFailureCategory(),e.isRetryable(),e.getDestinationReference(),e.getCreatedAt(),e.getUpdatedAt(),e.getDeliveredAt());}
    private String join(List<Integer> p){return p.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));}private List<Integer> parse(String s){if(s==null||s.isBlank())return List.of();return Arrays.stream(s.split(",")).filter(x->!x.isBlank()).map(Integer::parseInt).toList();}
}

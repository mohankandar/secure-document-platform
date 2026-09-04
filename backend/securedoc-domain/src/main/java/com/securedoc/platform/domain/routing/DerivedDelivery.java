package com.securedoc.platform.domain.routing;

import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persisted routing result and independent delivery state for one derived redacted PDF. */
public final class DerivedDelivery {
    private final UUID id;
    private final TenantId tenantId;
    private final PackageId packageId;
    private final DocumentId sourceDocumentId;
    private final int exportCycle;
    private final UUID routingRuleId;
    private final IntegrationConnectionId destinationConnectionId;
    private final Integer connectionConfigurationVersion;
    private final String destinationLabel;
    private final RoutingMarkerType markerType;
    private final String markerValue;
    private final List<Integer> pageNumbers;
    private final ArtifactId artifactId;
    private final String sourceDerivedDocumentId;
    private final String fileName;
    private DerivedDeliveryStatus status;
    private int attemptCount;
    private String lastFailureCategory;
    private boolean retryable;
    private String destinationReference;
    private final Instant createdAt;
    private Instant updatedAt;
    private Instant deliveredAt;

    private DerivedDelivery(UUID id, TenantId tenantId, PackageId packageId, DocumentId sourceDocumentId,
                            int exportCycle, UUID routingRuleId, IntegrationConnectionId destinationConnectionId,
                            Integer connectionConfigurationVersion, String destinationLabel,
                            RoutingMarkerType markerType, String markerValue, List<Integer> pageNumbers,
                            ArtifactId artifactId, String sourceDerivedDocumentId, String fileName,
                            DerivedDeliveryStatus status, int attemptCount, String lastFailureCategory,
                            boolean retryable, String destinationReference, Instant createdAt,
                            Instant updatedAt, Instant deliveredAt) {
        this.id = Objects.requireNonNull(id); this.tenantId = Objects.requireNonNull(tenantId);
        this.packageId = Objects.requireNonNull(packageId); this.sourceDocumentId = Objects.requireNonNull(sourceDocumentId);
        if (exportCycle < 1) throw new IllegalArgumentException("Export cycle must be >= 1");
        this.exportCycle = exportCycle; this.routingRuleId = routingRuleId; this.destinationConnectionId = destinationConnectionId;
        this.connectionConfigurationVersion = connectionConfigurationVersion;
        this.destinationLabel = destinationLabel; this.markerType = markerType; this.markerValue = markerValue;
        this.pageNumbers = List.copyOf(pageNumbers == null ? List.of() : pageNumbers); this.artifactId = artifactId;
        this.sourceDerivedDocumentId = Objects.requireNonNull(sourceDerivedDocumentId); this.fileName = fileName;
        this.status = Objects.requireNonNull(status); this.attemptCount = attemptCount;
        this.lastFailureCategory = lastFailureCategory; this.retryable = retryable;
        this.destinationReference = destinationReference; this.createdAt = Objects.requireNonNull(createdAt);
        this.updatedAt = Objects.requireNonNull(updatedAt); this.deliveredAt = deliveredAt;
    }

    public static DerivedDelivery create(TenantId tenantId, PackageId packageId, DocumentId sourceDocumentId,
                                         int exportCycle, RoutingRule rule, int connectionConfigurationVersion, List<Integer> pageNumbers,
                                         ArtifactId artifactId, String fileName, Instant now) {
        UUID id = UUID.randomUUID();
        return new DerivedDelivery(id, tenantId, packageId, sourceDocumentId, exportCycle, rule.id(), rule.destinationConnectionId(),
                connectionConfigurationVersion, rule.destinationLabel(), rule.markerType(), rule.markerValue(), pageNumbers,
                artifactId, "derived-" + id, fileName, DerivedDeliveryStatus.PENDING, 0, null, true, null, now, now, null);
    }

    public static DerivedDelivery routingAttention(TenantId tenantId, PackageId packageId, DocumentId sourceDocumentId,
                                                    int exportCycle, List<Integer> pages, String category, Instant now) {
        UUID id = UUID.randomUUID();
        return new DerivedDelivery(id, tenantId, packageId, sourceDocumentId, exportCycle, null, null, null,
                "Routing attention", null, null, pages, null, "derived-attention-" + id, null,
                DerivedDeliveryStatus.ATTENTION_REQUIRED, 0, category, false, null, now, now, null);
    }

    public static DerivedDelivery reconstitute(UUID id, TenantId tenantId, PackageId packageId, DocumentId sourceDocumentId,
                                                int exportCycle, UUID routingRuleId, IntegrationConnectionId destinationConnectionId,
                                                Integer connectionConfigurationVersion, String destinationLabel,
                                                RoutingMarkerType markerType, String markerValue, List<Integer> pageNumbers,
                                                ArtifactId artifactId, String sourceDerivedDocumentId, String fileName,
                                                DerivedDeliveryStatus status, int attemptCount, String lastFailureCategory,
                                                boolean retryable, String destinationReference, Instant createdAt,
                                                Instant updatedAt, Instant deliveredAt) {
        return new DerivedDelivery(id, tenantId, packageId, sourceDocumentId, exportCycle, routingRuleId, destinationConnectionId,
                connectionConfigurationVersion, destinationLabel, markerType, markerValue, pageNumbers, artifactId,
                sourceDerivedDocumentId, fileName, status, attemptCount, lastFailureCategory, retryable,
                destinationReference, createdAt, updatedAt, deliveredAt);
    }

    public void startAttempt(Instant now) {
        if (artifactId == null || destinationConnectionId == null) throw new IllegalStateException("Routing-attention item cannot be delivered");
        status = DerivedDeliveryStatus.DELIVERING; attemptCount++; lastFailureCategory = null; updatedAt = now;
    }
    public void markDelivered(String reference, Instant now) {
        if (status != DerivedDeliveryStatus.DELIVERING) throw new IllegalStateException("Derived delivery is not in progress");
        status = DerivedDeliveryStatus.DELIVERED; destinationReference = reference; retryable = false;
        deliveredAt = now; updatedAt = now;
    }
    public void markFailure(String category, boolean retryable, Instant now) {
        this.lastFailureCategory = category == null ? "DERIVED_DELIVERY_FAILED" : category;
        this.retryable = retryable;
        this.status = retryable ? DerivedDeliveryStatus.RETRY_PENDING : DerivedDeliveryStatus.ATTENTION_REQUIRED;
        this.updatedAt = now;
    }

    public UUID id(){return id;} public TenantId tenantId(){return tenantId;} public PackageId packageId(){return packageId;}
    public DocumentId sourceDocumentId(){return sourceDocumentId;} public int exportCycle(){return exportCycle;} public UUID routingRuleId(){return routingRuleId;}
    public IntegrationConnectionId destinationConnectionId(){return destinationConnectionId;}
    public Integer connectionConfigurationVersion(){return connectionConfigurationVersion;}
    public String destinationLabel(){return destinationLabel;} public RoutingMarkerType markerType(){return markerType;}
    public String markerValue(){return markerValue;} public List<Integer> pageNumbers(){return pageNumbers;}
    public ArtifactId artifactId(){return artifactId;} public String sourceDerivedDocumentId(){return sourceDerivedDocumentId;}
    public String fileName(){return fileName;} public DerivedDeliveryStatus status(){return status;}
    public int attemptCount(){return attemptCount;} public String lastFailureCategory(){return lastFailureCategory;}
    public boolean retryable(){return retryable;} public String destinationReference(){return destinationReference;}
    public Instant createdAt(){return createdAt;} public Instant updatedAt(){return updatedAt;} public Instant deliveredAt(){return deliveredAt;}
}

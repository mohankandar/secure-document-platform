package com.securedoc.platform.domain.finding;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Confidence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Entity representing a detected sensitive-data finding within a document.
 * Tracks detection confidence, location, and review decisions.
 * Tenant-scoped: all findings belong to a specific tenant.
 */
public final class Finding {

    private final FindingId id;
    private final TenantId tenantId;
    private final DocumentId documentId;
    private final EntityType entityType;
    private FindingLocation location;
    private final Confidence confidence;
    private final DetectorSource detectorSource;
    private final String snippet;
    private final Instant detectedAt;
    private FindingStatus status;
    private final List<EvidenceRecord> evidenceHistory;
    private Instant updatedAt;

    private Finding(FindingId id, TenantId tenantId, DocumentId documentId, EntityType entityType,
                    FindingLocation location, Confidence confidence,
                    DetectorSource detectorSource, String snippet, Instant detectedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.documentId = documentId;
        this.entityType = entityType;
        this.location = location;
        this.confidence = confidence;
        this.detectorSource = detectorSource;
        this.snippet = snippet;
        this.detectedAt = detectedAt;
        this.status = FindingStatus.DETECTED;
        this.evidenceHistory = new ArrayList<>();
        this.updatedAt = detectedAt;
    }

    public static Finding create(TenantId tenantId, DocumentId documentId, EntityType entityType,
                                  FindingLocation location, Confidence confidence,
                                  DetectorSource detectorSource, String snippet) {
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(entityType, "EntityType must not be null");
        Objects.requireNonNull(location, "Location must not be null");
        Objects.requireNonNull(confidence, "Confidence must not be null");
        Objects.requireNonNull(detectorSource, "DetectorSource must not be null");

        return new Finding(FindingId.generate(), tenantId, documentId, entityType, location,
                confidence, detectorSource, snippet, Instant.now());
    }

    public static Finding reconstitute(FindingId id, TenantId tenantId, DocumentId documentId,
                                        EntityType entityType, FindingLocation location,
                                        Confidence confidence, DetectorSource detectorSource,
                                        String snippet, Instant detectedAt, FindingStatus status,
                                        List<EvidenceRecord> evidenceHistory, Instant updatedAt) {
        Finding finding = new Finding(id, tenantId, documentId, entityType, location, confidence,
                detectorSource, snippet, detectedAt);
        finding.status = status;
        finding.evidenceHistory.addAll(evidenceHistory);
        finding.updatedAt = updatedAt;
        return finding;
    }

    public void confirm(ActorId actorId, String reason) {
        Objects.requireNonNull(actorId, "ActorId must not be null");
        if (status != FindingStatus.DETECTED) {
            throw new IllegalStateException("Cannot confirm finding in status: " + status);
        }
        EvidenceRecord evidence = EvidenceRecord.create(actorId, status, FindingStatus.CONFIRMED, reason);
        evidenceHistory.add(evidence);
        status = FindingStatus.CONFIRMED;
        updatedAt = Instant.now();
    }

    public void dismiss(ActorId actorId, String reason) {
        Objects.requireNonNull(actorId, "ActorId must not be null");
        if (status != FindingStatus.DETECTED) {
            throw new IllegalStateException("Cannot dismiss finding in status: " + status);
        }
        EvidenceRecord evidence = EvidenceRecord.create(actorId, status, FindingStatus.DISMISSED, reason);
        evidenceHistory.add(evidence);
        status = FindingStatus.DISMISSED;
        updatedAt = Instant.now();
    }

    public void markRedacted(ActorId actorId) {
        Objects.requireNonNull(actorId, "ActorId must not be null");
        if (status != FindingStatus.CONFIRMED) {
            throw new IllegalStateException("Cannot mark as redacted finding in status: " + status);
        }
        EvidenceRecord evidence = EvidenceRecord.create(actorId, status, FindingStatus.REDACTED, null);
        evidenceHistory.add(evidence);
        status = FindingStatus.REDACTED;
        updatedAt = Instant.now();
    }

    public void override(ActorId actorId, String reason) {
        Objects.requireNonNull(actorId, "ActorId must not be null");
        Objects.requireNonNull(reason, "Override reason must not be null");
        EvidenceRecord evidence = EvidenceRecord.create(actorId, status, FindingStatus.OVERRIDDEN, reason);
        evidenceHistory.add(evidence);
        status = FindingStatus.OVERRIDDEN;
        updatedAt = Instant.now();
    }

    /**
     * Updates the finding's location with a new or corrected bounding box.
     * Used during human review to provide spatial coordinates for redaction.
     * Preserves the existing page number and offsets if the new location has the same page.
     */
    public void updateLocation(FindingLocation newLocation) {
        Objects.requireNonNull(newLocation, "New location must not be null");
        this.location = newLocation;
        this.updatedAt = Instant.now();
    }

    // --- Accessors ---

    public FindingId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public DocumentId documentId() {
        return documentId;
    }

    public EntityType entityType() {
        return entityType;
    }

    public FindingLocation location() {
        return location;
    }

    public Confidence confidence() {
        return confidence;
    }

    public DetectorSource detectorSource() {
        return detectorSource;
    }

    public String snippet() {
        return snippet;
    }

    public Instant detectedAt() {
        return detectedAt;
    }

    public FindingStatus status() {
        return status;
    }

    public List<EvidenceRecord> evidenceHistory() {
        return Collections.unmodifiableList(evidenceHistory);
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean isActionable() {
        return status == FindingStatus.DETECTED || status == FindingStatus.CONFIRMED;
    }
}

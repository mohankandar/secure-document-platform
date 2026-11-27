package com.securedoc.platform.domain.finding;

import com.securedoc.platform.domain.business.RedactionStyle;
import com.securedoc.platform.domain.error.LvErrorCode;
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
 *
 * <p>A finding never holds the found value, in full, masked or in part (task 0015): only its
 * type, location, confidence, detector and review decisions.
 */
public final class Finding {

    private final FindingId id;
    private final TenantId tenantId;
    private final DocumentId documentId;
    private final EntityType entityType;
    private FindingLocation location;
    private final Confidence confidence;
    private final DetectorSource detectorSource;
    private final Instant detectedAt;
    /** Task 0064: which path(s) found it and which AI model(s) took part. */
    private final FindingOrigin origin;
    private FindingStatus status;
    private final List<EvidenceRecord> evidenceHistory;
    private Instant updatedAt;
    /** Task 0062: the style in force when the document was searched; a later policy change doesn't reach it. */
    private RedactionStyle redactionStyle = RedactionStyle.REMOVE_FULLY;
    /** Task 0105: how it was read, found and placed; null for a finding saved before task 0105. */
    private FindingPlacement placement;

    private Finding(FindingId id, TenantId tenantId, DocumentId documentId, EntityType entityType,
                    FindingLocation location, Confidence confidence,
                    DetectorSource detectorSource, Instant detectedAt, FindingOrigin origin) {
        this.origin = Objects.requireNonNull(origin, "Origin must not be null");
        this.id = id;
        this.tenantId = tenantId;
        this.documentId = documentId;
        this.entityType = entityType;
        this.location = location;
        this.confidence = confidence;
        this.detectorSource = detectorSource;
        this.detectedAt = detectedAt;
        this.status = FindingStatus.DETECTED;
        this.evidenceHistory = new ArrayList<>();
        this.updatedAt = detectedAt;
    }

    public static Finding create(TenantId tenantId, DocumentId documentId, EntityType entityType,
                                  FindingLocation location, Confidence confidence,
                                  DetectorSource detectorSource) {
        return create(tenantId, documentId, entityType, location, confidence, detectorSource,
                FindingOrigin.NOT_RECORDED);
    }

    /** Task 0064: with the path(s) that found it and the AI model(s) that took part. */
    public static Finding create(TenantId tenantId, DocumentId documentId, EntityType entityType,
                                  FindingLocation location, Confidence confidence,
                                  DetectorSource detectorSource, FindingOrigin origin) {
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(documentId, "DocumentId must not be null");
        Objects.requireNonNull(entityType, "EntityType must not be null");
        Objects.requireNonNull(location, "Location must not be null");
        Objects.requireNonNull(confidence, "Confidence must not be null");
        Objects.requireNonNull(detectorSource, "DetectorSource must not be null");

        return new Finding(FindingId.generate(), tenantId, documentId, entityType, location,
                confidence, detectorSource, Instant.now(), origin);
    }

    public static Finding reconstitute(FindingId id, TenantId tenantId, DocumentId documentId,
                                        EntityType entityType, FindingLocation location,
                                        Confidence confidence, DetectorSource detectorSource,
                                        Instant detectedAt, FindingStatus status,
                                        List<EvidenceRecord> evidenceHistory, Instant updatedAt) {
        return reconstitute(id, tenantId, documentId, entityType, location, confidence, detectorSource, detectedAt,
                status, evidenceHistory, updatedAt, FindingOrigin.NOT_RECORDED);
    }

    public static Finding reconstitute(FindingId id, TenantId tenantId, DocumentId documentId,
                                        EntityType entityType, FindingLocation location,
                                        Confidence confidence, DetectorSource detectorSource,
                                        Instant detectedAt, FindingStatus status,
                                        List<EvidenceRecord> evidenceHistory, Instant updatedAt,
                                        FindingOrigin origin) {
        Finding finding = new Finding(id, tenantId, documentId, entityType, location, confidence,
                detectorSource, detectedAt, origin);
        finding.status = status;
        finding.evidenceHistory.addAll(evidenceHistory);
        finding.updatedAt = updatedAt;
        return finding;
    }

    /** As above, with the stored redaction style; null (a finding from before task 0062) means removed in full. */
    public static Finding reconstitute(FindingId id, TenantId tenantId, DocumentId documentId,
                                        EntityType entityType, FindingLocation location,
                                        Confidence confidence, DetectorSource detectorSource,
                                        Instant detectedAt, FindingStatus status,
                                        List<EvidenceRecord> evidenceHistory, Instant updatedAt,
                                        RedactionStyle redactionStyle) {
        return reconstitute(id, tenantId, documentId, entityType, location, confidence, detectorSource, detectedAt,
                status, evidenceHistory, updatedAt, FindingOrigin.NOT_RECORDED, redactionStyle);
    }

    public static Finding reconstitute(FindingId id, TenantId tenantId, DocumentId documentId,
                                        EntityType entityType, FindingLocation location,
                                        Confidence confidence, DetectorSource detectorSource,
                                        Instant detectedAt, FindingStatus status,
                                        List<EvidenceRecord> evidenceHistory, Instant updatedAt,
                                        FindingOrigin origin, RedactionStyle redactionStyle) {
        Finding finding = reconstitute(id, tenantId, documentId, entityType, location, confidence, detectorSource,
                detectedAt, status, evidenceHistory, updatedAt, origin);
        if (redactionStyle != null) finding.redactionStyle = redactionStyle;
        return finding;
    }

    /** Task 0105: how it was read, found and placed; null for a finding saved before task 0105. */
    public void applyPlacement(FindingPlacement placement) {
        this.placement = placement;
    }

    public FindingPlacement placement() {
        return placement;
    }

    /**
     * Task 0105: a person placed or moved the box. Only a box that differs from the one there is a change; the same box
     * sent again (a plain confirmation) changes nothing and records nobody.
     *
     * @return true when the place changed
     */
    public boolean placeByPerson(FindingLocation.BoundingBox box, ActorId actor, Instant at) {
        Objects.requireNonNull(box, "box must not be null");
        if (same(box, location.boundingBox())) return false;
        this.location = FindingLocation.withBounds(location.pageNumber(), location.startOffset(), location.endOffset(), box);
        FindingPlacement before = placement != null ? placement
                : FindingPlacement.automatic(FindingPlacement.Reader.PERSON, FindingPlacement.Method.PERSON,
                FindingPlacement.How.PERSON);
        this.placement = before.placedBy(actor, at);
        this.updatedAt = at;
        return true;
    }

    /** The same box, give or take what a trip through the screen may round. */
    private static boolean same(FindingLocation.BoundingBox a, FindingLocation.BoundingBox b) {
        double close = 1e-6;
        return b != null && Math.abs(a.left() - b.left()) < close && Math.abs(a.top() - b.top()) < close
                && Math.abs(a.width() - b.width()) < close && Math.abs(a.height() - b.height()) < close;
    }

    /** Set once, when the document is searched. */
    public void applyStyle(RedactionStyle style) {
        this.redactionStyle = Objects.requireNonNull(style, "style must not be null");
    }

    public RedactionStyle redactionStyle() {
        return redactionStyle;
    }

    public void confirm(ActorId actorId, String reason) {
        Objects.requireNonNull(actorId, "ActorId must not be null");
        if (status != FindingStatus.DETECTED) {
            throw new com.securedoc.platform.domain.error.CodedStateException(LvErrorCode.REV_001, "Cannot confirm finding in status: " + status);
        }
        EvidenceRecord evidence = EvidenceRecord.create(actorId, status, FindingStatus.CONFIRMED, reason);
        evidenceHistory.add(evidence);
        status = FindingStatus.CONFIRMED;
        updatedAt = Instant.now();
    }

    public void dismiss(ActorId actorId, String reason) {
        Objects.requireNonNull(actorId, "ActorId must not be null");
        if (status != FindingStatus.DETECTED) {
            throw new com.securedoc.platform.domain.error.CodedStateException(LvErrorCode.REV_001, "Cannot dismiss finding in status: " + status);
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

    public FindingOrigin origin() {
        return origin;
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

package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "external_intake_documents", uniqueConstraints = @UniqueConstraint(
        name = "uk_external_intake_document_source_id", columnNames = {"package_id", "external_source_document_id"}))
public class ExternalIntakeDocumentEntity {
    @Id @Column(name = "id", nullable = false) private UUID id;
    @Column(name = "package_id", nullable = false) private UUID packageId;
    @Column(name = "external_source_document_id", nullable = false, length = 255) private String externalSourceDocumentId;
    @Column(name = "original_file_name", nullable = false, length = 255) private String originalFileName;
    @Column(name = "declared_sha256", length = 64) private String declaredSha256;
    @Column(name = "received_document_id") private UUID receivedDocumentId;
    @Column(name = "received_sha256", length = 64) private String receivedSha256;
    @Column(name = "idempotency_key", length = 255) private String idempotencyKey;
    @Column(name = "received_at") private Instant receivedAt;

    public UUID getId() { return id; } public void setId(UUID v) { id = v; }
    public UUID getPackageId() { return packageId; } public void setPackageId(UUID v) { packageId = v; }
    public String getExternalSourceDocumentId() { return externalSourceDocumentId; } public void setExternalSourceDocumentId(String v) { externalSourceDocumentId = v; }
    public String getOriginalFileName() { return originalFileName; } public void setOriginalFileName(String v) { originalFileName = v; }
    public String getDeclaredSha256() { return declaredSha256; } public void setDeclaredSha256(String v) { declaredSha256 = v; }
    public UUID getReceivedDocumentId() { return receivedDocumentId; } public void setReceivedDocumentId(UUID v) { receivedDocumentId = v; }
    public String getReceivedSha256() { return receivedSha256; } public void setReceivedSha256(String v) { receivedSha256 = v; }
    public String getIdempotencyKey() { return idempotencyKey; } public void setIdempotencyKey(String v) { idempotencyKey = v; }
    public Instant getReceivedAt() { return receivedAt; } public void setReceivedAt(Instant v) { receivedAt = v; }
}

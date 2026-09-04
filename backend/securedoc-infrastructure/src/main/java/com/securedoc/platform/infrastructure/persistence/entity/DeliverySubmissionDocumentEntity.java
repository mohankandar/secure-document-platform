package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="delivery_submission_documents")
public class DeliverySubmissionDocumentEntity {
    @Id private UUID id;
    @Column(name="submission_id", nullable=false) private UUID submissionId;
    @Column(name="tenant_id", nullable=false) private UUID tenantId;
    @Column(name="package_id", nullable=false) private UUID packageId;
    @Column(name="document_id", nullable=false) private UUID documentId;
    @Column(name="source_document_id", nullable=false, length=120) private String sourceDocumentId;
    @Column(name="file_name", nullable=false, length=512) private String fileName;
    @Column(name="size_bytes", nullable=false) private long sizeBytes;
    @Column(name="checksum_algorithm", nullable=false, length=32) private String checksumAlgorithm;
    @Column(name="checksum_value", nullable=false, length=256) private String checksumValue;
    @Column(nullable=false) private boolean acknowledged;
    @Column(name="destination_document_reference", length=255) private String destinationDocumentReference;
    @Column(name="destination_location", length=1024) private String destinationLocation;
    @Column(name="acknowledged_at") private Instant acknowledgedAt;
    public UUID getId(){return id;} public void setId(UUID v){id=v;}
    public UUID getSubmissionId(){return submissionId;} public void setSubmissionId(UUID v){submissionId=v;}
    public UUID getTenantId(){return tenantId;} public void setTenantId(UUID v){tenantId=v;}
    public UUID getPackageId(){return packageId;} public void setPackageId(UUID v){packageId=v;}
    public UUID getDocumentId(){return documentId;} public void setDocumentId(UUID v){documentId=v;}
    public String getSourceDocumentId(){return sourceDocumentId;} public void setSourceDocumentId(String v){sourceDocumentId=v;}
    public String getFileName(){return fileName;} public void setFileName(String v){fileName=v;}
    public long getSizeBytes(){return sizeBytes;} public void setSizeBytes(long v){sizeBytes=v;}
    public String getChecksumAlgorithm(){return checksumAlgorithm;} public void setChecksumAlgorithm(String v){checksumAlgorithm=v;}
    public String getChecksumValue(){return checksumValue;} public void setChecksumValue(String v){checksumValue=v;}
    public boolean isAcknowledged(){return acknowledged;} public void setAcknowledged(boolean v){acknowledged=v;}
    public String getDestinationDocumentReference(){return destinationDocumentReference;} public void setDestinationDocumentReference(String v){destinationDocumentReference=v;}
    public String getDestinationLocation(){return destinationLocation;} public void setDestinationLocation(String v){destinationLocation=v;}
    public Instant getAcknowledgedAt(){return acknowledgedAt;} public void setAcknowledgedAt(Instant v){acknowledgedAt=v;}
}

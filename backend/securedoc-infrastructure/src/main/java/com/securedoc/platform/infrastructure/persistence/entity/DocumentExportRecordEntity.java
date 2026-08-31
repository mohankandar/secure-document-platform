package com.securedoc.platform.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "document_export_records")
public class DocumentExportRecordEntity {
    @Id
    @Column(name = "id", nullable = false)
    private UUID id;
    @Column(name = "export_record_id", nullable = false)
    private UUID exportRecordId;
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;
    @Column(name = "package_id", nullable = false)
    private UUID packageId;
    @Column(name = "document_id", nullable = false)
    private UUID documentId;
    @Column(name = "file_name", nullable = false, length = 512)
    private String fileName;
    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;
    @Column(name = "checksum_algorithm", nullable = false)
    private String checksumAlgorithm;
    @Column(name = "checksum_value", nullable = false)
    private String checksumValue;
    @Column(name = "destination_location", nullable = false, length = 2048)
    private String destinationLocation;

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getExportRecordId() { return exportRecordId; }
    public void setExportRecordId(UUID value) { this.exportRecordId = value; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID value) { this.tenantId = value; }
    public UUID getPackageId() { return packageId; }
    public void setPackageId(UUID value) { this.packageId = value; }
    public UUID getDocumentId() { return documentId; }
    public void setDocumentId(UUID value) { this.documentId = value; }
    public String getFileName() { return fileName; }
    public void setFileName(String value) { this.fileName = value; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long value) { this.sizeBytes = value; }
    public String getChecksumAlgorithm() { return checksumAlgorithm; }
    public void setChecksumAlgorithm(String value) { this.checksumAlgorithm = value; }
    public String getChecksumValue() { return checksumValue; }
    public void setChecksumValue(String value) { this.checksumValue = value; }
    public String getDestinationLocation() { return destinationLocation; }
    public void setDestinationLocation(String value) { this.destinationLocation = value; }
}

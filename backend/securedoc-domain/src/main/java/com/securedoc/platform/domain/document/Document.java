package com.securedoc.platform.domain.document;

import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Entity representing a single document within a package.
 * Owns pages and tracks processing lifecycle.
 */
public final class Document {

    private final DocumentId id;
    private final TenantId tenantId;
    private final PackageId packageId;
    private final FileName fileName;
    private final MimeType mimeType;
    private final long sizeBytes;
    private final Checksum checksum;
    private final DocumentType documentType;
    private final Instant uploadedAt;
    private DocumentStatus status;
    private final List<Page> pages;
    private Instant updatedAt;

    private Document(DocumentId id, TenantId tenantId, PackageId packageId, FileName fileName,
                     MimeType mimeType, long sizeBytes, Checksum checksum,
                     DocumentType documentType, Instant uploadedAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.packageId = packageId;
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
        this.checksum = checksum;
        this.documentType = documentType;
        this.uploadedAt = uploadedAt;
        this.status = DocumentStatus.UPLOADED;
        this.pages = new ArrayList<>();
        this.updatedAt = uploadedAt;
    }

    public static Document create(TenantId tenantId, PackageId packageId, FileName fileName,
                                   MimeType mimeType, long sizeBytes, Checksum checksum,
                                   DocumentType documentType) {
        Objects.requireNonNull(tenantId, "TenantId must not be null");
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(fileName, "FileName must not be null");
        Objects.requireNonNull(mimeType, "MimeType must not be null");
        Objects.requireNonNull(checksum, "Checksum must not be null");
        Objects.requireNonNull(documentType, "DocumentType must not be null");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("Size bytes must be >= 0, got: " + sizeBytes);
        }

        return new Document(DocumentId.generate(), tenantId, packageId, fileName, mimeType,
                sizeBytes, checksum, documentType, Instant.now());
    }

    public static Document reconstitute(DocumentId id, TenantId tenantId, PackageId packageId,
                                         FileName fileName, MimeType mimeType, long sizeBytes,
                                         Checksum checksum, DocumentType documentType,
                                         Instant uploadedAt, DocumentStatus status,
                                         List<Page> pages, Instant updatedAt) {
        Document doc = new Document(id, tenantId, packageId, fileName, mimeType,
                sizeBytes, checksum, documentType, uploadedAt);
        doc.status = status;
        doc.pages.addAll(pages);
        doc.updatedAt = updatedAt;
        return doc;
    }

    public void startExtraction() {
        if (status != DocumentStatus.UPLOADED) {
            throw new IllegalStateException("Cannot start extraction in status: " + status);
        }
        status = DocumentStatus.EXTRACTING;
        updatedAt = Instant.now();
    }

    public void completeExtraction(List<Page> extractedPages) {
        Objects.requireNonNull(extractedPages, "Pages must not be null");
        if (status != DocumentStatus.EXTRACTING) {
            throw new IllegalStateException("Cannot complete extraction in status: " + status);
        }
        pages.clear();
        pages.addAll(extractedPages);
        status = DocumentStatus.DETECTING;
        updatedAt = Instant.now();
    }

    public void startRedaction() {
        if (status != DocumentStatus.DETECTING) {
            throw new IllegalStateException("Cannot start redaction in status: " + status);
        }
        status = DocumentStatus.REDACTING;
        updatedAt = Instant.now();
    }

    public void completeProcessing() {
        if (status != DocumentStatus.REDACTING && status != DocumentStatus.DETECTING) {
            throw new IllegalStateException("Cannot complete processing in status: " + status);
        }
        status = DocumentStatus.PROCESSED;
        updatedAt = Instant.now();
    }


    /** Prepares a successfully processed document for another processing cycle using the retained original. */
    public void prepareForReprocessing() {
        if (status != DocumentStatus.PROCESSED) {
            throw new IllegalStateException("Only a processed document can be reprocessed");
        }
        pages.clear();
        status = DocumentStatus.UPLOADED;
        updatedAt = Instant.now();
    }

    public void fail() {
        status = DocumentStatus.FAILED;
        updatedAt = Instant.now();
    }

    // --- Accessors ---

    public DocumentId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public PackageId packageId() {
        return packageId;
    }

    public FileName fileName() {
        return fileName;
    }

    public MimeType mimeType() {
        return mimeType;
    }

    public long sizeBytes() {
        return sizeBytes;
    }

    public Checksum checksum() {
        return checksum;
    }

    public DocumentType documentType() {
        return documentType;
    }

    public Instant uploadedAt() {
        return uploadedAt;
    }

    public DocumentStatus status() {
        return status;
    }

    public List<Page> pages() {
        return Collections.unmodifiableList(pages);
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public int pageCount() {
        return pages.size();
    }
}

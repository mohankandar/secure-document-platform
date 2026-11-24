package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.MimeType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Task 0049: what one page batch leaves for the gathering of its document. A batch's result is a file in the case's
 * own processing storage (the store that holds the document's text), with an artifact record like every other file of
 * the document, so it is deleted with the case. Gathering marks the records deleted in the same transaction and the
 * files are deleted once that is saved.
 *
 * <p>A result is found by the job that wrote it, and its checksum is verified: a missing, changed or unreadable result
 * refuses the gathering (fail closed).
 */
public final class PageBatchFiles {
    private static final int FORMAT = 0x4C564201;
    private static final String MIME_TYPE = "application/octet-stream";

    private final ArtifactRepository artifacts;
    private final StoragePort storage;
    private final ChecksumPort checksums;

    public PageBatchFiles(ArtifactRepository artifacts, StoragePort storage, ChecksumPort checksums) {
        this.artifacts = Objects.requireNonNull(artifacts);
        this.storage = Objects.requireNonNull(storage);
        this.checksums = Objects.requireNonNull(checksums);
    }

    /** Writes the body of a batch result. */
    @FunctionalInterface
    public interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    /** Reads the body of a batch result. */
    @FunctionalInterface
    public interface BodyReader<T> {
        T read(DataInputStream in) throws IOException;
    }

    public void save(ArtifactType type, PageBatchCommand command, Body body) {
        byte[] bytes;
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buffer);
            out.writeInt(FORMAT);
            writeText(out, command.batch().jobId().toString());
            out.writeInt(command.batch().from());
            out.writeInt(command.batch().to());
            body.write(out);
            out.flush();
            bytes = buffer.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Page batch result could not be written", e);
        }
        Artifact artifact = Artifact.create(command.tenantId(), command.documentId(), type, MimeType.of(MIME_TYPE),
                bytes.length, checksums.compute(new ByteArrayInputStream(bytes)));
        String path = command.tenantId() + "/" + command.packageId() + "/" + command.documentId() + "/" + type.name()
                + "/" + artifact.id();
        artifact.markAvailable(storage.storeForPackage(command.tenantId(), command.packageId(), path,
                new ByteArrayInputStream(bytes), bytes.length, MIME_TYPE));
        artifacts.save(artifact);
    }

    /** The results of these batches, in the batches' order. Refused when one is missing or changed. */
    public <T> List<T> load(ArtifactType type, DocumentId documentId, TenantId tenantId, List<PageBatch> batches,
                            BodyReader<T> reader) {
        Map<UUID, byte[]> byJob = new HashMap<>();
        for (Artifact artifact : of(type, documentId, tenantId)) {
            if (!artifact.isAvailable()) continue;
            byte[] bytes;
            try (InputStream in = storage.retrieve(artifact.storageReference())) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("Page batch result could not be read", e);
            }
            if (!checksums.compute(new ByteArrayInputStream(bytes)).matches(artifact.checksum())) {
                throw new IllegalStateException("Page batch result does not match its checksum");
            }
            byJob.put(open(bytes, (in, jobId, from, to) -> jobId), bytes);
        }
        return batches.stream().map(batch -> {
            byte[] bytes = byJob.get(batch.jobId());
            if (bytes == null) throw new IllegalStateException("A page batch result is missing");
            return open(bytes, (in, jobId, from, to) -> {
                if (from != batch.from() || to != batch.to()) {
                    throw new IllegalStateException("A page batch result covers other pages");
                }
                return reader.read(in);
            });
        }).toList();
    }

    /** Marks every batch result of the document deleted; the files go once this transaction is saved. */
    public void discard(ArtifactType type, DocumentId documentId, TenantId tenantId) {
        for (Artifact artifact : of(type, documentId, tenantId)) {
            if (artifact.status() == ArtifactStatus.DELETED) continue;
            ArtifactStorageReference reference = artifact.storageReference();
            artifact.delete();
            artifacts.save(artifact);
            if (reference != null) StagedStepScope.deleteWhenSaved(reference);
        }
    }

    private List<Artifact> of(ArtifactType type, DocumentId documentId, TenantId tenantId) {
        return artifacts.findByDocumentIdAndTenantId(documentId, tenantId).stream()
                .filter(artifact -> artifact.artifactType() == type).toList();
    }

    @FunctionalInterface
    private interface Opened<T> {
        T read(DataInputStream in, UUID jobId, int from, int to) throws IOException;
    }

    private static <T> T open(byte[] bytes, Opened<T> opened) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != FORMAT) throw new IllegalStateException("Unknown page batch result format");
            return opened.read(in, UUID.fromString(readText(in)), in.readInt(), in.readInt());
        } catch (IOException e) {
            throw new UncheckedIOException("Page batch result could not be read", e);
        }
    }

    public static void writeText(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    public static String readText(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > in.available()) throw new IOException("Invalid length");
        return new String(in.readNBytes(length), StandardCharsets.UTF_8);
    }
}

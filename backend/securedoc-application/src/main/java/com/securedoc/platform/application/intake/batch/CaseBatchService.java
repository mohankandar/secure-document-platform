package com.securedoc.platform.application.intake.batch;

import com.securedoc.platform.application.port.out.CaseBatchStore;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Arrival;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Known;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Outcome;
import com.securedoc.platform.domain.document.version.DocumentVersionRecognition.Result;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Task 0104 ({@code docs/design/CASE_VERSIONS.md}, section 3): classifies a batch arriving for a case, file by file,
 * against every version the case ever received and against the files before it in the same batch, so nothing unchanged
 * is processed twice. Each file to store gets its document id here, so a later file of the batch can name it. Refuses
 * nothing. Not yet wired into the intakes (next step of the task).
 */
public class CaseBatchService {

    private final CaseBatchStore store;

    public CaseBatchService(CaseBatchStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /**
     * @param result        what the file is
     * @param newDocumentId the id to store it under; null for a skipped copy (it is not stored)
     */
    public record Decision(Result result, UUID newDocumentId) {}

    public List<Decision> classify(TenantId tenantId, PackageId packageId, List<Arrival> files) {
        List<Known> known = new ArrayList<>(store.knownVersions(tenantId, packageId));
        List<Decision> decisions = new ArrayList<>(files.size());
        for (Arrival file : files) {
            Result r = DocumentVersionRecognition.recognise(file, known);
            if (r.outcome() == Outcome.SKIPPED_IDENTICAL) {
                decisions.add(new Decision(r, null));
                continue;
            }
            UUID id = UUID.randomUUID();
            UUID lineage = r.outcome() == Outcome.NEW_VERSION ? r.lineageId() : id;
            Result stored = r.outcome() == Outcome.NEW_VERSION ? r
                    : new Result(r.outcome(), r.rule(), null, lineage, 1, null, r.possibleVersionOf());
            // The files after it in this batch are compared against it too.
            known.add(new Known(id, lineage, stored.versionNumber(), file.sha256(), file.sizeBytes(), file.nameHash(),
                    file.pdfId(), file.xmpDocumentId(), file.sourceDocumentId()));
            decisions.add(new Decision(stored, id));
        }
        return decisions;
    }

    /**
     * An arriving file, read from {@code content} as often as needed (its hash, and the hash of a beginning for the
     * incremental-update rule). The name and the source's id are hashed here; neither is kept as it is.
     */
    public static Arrival arrival(long sizeBytes, Supplier<InputStream> content, String fileName, String sourceDocumentId,
                                  String replaces, String pdfIdHash, String xmpIdHash) {
        Map<Long, String> prefixes = new HashMap<>();
        return new Arrival(digest(content, Long.MAX_VALUE), sizeBytes, nameHash(fileName),
                sourceDocumentId == null ? null : sha256(sourceDocumentId.getBytes(StandardCharsets.UTF_8)), replaces,
                pdfIdHash, xmpIdHash, length -> prefixes.computeIfAbsent(length, n -> digest(content, n)));
    }

    /** Hash of a file name, compared without case; null for none. */
    public static String nameHash(String fileName) {
        if (fileName == null || fileName.isBlank()) return null;
        return sha256(fileName.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha().digest(bytes));
    }

    private static String digest(Supplier<InputStream> content, long limit) {
        MessageDigest md = sha();
        try (InputStream in = new DigestInputStream(content.get(), md)) {
            byte[] buffer = new byte[64 * 1024];
            long left = limit;
            while (left > 0) {
                int n = in.read(buffer, 0, (int) Math.min(buffer.length, left));
                if (n < 0) break;
                left -= n;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read an arriving file", e);
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static MessageDigest sha() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}

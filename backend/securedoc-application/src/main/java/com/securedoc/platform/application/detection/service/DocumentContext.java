package com.securedoc.platform.application.detection.service;

import com.securedoc.platform.application.extraction.service.TextExtractionService;
import com.securedoc.platform.application.intake.CaseInstructionsService;
import com.securedoc.platform.application.pipeline.StagedStepScope;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.ProcessingLockPort;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort.AnalysisPolicy;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TransactionPort;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStatus;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.finding.AiModel;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.finding.FindingOrigin;
import com.securedoc.platform.domain.vo.MimeType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Task 0065: one first pass reads a document's beginning (with the case's instructions) for the people it is about, and
 * every page batch looks for their names. Made once: the page batches of a staged document share it as a file of the
 * document, encrypted like its text, until they are gathered. Only with the entity's setting on and Person name enabled.
 */
public final class DocumentContext {
    private static final Logger log = LoggerFactory.getLogger(DocumentContext.class);
    /** How much of the beginning is read: about 3,000 tokens, which fits with the answer in the local model's window. */
    static final int BEGINNING = 12_000;
    private static final String FORMAT = "document-names-1";
    private static final Duration LOCK = Duration.ofMinutes(10);

    private final SensitiveContentAnalysisPort ai;
    private final ArtifactRepository artifacts;
    private final StoragePort storage;
    private final ChecksumPort checksums;
    private final CaseInstructionsService instructions;
    private final ProcessingLockPort locks;
    private final TransactionPort transactions;

    public DocumentContext(SensitiveContentAnalysisPort ai, ArtifactRepository artifacts, StoragePort storage,
                           ChecksumPort checksums, CaseInstructionsService instructions, ProcessingLockPort locks,
                           TransactionPort transactions) {
        this.ai = Objects.requireNonNull(ai);
        this.artifacts = Objects.requireNonNull(artifacts);
        this.storage = Objects.requireNonNull(storage);
        this.checksums = Objects.requireNonNull(checksums);
        this.instructions = Objects.requireNonNull(instructions);
        this.locks = Objects.requireNonNull(locks);
        this.transactions = Objects.requireNonNull(transactions);
    }

    static boolean wanted(TenantRedactionPolicy policy) {
        return policy.documentContext() && policy.isEnabled(EntityType.PERSON_NAME);
    }

    /** What a batch searched with: the setting and which instructions. Empty with the setting off. */
    String stamp(Document document, TenantRedactionPolicy policy) {
        return wanted(policy) ? ";context=" + instructionsId(document) : "";
    }

    private String instructionsId(Document document) {
        return instructions.find(document.tenantId(), document.packageId()).map(i -> i.id().toString()).orElse("none");
    }

    /** For a document searched as a whole: nothing is stored. */
    KnownNames of(Document document, TenantRedactionPolicy policy) {
        if (!wanted(policy)) return KnownNames.NONE;
        return stored(document).orElseGet(() -> read(document));
    }

    /** For a page batch: the stored one, or made and stored in the batch's own transaction. */
    KnownNames ofBatch(Document document, TenantRedactionPolicy policy) {
        if (!wanted(policy)) return KnownNames.NONE;
        return stored(document).orElseGet(() -> {
            KnownNames made = read(document);
            save(document, made);
            return made;
        });
    }

    /**
     * Before a page batch's transaction: the first batch makes it and the others wait for it, so that the document is
     * summarized once. A failure is thrown; the batch's own step then tries once more and records what is wrong.
     */
    void prepare(Document document, TenantRedactionPolicy policy) {
        if (!wanted(policy)) return;
        String key = "document-context:" + document.id().value();
        while (stored(document).isEmpty()) {
            Optional<String> token = acquire(key);
            if (token.isEmpty()) {
                pause();
                continue;
            }
            try {
                if (stored(document).isPresent()) return;
                KnownNames made = read(document);
                transactions.inNewTransaction(() -> {
                    save(document, made);
                    return null;
                });
            } finally {
                locks.release(key, token.get());
            }
        }
    }

    private Optional<String> acquire(String key) {
        try {
            return locks.tryAcquire(key, LOCK);
        } catch (RuntimeException takenMeanwhile) {
            return Optional.empty();
        }
    }

    private static void pause() {
        try {
            Thread.sleep(250);
        } catch (InterruptedException stopping) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while a document's names were read", stopping);
        }
    }

    /** Once the batches are gathered it is no longer needed. */
    void discard(Document document) {
        for (Artifact artifact : all(document)) {
            if (artifact.status() == ArtifactStatus.DELETED) continue;
            ArtifactStorageReference reference = artifact.storageReference();
            artifact.delete();
            artifacts.save(artifact);
            if (reference != null) StagedStepScope.deleteWhenSaved(reference);
        }
    }

    // ------------------------------------------------------------------------------------------- the first pass

    private KnownNames read(Document document) {
        long started = System.nanoTime();
        String instructed = instructions.find(document.tenantId(), document.packageId())
                .map(CaseInstructionsService.Instructions::text).orElse("");
        String beginning = beginning(document);
        String text = (instructed.isBlank() ? "" : "Instructions for this case:\n" + instructed + "\n\n") + beginning;
        if (text.isBlank()) return KnownNames.NONE;
        var output = ai.analyze(new SensitiveContentAnalysisPort.AnalysisRequest(document.tenantId(),
                List.of(new SensitiveContentAnalysisPort.PageContent(1, text)),
                AnalysisPolicy.of(AnalysisPolicy.Ask.SUMMARY)));
        List<String> full = new ArrayList<>();
        List<String> other = new ArrayList<>();
        output.findings().forEach(f -> {
            if (AnalysisPolicy.SUMMARY_PERSON.equals(f.entityType())) full.add(f.matchedText());
            else if (AnalysisPolicy.SUMMARY_ALSO_CALLED.equals(f.entityType())) other.add(f.matchedText());
        });
        KnownNames names = new KnownNames(KnownNames.forms(full, other), output.models());
        log.info("DOCUMENT_SUMMARY_READ document={} instructed={} people={} forms={} durationMs={}", document.id(),
                !instructed.isBlank(), full.size(), names.forms().size(), (System.nanoTime() - started) / 1_000_000L);
        return names;
    }

    private String beginning(Document document) {
        Artifact text = artifacts.findByDocumentIdAndTenantId(document.id(), document.tenantId()).stream()
                .filter(a -> a.artifactType() == ArtifactType.EXTRACTED_TEXT && a.isAvailable())
                .max(Comparator.comparing(Artifact::createdAt))
                .orElseThrow(() -> new IllegalStateException("The document's text is not stored"));
        try (InputStream in = storage.retrieve(text.storageReference())) {
            String all = new String(in.readNBytes(BEGINNING * 4 + 4), StandardCharsets.UTF_8);
            return all.substring(0, Math.min(BEGINNING, all.length()))
                    .replace(TextExtractionService.PAGE_SEPARATOR, "\n\n").strip();
        } catch (IOException e) {
            throw new UncheckedIOException("The document's text could not be read", e);
        }
    }

    // ------------------------------------------------------------------------------------------- the stored file

    private List<Artifact> all(Document document) {
        return artifacts.findByDocumentIdAndTenantId(document.id(), document.tenantId()).stream()
                .filter(a -> a.artifactType() == ArtifactType.DOCUMENT_SUMMARY)
                .sorted(Comparator.comparing(Artifact::createdAt)).toList();
    }

    /** The first one made with the case's current instructions. A changed or unreadable file is refused. */
    private Optional<KnownNames> stored(Document document) {
        String id = null;
        for (Artifact artifact : all(document)) {
            if (!artifact.isAvailable()) continue;
            byte[] bytes;
            try (InputStream in = storage.retrieve(artifact.storageReference())) {
                bytes = in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("The document's names could not be read", e);
            }
            if (!checksums.compute(new ByteArrayInputStream(bytes)).matches(artifact.checksum())) {
                throw new IllegalStateException("The document's names do not match their checksum");
            }
            List<String> lines = new String(bytes, StandardCharsets.UTF_8).lines().toList();
            if (lines.size() < 3 || !FORMAT.equals(lines.get(0))) {
                throw new IllegalStateException("Unknown format of a document's names");
            }
            if (id == null) id = instructionsId(document);
            if (!lines.get(1).equals(id)) continue;
            return Optional.of(new KnownNames(lines.subList(3, lines.size()), FindingOrigin.decodeModels(lines.get(2))));
        }
        return Optional.empty();
    }

    private void save(Document document, KnownNames names) {
        List<AiModel> models = names.models();
        byte[] bytes = (FORMAT + "\n" + instructionsId(document) + "\n"
                + (models.isEmpty() ? "" : FindingOrigin.encodeModels(models)) + "\n"
                + String.join("\n", names.forms())).getBytes(StandardCharsets.UTF_8);
        Artifact artifact = Artifact.create(document.tenantId(), document.id(), ArtifactType.DOCUMENT_SUMMARY,
                MimeType.of("application/octet-stream"), bytes.length, checksums.compute(new ByteArrayInputStream(bytes)));
        artifact.markAvailable(storage.storeForPackage(document.tenantId(), document.packageId(), document.tenantId() + "/"
                + document.packageId() + "/" + document.id() + "/" + ArtifactType.DOCUMENT_SUMMARY.name() + "/"
                + artifact.id(), new ByteArrayInputStream(bytes), bytes.length, "application/octet-stream"));
        artifacts.save(artifact);
    }
}

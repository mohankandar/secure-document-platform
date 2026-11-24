package com.securedoc.benchmark.textpath;

import com.securedoc.benchmark.score.Prediction;
import com.securedoc.platform.ai.analysis.OllamaSensitiveContentAnalysisAdapter;
import com.securedoc.platform.application.detection.command.DetectSensitiveDataCommand;
import com.securedoc.platform.application.detection.service.SensitiveDataDetectionService;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.DocumentRepository;
import com.securedoc.platform.application.port.out.FindingRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.SensitiveContentAnalysisPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.port.out.TextExtractionPort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.document.Page;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.ArtifactId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import com.securedoc.platform.infrastructure.detection.LocalRuleBasedDetectionAdapter;
import com.securedoc.platform.infrastructure.extraction.HybridPdfTextExtractionAdapter;
import com.securedoc.platform.infrastructure.extraction.PdfBoxTextExtractionAdapter;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrAdapter;
import com.securedoc.platform.infrastructure.ocr.TesseractOcrProperties;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;

/**
 * Runs the product's text path on one PDF, as the product runs it today: the product's
 * {@link SensitiveDataDetectionService} with the product's extraction (text layer, else Tesseract), the
 * product's rule-based detectors and the product's Ollama text-model adapter, all with the product's
 * default settings (application.yml and Start-LexaVault-Local.ps1). Only the storage and the database are
 * replaced by in-memory stand-ins. Findings are kept as type, page and box; the product keeps no value
 * in a finding.
 *
 * <p>One deliberate difference: the product stops a case when a page's OCR confidence is below 0.55
 * ({@code minimum-page-confidence}). The benchmark lowers that to 0 so it can see what the text path would
 * find on such pages; which pages would have stopped the case is reported from the routing measurements.
 */
public final class TextPathRunner {

    public record Result(List<Prediction> findings, double extractionSeconds, double modelSeconds,
                         double totalSeconds, int ocrPages, String failure) {
    }

    private final String ollamaUrl;
    private final String model;
    private final boolean allKinds;

    public TextPathRunner(String ollamaUrl, String model) {
        this(ollamaUrl, model, false);
    }

    /**
     * @param allKinds task 0043: true switches the opt-in pattern kinds on (routing, account, ITIN, EIN, driver's
     *                 license, passport) next to the V1 four, as a Business Admin would; false is the product default.
     */
    public TextPathRunner(String ollamaUrl, String model, boolean allKinds) {
        this.ollamaUrl = ollamaUrl;
        this.model = model;
        this.allKinds = allKinds;
    }

    /** The answer-file type for a product finding type (task 0043 kinds map onto the answer files' types). */
    public static String answerType(String productType) {
        return switch (productType) {
            case "BANK_ROUTING_NUMBER" -> "ROUTING_NUMBER";
            case "BANK_ACCOUNT_NUMBER" -> "BANK_ACCOUNT";
            case "DRIVERS_LICENSE_NUMBER", "PASSPORT_NUMBER" -> "GOVERNMENT_ID";
            default -> productType;
        };
    }

    public static TesseractOcrProperties productOcrProperties() {
        TesseractOcrProperties p = new TesseractOcrProperties();
        p.setMinimumPageConfidence(0.0);
        return p;
    }

    public Result run(byte[] pdf, int pageCount) {
        TenantId tenantId = TenantId.of(UUID.randomUUID());
        DocumentPackage pkg = DocumentPackage.create(tenantId, PackageType.MANUAL_UPLOAD,
                ActorId.of(UUID.randomUUID()), PackageMetadata.empty());
        Document document = Document.reconstitute(DocumentId.of(UUID.randomUUID()), tenantId, pkg.id(),
                FileName.of("sample.pdf"), MimeType.pdf(), pdf.length, Checksum.sha256("benchmark"), DocumentType.PDF,
                Instant.now(), DocumentStatus.DETECTING,
                new ArrayList<>(IntStream.rangeClosed(1, pageCount).mapToObj(n -> Page.of(n, 0, 0)).toList()),
                Instant.now());
        pkg.addDocument(document);
        pkg.submit();
        pkg.startProcessing();
        if (pkg.status() != PackageStatus.PROCESSING) throw new IllegalStateException("package not processing");
        Artifact original = Artifact.create(tenantId, document.id(), ArtifactType.ORIGINAL, MimeType.pdf(),
                pdf.length, Checksum.sha256("o"));
        original.markAvailable(ArtifactStorageReference.local("original.pdf"));

        TesseractOcrProperties ocrProperties = productOcrProperties();
        HybridPdfTextExtractionAdapter hybrid = new HybridPdfTextExtractionAdapter(new PdfBoxTextExtractionAdapter(),
                new TesseractOcrAdapter(ocrProperties), ocrProperties);
        long[] extractionNanos = {0};
        int[] ocrPages = {0};
        TextExtractionPort timedExtraction = (content, mime) -> {
            long t = System.nanoTime();
            TextExtractionPort.TextExtractionOutput out = hybrid.extract(content, mime);
            extractionNanos[0] += System.nanoTime() - t;
            ocrPages[0] = (int) out.ocrPageCount();
            return out;
        };
        // Product defaults: application.yml (timeout 60 s, 2 attempts, 500 ms back-off, structured output on,
        // thinking off, page batch 1, 14 000 characters) with the local start script's parallelism 2 and keep-alive 30m.
        OllamaSensitiveContentAnalysisAdapter ai = new OllamaSensitiveContentAnalysisAdapter(ollamaUrl, model,
                Duration.ofSeconds(60), 2, 2, Duration.ofMillis(500), "30m", true, false, 0, 0, 1, 14_000);
        long[] modelNanos = {0};
        SensitiveContentAnalysisPort timedAi = request -> {
            long t = System.nanoTime();
            try {
                return ai.analyze(request);
            } finally {
                modelNanos[0] += System.nanoTime() - t;
            }
        };
        List<Finding> saved = new ArrayList<>();
        LocalRuleBasedDetectionAdapter rules = new LocalRuleBasedDetectionAdapter();
        SensitiveDataDetectionService service = new SensitiveDataDetectionService(
                new Documents(document), new Packages(pkg), new Artifacts(original), new Storage(pdf),
                timedAi, rules, rules, new Policies(allKinds), timedExtraction, new Findings(saved),
                event -> { }, new AuditService(record -> { }));

        long started = System.nanoTime();
        String failure = null;
        try {
            service.execute(DetectSensitiveDataCommand.of(document.id(), pkg.id(), tenantId, CorrelationId.generate()));
        } catch (RuntimeException e) {
            // The product's exceptions carry no value; keep only the type.
            failure = e.getClass().getSimpleName();
        }
        double total = (System.nanoTime() - started) / 1e9;
        List<Prediction> predictions = new ArrayList<>();
        for (Finding f : saved) {
            var box = f.location().boundingBox();
            predictions.add(new Prediction(f.location().pageNumber(), answerType(f.entityType().name()),
                    box == null ? null : new double[]{box.left(), box.top(), box.width(), box.height()},
                    "text:" + f.detectorSource().detectorName()));
        }
        return new Result(predictions, extractionNanos[0] / 1e9, modelNanos[0] / 1e9, total, ocrPages[0], failure);
    }

    // --- in-memory stand-ins for the database and storage -------------------------------------------

    private record Documents(Document document) implements DocumentRepository {
        @Override public void save(Document d) { }
        @Override public Optional<Document> findByIdAndTenantId(DocumentId id, TenantId t) {
            return document.id().equals(id) ? Optional.of(document) : Optional.empty();
        }
        @Override public List<Document> findByPackageIdAndTenantId(PackageId p, TenantId t) { return List.of(document); }
    }

    private record Packages(DocumentPackage pkg) implements PackageRepository {
        @Override public void save(DocumentPackage p) { }
        @Override public Optional<DocumentPackage> findByIdAndTenantId(PackageId id, TenantId t) {
            return pkg.id().equals(id) ? Optional.of(pkg) : Optional.empty();
        }
        @Override public List<DocumentPackage> findByTenantId(TenantId t, int offset, int limit) { return List.of(pkg); }
        @Override public long countByTenantId(TenantId t) { return 1; }
        @Override public Optional<DocumentPackage> findByTenantIdAndIntakeFingerprint(TenantId t, String f) { return Optional.empty(); }
        @Override public List<DocumentPackage> findByStatus(PackageStatus s, int limit) { return List.of(); }
    }

    private record Artifacts(Artifact original) implements ArtifactRepository {
        @Override public void save(Artifact a) { }
        @Override public Optional<Artifact> findByIdAndTenantId(ArtifactId id, TenantId t) { return Optional.of(original); }
        @Override public List<Artifact> findByDocumentIdAndTenantId(DocumentId d, TenantId t) { return List.of(original); }
    }

    private record Storage(byte[] pdf) implements StoragePort {
        @Override public ArtifactStorageReference store(TenantId t, String path, InputStream c, long s, String m) {
            throw new UnsupportedOperationException();
        }
        @Override public InputStream retrieve(ArtifactStorageReference r) { return new ByteArrayInputStream(pdf); }
        @Override public void delete(ArtifactStorageReference r) { }
    }

    /** No stored policy (the product default), or the default with every configurable kind switched on. */
    private record Policies(boolean allKinds) implements TenantRedactionPolicyRepository {
        @Override public Optional<TenantRedactionPolicy> findByTenantId(TenantId t) {
            if (!allKinds) return Optional.empty();
            TenantRedactionPolicy d = TenantRedactionPolicy.defaults(t);
            return Optional.of(new TenantRedactionPolicy(t, com.securedoc.platform.domain.business.RedactionCatalog.kinds(),
                    d.autoRedactThreshold(), d.reviewMode(), d.maskSsnLastFour(), d.updatedAt()));
        }
        @Override public void save(TenantRedactionPolicy p) { }
    }

    private record Findings(List<Finding> saved) implements FindingRepository {
        @Override public void save(Finding f) { saved.add(f); }
        @Override public void saveAll(List<Finding> f) { saved.addAll(f); }
        @Override public Optional<Finding> findByIdAndTenantId(FindingId id, TenantId t) { return Optional.empty(); }
        @Override public List<Finding> findByDocumentIdAndTenantId(DocumentId d, TenantId t) { return List.of(); }
        @Override public void deleteByDocumentIdAndTenantId(DocumentId d, TenantId t) { }
    }
}

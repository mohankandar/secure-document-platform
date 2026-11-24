package com.securedoc.platform.application.vision;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.PageImagePort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.VisionModelPort;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.business.VisionSettings;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.vision.VisionPageResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Task 0064: reads one routed page with the vision model. The page image and the model's answer live in memory only.
 * A page that cannot be read is a result (it goes to a person); only a missing or unreadable original is a failure.
 */
public final class VisionPageReader {
    private static final Logger log = LoggerFactory.getLogger(VisionPageReader.class);

    private final ArtifactRepository artifacts;
    private final StoragePort storage;
    private final PageImagePort images;
    private final VisionModelPort model;

    public VisionPageReader(ArtifactRepository artifacts, StoragePort storage, PageImagePort images,
                            VisionModelPort model) {
        this.artifacts = Objects.requireNonNull(artifacts);
        this.storage = Objects.requireNonNull(storage);
        this.images = Objects.requireNonNull(images);
        this.model = Objects.requireNonNull(model);
    }

    public VisionPageResult read(Document document, VisionSettings settings, int page) {
        if (!settings.enabled()) return VisionPageResult.notRead(page, null, "VISION_OFF");
        Artifact original = artifacts.findByDocumentIdAndTenantId(document.id(), document.tenantId()).stream()
                .filter(a -> a.artifactType() == ArtifactType.ORIGINAL && a.isAvailable())
                .findFirst()
                .orElseThrow(() -> new NotFoundException("Original artifact for document",
                        document.id().toString(), LvErrorCode.PRC_007));
        long started = System.nanoTime();
        InputStream content = storage.retrieve(original.storageReference());
        VisionPageResult result;
        try (content) {
            PageImagePort.PageImage image = images.render(content, page, VisionModelPort.IMAGE_LONG_SIDE);
            result = model.read(image, page);
        } catch (IOException | RuntimeException notRendered) {
            if (Thread.currentThread().isInterrupted()) {
                // The server is stopping: nothing is known about the page, so nothing is recorded about it.
                throw new IllegalStateException("Vision page reading was interrupted");
            }
            log.warn("VISION_PAGE_NOT_RENDERED document={} page={} type={}", document.id(), page,
                    notRendered.getClass().getSimpleName());
            result = VisionPageResult.notRead(page, LvErrorCode.PRC_006.code(), "PAGE_NOT_PREPARED");
        }
        // Page number, counts and codes only.
        log.info("VISION_PAGE_{} document={} page={} items={} incomplete={} reasonCode={} code={} durationMs={}",
                result.read() ? "READ" : "NOT_READ", document.id(), page, result.items().size(), result.incomplete(),
                result.reason(), result.code(), (System.nanoTime() - started) / 1_000_000L);
        return result;
    }
}

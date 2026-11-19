package com.securedoc.platform.application.review.service;

import com.securedoc.platform.application.extraction.service.TextExtractionService;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.review.result.ReviewFoundText;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.finding.Finding;
import com.securedoc.platform.domain.finding.FindingLocation;
import com.securedoc.platform.domain.identity.FindingId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Reads the text a pending finding covers from the document's stored EXTRACTED_TEXT artifact,
 * using the finding's page and character offsets. Nothing is stored or cached beyond one call.
 *
 * <p>Fails closed: whenever the text cannot be read with certainty (no single available text
 * artifact, a page count that does not match the document, offsets outside the page, an
 * unlocated finding, an over-long or blank span, or any read error) the finding gets no text and the
 * UI hides the row. Log lines name the document and a reason code only, never any text.
 */
public final class ReviewFoundTextReader {

    private static final Logger log = LoggerFactory.getLogger(ReviewFoundTextReader.class);

    static final int MAX_TEXT_LENGTH = 200;
    static final int CONTEXT_LENGTH = 40;
    static final long MAX_ARTIFACT_BYTES = 20L * 1024 * 1024;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern PAGE_SPLIT = Pattern.compile(Pattern.quote(TextExtractionService.PAGE_SEPARATOR));

    private final ArtifactRepository artifactRepository;
    private final StoragePort storagePort;

    public ReviewFoundTextReader(ArtifactRepository artifactRepository, StoragePort storagePort) {
        this.artifactRepository = Objects.requireNonNull(artifactRepository);
        this.storagePort = Objects.requireNonNull(storagePort);
    }

    /** Returns the text for each finding that can be read safely; findings left out get none. */
    public Map<FindingId, ReviewFoundText> read(Document document, List<Finding> findings) {
        Objects.requireNonNull(document);
        Objects.requireNonNull(findings);
        Map<FindingId, ReviewFoundText> result = new HashMap<>();
        if (findings.isEmpty()) {
            return result;
        }
        String[] pages = loadPages(document);
        if (pages == null) {
            return result;
        }
        for (Finding finding : findings) {
            if (!finding.documentId().equals(document.id())) {
                continue;
            }
            ReviewFoundText text = slice(pages, finding.location());
            if (text != null) {
                result.put(finding.id(), text);
            }
        }
        return result;
    }

    private String[] loadPages(Document document) {
        List<Artifact> available = artifactRepository
                .findByDocumentIdAndTenantId(document.id(), document.tenantId()).stream()
                .filter(a -> a.artifactType() == ArtifactType.EXTRACTED_TEXT && a.isAvailable())
                .toList();
        if (available.size() != 1) {
            log.info("REVIEW_FOUND_TEXT_SKIPPED document={} reason=TEXT_ARTIFACT_COUNT count={}",
                    document.id(), available.size());
            return null;
        }
        Artifact artifact = available.get(0);
        if (artifact.sizeBytes() > MAX_ARTIFACT_BYTES) {
            log.info("REVIEW_FOUND_TEXT_SKIPPED document={} reason=TEXT_ARTIFACT_TOO_LARGE", document.id());
            return null;
        }
        String fullText;
        try (InputStream stream = storagePort.retrieve(artifact.storageReference())) {
            fullText = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("REVIEW_FOUND_TEXT_SKIPPED document={} reason=READ_FAILED error={}",
                    document.id(), e.getClass().getSimpleName());
            return null;
        }
        String[] pages = PAGE_SPLIT.split(fullText, -1);
        if (document.pageCount() < 1 || pages.length != document.pageCount()) {
            // A page text that itself contains the separator, or a stale artifact, would shift
            // every offset. Refuse rather than show the wrong text.
            log.info("REVIEW_FOUND_TEXT_SKIPPED document={} reason=PAGE_COUNT_MISMATCH", document.id());
            return null;
        }
        return pages;
    }

    static ReviewFoundText slice(String[] pages, FindingLocation location) {
        // Unlocated findings carry offsets 0..0 and are refused by the offset check below.
        if (location == null) {
            return null;
        }
        int pageNumber = location.pageNumber();
        if (pageNumber < 1 || pageNumber > pages.length) {
            return null;
        }
        String page = pages[pageNumber - 1];
        int start = location.startOffset();
        int end = location.endOffset();
        if (start < 0 || end <= start || end > page.length() || end - start > MAX_TEXT_LENGTH) {
            return null;
        }
        // A span may cross a line (an address); show it on one line.
        String text = WHITESPACE.matcher(page.substring(start, end)).replaceAll(" ").strip();
        if (text.isEmpty()) {
            return null;
        }
        String before = page.substring(Math.max(0, start - CONTEXT_LENGTH), start);
        int lineBreak = Math.max(before.lastIndexOf('\n'), before.lastIndexOf('\r'));
        if (lineBreak >= 0) {
            before = before.substring(lineBreak + 1);
        }
        String after = page.substring(end, Math.min(page.length(), end + CONTEXT_LENGTH));
        int afterBreak = firstLineBreak(after);
        if (afterBreak >= 0) {
            after = after.substring(0, afterBreak);
        }
        return new ReviewFoundText(text, before.stripLeading(), after.stripTrailing());
    }

    private static int firstLineBreak(String value) {
        int n = value.indexOf('\n');
        int r = value.indexOf('\r');
        if (n < 0) return r;
        if (r < 0) return n;
        return Math.min(n, r);
    }
}

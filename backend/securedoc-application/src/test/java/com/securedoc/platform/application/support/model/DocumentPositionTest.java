package com.securedoc.platform.application.support.model;

import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentStatus;
import com.securedoc.platform.domain.document.DocumentType;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0038: "document 2 of 3, page 14" for Support, in upload order, never the file name. */
class DocumentPositionTest {
    private final TenantId tenant = TenantId.generate();
    private final PackageId pkg = PackageId.generate();
    private final Instant t0 = Instant.parse("2026-11-22T10:00:00Z");

    @Test
    void thePositionFollowsUploadOrderAndThePageIsShownOnlyWhenRecorded() {
        Document first = doc("Zebra.pdf", t0);
        Document second = doc("Alpha.pdf", t0.plusSeconds(5));
        Document third = doc("Middle.pdf", t0.plusSeconds(9));
        List<Document> shuffled = List.of(third, first, second);

        assertEquals(Optional.of(new DocumentPosition(2, 3, 14)),
                DocumentPosition.of(shuffled, second.id(), Map.of("page", "14")));
        assertEquals(Optional.of(new DocumentPosition(1, 3, null)),
                DocumentPosition.of(shuffled, first.id(), Map.of()), "no page recorded: none shown");
        assertEquals(Optional.of(new DocumentPosition(3, 3, 7)),
                DocumentPosition.of(shuffled, third.id(), Map.of("pageNumber", "7")));
        for (String odd : new String[] {"0", "-1", "14 of Title_Commitment.pdf", "x"}) {
            assertNull(DocumentPosition.of(shuffled, first.id(), Map.of("page", odd)).orElseThrow().page(), odd);
        }
        assertFalse(DocumentPosition.of(shuffled, second.id(), Map.of()).orElseThrow().toString().contains("Alpha"),
                "never the file name");
    }

    @Test
    void twoFilesUploadedAtTheSameInstantKeepOneStableOrder() {
        Document a = doc("a.pdf", t0);
        Document b = doc("b.pdf", t0);
        int aFirst = DocumentPosition.of(List.of(a, b), a.id(), Map.of()).orElseThrow().position();
        assertEquals(aFirst, DocumentPosition.of(List.of(b, a), a.id(), Map.of()).orElseThrow().position());
    }

    @Test
    void aDocumentThatIsNotInTheCaseGetsNoPosition() {
        assertTrue(DocumentPosition.of(List.of(doc("a.pdf", t0)), DocumentId.generate(), Map.of()).isEmpty());
        assertTrue(DocumentPosition.of(List.of(), DocumentId.generate(), Map.of()).isEmpty());
        assertTrue(DocumentPosition.of(List.of(doc("a.pdf", t0)), null, Map.of()).isEmpty());
    }

    private Document doc(String name, Instant uploadedAt) {
        return Document.reconstitute(DocumentId.of(UUID.randomUUID()), tenant, pkg, FileName.of(name),
                MimeType.of("application/pdf"), 10, Checksum.sha256("a".repeat(64)), DocumentType.PDF, uploadedAt,
                DocumentStatus.UPLOADED, List.of(), uploadedAt);
    }
}

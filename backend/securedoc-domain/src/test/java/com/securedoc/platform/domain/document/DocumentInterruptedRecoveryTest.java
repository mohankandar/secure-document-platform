package com.securedoc.platform.domain.document;

import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DocumentInterruptedRecoveryTest {

    @Test
    void rewindsExtractingDetectingAndRedactingToUploaded() {
        Document extracting = document();
        extracting.startExtraction();
        extracting.recoverInterruptedProcessing();
        assertEquals(DocumentStatus.UPLOADED, extracting.status());
        assertEquals(0, extracting.pageCount());

        Document detecting = document();
        detecting.startExtraction();
        detecting.completeExtraction(List.of(Page.withText(1, 612, 792, "synthetic")));
        detecting.recoverInterruptedProcessing();
        assertEquals(DocumentStatus.UPLOADED, detecting.status());
        assertEquals(0, detecting.pageCount());

        Document redacting = document();
        redacting.startExtraction();
        redacting.completeExtraction(List.of(Page.withText(1, 612, 792, "synthetic")));
        redacting.startRedaction();
        redacting.recoverInterruptedProcessing();
        assertEquals(DocumentStatus.UPLOADED, redacting.status());
        assertEquals(0, redacting.pageCount());
    }

    @Test
    void replaysProcessedDocumentOnlyThroughMissingOutputRecovery() {
        Document processed = document();
        processed.startExtraction();
        processed.completeExtraction(List.of(Page.withText(1, 612, 792, "synthetic")));
        processed.startRedaction();
        processed.completeProcessing();

        assertThrows(IllegalStateException.class, processed::recoverInterruptedProcessing);
        processed.recoverMissingProcessedOutput();
        assertEquals(DocumentStatus.UPLOADED, processed.status());
        assertEquals(0, processed.pageCount());
    }

    @Test
    void refusesToRewindNonInflightState() {
        assertThrows(IllegalStateException.class, () -> document().recoverInterruptedProcessing());
        assertThrows(IllegalStateException.class, () -> document().recoverMissingProcessedOutput());
    }

    private Document document() {
        return Document.create(
                TenantId.of(UUID.fromString("11111111-1111-1111-1111-111111111111")),
                PackageId.of(UUID.randomUUID()),
                FileName.of("test.pdf"),
                MimeType.of("application/pdf"),
                100,
                Checksum.sha256("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"),
                DocumentType.PDF);
    }
}

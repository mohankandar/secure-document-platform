package com.securedoc.platform.domain.document;

import com.securedoc.platform.domain.error.CodedStateException;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.domain.vo.FileName;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Task 0104: a case takes its next batch only between batches (delivered), never while one is processing or in Review,
 * and never when it is rejected or cancelled (such a contract gets a new case).
 */
class DocumentPackageBatchTest {

    private final TenantId tenant = TenantId.generate();

    private DocumentPackage pkg(PackageStatus status) {
        return DocumentPackage.reconstitute(PackageId.generate(), tenant, PackageType.FOLDER_INTAKE, null, null, null,
                ActorId.generate(), Instant.now(), status, PackageMetadata.empty(), new ArrayList<>(List.of(processed())), Instant.now());
    }

    private Document uploaded() {
        return Document.create(tenant, PackageId.generate(), FileName.of("b.pdf"), MimeType.of("application/pdf"), 10,
                Checksum.sha256("b".repeat(64)), DocumentType.PDF);
    }

    private Document processed() {
        Document d = uploaded();
        d.startExtraction();
        d.completeExtraction(List.of(Page.withText(1, 612, 792, "synthetic")));
        d.startRedaction();
        d.completeProcessing();
        return d;
    }

    @Test
    void aDeliveredCaseTakesTheNextBatchAndGoesBackToSubmitted() {
        DocumentPackage p = pkg(PackageStatus.COMPLETED);
        assertTrue(p.takesNextBatchNow());
        p.addBatchDocument(uploaded());
        p.submitNextBatch();
        assertEquals(PackageStatus.SUBMITTED, p.status());
        assertEquals(2, p.documents().size(), "the earlier version stays in the case");
    }

    @Test
    void aBusyCaseMakesTheBatchWaitNeverRefusesIt() {
        for (PackageStatus busy : List.of(PackageStatus.SUBMITTED, PackageStatus.PROCESSING, PackageStatus.PENDING_REVIEW,
                PackageStatus.IN_REVIEW, PackageStatus.PENDING_FINAL_APPROVAL, PackageStatus.APPROVED)) {
            DocumentPackage p = pkg(busy);
            assertTrue(p.isOpenForBatches(), busy.name());
            assertFalse(p.takesNextBatchNow(), busy + ": the batch waits for the running one");
            assertThrows(CodedStateException.class, () -> p.addBatchDocument(uploaded()));
        }
    }

    @Test
    void aRejectedOrCancelledCaseTakesNoBatch() {
        for (PackageStatus closed : List.of(PackageStatus.REJECTED, PackageStatus.CANCELLED)) {
            assertFalse(pkg(closed).isOpenForBatches(), closed.name());
            assertFalse(pkg(closed).takesNextBatchNow());
        }
    }

    @Test
    void aBatchWithNothingNewIsNotSubmitted() {
        DocumentPackage p = pkg(PackageStatus.COMPLETED);
        assertThrows(CodedStateException.class, p::submitNextBatch, "every file was a skipped copy: nothing to process");
        assertEquals(PackageStatus.COMPLETED, p.status());
    }
}

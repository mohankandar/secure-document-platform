package com.securedoc.platform.domain.delivery;

import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.Checksum;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0028: the rebuild keeps the identities the destination knows the case run by, and drops its answers. */
class DeliverySubmissionRebuildTest {

    @Test
    void aRebuildKeepsTheIdempotencyIdentitiesAndDropsWhatTheDestinationAnswered() {
        DocumentId doc = DocumentId.generate();
        DeliverySubmission submission = DeliverySubmission.create(TenantId.generate(), PackageId.generate(), 1, "sdv",
                List.of(new DeliverySubmission.NewDocument(doc, "a.pdf", 10, Checksum.sha256("a".repeat(64)))),
                Instant.now());
        submission.startAttempt(Instant.now());
        submission.recordDestinationSubmissionReference("remote-1", Instant.now());
        submission.acknowledgeDocument(doc, "remote-doc", "sdv://remote-1/remote-doc", Instant.now());
        submission.markFailure("DELIVERY_IO", true, Instant.now());

        DeliverySubmission rebuilt = submission.rebuild(
                List.of(new DeliverySubmission.NewDocument(doc, "a.pdf", 10, Checksum.sha256("a".repeat(64)))),
                Instant.now());

        assertEquals(submission.id(), rebuilt.id());
        assertEquals(submission.sourceSubmissionId(), rebuilt.sourceSubmissionId());
        assertEquals(submission.exportCycle(), rebuilt.exportCycle());
        assertEquals(submission.documents().get(0).sourceDocumentId(), rebuilt.documents().get(0).sourceDocumentId());
        assertNull(rebuilt.destinationSubmissionReference());
        assertFalse(rebuilt.documents().get(0).acknowledged());
        assertEquals(DeliverySubmissionStatus.RETRY_PENDING, rebuilt.status());
        assertEquals(submission.attemptCount(), rebuilt.attemptCount());
    }

    @Test
    void aRebuildRefusesOtherDocumentsAndADeliveredRun() {
        DocumentId doc = DocumentId.generate();
        DeliverySubmission submission = DeliverySubmission.create(TenantId.generate(), PackageId.generate(), 1, "sdv",
                List.of(new DeliverySubmission.NewDocument(doc, "a.pdf", 10, Checksum.sha256("a".repeat(64)))),
                Instant.now());
        assertThrows(IllegalStateException.class, () -> submission.rebuild(List.of(new DeliverySubmission.NewDocument(
                DocumentId.generate(), "b.pdf", 10, Checksum.sha256("b".repeat(64)))), Instant.now()));
        assertThrows(IllegalStateException.class, () -> submission.rebuild(List.of(), Instant.now()));

        submission.startAttempt(Instant.now());
        submission.acknowledgeDocument(doc, "r", "l", Instant.now());
        submission.markDelivered("remote", Instant.now());
        assertThrows(IllegalStateException.class, () -> submission.rebuild(List.of(new DeliverySubmission.NewDocument(
                doc, "a.pdf", 10, Checksum.sha256("a".repeat(64)))), Instant.now()));
    }
}

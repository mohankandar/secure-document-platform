package com.securedoc.platform.domain.delivery;

import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.vo.Checksum;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DeliverySubmissionTest {
    @Test
    void preservesExternalIdentitiesAcrossRetryAndRequiresAllAcknowledgements() {
        TenantId tenant=TenantId.of(UUID.randomUUID()); PackageId pkg=PackageId.of(UUID.randomUUID());
        DocumentId a=DocumentId.of(UUID.randomUUID()), b=DocumentId.of(UUID.randomUUID());
        var submission=DeliverySubmission.create(tenant,pkg,1,"document-store", List.of(
                new DeliverySubmission.NewDocument(a,"a.pdf",10, Checksum.of("SHA-256","aa")),
                new DeliverySubmission.NewDocument(b,"b.pdf",11, Checksum.of("SHA-256","bb"))), Instant.parse("2026-09-03T10:00:00Z"));
        String sourceSubmission=submission.sourceSubmissionId();
        String sourceA=submission.findDocument(a).orElseThrow().sourceDocumentId();
        assertEquals(1, submission.startAttempt(Instant.parse("2026-09-03T10:01:00Z")));
        submission.recordDestinationSubmissionReference("store-sub-1",Instant.parse("2026-09-03T10:01:01Z"));
        submission.acknowledgeDocument(a,"store-doc-a","store://store-sub-1/a",Instant.parse("2026-09-03T10:01:02Z"));
        assertFalse(submission.allAcknowledged());
        submission.markFailure("DELIVERY_IO",true,Instant.parse("2026-09-03T10:01:03Z"));
        assertEquals(DeliverySubmissionStatus.RETRY_PENDING,submission.status());
        assertEquals(2,submission.startAttempt(Instant.parse("2026-09-03T10:02:00Z")));
        assertEquals(sourceSubmission,submission.sourceSubmissionId());
        assertEquals(sourceA,submission.findDocument(a).orElseThrow().sourceDocumentId());
        assertEquals(1,submission.pendingDocuments().size());
        submission.acknowledgeDocument(b,"store-doc-b","store://store-sub-1/b",Instant.parse("2026-09-03T10:02:01Z"));
        submission.markDelivered("store-sub-1",Instant.parse("2026-09-03T10:02:02Z"));
        assertEquals(DeliverySubmissionStatus.DELIVERED,submission.status());
        assertThrows(IllegalStateException.class,()->submission.startAttempt(Instant.now()));
    }
}

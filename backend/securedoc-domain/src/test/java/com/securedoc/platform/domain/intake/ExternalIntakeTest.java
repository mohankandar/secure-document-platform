package com.securedoc.platform.domain.intake;

import com.securedoc.platform.domain.identity.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ExternalIntakeTest {
    private final TenantId tenantId = TenantId.generate();
    private final IntegrationConnectionId connectionId = IntegrationConnectionId.generate();
    private final PackageId packageId = PackageId.generate();
    private final Instant created = Instant.parse("2026-09-02T12:00:00Z");
    private final Instant expires = created.plusSeconds(24 * 3600);

    @Test
    void completesOnlyAfterEveryManifestDocumentIsReceivedAndDeadlineDoesNotSlide() {
        ExternalIntake intake = intake();
        assertEquals(ExternalIntakeStatus.AWAITING_DOCUMENTS, intake.status());
        assertEquals(expires, intake.expiresAt());

        intake.recordReceived("DOC-1", DocumentId.generate(), hex('a'), "key-1", created.plusSeconds(60));
        assertEquals(ExternalIntakeStatus.AWAITING_DOCUMENTS, intake.status());
        assertEquals(1, intake.receivedCount());
        assertEquals(List.of("DOC-2"), intake.missingExternalSourceDocumentIds());
        assertEquals(expires, intake.expiresAt(), "receipt must not extend the fixed deadline");

        intake.recordReceived("DOC-2", DocumentId.generate(), hex('b'), "key-2", created.plusSeconds(120));
        assertEquals(ExternalIntakeStatus.ACCEPTED_FOR_PROCESSING, intake.status());
        assertEquals(2, intake.receivedCount());
        assertTrue(intake.missingExternalSourceDocumentIds().isEmpty());
        assertNotNull(intake.acceptedAt());
    }

    @Test
    void idempotencyKeyCannotBeReusedForAnotherManifestDocument() {
        ExternalIntake intake = intake();
        intake.recordReceived("DOC-1", DocumentId.generate(), hex('a'), "same-key", created.plusSeconds(60));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> intake.ensureDocumentReceiptAllowed("DOC-2", "same-key", created.plusSeconds(120)));
        assertTrue(error.getMessage().contains("Idempotency key"));
    }

    @Test
    void intakeExpiresAtFixedDeadlineAndRejectsLateReceipt() {
        ExternalIntake intake = intake();
        assertTrue(intake.expireIfDue(expires));
        assertEquals(ExternalIntakeStatus.INCOMPLETE_EXPIRED, intake.status());
        assertNotNull(intake.expiredAt());
        assertThrows(IllegalStateException.class,
                () -> intake.recordReceived("DOC-1", DocumentId.generate(), hex('a'), "key", expires.plusSeconds(1)));
    }

    @Test
    void duplicateExternalDocumentIdsInManifestAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> ExternalIntake.create(packageId, tenantId, connectionId,
                ExternalIntakeChannel.PUSH_API, "PKG-1", "1", hex('f'), created, expires,
                List.of(ExternalManifestDocument.expected("DOC-1", "a.pdf", null),
                        ExternalManifestDocument.expected("DOC-1", "b.pdf", null))));
    }

    private ExternalIntake intake() {
        return ExternalIntake.create(packageId, tenantId, connectionId, ExternalIntakeChannel.PUSH_API,
                "PKG-1", "1", hex('f'), created, expires,
                List.of(ExternalManifestDocument.expected("DOC-1", "a.pdf", null),
                        ExternalManifestDocument.expected("DOC-2", "b.pdf", null)));
    }

    private static String hex(char value) { return String.valueOf(value).repeat(64); }
}

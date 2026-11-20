package com.securedoc.platform.application.service;

import com.securedoc.platform.application.audit.AuditCaseIds;
import com.securedoc.platform.application.audit.RequestActorDisplayName;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Task 0016 A1/A6: display name and case ID recorded at the time of the action, without touching call sites. */
class AuditServiceActorNameCaseIdTest {

    private static final ActorId SYSTEM = ActorId.of(RequestActorDisplayName.SYSTEM_ACTOR_ID);

    private final RequestActorDisplayName names = new RequestActorDisplayName();
    private final List<AuditRecord> records = new ArrayList<>();
    private final AuditService audit = new AuditService(records::add, names);
    private final TenantId tenant = TenantId.generate();
    private final ActorId person = ActorId.generate();

    @AfterEach
    void clear() {
        names.clear();
    }

    @Test
    void signedInPersonGetsTheDisplayNameClaim() {
        names.bind(person, "Dana Reviewer", "dreviewer");
        record(person, "Finding", UUID.randomUUID().toString(), "Finding confirmed: type=SSN");
        assertEquals("Dana Reviewer", last().actorDisplayName());
    }

    @Test
    void usernameIsUsedWhenThereIsNoNameClaim() {
        names.bind(person, null, "dreviewer");
        record(person, "Finding", UUID.randomUUID().toString(), null);
        assertEquals("dreviewer", last().actorDisplayName());

        names.bind(person, "   ", "dreviewer");
        record(person, "Finding", UUID.randomUUID().toString(), null);
        assertEquals("dreviewer", last().actorDisplayName());
    }

    @Test
    void systemWorkRecordsNoDisplayName() {
        names.bind(person, "Dana Reviewer", "dreviewer");
        record(SYSTEM, "Document", UUID.randomUUID().toString(), "Detection completed: findings=1");
        assertNull(last().actorDisplayName(), "a system record inside a person's request has no name");

        names.bind(SYSTEM, "LexaVault", "system");
        record(SYSTEM, "Document", UUID.randomUUID().toString(), null);
        assertNull(last().actorDisplayName(), "the system actor can never be given a name");
    }

    @Test
    void noNameForAnotherActorOrWhenNothingIsBound() {
        record(person, "Finding", UUID.randomUUID().toString(), null);
        assertNull(last().actorDisplayName(), "nothing bound");

        names.bind(person, "Dana Reviewer", "dreviewer");
        record(ActorId.generate(), "Finding", UUID.randomUUID().toString(), null);
        assertNull(last().actorDisplayName(), "a different actor never gets this person's name");

        names.clear();
        record(person, "Finding", UUID.randomUUID().toString(), null);
        assertNull(last().actorDisplayName(), "cleared at the end of the request");
    }

    @Test
    void anotherThreadNeverSeesTheName() throws InterruptedException {
        names.bind(person, "Dana Reviewer", "dreviewer");
        AtomicReference<AuditRecord> seen = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            audit.recordSuccess(tenant, person, AuditAction.DOCUMENT_EXTRACTION_COMPLETED, "Document",
                    UUID.randomUUID().toString(), CorrelationId.generate(), null);
            seen.set(records.getLast());
        });
        worker.start();
        worker.join();
        assertNull(seen.get().actorDisplayName());
    }

    @Test
    void longNamesAreCutToTheColumnWidthAndControlCharactersDropped() {
        names.bind(person, "A".repeat(250), "x");
        record(person, "Finding", UUID.randomUUID().toString(), null);
        assertEquals(200, last().actorDisplayName().length());

        names.bind(person, "Dana\r\nReviewer\t", "x");
        record(person, "Finding", UUID.randomUUID().toString(), null);
        assertEquals("DanaReviewer", last().actorDisplayName());
    }

    @Test
    void caseActionRecordsTheCaseId() {
        UUID packageId = UUID.randomUUID();
        names.bind(person, "Dana Reviewer", "dreviewer");
        record(person, "DocumentPackage", packageId.toString(), "Package approved");
        assertEquals(packageId.toString(), last().caseId());
        assertEquals("Dana Reviewer", last().actorDisplayName());

        UUID created = UUID.randomUUID();
        record(person, "IntakeBatch", created.toString(), "Intake batch created: channel=MANUAL_UPLOAD");
        assertEquals(created.toString(), last().caseId(), "the case's creation record (PACKAGE_CREATED)");
    }

    @Test
    void somethingInsideACaseRecordsTheCaseIdWhenTheDetailNamesIt() {
        UUID packageId = UUID.randomUUID();
        record(SYSTEM, "ReviewTask", UUID.randomUUID().toString(), "Review task created for package=" + packageId);
        assertEquals(packageId.toString(), last().caseId());
        record(SYSTEM, "Document", UUID.randomUUID().toString(), "Something: packageId=" + packageId.toString().toUpperCase());
        assertEquals(packageId.toString(), last().caseId(), "normalized to lower case");
    }

    @Test
    void noCaseIdWhenItCannotBeFoundWithoutAQuery() {
        record(person, "Finding", UUID.randomUUID().toString(), "Finding confirmed: type=SSN");
        assertNull(last().caseId());
        record(person, "SensitiveDataPolicy", tenant.toString(), "Sensitive-data policy changed: enabledCount=4");
        assertNull(last().caseId());
        record(person, "DocumentPackage", "not-a-uuid", null);
        assertNull(last().caseId(), "anything that is not a UUID is refused, not guessed");
    }

    @Test
    void caseIdRuleRefusesLookalikes() {
        UUID id = UUID.randomUUID();
        assertNull(AuditCaseIds.caseIdFor("Document", "x", "subpackage=" + id), "word boundary");
        assertNull(AuditCaseIds.caseIdFor("Document", "x", "package=" + id + "ab"), "longer hex run");
        assertNull(AuditCaseIds.caseIdFor("Document", "x", "package=" + id.toString().substring(0, 30)));
        assertNull(AuditCaseIds.caseIdFor(null, null, null));
        assertEquals(id.toString(), AuditCaseIds.caseIdFor("Package", id.toString(), null));
    }

    @Test
    void legacyConstructorRecordsNoNameButStillTheCaseId() {
        List<AuditRecord> plain = new ArrayList<>();
        names.bind(person, "Dana Reviewer", "dreviewer");
        UUID packageId = UUID.randomUUID();
        new AuditService(plain::add).recordSuccess(tenant, person, AuditAction.PACKAGE_APPROVED, "DocumentPackage",
                packageId.toString(), CorrelationId.generate(), null);
        assertNull(plain.getLast().actorDisplayName());
        assertEquals(packageId.toString(), plain.getLast().caseId());
    }

    private void record(ActorId actor, String resourceType, String resourceId, String detail) {
        audit.recordSuccess(tenant, actor, AuditAction.PACKAGE_APPROVED, resourceType, resourceId,
                CorrelationId.generate(), detail);
    }

    private AuditRecord last() {
        return records.getLast();
    }
}

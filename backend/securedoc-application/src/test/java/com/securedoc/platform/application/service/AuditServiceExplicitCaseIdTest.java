package com.securedoc.platform.application.service;

import com.securedoc.platform.application.audit.RequestActorDisplayName;
import com.securedoc.platform.application.audit.RequestActorDisplayName.MachineClientKind;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditOutcome;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Task 0017 A1 and A2: the explicit case ID overload, and machine clients recorded by their configured name. */
class AuditServiceExplicitCaseIdTest {

    private static final ActorId SYSTEM = ActorId.of(RequestActorDisplayName.SYSTEM_ACTOR_ID);

    private final RequestActorDisplayName names = new RequestActorDisplayName();
    private final List<AuditRecord> records = new ArrayList<>();
    private final AuditService audit = new AuditService(records::add, names);
    private final TenantId tenant = TenantId.generate();

    @AfterEach
    void clear() {
        names.clear();
    }

    @Test
    void explicitCaseIdIsRecordedForEveryOutcome() {
        PackageId caseId = PackageId.of(UUID.randomUUID());
        String documentId = UUID.randomUUID().toString();
        audit.recordSuccess(tenant, SYSTEM, AuditAction.DOCUMENT_EXTRACTION_COMPLETED, "Document", documentId, caseId,
                CorrelationId.generate(), "Extraction completed: pages=1");
        audit.recordFailure(tenant, SYSTEM, AuditAction.DOCUMENT_PROCESSING_FAILED, "Document", documentId, caseId,
                CorrelationId.generate(), "Processing failed");
        audit.recordDenied(tenant, ActorId.generate(), AuditAction.DOCUMENT_DOWNLOADED, "Artifact",
                UUID.randomUUID().toString(), caseId, CorrelationId.generate(), "denied");
        assertEquals(List.of(AuditOutcome.SUCCESS, AuditOutcome.FAILURE, AuditOutcome.DENIED),
                records.stream().map(AuditRecord::outcome).toList());
        records.forEach(r -> assertEquals(caseId.value().toString(), r.caseId()));
        assertEquals(documentId, records.get(0).resourceId(), "the resource stays the document");
    }

    @Test
    void explicitCaseIdWinsOverTheDetailAndNullFallsBack() {
        PackageId caseId = PackageId.of(UUID.randomUUID());
        UUID other = UUID.randomUUID();
        audit.recordSuccess(tenant, SYSTEM, AuditAction.REVIEW_TASK_CREATED, "ReviewTask", UUID.randomUUID().toString(),
                caseId, CorrelationId.generate(), "Review task created: package=" + other);
        assertEquals(caseId.value().toString(), records.get(0).caseId());

        audit.recordSuccess(tenant, SYSTEM, AuditAction.REVIEW_TASK_CREATED, "ReviewTask", UUID.randomUUID().toString(),
                null, CorrelationId.generate(), "Review task created: package=" + other);
        assertEquals(other.toString(), records.get(1).caseId(), "null falls back to the derived case ID");

        audit.recordSuccess(tenant, SYSTEM, AuditAction.FINDING_CONFIRMED, "Finding", UUID.randomUUID().toString(),
                null, CorrelationId.generate(), "Finding confirmed: type=SSN");
        assertNull(records.get(2).caseId());
    }

    @Test
    void machineClientsAreRecordedByTheirConfiguredName() {
        ActorId scanner = ActorId.generate();
        names.bindMachineClient(scanner, MachineClientKind.SCANNER, "front desk");
        audit.recordSuccess(tenant, scanner, AuditAction.EXTERNAL_INTAKE_CREATED, "DocumentPackage",
                UUID.randomUUID().toString(), CorrelationId.generate(), "created");
        assertEquals("Scanner: front desk", records.get(0).actorDisplayName());

        ActorId push = ActorId.generate();
        names.bindMachineClient(push, MachineClientKind.PUSH_API, "  Loan origination\u0007 ");
        audit.recordSuccess(tenant, push, AuditAction.EXTERNAL_INTAKE_CREATED, "DocumentPackage",
                UUID.randomUUID().toString(), CorrelationId.generate(), "created");
        assertEquals("Push API: Loan origination", records.get(1).actorDisplayName(), "trimmed, control characters dropped");
    }

    @Test
    void systemActorAndBlankNamesStillRecordNone() {
        names.bindMachineClient(SYSTEM, MachineClientKind.SCANNER, "front desk");
        audit.recordSuccess(tenant, SYSTEM, AuditAction.EXTERNAL_INTAKE_CREATED, "DocumentPackage",
                UUID.randomUUID().toString(), CorrelationId.generate(), "created");
        assertNull(records.get(0).actorDisplayName());

        ActorId scanner = ActorId.generate();
        names.bindMachineClient(scanner, MachineClientKind.SCANNER, "   ");
        audit.recordSuccess(tenant, scanner, AuditAction.EXTERNAL_INTAKE_CREATED, "DocumentPackage",
                UUID.randomUUID().toString(), CorrelationId.generate(), "created");
        assertNull(records.get(1).actorDisplayName());

        names.bindMachineClient(scanner, MachineClientKind.SCANNER, "front desk");
        audit.recordSuccess(tenant, ActorId.generate(), AuditAction.EXTERNAL_INTAKE_CREATED, "DocumentPackage",
                UUID.randomUUID().toString(), CorrelationId.generate(), "created");
        assertNull(records.get(2).actorDisplayName(), "another actor never gets the machine's name");
    }
}

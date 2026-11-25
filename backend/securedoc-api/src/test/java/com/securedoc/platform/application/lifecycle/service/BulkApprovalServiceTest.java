package com.securedoc.platform.application.lifecycle.service;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.lifecycle.command.ApproveFinalDeliveryCommand;
import com.securedoc.platform.application.lifecycle.service.BulkApprovalService.Outcome;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditOutcome;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.lifecycle.TenantLifecyclePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 0076: limits, four eyes on the server, one record per case, and a partial failure. */
class BulkApprovalServiceTest {

    private final TenantId tenant = TenantId.generate();
    private final ActorId person = ActorId.generate();
    private final Set<PackageId> own = new HashSet<>();
    private final List<ApproveFinalDeliveryCommand> approved = new ArrayList<>();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final AuditService audit = new AuditService(audits::add);
    private final PackageLifecycleService lifecycle = mock(PackageLifecycleService.class);
    private PackageId gone;
    private PackageId rejected;
    private PackageId broken;
    private final BulkApprovalService service = new BulkApprovalService(command -> {
        if (command.packageId().equals(gone)) throw new NotFoundException("DocumentPackage", "x");
        if (command.packageId().equals(rejected)) throw new ValidationException(LvErrorCode.REV_002, "not waiting");
        if (command.packageId().equals(broken)) throw new IllegalStateException("database password in here");
        approved.add(command);
        audit.recordSuccess(command.tenantId(), command.actorId(), AuditAction.PACKAGE_FINAL_APPROVED, "DocumentPackage",
                command.packageId().toString(), command.correlationId(), "Final delivery approved: bulkAction=" + command.bulkAction());
    }, lifecycle, (t, p, cases) -> {
        Set<PackageId> found = new HashSet<>(cases);
        found.retainAll(own);
        return found;
    }, audit);

    @BeforeEach
    void approvalIsOn() {
        approvalBeforeDelivery(true);
    }

    @Test
    void oneToFiftyCasesAndAnIdTwiceCountsOnce() {
        assertEquals(LvErrorCode.INT_020, refused(List.of()));
        assertEquals(LvErrorCode.INT_020, refused(Stream.generate(PackageId::generate).limit(51).toList()));
        assertTrue(approved.isEmpty() && audits.isEmpty());
        assertEquals(50, approve(Stream.generate(PackageId::generate).limit(50).toList()).size());
        assertEquals(50, approved.size());
        PackageId twice = PackageId.generate();
        assertEquals(1, approve(List.of(twice, twice)).size());
    }

    @Test
    void nobodyApprovesACaseTheyReviewedWhateverTheScreenSent() {
        PackageId mine = PackageId.generate();
        PackageId other = PackageId.generate();
        own.add(mine);
        assertEquals(List.of(new Outcome(mine, LvErrorCode.REV_015), new Outcome(other, null)), approve(List.of(mine, other)));
        assertEquals(List.of(other), approved.stream().map(ApproveFinalDeliveryCommand::packageId).toList());
        assertEquals(Set.of(mine), service.offer(tenant, person, List.of(mine, other)).reviewedByYou());
    }

    @Test
    void oneRecordPerCaseNamesTheBulkActionAndAFailureStopsNoOther() {
        gone = PackageId.generate();
        rejected = PackageId.generate();
        broken = PackageId.generate();
        PackageId first = PackageId.generate();
        PackageId last = PackageId.generate();
        List<PackageId> cases = List.of(first, gone, rejected, broken, last);
        List<Outcome> outcomes = approve(cases);
        assertEquals(List.of(true, false, false, false, true), outcomes.stream().map(Outcome::approved).toList());
        assertEquals(LvErrorCode.REV_002, outcomes.get(2).reason());
        assertEquals(LvErrorCode.SYS_001, outcomes.get(3).reason());

        assertEquals(cases.stream().map(PackageId::toString).toList(), audits.stream().map(AuditRecord::resourceId).toList());
        assertTrue(audits.stream().allMatch(a -> a.action() == AuditAction.PACKAGE_FINAL_APPROVED && a.actorId().equals(person)));
        assertEquals(1, audits.stream().map(a -> a.detail().replaceFirst("^.*bulkAction=", "")).distinct().count(),
                "the same bulk action, with its number of cases");
        assertTrue(audits.get(0).detail().endsWith(", cases=5"), audits.get(0).detail());
        assertEquals(List.of(AuditOutcome.SUCCESS, AuditOutcome.FAILURE, AuditOutcome.FAILURE, AuditOutcome.FAILURE,
                AuditOutcome.SUCCESS), audits.stream().map(AuditRecord::outcome).toList());
        assertTrue(audits.stream().noneMatch(a -> a.detail().contains("password")), "never an exception's text");
    }

    @Test
    void offWhileApprovalBeforeDeliveryIsOff() {
        approvalBeforeDelivery(false);
        assertEquals(LvErrorCode.REV_002, refused(List.of(PackageId.generate())));
        assertEquals(false, service.offer(tenant, person, List.of(PackageId.generate())).enabled());
        assertTrue(approved.isEmpty() && audits.isEmpty());
    }

    private void approvalBeforeDelivery(boolean on) {
        TenantLifecyclePolicy d = TenantLifecyclePolicy.defaults(tenant);
        when(lifecycle.policyFor(any())).thenReturn(new TenantLifecyclePolicy(tenant, d.originalRetentionHours(),
                d.redactedCacheHours(), d.neverExportedHoldHours(), on, Instant.now()));
    }

    private List<Outcome> approve(List<PackageId> cases) {
        return service.approve(tenant, person, CorrelationId.generate(), cases);
    }

    private LvErrorCode refused(List<PackageId> cases) {
        return assertThrows(ValidationException.class, () -> approve(cases)).lvCode();
    }
}

package com.securedoc.platform.application.review.service;

import com.securedoc.platform.api.controller.RejectCaseController;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.lifecycle.service.FinalDeliveryApprovalService;
import com.securedoc.platform.application.lifecycle.command.ApproveFinalDeliveryCommand;
import com.securedoc.platform.application.lifecycle.service.PackageLifecycleService;
import com.securedoc.platform.application.port.out.PackageProcessingDispatcher;
import com.securedoc.platform.application.port.out.PackageRejectionRepository;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.PipelineJobRepository;
import com.securedoc.platform.application.review.service.RejectCaseService.Step;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.review.RejectionReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Task 0071: rejecting a case in Review and in Approval, and the decisions that lose to a rejection. */
class RejectCaseServiceTest {

    private final TenantId tenant = TenantId.generate();
    private final ActorId actor = ActorId.generate();
    private final PackageRepository packages = mock(PackageRepository.class);
    private final PackageRejectionRepository rejections = mock(PackageRejectionRepository.class);
    private final PackageLifecycleService lifecycle = mock(PackageLifecycleService.class);
    private final PipelineJobRepository jobs = mock(PipelineJobRepository.class);
    private final List<AuditRecord> audits = new ArrayList<>();
    private final RejectCaseService service = new RejectCaseService(packages, rejections, lifecycle, jobs,
            new AuditService(audits::add));
    private final DocumentPackage pkg = DocumentPackage.create(tenant, PackageType.API_SUBMISSION, actor, PackageMetadata.empty());

    @BeforeEach
    void setUp() {
        when(packages.findByIdAndTenantId(pkg.id(), tenant)).thenReturn(Optional.of(pkg));
        when(packages.changeStatus(any(), any())).thenReturn(true);
        when(lifecycle.nextExportCycle(pkg.id(), tenant)).thenReturn(1);
    }

    @Test
    void everyReasonRejectsInReviewAndInApproval() {
        for (RejectionReason reason : RejectionReason.values()) {
            for (PackageStatus status : List.of(PackageStatus.PENDING_REVIEW, PackageStatus.IN_REVIEW,
                    PackageStatus.PENDING_FINAL_APPROVAL)) {
                audits.clear();
                at(status);
                Step step = status == PackageStatus.PENDING_FINAL_APPROVAL ? Step.APPROVAL : Step.REVIEW;
                assertEquals(reason, reject(step, reason.code().code()));
                assertEquals(PackageStatus.REJECTED, pkg.status());
                assertEquals("Case rejected: code=" + reason.code().code() + ", step=" + step, audits.get(0).detail());
                assertEquals(AuditAction.PACKAGE_REJECTED, audits.get(0).action());
                assertEquals(actor, audits.get(0).actorId());
            }
        }
        verify(jobs, org.mockito.Mockito.times(15)).removeStageJobs(tenant, pkg.id(), 1);
        verify(lifecycle, org.mockito.Mockito.times(15)).recordTechnicalFailure(eq(pkg.id()), eq(tenant), any());
    }

    @Test
    void theWrongStepOrStateOrReasonChangesNothing() {
        at(PackageStatus.PENDING_REVIEW);
        assertRefused(Step.APPROVAL, "LV-REV-010", LvErrorCode.REV_002);
        for (String reason : new String[]{null, "", "LV-REV-002", "because I say so"}) {
            ValidationException refused = assertRefused(Step.REVIEW, reason, LvErrorCode.INT_020);
            assertTrue(reason == null || reason.isEmpty() || !refused.getMessage().contains(reason), "never repeated");
        }
        for (PackageStatus status : List.of(PackageStatus.PROCESSING, PackageStatus.COMPLETED, PackageStatus.FAILED,
                PackageStatus.REJECTED)) {
            at(status);
            assertRefused(Step.REVIEW, "LV-REV-010", LvErrorCode.REV_002);
            assertRefused(Step.APPROVAL, "LV-REV-010", LvErrorCode.REV_002);
        }
        verifyNoInteractions(rejections, jobs);
        verify(packages, never()).changeStatus(any(), any());
        assertTrue(audits.isEmpty());
    }

    @Test
    void aDecisionThatCameFirstStands() {
        at(PackageStatus.PENDING_REVIEW);
        when(packages.changeStatus(any(), any())).thenReturn(false);
        assertRefused(Step.REVIEW, "LV-REV-010", LvErrorCode.REV_002);
        verifyNoInteractions(rejections);
        verify(jobs, never()).removeStageJobs(any(), any(), anyInt());
        assertTrue(audits.isEmpty());
    }

    /** The approval read the case before the rejection: nothing is sent, and the approval is taken back. */
    @Test
    void anApprovalThatLosesToARejectionExportsNothing() {
        at(PackageStatus.PENDING_FINAL_APPROVAL);
        when(packages.changeStatus(any(), any())).thenReturn(false);
        PackageProcessingDispatcher dispatcher = mock(PackageProcessingDispatcher.class);
        FinalDeliveryApprovalService approval = new FinalDeliveryApprovalService(packages, lifecycle, dispatcher,
                new AuditService(audits::add));
        assertThrows(ValidationException.class, () -> approval.execute(new ApproveFinalDeliveryCommand(pkg.id(), tenant,
                actor, CorrelationId.generate())));
        verifyNoInteractions(dispatcher);
        verify(lifecycle).clearFinalApproval(eq(pkg.id()), eq(tenant), any());
        assertTrue(audits.isEmpty());
    }

    /** Whoever may review rejects in Review; whoever may approve rejects in Approval; nobody else. */
    @Test
    void eachPlaceNeedsItsOwnPermission() {
        assertEquals(UserCapability.MANUAL_REVIEW, permission("inReview"));
        assertEquals(UserCapability.PACKAGE_APPROVE_DELIVERY, permission("inApproval"));
        for (String name : List.of("inReview", "inApproval")) {
            assertTrue(method(name).isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class));
        }
    }

    private static UserCapability permission(String name) {
        return method(name).getAnnotation(RequireCapability.class).value()[0];
    }

    private static Method method(String name) {
        return Arrays.stream(RejectCaseController.class.getDeclaredMethods()).filter(m -> m.getName().equals(name))
                .findFirst().orElseThrow();
    }

    private void at(PackageStatus status) {
        ReflectionTestUtils.setField(pkg, "status", status);
    }

    private RejectionReason reject(Step step, String code) {
        return service.reject(pkg.id(), tenant, actor, CorrelationId.generate(), step, code);
    }

    private ValidationException assertRefused(Step step, String code, LvErrorCode expected) {
        ValidationException refused = assertThrows(ValidationException.class, () -> reject(step, code));
        assertEquals(expected, refused.lvCode());
        return refused;
    }
}

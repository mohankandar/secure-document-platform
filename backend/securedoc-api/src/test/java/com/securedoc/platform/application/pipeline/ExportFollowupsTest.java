package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.delivery.DeliverySubmission;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.pipeline.DestinationState;
import com.securedoc.platform.domain.pipeline.ExportDestinations;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.securedoc.platform.application.pipeline.ExportJobFixture.minutes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Task 0028: pausing by evidence, the single-case schedule with one rebuild, 429, the network alert, grouped IT alerts, resend all. */
class ExportFollowupsTest {

    // ------------------------------------------------------------------ check fails: pause, resume in order

    @Test
    void aFailedCheckPausesTheDestinationAtOnceAndItResumesOnItsOwnInOrder() {
        ExportJobFixture f = new ExportJobFixture();
        List<DocumentPackage> cases = new ArrayList<>();
        for (int i = 0; i < 4; i++) cases.add(f.caseReadyForExport());
        f.destination.down();

        assertTrue(f.step("w").isPresent());
        assertEquals(DestinationState.PAUSED, f.states.stateOf(f.destination.id), "paused at the first failure");
        assertEquals(List.of(f.destination.id), f.checked, "the check ran at once, once");
        PipelineJob first = f.jobOf(cases.get(0));
        assertEquals(PipelineJobStatus.WAITING_RETRY, first.status());
        assertEquals(0, first.attempts(), "the case is not at fault: no attempt used");
        assertEquals("DESTINATION_PAUSED", first.lastDetail());
        assertTrue(f.step("w").isEmpty(), "nothing is taken while paused");

        // Checked every 5 minutes while it stays down.
        f.clock.advance(minutes(4));
        assertEquals(0, f.service.probePausedDestinations());
        assertEquals(1, f.checked.size(), "not checked again before 5 minutes");
        f.clock.advance(minutes(1));
        assertEquals(0, f.service.probePausedDestinations());
        assertEquals(2, f.checked.size());

        f.destination.up();
        f.clock.advance(minutes(5));
        assertEquals(1, f.service.probePausedDestinations());
        assertEquals(4, f.drain());
        assertEquals(cases.stream().map(DocumentPackage::id).toList(), f.destination.deliveredInOrder,
                "every case goes out in its original order");
        assertTrue(f.jobs.all().stream().allMatch(j -> j.status() == PipelineJobStatus.DONE && j.attempts() == 1));
        // Task 0036: the only destination down raises "can't reach its destinations" (one incident); no case goes to IT.
        verify(f.incidents, times(1)).recordFailure(any(), any(), any(), any(), any(), any(), any(),
                argThat(m -> "ALL_DESTINATIONS_UNREACHABLE".equals(m.get("detail"))));
        verify(f.incidents, times(1)).recordFailure(any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void theLocalExportFolderIsCheckedAndPausedLikeAnyDestination() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        IntegrationConnectionId local = ExportDestinations.localFolder(f.tenant);
        // Re-queue the case to the local folder (what the queue does when the master is the local folder).
        f.jobs.all().forEach(j -> f.jobs.changeStatus(f.tenant, j.id(), j.status(), PipelineJobStatus.STOPPED, 0, null, null));
        f.jobs.enqueue(PipelineJob.ready(f.tenant, PipelineStage.EXPORT, pkg.id(), null, local, null, 2,
                f.clock.instant()));
        f.nextCycle.put(pkg.id(), 2);
        f.checks.put(local, false);
        f.destination.failWith = LvErrorCode.DLV_001;

        f.step("w");

        assertTrue(f.checked.contains(local), "the local folder's own check ran");
        assertEquals(DestinationState.PAUSED, f.states.stateOf(local));
        assertNotEquals(f.destination.id, local);
        assertEquals(local, ExportDestinations.localFolder(f.tenant), "every server derives the same id");
    }

    // ------------------------------------------------------------------ check passes: the case's schedule, one rebuild

    @Test
    void aSingleFailingCaseWithAPassingCheckFollowsItsScheduleIsRebuiltOnceBeforeTheThirdRetryThenNeedsIt() {
        for (LvErrorCode code : List.of(LvErrorCode.DLV_001, LvErrorCode.DLV_002)) {
            ExportJobFixture f = new ExportJobFixture();
            DocumentPackage pkg = f.caseReadyForExport();
            f.destination.failWith = code; // fails, but its check passes: it is this case

            List<Duration> schedule = List.of(minutes(5), minutes(15), Duration.ofHours(1), Duration.ofHours(4),
                    Duration.ofHours(12));
            f.step("w");
            for (int retry = 0; retry < schedule.size(); retry++) {
                PipelineJob job = f.jobOf(pkg);
                assertEquals(PipelineJobStatus.WAITING_RETRY, job.status(), code + " after try " + (retry + 1));
                assertEquals(retry + 1, job.attempts());
                assertEquals(f.clock.instant().plus(schedule.get(retry)), job.nextAttemptAt(),
                        code + ": the delay before retry " + (retry + 1));
                f.clock.advance(schedule.get(retry).minusSeconds(1));
                assertTrue(f.step("w").isEmpty(), "not before its delay");
                f.clock.advance(Duration.ofSeconds(1));
                assertTrue(f.step("w").isPresent());
                boolean rebuilt = f.jobOf(pkg).rebuilt();
                assertEquals(retry >= 2, rebuilt, code + ": rebuilt only from the 3rd retry on (retry " + (retry + 1) + ")");
            }

            assertJob(f, pkg, PipelineJobStatus.NEEDS_IT, 6, code.code());
            assertEquals(DestinationState.WORKING, f.states.stateOf(f.destination.id), "one case never pauses it");
            assertEquals(PackageStatus.PROCESSING, pkg.status(), "the case is not failed");
            assertEquals(ExportJobService.CaseExportState.NEEDS_IT, f.service.caseExportState(f.tenant, pkg.id()));
            verify(f.audit, times(1)).recordSuccess(eq(f.tenant), any(), eq(AuditAction.EXPORT_JOB_REBUILT),
                    anyString(), anyString(), eq(pkg.id()), any(), contains("submissionRebuilt=true"));
            // The destination recognises every try as the same request, the rebuilt one too.
            assertEquals(6, f.destination.keys.size());
            assertEquals(1, f.destination.keys.stream().distinct().count(), "the same Idempotency-Key every time");
            DeliverySubmission submission = f.submissions.get(pkg.id() + "#1");
            assertEquals(f.destination.keys.get(0), submission.sourceSubmissionId());
            verify(f.storage, atLeastOnce()).retrieve(any());
            verify(f.incidents, times(1)).recordFailure(eq(f.tenant), eq(pkg.id()), any(), any(),
                    argThat(e -> com.securedoc.platform.application.error.LvErrorCodes.of(e) == code), any(),
                    anyString(), any());
        }
    }

    @Test
    void theRebuildKeepsTheIdentitiesAndDropsWhatTheDestinationAnswered() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        f.destination.failWith = LvErrorCode.DLV_008; // not every document confirmed
        f.step("w");
        DeliverySubmission before = f.submissions.get(pkg.id() + "#1");
        // As if the destination had answered part of it: a reference and one acknowledgement.
        before.recordDestinationSubmissionReference("remote-ref", java.time.Instant.now());
        before.acknowledgeDocument(before.documents().get(0).documentId(), "remote-doc", "sdv://x", java.time.Instant.now());
        f.submissions.put(pkg.id() + "#1", before);
        String sourceDocumentId = before.documents().get(0).sourceDocumentId();

        for (int i = 0; i < 3; i++) {
            f.clock.advance(Duration.ofHours(2));
            f.step("w");
        }

        DeliverySubmission after = f.submissions.get(pkg.id() + "#1");
        assertTrue(f.jobOf(pkg).rebuilt());
        assertEquals(before.id(), after.id());
        assertEquals(before.sourceSubmissionId(), after.sourceSubmissionId(), "the same Idempotency-Key");
        assertEquals(sourceDocumentId, after.documents().get(0).sourceDocumentId(), "the same document key");
        assertNull(after.destinationSubmissionReference(), "the rebuilt export starts afresh at the destination");
        assertFalse(after.documents().get(0).acknowledged());
        assertEquals("remote-ref", f.destination.existingReferences.get(2), "the 3rd try still used the old answer");
        assertNull(f.destination.existingReferences.get(3), "the 4th (after the rebuild) starts afresh");
    }

    @Test
    void aStoredCopyThatNoLongerMatchesItsChecksumIsNotSentAndGoesToIt() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        f.destination.failWith = LvErrorCode.DLV_002;
        for (int i = 0; i < 3; i++) {
            f.step("w");
            f.clock.advance(Duration.ofHours(2));
        }
        int sends = f.destination.keys.size();
        when(f.storage.retrieve(any())).thenAnswer(i -> new java.io.ByteArrayInputStream("changed".getBytes()));

        f.step("w");

        assertEquals(sends, f.destination.keys.size(), "nothing is sent from a damaged copy");
        assertEquals(PipelineJobStatus.NEEDS_IT, f.jobOf(pkg).status());
        verify(f.audit).recordFailure(eq(f.tenant), any(), eq(AuditAction.EXPORT_JOB_REBUILT), anyString(),
                anyString(), eq(pkg.id()), any(), contains("Export rebuild failed"));
    }

    // ------------------------------------------------------------------ backup rule: answering but failing

    @Test
    void failuresFromThreeDifferentCasesWithinTenMinutesPauseADestinationWhoseCheckPasses() {
        // A first delay of 30 minutes, so each step below takes the next case, not a retry.
        ExportJobFixture f = new ExportJobFixture(firstDelay(minutes(30)));
        List<DocumentPackage> cases = List.of(f.caseReadyForExport(), f.caseReadyForExport(), f.caseReadyForExport());
        f.destination.failWith = LvErrorCode.DLV_002; // answering (the check passes) but failing

        f.step("w");
        f.clock.advance(minutes(4));
        f.step("w");
        assertEquals(DestinationState.WORKING, f.states.stateOf(f.destination.id), "2 cases: still working");
        f.clock.advance(minutes(4));
        f.step("w");

        assertEquals(DestinationState.PAUSED, f.states.stateOf(f.destination.id), "3 cases within 10 minutes");
        verify(f.audit).recordFailure(eq(f.tenant), any(), eq(AuditAction.EXPORT_DESTINATION_PAUSED), anyString(),
                anyString(), any(), contains("reason=FAILING_CASES, cases=3"));
        assertTrue(cases.stream().allMatch(c -> f.jobOf(c).attempts() == 1));
    }

    @Test
    void failuresFromDifferentCasesSpreadOverMoreThanTenMinutesDoNotPause() {
        // A first delay of 30 minutes, so no case retries inside the test.
        ExportJobFixture f = new ExportJobFixture(firstDelay(minutes(30)));
        f.caseReadyForExport();
        f.caseReadyForExport();
        f.destination.failWith = LvErrorCode.DLV_002;
        assertEquals(2, f.drain());
        f.clock.advance(minutes(11));
        f.caseReadyForExport();
        assertEquals(1, f.drain());
        assertEquals(DestinationState.WORKING, f.states.stateOf(f.destination.id),
                "3 different cases, but not within 10 minutes");
    }

    // ------------------------------------------------------------------ 429

    @Test
    void a429WaitsForRetryAfterUsesNoAttemptAndTakesOneWorkerOffTheDestinationForTenQuietMinutes() {
        ExportJobFixture f = new ExportJobFixture();
        List<DocumentPackage> cases = new ArrayList<>();
        for (int i = 0; i < 6; i++) cases.add(f.caseReadyForExport());
        f.destination.rateLimitCalls = 1;
        f.destination.retryAfter = Duration.ofSeconds(90);

        f.step("w");

        PipelineJob first = f.jobOf(cases.get(0));
        assertEquals(PipelineJobStatus.WAITING_RETRY, first.status());
        assertEquals(0, first.attempts(), "a 429 is not a failure");
        assertEquals("RATE_LIMITED", first.lastDetail());
        assertEquals(DestinationState.WORKING, f.states.stateOf(f.destination.id), "no pause");
        assertTrue(f.checked.isEmpty(), "no check: the destination answered");
        assertEquals(3, f.states.rowOf(f.destination.id).workerLimit(f.settings.workersPerDestination()),
                "one worker less");
        verify(f.audit).recordSuccess(eq(f.tenant), any(), eq(AuditAction.EXPORT_DESTINATION_RATE_LIMITED),
                anyString(), anyString(), any(), contains("waitSeconds=90"));

        // Nothing goes to this destination before Retry-After.
        f.clock.advance(Duration.ofSeconds(89));
        assertTrue(f.step("w").isEmpty());
        f.clock.advance(Duration.ofSeconds(1));

        // With one worker less, only 3 run at once.
        List<PipelineJob> running = new ArrayList<>();
        for (int i = 0; i < 5; i++) f.service.claimNext("w" + i).ifPresent(running::add);
        assertEquals(3, running.size());
        running.forEach(f.service::run);

        // Back up by one after 10 minutes without a 429 (90 s have passed), never above the limit.
        f.clock.advance(minutes(8));
        assertEquals(0, f.service.restoreWorkers());
        f.clock.advance(minutes(1));
        assertEquals(1, f.service.restoreWorkers());
        assertEquals(4, f.states.rowOf(f.destination.id).workerLimit(f.settings.workersPerDestination()));
        f.clock.advance(minutes(10));
        assertEquals(0, f.service.restoreWorkers());

        f.drain();
        assertTrue(f.jobs.all().stream().allMatch(j -> j.status() == PipelineJobStatus.DONE));
        assertEquals(1, f.jobOf(cases.get(0)).attempts(), "only the successful send counted");
    }

    @Test
    void a429WithoutRetryAfterWaitsSixtySecondsAndWorkersNeverGoBelowOne() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        f.destination.rateLimitCalls = 10;
        f.destination.retryAfter = null;
        for (int i = 0; i < 6; i++) {
            f.step("w");
            assertEquals(f.clock.instant().plusSeconds(60), f.jobOf(pkg).nextAttemptAt());
            f.clock.advance(Duration.ofSeconds(60));
        }
        assertEquals(1, f.states.rowOf(f.destination.id).workerLimit(f.settings.workersPerDestination()),
                "never below 1");
        assertEquals(0, f.jobOf(pkg).attempts());
    }

    // ------------------------------------------------------------------ every destination down: LexaVault's network

    @Test
    void everyDestinationFailingItsCheckRaisesOneAlertPausesNothingAndUsesNoAttempts() {
        ExportJobFixture f = new ExportJobFixture();
        IntegrationConnectionId split = f.addDestination(false);
        List<DocumentPackage> cases = List.of(f.caseReadyForExport(), f.caseReadyForExport());
        f.destination.down();

        f.drain();
        f.clock.advance(minutes(5));
        f.drain();

        assertTrue(f.alerts.raised(f.tenant));
        assertEquals(DestinationState.WORKING, f.states.stateOf(f.destination.id), "not paused");
        assertEquals(DestinationState.WORKING, f.states.stateOf(split), "not paused");
        assertTrue(cases.stream().allMatch(c -> f.jobOf(c).attempts() == 0), "no attempts used");
        assertTrue(cases.stream().allMatch(c -> "DESTINATIONS_UNREACHABLE".equals(f.jobOf(c).lastDetail())));
        verify(f.audit, times(1)).recordFailure(eq(f.tenant), any(),
                eq(AuditAction.EXPORT_DESTINATIONS_UNREACHABLE_ALERT), anyString(), anyString(), any(),
                contains("code=LV-DLV-001, detail=ALL_DESTINATIONS_UNREACHABLE"));
        verify(f.incidents, times(1)).recordFailure(eq(f.tenant), any(), any(), any(),
                argThat(e -> com.securedoc.platform.application.error.LvErrorCodes.of(e) == LvErrorCode.DLV_001),
                any(), anyString(), argThat(m -> "ALL_DESTINATIONS_UNREACHABLE".equals(m.get("detail"))));
        ExportJobService.AlertView view = f.service.reachabilityAlert(f.tenant);
        assertTrue(view.raised());
        assertEquals("ALL_DESTINATIONS_UNREACHABLE", view.detail());

        // Checked again every 5 minutes; cleared when any check passes; the cases go out in order.
        f.clock.advance(minutes(4));
        assertEquals(0, f.service.checkUnreachableAlerts(), "checked, every destination still down");
        assertTrue(f.alerts.raised(f.tenant));
        f.checks.put(split, true);
        f.clock.advance(minutes(4));
        assertEquals(0, f.service.checkUnreachableAlerts(), "not checked again before 5 minutes");
        f.clock.advance(minutes(1));
        assertEquals(1, f.service.checkUnreachableAlerts());
        assertFalse(f.alerts.raised(f.tenant));
        verify(f.audit).recordSuccess(eq(f.tenant), any(), eq(AuditAction.EXPORT_DESTINATIONS_UNREACHABLE_ALERT_CLEARED),
                anyString(), anyString(), any(), contains("jobsMadeDue=2"));
        f.destination.up();
        f.drain();
        assertEquals(cases.stream().map(DocumentPackage::id).toList(), f.destination.deliveredInOrder);
        assertTrue(cases.stream().allMatch(c -> f.jobOf(c).attempts() == 1));
    }

    @Test
    void oneDestinationDownWhileAnotherAnswersIsAPauseNotTheNetwork() {
        ExportJobFixture f = new ExportJobFixture();
        f.addDestination(true);
        f.caseReadyForExport();
        f.destination.down();
        f.step("w");
        assertFalse(f.alerts.raised(f.tenant));
        assertEquals(DestinationState.PAUSED, f.states.stateOf(f.destination.id));
    }

    @Test
    void theOnlyDestinationDownIsPausedAndRaisesTheAlertWhichClearsWhenItResumes() {
        // Task 0036 (owner's answer 1): one destination is "every destination". The pause stays; the alert is raised.
        ExportJobFixture f = new ExportJobFixture(); // only the master
        List<DocumentPackage> cases = List.of(f.caseReadyForExport(), f.caseReadyForExport());
        f.destination.down();
        f.drain();

        assertEquals(DestinationState.PAUSED, f.states.stateOf(f.destination.id), "the pause stays");
        assertTrue(f.alerts.raised(f.tenant), "and IT gets the alert");
        assertTrue(f.service.reachabilityAlert(f.tenant).raised());
        assertTrue(cases.stream().allMatch(c -> f.jobOf(c).attempts() == 0), "no attempts used");
        verify(f.audit, times(1)).recordFailure(eq(f.tenant), any(), eq(AuditAction.EXPORT_DESTINATION_PAUSED),
                anyString(), anyString(), any(), contains("reason=CHECK_FAILED"));
        verify(f.audit, times(1)).recordFailure(eq(f.tenant), any(),
                eq(AuditAction.EXPORT_DESTINATIONS_UNREACHABLE_ALERT), anyString(), anyString(), any(),
                contains("code=LV-DLV-001, detail=ALL_DESTINATIONS_UNREACHABLE"));

        // Still down at the next probe: paused, alert still raised.
        f.clock.advance(minutes(5));
        assertEquals(0, f.service.probePausedDestinations());
        assertTrue(f.alerts.raised(f.tenant));

        // Back: the probe resumes it, which clears the alert; the cases go out in order.
        f.destination.up();
        f.clock.advance(minutes(5));
        assertEquals(1, f.service.probePausedDestinations());
        assertEquals(DestinationState.WORKING, f.states.stateOf(f.destination.id));
        assertFalse(f.alerts.raised(f.tenant), "cleared when the destination resumes");
        verify(f.audit, times(1)).recordSuccess(eq(f.tenant), any(),
                eq(AuditAction.EXPORT_DESTINATIONS_UNREACHABLE_ALERT_CLEARED), anyString(), anyString(), any(),
                anyString());
        f.drain();
        assertEquals(cases.stream().map(DocumentPackage::id).toList(), f.destination.deliveredInOrder);
    }

    @Test
    void theOnlyDestinationsAlertCheckResumesItWhenItAnswers() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        f.destination.down();
        f.drain();
        assertTrue(f.alerts.raised(f.tenant));

        f.destination.up();
        f.clock.advance(minutes(5));
        assertEquals(1, f.service.checkUnreachableAlerts(), "the alert's own check finds it back");
        assertFalse(f.alerts.raised(f.tenant));
        assertEquals(DestinationState.WORKING, f.states.stateOf(f.destination.id), "resumed with the alert");
        f.drain();
        assertEquals(List.of(pkg.id()), f.destination.deliveredInOrder);
    }

    @Test
    void checkNowBringingTheOnlyDestinationBackClearsTheAlert() {
        ExportJobFixture f = new ExportJobFixture();
        f.caseReadyForExport();
        f.destination.down();
        f.drain();
        assertTrue(f.alerts.raised(f.tenant));
        f.destination.up();
        var result = f.service.checkDestinationNow(f.tenant, f.destination.id, ActorId.generate(),
                CorrelationId.generate()).orElseThrow();
        assertTrue(result.resumed());
        assertFalse(f.alerts.raised(f.tenant));
    }

    // ------------------------------------------------------------------ grouped IT alerts

    @Test
    void needsItJobsOfOneDestinationAndCodeFormOneGroupWithOneIncidentAndOneRetry() {
        ExportJobFixture f = new ExportJobFixture();
        List<DocumentPackage> cases = List.of(f.caseReadyForExport(), f.caseReadyForExport(), f.caseReadyForExport());
        f.destination.failWith = LvErrorCode.DLV_006;
        f.drain();

        List<ExportJobService.ItGroup> groups = f.service.needsItGroups(f.tenant);
        assertEquals(1, groups.size());
        ExportJobService.ItGroup group = groups.get(0);
        assertEquals(f.destination.id.value(), group.destinationId());
        assertEquals("LV-DLV-006", group.lvCode());
        assertEquals(3, group.count());
        assertEquals(cases.stream().map(c -> c.id().value()).toList(), group.caseIds());
        assertNotNull(group.firstFailedAt());
        verify(f.incidents, times(1)).recordFailure(any(), any(), any(), any(), any(), any(), anyString(), any());
        verify(f.audit, times(1)).recordFailure(eq(f.tenant), any(), eq(AuditAction.EXPORT_JOB_NEEDS_IT_NOTIFICATION),
                anyString(), anyString(), any(), any(), contains("group=new"));

        f.destination.failWith = null; // IT fixed it
        List<ExportJobService.JobResult> retried = f.service.retryGroup(f.tenant, f.destination.id, "LV-DLV-006",
                ActorId.generate(), CorrelationId.generate());
        assertEquals(3, retried.size());
        assertTrue(retried.stream().allMatch(r -> "READY".equals(r.status())));
        verify(f.audit).recordSuccess(eq(f.tenant), any(), eq(AuditAction.EXPORT_JOB_IT_GROUP_RETRY_REQUESTED),
                anyString(), anyString(), any(), contains("jobs=3"));
        f.drain();
        assertTrue(cases.stream().allMatch(c -> c.status() == PackageStatus.COMPLETED));
        assertTrue(f.service.retryGroup(f.tenant, f.destination.id, "LV-DLV-006", ActorId.generate(),
                CorrelationId.generate()).isEmpty(), "no group left");
    }

    @Test
    void aDifferentCodeOrDestinationIsAnotherGroup() {
        ExportJobFixture f = new ExportJobFixture();
        f.caseReadyForExport();
        f.destination.failWith = LvErrorCode.DLV_006;
        f.drain();
        DocumentPackage other = f.caseReadyForExport();
        f.documents.put(other.id(), List.of()); // LV-DLV-011
        f.destination.failWith = null;
        f.drain();
        assertEquals(2, f.service.needsItGroups(f.tenant).size());
        verify(f.incidents, times(2)).recordFailure(any(), any(), any(), any(), any(), any(), anyString(), any());
    }

    // ------------------------------------------------------------------ resend all

    @Test
    void resendAllPutsEveryWaitingCaseOfTheDestinationBackInTheirOriginalOrderOneAttemptEach() {
        ExportJobFixture f = new ExportJobFixture();
        List<DocumentPackage> cases = List.of(f.caseReadyForExport(), f.caseReadyForExport(), f.caseReadyForExport());
        f.destination.failWith = LvErrorCode.DLV_004;
        f.drain();
        List<ExportJobService.WaitingGroup> waiting = f.service.waitingForYou(f.tenant);
        assertEquals(1, waiting.size(), "grouped by destination");
        assertEquals(3, waiting.get(0).total());
        assertEquals(Map.of("LV-DLV-004", 3), waiting.get(0).countsByCode());
        f.destination.failWith = null;
        int sendsBefore = f.destination.keys.size();

        List<UUID> queued = f.service.resendAll(f.tenant, f.destination.id, ActorId.generate(), CorrelationId.generate());

        assertEquals(cases.stream().map(c -> f.jobOf(c).id()).toList(), queued, "oldest first");
        assertTrue(cases.stream().allMatch(c -> f.jobOf(c).status() == PipelineJobStatus.READY && f.jobOf(c).resend()));
        assertEquals(sendsBefore, f.destination.keys.size(), "the workers send them, not the request");
        f.drain();
        assertEquals(cases.stream().map(DocumentPackage::id).toList(), f.destination.deliveredInOrder);
        assertEquals(sendsBefore + 3, f.destination.keys.size(), "one attempt each");
        assertTrue(cases.stream().allMatch(c -> f.jobOf(c).status() == PipelineJobStatus.DONE && !f.jobOf(c).resend()));
        verify(f.audit).recordSuccess(eq(f.tenant), any(), eq(AuditAction.EXPORT_JOB_RESEND_ALL_REQUESTED), anyString(),
                anyString(), any(), contains("jobs=3"));
    }

    @Test
    void aResendAllThatFailsGoesToItWithDlv015AndOtherDestinationsAreLeftAlone() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        f.destination.failWith = LvErrorCode.DLV_004;
        f.drain();
        // A case waiting for a person at another destination is not touched.
        DocumentPackage elsewhere = f.caseReadyForExport();
        IntegrationConnectionId other = IntegrationConnectionId.generate();
        PipelineJob otherJob = PipelineJob.ready(f.tenant, PipelineStage.EXPORT, elsewhere.id(), null, other, null, 2,
                f.clock.instant());
        f.jobs.enqueue(otherJob);
        f.jobs.changeStatus(f.tenant, otherJob.id(), PipelineJobStatus.READY, PipelineJobStatus.WAITING_FOR_YOU, 1,
                "LV-DLV-004", null);

        f.service.resendAll(f.tenant, f.destination.id, ActorId.generate(), CorrelationId.generate());
        f.step("w");

        PipelineJob job = f.jobOf(pkg);
        assertEquals(PipelineJobStatus.NEEDS_IT, job.status());
        assertEquals("LV-DLV-015", job.lastLvCode());
        assertEquals("LV-DLV-004", job.lastDetail());
        assertEquals(PipelineJobStatus.WAITING_FOR_YOU, f.jobs.get(otherJob.id()).status());
    }

    @Test
    void aResendThatMeetsAPausedDestinationWaitsAsAResendInsteadOfGoingToIt() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        f.destination.failWith = LvErrorCode.DLV_004;
        f.drain();
        f.destination.down();
        UUID id = f.jobOf(pkg).id();

        ExportJobService.JobResult result = f.service.resend(f.tenant, List.of(id), ActorId.generate(),
                CorrelationId.generate()).get(0);

        assertEquals("WAITING_RETRY", result.status());
        assertTrue(f.jobs.get(id).resend(), "still one resend by a person");
        f.destination.up();
        f.clock.advance(minutes(5));
        f.service.probePausedDestinations();
        f.drain();
        assertEquals(PipelineJobStatus.DONE, f.jobs.get(id).status());
    }

    // ------------------------------------------------------------------ export state for the screens

    @Test
    void theCasesListGetsEveryCaseExportStateInOneGoAndExportedMeansEveryExportIsDone() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage ready = f.caseReadyForExport();
        DocumentPackage splitOpen = f.caseReadyForExport();
        DocumentPackage noJobs = f.caseReadyForExport();
        f.jobs.all().stream().filter(j -> j.packageId().equals(noJobs.id())).forEach(j ->
                f.jobs.changeStatus(f.tenant, j.id(), PipelineJobStatus.READY, PipelineJobStatus.STOPPED, 0, null, null));
        f.step("w"); // the first case goes out
        f.step("w"); // the second's master export goes out too ...
        f.queue.queueSplitExport(f.tenant, splitOpen.id(), null, UUID.randomUUID(), IntegrationConnectionId.generate(),
                1, CorrelationId.generate()); // ... but its split document waits
        PackageId unknown = PackageId.generate();

        Map<PackageId, ExportJobService.CaseExportState> states = f.service.caseExportStates(f.tenant,
                List.of(ready.id(), splitOpen.id(), noJobs.id(), unknown));

        assertEquals(ExportJobService.CaseExportState.EXPORTED, states.get(ready.id()));
        assertEquals(ExportJobService.CaseExportState.READY, states.get(splitOpen.id()),
                "not fully exported while a split export is open");
        assertEquals(ExportJobService.CaseExportState.NONE, states.get(noJobs.id()));
        assertEquals(ExportJobService.CaseExportState.NONE, states.get(unknown));
        assertEquals(ExportJobService.CaseExportState.READY, f.service.caseExportState(f.tenant, splitOpen.id()),
                "the case's own endpoint agrees");
    }

    @Test
    void theCasesListGetsWhyACaseWaitsAsACodeAndItsDestinationsName() {
        // Task 0038: the reason a case waits, as an LV code only, and the destination's name once one is chosen.
        ExportJobFixture f = new ExportJobFixture();
        var archive = org.mockito.Mockito.mock(com.securedoc.platform.domain.integration.IntegrationConnection.class);
        org.mockito.Mockito.when(archive.name()).thenReturn("Records archive");
        org.mockito.Mockito.when(f.connections.findByTenantIdAndId(f.tenant, f.destination.id))
                .thenReturn(java.util.Optional.of(archive));
        DocumentPackage forIt = f.caseReadyForExport();
        DocumentPackage unknownCode = f.caseReadyForExport();
        DocumentPackage queued = f.caseReadyForExport();
        PipelineJob itJob = f.jobs.all().stream().filter(j -> j.packageId().equals(forIt.id())).findFirst().orElseThrow();
        f.jobs.changeStatus(f.tenant, itJob.id(), PipelineJobStatus.READY, PipelineJobStatus.NEEDS_IT, 1, "LV-DLV-004",
                "DESTINATION_REFUSED");
        PipelineJob oddJob = f.jobs.all().stream().filter(j -> j.packageId().equals(unknownCode.id())).findFirst()
                .orElseThrow();
        f.jobs.changeStatus(f.tenant, oddJob.id(), PipelineJobStatus.READY, PipelineJobStatus.NEEDS_IT, 1,
                "Jane Doe 123-45-6789", "X");

        Map<PackageId, ExportJobService.CaseExportRow> rows = f.service.caseExportRows(f.tenant,
                List.of(forIt.id(), unknownCode.id(), queued.id(), PackageId.generate()));

        assertEquals(new ExportJobService.CaseExportRow(ExportJobService.CaseExportState.NEEDS_IT, "LV-DLV-004",
                "Records archive"), rows.get(forIt.id()));
        assertEquals(new ExportJobService.CaseExportRow(ExportJobService.CaseExportState.NEEDS_IT, null,
                "Records archive"), rows.get(unknownCode.id()), "text that is no LV code is never passed on");
        assertEquals(new ExportJobService.CaseExportRow(ExportJobService.CaseExportState.READY, null, "Records archive"),
                rows.get(queued.id()), "queued: no reason, the destination is chosen");
        rows.values().forEach(r -> assertFalse(String.valueOf(r).contains("Jane")));
    }

    // ------------------------------------------------------------------ helpers

    /** The defaults with a different first retry delay (the rest of the schedule as default). */
    private static ExportJobSettings firstDelay(Duration first) {
        ExportJobSettings d = ExportJobSettings.defaults(ExportMode.JOB);
        List<Duration> schedule = new ArrayList<>(d.retrySchedule());
        schedule.set(0, first);
        return new ExportJobSettings(d.mode(), d.retryDelay(), d.maxAttempts(), d.pauseAfterFailures(),
                d.probeInterval(), d.claimLease(), d.workersPerDestination(), d.workersTotal(), schedule,
                d.rebuildBeforeRetry(), d.failingCasesToPause(), d.failingCasesWindow(), d.rateLimitDefaultWait(),
                d.rateLimitRecovery(), d.allUnreachableWindow());
    }

    private static void assertJob(ExportJobFixture f, DocumentPackage pkg, PipelineJobStatus status, int attempts,
                                  String code) {
        PipelineJob job = f.jobOf(pkg);
        assertEquals(status, job.status(), "status");
        assertEquals(attempts, job.attempts(), "attempts");
        assertEquals(code, job.lastLvCode(), "code");
    }

    private static String contains(String text) {
        return argThat(value -> value != null && value.contains(text));
    }
}

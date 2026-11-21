package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.pipeline.DestinationState;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import com.securedoc.platform.domain.routing.DerivedDelivery;
import com.securedoc.platform.domain.routing.DerivedDeliveryStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.securedoc.platform.application.pipeline.ExportJobFixture.minutes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Task 0027 (staged pipeline P1): export as its own job, with retries by code. */
class ExportJobServiceTest {

    // ------------------------------------------------------------------ a case and its split documents

    @Test
    void aCaseIsExportedByItsJobAndOnlyThenCompleted() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();

        assertEquals(PackageStatus.PROCESSING, pkg.status());
        assertEquals(ExportJobService.CaseExportState.READY_FOR_EXPORT, f.service.caseExportState(f.tenant, pkg.id()));
        assertEquals(f.destination.id, f.jobOf(pkg).destinationId(), "one EXPORT job for the case's destination");

        assertEquals(1, f.drain());

        PipelineJob job = f.jobOf(pkg);
        assertEquals(PipelineJobStatus.DONE, job.status());
        assertEquals(1, job.attempts());
        assertEquals(PackageStatus.COMPLETED, pkg.status());
        assertEquals(ExportJobService.CaseExportState.EXPORTED, f.service.caseExportState(f.tenant, pkg.id()));
        assertEquals(List.of(pkg.id()), f.destination.deliveredInOrder);
        verify(f.lifecycle).recordSuccessfulExport(eq(pkg.id()), eq(f.tenant), eq(1), eq("sdv"), anyList(), any());
        verify(f.routing).processPackage(eq(pkg.id()), eq(f.tenant), eq(1), any());
        verify(f.audit).recordSuccess(eq(f.tenant), any(), eq(AuditAction.EXPORT_JOB_QUEUED), anyString(), anyString(),
                eq(pkg.id()), any(), contains("stage=EXPORT"));
        verify(f.audit).recordSuccess(eq(f.tenant), any(), eq(AuditAction.EXPORT_JOB_STATUS_CHANGED), anyString(),
                anyString(), eq(pkg.id()), any(), contains("to=DONE"));
    }

    @Test
    void queuingTheSameCaseRunTwiceAddsNothing() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        f.queue.queueCaseExport(f.tenant, pkg.id(), 1, CorrelationId.generate());
        assertEquals(1, f.jobs.all().size());
    }

    @Test
    void splitDocumentsAreSentByTheirOwnJobs() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        IntegrationConnectionId splitDestination = IntegrationConnectionId.generate();
        DocumentId source = f.documents.get(pkg.id()).get(0).id();
        List<DerivedDelivery> splits = List.of(split(UUID.randomUUID()), split(UUID.randomUUID()));
        // What derived routing does in job mode: one SPLIT_EXPORT job per split document instead of sending it.
        doAnswer(i -> {
            for (DerivedDelivery d : splits) {
                f.queue.queueSplitExport(f.tenant, pkg.id(), source, d.id(), splitDestination, 1, i.getArgument(3));
            }
            return null;
        }).when(f.routing).processPackage(eq(pkg.id()), eq(f.tenant), eq(1), any());
        Map<UUID, DerivedDelivery> byId = new java.util.HashMap<>();
        for (DerivedDelivery d : splits) byId.put(d.id(), d);
        doAnswer(i -> byId.get(i.<UUID>getArgument(1))).when(f.derived).get(eq(f.tenant), any(UUID.class));

        assertEquals(3, f.drain(), "the case, then its two split documents");

        for (UUID id : byId.keySet()) {
            verify(f.derived).autoSend(eq(f.tenant), eq(id), any());
            PipelineJob job = f.jobs.all().stream().filter(j -> id.equals(j.itemId())).findFirst().orElseThrow();
            assertEquals(PipelineStage.SPLIT_EXPORT, job.stage());
            assertEquals(PipelineJobStatus.DONE, job.status());
            assertEquals(splitDestination, job.destinationId());
            assertEquals(source, job.documentId());
        }
    }

    @Test
    void aSplitDocumentAlreadyDeliveredIsNotSentAgain() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        f.drain();
        UUID deliveredId = UUID.randomUUID();
        DerivedDelivery delivered = split(deliveredId);
        when(delivered.status()).thenReturn(DerivedDeliveryStatus.DELIVERED);
        when(f.derived.get(f.tenant, deliveredId)).thenReturn(delivered);
        f.queue.queueSplitExport(f.tenant, pkg.id(), null, deliveredId, IntegrationConnectionId.generate(), 1,
                CorrelationId.generate());

        f.drain();

        verify(f.derived, never()).autoSend(any(), any(), any());
    }

    // ------------------------------------------------------------------ the P1 proof

    @Test
    void p1ProofDestinationDownMidRunPausesThenEveryCaseGoesOutInOrder() {
        ExportJobFixture f = new ExportJobFixture();
        List<DocumentPackage> cases = new ArrayList<>();
        for (int i = 0; i < 6; i++) cases.add(f.caseReadyForExport());

        // The first case goes out; then the destination goes down mid-run.
        assertTrue(f.step("worker-1").isPresent());
        f.destination.down();
        for (int i = 0; i < 3; i++) assertTrue(f.step("worker-1").isPresent());

        // 3 failures in a row across the destination's cases: paused.
        assertEquals(DestinationState.PAUSED, f.states.stateOf(f.destination.id));
        Map<UUID, Integer> attemptsWhenPaused = attempts(f);
        verify(f.audit).recordFailure(eq(f.tenant), any(), eq(AuditAction.EXPORT_DESTINATION_PAUSED), anyString(),
                anyString(), any(), contains("code=LV-DLV-001"));

        // While paused, nothing is taken and no attempt is used, however long it takes; nothing fails.
        for (int i = 0; i < 4; i++) {
            f.clock.advance(minutes(4));
            assertEquals(0, f.service.probePausedDestinations(), "the destination is still down");
            assertTrue(f.step("worker-1").isEmpty(), "no job is taken while the destination is paused");
        }
        assertEquals(attemptsWhenPaused, attempts(f));
        assertTrue(f.jobs.all().stream().noneMatch(j -> j.status() == PipelineJobStatus.NEEDS_IT
                || j.status() == PipelineJobStatus.STOPPED || j.status() == PipelineJobStatus.WAITING_FOR_YOU));
        assertTrue(cases.stream().skip(1).allMatch(c -> c.status() == PackageStatus.PROCESSING), "nothing failed");
        assertEquals(ExportJobService.CaseExportState.DESTINATION_PAUSED,
                f.service.caseExportState(f.tenant, cases.get(5).id()));
        verify(f.incidents, never()).recordFailure(any(), any(), any(), any(), any(), any(), any(), any());

        // It comes back: the next probe finds it and every case goes out in its original order.
        f.destination.up();
        f.clock.advance(minutes(5));
        assertEquals(1, f.service.probePausedDestinations());
        assertEquals(DestinationState.WORKING, f.states.stateOf(f.destination.id));
        assertEquals(5, f.drain());

        assertEquals(cases.stream().map(DocumentPackage::id).toList(), f.destination.deliveredInOrder);
        assertTrue(cases.stream().allMatch(c -> c.status() == PackageStatus.COMPLETED));
        assertTrue(f.jobs.all().stream().allMatch(j -> j.status() == PipelineJobStatus.DONE));
        verify(f.audit).recordSuccess(eq(f.tenant), any(), eq(AuditAction.EXPORT_DESTINATION_RESUMED), anyString(),
                anyString(), any(), contains("by=probe"));
    }

    @Test
    void aPausedDestinationIsProbedOnlyEveryProbeInterval() {
        ExportJobFixture f = new ExportJobFixture();
        for (int i = 0; i < 3; i++) f.caseReadyForExport();
        f.destination.down();
        f.drain();
        assertEquals(DestinationState.PAUSED, f.states.stateOf(f.destination.id));
        f.destination.up();

        f.clock.advance(minutes(4));
        assertEquals(0, f.service.probePausedDestinations(), "not before 5 minutes");
        f.clock.advance(minutes(1));
        assertEquals(1, f.service.probePausedDestinations());
    }

    // ------------------------------------------------------------------ retries by code (section 3 table)

    @Test
    void unreachableOrBusyWaitsFiveMinutesThenNeedsItAfterTheThirdTry() {
        for (LvErrorCode code : List.of(LvErrorCode.DLV_001, LvErrorCode.DLV_002)) {
            // A lone job: pausing is set higher here so the row "after the 3rd try, NEEDS_IT" is seen on its own.
            ExportJobFixture f = new ExportJobFixture(settings(10));
            DocumentPackage pkg = f.caseReadyForExport();
            f.destination.failWith = code;

            f.step("w");
            assertJob(f, pkg, PipelineJobStatus.WAITING_RETRY, 1, code.code());
            f.clock.advance(minutes(4));
            assertTrue(f.step("w").isEmpty(), code + ": the next try is 5 minutes later");
            f.clock.advance(minutes(1));
            f.step("w");
            assertJob(f, pkg, PipelineJobStatus.WAITING_RETRY, 2, code.code());
            f.clock.advance(minutes(5));
            f.step("w");

            assertJob(f, pkg, PipelineJobStatus.NEEDS_IT, 3, code.code());
            assertEquals(PackageStatus.PROCESSING, pkg.status(), "the case is not failed");
            assertEquals(ExportJobService.CaseExportState.NEEDS_IT, f.service.caseExportState(f.tenant, pkg.id()));
            assertItQueue(f, pkg, code);
        }
    }

    @Test
    void withTheDefaultsALoneFailingJobPausesItsDestinationAtItsThirdTry() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        f.destination.down();
        for (int i = 0; i < 3; i++) {
            f.step("w");
            f.clock.advance(minutes(5));
        }
        assertJob(f, pkg, PipelineJobStatus.WAITING_RETRY, 3, "LV-DLV-001");
        assertEquals(DestinationState.PAUSED, f.states.stateOf(f.destination.id));
    }

    @Test
    void theLocalFolderHasNoDestinationStateSoAJobGoesToItAfterThreeTries() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        // Re-queue the same case as a local-folder job (no connection id).
        f.jobs.all().forEach(j -> f.jobs.changeStatus(f.tenant, j.id(), j.status(), PipelineJobStatus.STOPPED, 0, null, null));
        f.jobs.enqueue(PipelineJob.ready(f.tenant, PipelineStage.EXPORT, pkg.id(), null, null, null, 2,
                f.clock.instant()));
        f.nextCycle.put(pkg.id(), 2);
        f.destination.failWith = LvErrorCode.DLV_001;
        for (int i = 0; i < 3; i++) {
            f.step("w");
            f.clock.advance(minutes(5));
        }
        PipelineJob job = f.jobs.all().stream().filter(j -> j.exportCycle() == 2).findFirst().orElseThrow();
        assertEquals(PipelineJobStatus.NEEDS_IT, job.status());
    }

    @Test
    void alreadyReceivedCountsAsExportedAndIsAuditedWithDlv009() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        f.destination.answerAlreadyReceived = true;

        f.drain();

        assertJob(f, pkg, PipelineJobStatus.DONE, 1, "LV-DLV-009");
        assertEquals(PackageStatus.COMPLETED, pkg.status());
        verify(f.audit).recordSuccess(eq(f.tenant), any(), eq(AuditAction.EXPORT_JOB_STATUS_CHANGED), anyString(),
                anyString(), eq(pkg.id()), any(), contains("code=LV-DLV-009"));
    }

    @Test
    void anAnswerCodedAlreadyReceivedIsDoneAndNeverRetried() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        f.destination.failWith = LvErrorCode.DLV_009;

        f.drain();
        f.clock.advance(minutes(30));
        f.drain();

        assertJob(f, pkg, PipelineJobStatus.DONE, 1, "LV-DLV-009");
        assertEquals(1, f.destination.keys.size(), "never retried");
    }

    @Test
    void refusedLevelOrFileWaitsForAPersonWithoutRetrying() {
        for (LvErrorCode code : List.of(LvErrorCode.DLV_003, LvErrorCode.DLV_004)) {
            ExportJobFixture f = new ExportJobFixture();
            DocumentPackage pkg = f.caseReadyForExport();
            f.destination.failWith = code;

            f.drain();
            f.clock.advance(minutes(60));
            f.drain();

            assertJob(f, pkg, PipelineJobStatus.WAITING_FOR_YOU, 1, code.code());
            assertEquals(1, f.destination.keys.size(), code + ": no automatic retry");
            assertEquals(ExportJobService.CaseExportState.WAITING_FOR_YOU,
                    f.service.caseExportState(f.tenant, pkg.id()));
            assertEquals(1, f.service.waitingForYou(f.tenant).size());
            assertEquals(DestinationState.WORKING, f.states.stateOf(f.destination.id));
        }
    }

    @Test
    void signInRefusedPutsTheDestinationAndTheJobInTheItQueue() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage first = f.caseReadyForExport();
        DocumentPackage second = f.caseReadyForExport();
        f.destination.failWith = LvErrorCode.DLV_005;

        f.drain();

        assertJob(f, first, PipelineJobStatus.NEEDS_IT, 1, "LV-DLV-005");
        assertEquals(DestinationState.NEEDS_IT, f.states.stateOf(f.destination.id));
        assertJob(f, second, PipelineJobStatus.READY, 0, null);
        assertEquals(ExportJobService.CaseExportState.DESTINATION_PAUSED,
                f.service.caseExportState(f.tenant, second.id()));
        verify(f.audit).recordFailure(eq(f.tenant), any(), eq(AuditAction.EXPORT_DESTINATION_NEEDS_IT), anyString(),
                anyString(), any(), contains("code=LV-DLV-005"));
        assertItQueue(f, first, LvErrorCode.DLV_005);
    }

    @Test
    void answerNotUnderstoodOrNotAllConfirmedIsTriedThreeTimesThenNeedsIt() {
        for (LvErrorCode code : List.of(LvErrorCode.DLV_007, LvErrorCode.DLV_008)) {
            ExportJobFixture f = new ExportJobFixture();
            DocumentPackage pkg = f.caseReadyForExport();
            f.destination.failWith = code;
            for (int i = 0; i < 3; i++) {
                f.step("w");
                f.clock.advance(minutes(5));
            }
            assertJob(f, pkg, PipelineJobStatus.NEEDS_IT, 3, code.code());
            assertEquals(DestinationState.WORKING, f.states.stateOf(f.destination.id),
                    code + ": the destination answered, so it is not paused");
        }
    }

    @Test
    void anythingElseGoesToItWithItsCodeOrSys001() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage notSetUp = f.caseReadyForExport();
        f.destination.failWith = LvErrorCode.DLV_006;
        f.step("w");
        assertJob(f, notSetUp, PipelineJobStatus.NEEDS_IT, 1, "LV-DLV-006");

        ExportJobFixture g = new ExportJobFixture();
        DocumentPackage pkg = g.caseReadyForExport();
        g.documents.put(pkg.id(), List.of()); // the export service refuses: nothing to export (LV-DLV-011)
        g.step("w");
        assertJob(g, pkg, PipelineJobStatus.NEEDS_IT, 1, "LV-DLV-011");

        ExportJobFixture h = new ExportJobFixture();
        DocumentPackage odd = h.caseReadyForExport();
        when(h.lifecycle.nextExportCycle(odd.id(), h.tenant)).thenThrow(new IllegalArgumentException("odd"));
        h.step("w");
        assertJob(h, odd, PipelineJobStatus.NEEDS_IT, 1, "LV-SYS-001");
    }

    // ------------------------------------------------------------------ resend and the IT queue

    @Test
    void resendOfSeveralJobsIsOneAttemptEachAndAFailedResendGoesToItWithDlv015() {
        ExportJobFixture f = new ExportJobFixture();
        List<DocumentPackage> cases = List.of(f.caseReadyForExport(), f.caseReadyForExport(), f.caseReadyForExport());
        f.destination.failWith = LvErrorCode.DLV_004;
        f.drain();
        List<UUID> waiting = f.service.waitingForYou(f.tenant).stream().map(ExportJobService.JobView::jobId).toList();
        assertEquals(3, waiting.size());
        int callsBefore = f.destination.keys.size();

        // The destination now takes two of them and still refuses the third.
        List<UUID> accepted = List.of(waiting.get(0), waiting.get(1));
        f.destination.failWith = null;
        List<ExportJobService.JobResult> first = f.service.resend(f.tenant, accepted, ActorId.generate(),
                CorrelationId.generate());
        f.destination.failWith = LvErrorCode.DLV_004;
        List<ExportJobService.JobResult> second = f.service.resend(f.tenant, List.of(waiting.get(2)),
                ActorId.generate(), CorrelationId.generate());

        assertEquals(List.of("DONE", "DONE"), first.stream().map(ExportJobService.JobResult::status).toList());
        assertEquals("NEEDS_IT", second.get(0).status());
        assertEquals("LV-DLV-015", second.get(0).lvCode());
        assertEquals(callsBefore + 3, f.destination.keys.size(), "one attempt each");
        PipelineJob failed = f.jobs.get(waiting.get(2));
        assertEquals("LV-DLV-015", failed.lastLvCode());
        assertEquals("LV-DLV-004", failed.lastDetail(), "the failure's own code is the detail");
        assertEquals(2, failed.attempts());
        assertEquals(2, cases.stream().filter(c -> c.status() == PackageStatus.COMPLETED).count());
        verify(f.audit, times(3)).recordSuccess(eq(f.tenant), any(), eq(AuditAction.EXPORT_JOB_RESEND_REQUESTED),
                anyString(), anyString(), any(), any(), anyString());
        assertItQueue(f, f.packages.get(failed.packageId()), LvErrorCode.DLV_015);

        // A job that is not waiting for a person is refused and left as it is.
        ExportJobService.JobResult again = f.service.resend(f.tenant, List.of(waiting.get(0)), ActorId.generate(),
                CorrelationId.generate()).get(0);
        assertFalse(again.saved());
        assertEquals("LV-CFG-011", again.lvCode());
        assertEquals(PipelineJobStatus.DONE, f.jobs.get(waiting.get(0)).status());
    }

    @Test
    void anItRetryPutsTheJobBackToReadyAndCanSetItsDestinationWorking() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        f.destination.failWith = LvErrorCode.DLV_005;
        f.drain();
        assertEquals(DestinationState.NEEDS_IT, f.states.stateOf(f.destination.id));
        UUID jobId = f.jobOf(pkg).id();
        assertEquals(List.of(jobId), f.service.needsIt(f.tenant).stream().map(ExportJobService.JobView::jobId).toList());

        f.destination.failWith = null; // IT fixed the credential
        List<ExportJobService.JobResult> result = f.service.itRetry(f.tenant, List.of(jobId), true,
                ActorId.generate(), CorrelationId.generate());

        assertEquals("READY", result.get(0).status());
        assertJob(f, pkg, PipelineJobStatus.READY, 0, "LV-DLV-005");
        assertEquals(DestinationState.WORKING, f.states.stateOf(f.destination.id));
        f.drain();
        assertJob(f, pkg, PipelineJobStatus.DONE, 1, null);
        assertEquals(PackageStatus.COMPLETED, pkg.status());

        // Only NEEDS_IT jobs can be retried by IT.
        assertEquals("LV-CFG-011", f.service.itRetry(f.tenant, List.of(jobId), false, ActorId.generate(),
                CorrelationId.generate()).get(0).lvCode());
    }

    // ------------------------------------------------------------------ claims

    @Test
    void twoWorkersCanNotTakeTheSameJob() {
        ExportJobFixture f = new ExportJobFixture();
        f.caseReadyForExport();
        Optional<PipelineJob> a = f.service.claimNext("worker-a");
        Optional<PipelineJob> b = f.service.claimNext("worker-b");
        assertTrue(a.isPresent());
        assertTrue(b.isEmpty());
    }

    @Test
    void aWorkerWhoseClaimRanOutCanNotSaveItsResult() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        PipelineJob stale = f.service.claimNext("worker-a").orElseThrow();

        f.clock.advance(f.settings.claimLease().plusSeconds(1));
        assertEquals(1, f.service.releaseExpiredClaims());
        assertJob(f, pkg, PipelineJobStatus.READY, 1, null);
        PipelineJob current = f.service.claimNext("worker-b").orElseThrow();
        assertTrue(current.claimToken() > stale.claimToken());

        ExportJobService.JobResult late = f.service.run(stale);
        assertFalse(late.saved(), "the old token no longer counts");
        assertTrue(f.destination.keys.isEmpty(), "a worker whose claim ran out sends nothing");
        assertEquals(PipelineJobStatus.RUNNING, f.jobOf(pkg).status());
        assertTrue(f.service.run(current).saved());
        assertJob(f, pkg, PipelineJobStatus.DONE, 2, null);
    }

    @Test
    void aRenewedClaimDoesNotRunOut() {
        ExportJobFixture f = new ExportJobFixture();
        f.caseReadyForExport();
        PipelineJob job = f.service.claimNext("worker-a").orElseThrow();
        for (int i = 0; i < 5; i++) {
            f.clock.advance(Duration.ofSeconds(40));
            assertTrue(f.service.renew(job));
        }
        assertEquals(0, f.service.releaseExpiredClaims());
    }

    @Test
    void aServerStoppingMidExportComesBackAfterTheClaimRunsOutAndIsSentOnceWithTheSameKey() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        f.destination.stopServerOnNextCall = true;

        PipelineJob first = f.service.claimNext("server-1").orElseThrow();
        assertThrows(ExportJobFixture.ServerStopped.class, () -> f.service.run(first));
        assertEquals(PipelineJobStatus.RUNNING, f.jobOf(pkg).status(), "nothing was saved");

        f.clock.advance(f.settings.claimLease().plusSeconds(1));
        f.service.releaseExpiredClaims();
        assertJob(f, pkg, PipelineJobStatus.READY, 1, null);
        f.drain();

        assertJob(f, pkg, PipelineJobStatus.DONE, 2, null);
        assertEquals(2, f.destination.keys.size());
        assertEquals(1, f.destination.keys.stream().distinct().count(), "the same idempotency key both times");
        assertEquals(1, f.destination.stored.size(), "the destination has it once");
        assertEquals(PackageStatus.COMPLETED, pkg.status());
    }

    @Test
    void aJobWhoseClaimRunsOutOnItsLastAttemptGoesToItWithPrc008() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        for (int i = 0; i < 3; i++) {
            f.service.claimNext("server-" + i).orElseThrow();
            f.clock.advance(f.settings.claimLease().plusSeconds(1));
            f.service.releaseExpiredClaims();
        }
        assertJob(f, pkg, PipelineJobStatus.NEEDS_IT, 3, "LV-PRC-008");
        assertItQueue(f, pkg, LvErrorCode.PRC_008);
    }

    @Test
    void anExportAlreadyRecordedForThisRunIsNotSentAgain() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        f.nextCycle.put(pkg.id(), 2); // the export of cycle 1 was recorded, but its job result was lost

        f.drain();

        assertJob(f, pkg, PipelineJobStatus.DONE, 1, null);
        assertTrue(f.destination.keys.isEmpty());
        assertEquals(PackageStatus.COMPLETED, pkg.status());
    }

    @Test
    void aCaseFinishedByItsLastReviewDecisionIsExportedAndCompletedToo() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        pkg.completeProcessing(); // PROCESSING -> PENDING_REVIEW, as after a review
        assertEquals(PackageStatus.PENDING_REVIEW, pkg.status());

        f.drain();

        assertJob(f, pkg, PipelineJobStatus.DONE, 1, null);
        assertEquals(PackageStatus.COMPLETED, pkg.status());
    }

    @Test
    void aCaseThatIsNoLongerProcessingIsStopped() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        pkg.fail();
        f.drain();
        assertJob(f, pkg, PipelineJobStatus.STOPPED, 1, "LV-CFG-011");
        assertTrue(f.destination.keys.isEmpty());
    }

    // ------------------------------------------------------------------ helpers

    private static ExportJobSettings settings(int pauseAfterFailures) {
        ExportJobSettings d = ExportJobSettings.defaults(ExportMode.JOB);
        return new ExportJobSettings(d.mode(), d.retryDelay(), d.maxAttempts(), pauseAfterFailures, d.probeInterval(),
                d.claimLease(), d.workersPerDestination(), d.workersTotal());
    }

    private static DerivedDelivery split(UUID id) {
        DerivedDelivery d = mock(DerivedDelivery.class);
        when(d.id()).thenReturn(id);
        when(d.status()).thenReturn(DerivedDeliveryStatus.PENDING);
        return d;
    }

    private static Map<UUID, Integer> attempts(ExportJobFixture f) {
        return f.jobs.all().stream().collect(Collectors.toMap(PipelineJob::id, PipelineJob::attempts));
    }

    private static void assertJob(ExportJobFixture f, DocumentPackage pkg, PipelineJobStatus status, int attempts,
                                  String code) {
        PipelineJob job = f.jobOf(pkg);
        assertEquals(status, job.status(), "status");
        assertEquals(attempts, job.attempts(), "attempts");
        assertEquals(code, job.lastLvCode(), "code");
    }

    private static void assertItQueue(ExportJobFixture f, DocumentPackage pkg, LvErrorCode code) {
        verify(f.incidents, atLeastOnce()).recordFailure(eq(f.tenant), eq(pkg.id()), any(), any(),
                argThat(e -> com.securedoc.platform.application.error.LvErrorCodes.of(e) == code), any(), anyString(),
                any());
        verify(f.audit, atLeastOnce()).recordFailure(eq(f.tenant), any(), eq(AuditAction.EXPORT_JOB_NEEDS_IT_NOTIFICATION),
                anyString(), eq(pkg.id().toString()), eq(pkg.id()), any(), contains("code=" + code.code()));
    }

    private static String contains(String text) {
        return argThat(value -> value != null && value.contains(text));
    }
}

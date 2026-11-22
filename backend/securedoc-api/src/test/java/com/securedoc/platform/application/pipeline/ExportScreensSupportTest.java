package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.pipeline.DestinationState;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import static com.securedoc.platform.application.pipeline.ExportJobFixture.minutes;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/** Task 0029: what the Support export screen needs from the backend: "Check now", the next check, the waiting count. */
class ExportScreensSupportTest {

    @Test
    void checkNowBringsAPausedDestinationBackWhenItsCheckPassesAndItsCasesGoOutInOrder() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage first = f.caseReadyForExport();
        DocumentPackage second = f.caseReadyForExport();
        f.destination.down();
        f.step("w");
        assertEquals(DestinationState.PAUSED, f.states.stateOf(f.destination.id));

        f.destination.up();
        ActorId it = ActorId.generate();
        var result = f.service.checkDestinationNow(f.tenant, f.destination.id, it, CorrelationId.generate());

        assertTrue(result.isPresent());
        assertTrue(result.get().passed());
        assertTrue(result.get().resumed());
        assertEquals("WORKING", result.get().state());
        assertEquals(DestinationState.WORKING, f.states.stateOf(f.destination.id));
        verify(f.audit).recordSuccess(eq(f.tenant), eq(it), eq(AuditAction.EXPORT_DESTINATION_RESUMED),
                eq("IntegrationConnection"), eq(f.destination.id.value().toString()), any(),
                org.mockito.ArgumentMatchers.contains("by=check-now"));
        assertEquals(2, f.drain());
        assertEquals(List.of(first.id(), second.id()), f.destination.deliveredInOrder);
    }

    @Test
    void checkNowThatFailsLeavesThePausedDestinationPausedAndRecordsTheCheck() {
        ExportJobFixture f = new ExportJobFixture();
        f.caseReadyForExport();
        f.destination.down();
        f.step("w");
        f.clock.advance(minutes(2));

        var result = f.service.checkDestinationNow(f.tenant, f.destination.id, ActorId.generate(),
                CorrelationId.generate());

        assertTrue(result.isPresent());
        assertFalse(result.get().passed());
        assertFalse(result.get().resumed());
        assertEquals("PAUSED", result.get().state());
        assertEquals(f.clock.instant(), f.states.rowOf(f.destination.id).lastProbeAt(), "the check is recorded");
        assertNotNull(f.states.rowOf(f.destination.id).lastCheckFailedAt());
    }

    @Test
    void checkNowOnAWorkingDestinationOnlyChecks() {
        ExportJobFixture f = new ExportJobFixture();
        var result = f.service.checkDestinationNow(f.tenant, f.destination.id, ActorId.generate(),
                CorrelationId.generate());
        assertTrue(result.get().passed());
        assertFalse(result.get().resumed());
        assertEquals("WORKING", result.get().state());
    }

    @Test
    void checkNowRefusesADestinationThatIsNotTheTenants() {
        ExportJobFixture f = new ExportJobFixture();
        assertTrue(f.service.checkDestinationNow(f.tenant, IntegrationConnectionId.generate(), ActorId.generate(),
                CorrelationId.generate()).isEmpty());
        assertTrue(f.checked.isEmpty(), "no check runs for an unknown destination");
    }

    @Test
    void destinationsShowTheNextCheckWhilePausedAndHowManyExportsAreStillToBeDone() {
        ExportJobFixture f = new ExportJobFixture();
        IntegrationConnectionId split = f.addDestination(true);
        DocumentPackage pkg = f.caseReadyForExport();
        for (int i = 0; i < 3; i++) {
            f.jobs.enqueue(PipelineJob.ready(f.tenant, PipelineStage.SPLIT_EXPORT, pkg.id(), DocumentId.generate(),
                    split, UUID.randomUUID(), 1, f.clock.instant()));
        }
        PipelineJob done = PipelineJob.ready(f.tenant, PipelineStage.SPLIT_EXPORT, pkg.id(), DocumentId.generate(),
                split, UUID.randomUUID(), 1, f.clock.instant());
        f.jobs.enqueue(done);
        f.jobs.changeStatus(f.tenant, done.id(), PipelineJobStatus.READY, PipelineJobStatus.DONE, 1, null, null);

        var working = f.service.destinationStates(f.tenant).stream()
                .filter(d -> d.destinationId().equals(split.value())).findFirst().orElseThrow();
        assertEquals("WORKING", working.state());
        assertNull(working.nextCheckAt(), "no next check while working");
        assertEquals(3, working.openJobs(), "a done export is not counted");

        f.states.transition(f.tenant, split, EnumSet.of(DestinationState.WORKING), DestinationState.PAUSED, "LV-DLV-001");
        var paused = f.service.destinationStates(f.tenant).stream()
                .filter(d -> d.destinationId().equals(split.value())).findFirst().orElseThrow();
        assertEquals("PAUSED", paused.state());
        assertEquals(f.clock.instant().plus(f.settings.probeInterval()), paused.nextCheckAt(),
                "checked one probe interval after it paused");
        assertEquals(3, paused.openJobs());
    }

    @Test
    void waitingCountsEachStateOnItsOwnAndNeverCountsNeedsItAsReady() {
        // Task 0036 (0035 question 5): Support › Destinations, "2 ready", "1 waiting for you", "2 need IT".
        ExportJobFixture f = new ExportJobFixture();
        IntegrationConnectionId split = f.addDestination(true);
        DocumentPackage pkg = f.caseReadyForExport();
        List<PipelineJob> jobs = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            PipelineJob job = PipelineJob.ready(f.tenant, PipelineStage.SPLIT_EXPORT, pkg.id(), DocumentId.generate(),
                    split, UUID.randomUUID(), 1, f.clock.instant());
            f.jobs.enqueue(job);
            jobs.add(job);
        }
        f.jobs.changeStatus(f.tenant, jobs.get(1).id(), PipelineJobStatus.READY, PipelineJobStatus.WAITING_RETRY, 1,
                "LV-DLV-001", null);
        f.jobs.changeStatus(f.tenant, jobs.get(2).id(), PipelineJobStatus.READY, PipelineJobStatus.WAITING_FOR_YOU, 1,
                "LV-DLV-004", null);
        f.jobs.changeStatus(f.tenant, jobs.get(3).id(), PipelineJobStatus.READY, PipelineJobStatus.NEEDS_IT, 6,
                "LV-DLV-001", null);
        f.jobs.changeStatus(f.tenant, jobs.get(4).id(), PipelineJobStatus.READY, PipelineJobStatus.NEEDS_IT, 6,
                "LV-DLV-001", null);
        f.jobs.changeStatus(f.tenant, jobs.get(5).id(), PipelineJobStatus.READY, PipelineJobStatus.DONE, 1, null, null);

        var view = f.service.destinationStates(f.tenant).stream()
                .filter(d -> d.destinationId().equals(split.value())).findFirst().orElseThrow();
        assertEquals(new ExportJobService.WaitingCounts(2, 1, 2), view.waiting());
        assertEquals(5, view.openJobs(), "the total is still every open job");
    }

    @Test
    void timingsComeFromTheSettings() {
        ExportJobService.ExportTimings defaults = new ExportJobFixture().service.timings();
        assertEquals(300, defaults.probeIntervalSeconds(), "5 minutes");
        assertEquals(17 * 3600 + 20 * 60, defaults.retrySpanSeconds(), "5 min + 15 min + 1 h + 4 h + 12 h = 17 h 20 min");
        assertEquals(5, defaults.retries());
        assertTrue(defaults.rebuiltOnce(), "rebuilt before the 3rd retry");
        assertEquals(3, defaults.maxInterruptions());

        ExportJobSettings d = ExportJobSettings.defaults(ExportMode.JOB);
        ExportJobSettings custom = new ExportJobSettings(d.mode(), d.retryDelay(), 4, d.pauseAfterFailures(),
                java.time.Duration.ofSeconds(4), d.claimLease(), d.workersPerDestination(), d.workersTotal(),
                ExportJobSettings.parseSchedule("2s,2s"), 3, d.failingCasesToPause(), d.failingCasesWindow(),
                d.rateLimitDefaultWait(), d.rateLimitRecovery(), d.allUnreachableWindow());
        ExportJobService.ExportTimings timings = new ExportJobFixture(custom).service.timings();
        assertEquals(4, timings.probeIntervalSeconds());
        assertEquals(4, timings.retrySpanSeconds());
        assertEquals(2, timings.retries());
        assertFalse(timings.rebuiltOnce(), "the rebuild comes before the 3rd retry, and there are only 2");
        assertEquals(4, timings.maxInterruptions());
    }

    @Test
    void itGroupsListTheirCasesByCaseNumber() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage numbered = f.caseReadyForExport();
        DocumentPackage unnumbered = f.caseReadyForExport();
        f.caseNumbers.put(numbered.id(), "100200300400");
        f.destination.failWith = com.securedoc.platform.domain.error.LvErrorCode.DLV_006;
        f.drain();

        List<ExportJobService.ItGroup> groups = f.service.needsItGroups(f.tenant);
        assertEquals(1, groups.size());
        assertEquals(List.of(new ExportJobService.CaseRef(numbered.id().value(), "100200300400"),
                new ExportJobService.CaseRef(unnumbered.id().value(), null)), groups.get(0).cases());
        assertEquals(List.of(numbered.id().value(), unnumbered.id().value()), groups.get(0).caseIds());
    }
}

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
}

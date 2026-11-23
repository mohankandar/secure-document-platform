package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import com.securedoc.platform.domain.routing.DerivedDelivery;
import com.securedoc.platform.domain.routing.DerivedDeliveryStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Task 0030: the destination's own error code (for example DS-SEN-006) is stored with the job in {@code last_detail},
 * after LexaVault's internal reason, and the job's view (what the screens read) carries it. Only a code that matches the
 * safe pattern (letters, digits, '-', '_', up to 40 characters) is kept; anything else is dropped. One test per
 * destination kind: the full case (main export) and a split document.
 */
class ExportDestinationCodeTest {

    @Test
    void theMainExportStoresAndShowsASafeDestinationCode() {
        ExportJobFixture f = new ExportJobFixture();
        DocumentPackage pkg = f.caseReadyForExport();
        f.destination.failWith = LvErrorCode.DLV_003;
        f.destination.destinationCode = "DS-SEN-006";

        f.drain();

        PipelineJob job = f.jobOf(pkg);
        assertEquals(PipelineJobStatus.WAITING_FOR_YOU, job.status());
        assertEquals("LV-DLV-003", job.lastLvCode());
        assertEquals("DELIVERY_SENSITIVITY_REFUSED; destination=DS-SEN-006", job.lastDetail());
        assertEquals("DELIVERY_SENSITIVITY_REFUSED; destination=DS-SEN-006",
                f.service.jobsOfCase(f.tenant, pkg.id()).get(0).detail());
        assertEquals("DELIVERY_SENSITIVITY_REFUSED; destination=DS-SEN-006",
                f.service.waitingForYou(f.tenant).get(0).jobs().get(0).detail());
    }

    @Test
    void theMainExportDropsAnUnsafeDestinationCode() {
        for (String unsafe : List.of("<b>DS-SEN-006</b>", "DS SEN 006", "DS-SEN-006; destination=X", "DS-SEN-006\nx",
                "ÄDS", "", "a/b", "1234567890123456789012345678901234567890X")) {
            ExportJobFixture f = new ExportJobFixture();
            DocumentPackage pkg = f.caseReadyForExport();
            f.destination.failWith = LvErrorCode.DLV_003;
            f.destination.destinationCode = unsafe;

            f.drain();

            assertEquals("DELIVERY_SENSITIVITY_REFUSED", f.jobOf(pkg).lastDetail(), unsafe);
            assertEquals("DELIVERY_SENSITIVITY_REFUSED", f.service.jobsOfCase(f.tenant, pkg.id()).get(0).detail(), unsafe);
        }
    }

    @Test
    void aSplitDocumentStoresASafeDestinationCodeAndDropsAnUnsafeOne() {
        for (String[] answer : new String[][] {{"TC-REFUSED_17", "DELIVERY_DESTINATION_INVALID; destination=TC-REFUSED_17"},
                {"<script>", "DELIVERY_DESTINATION_INVALID"}}) {
            ExportJobFixture f = new ExportJobFixture();
            DocumentPackage pkg = f.caseReadyForExport();
            f.drain();
            UUID splitId = UUID.randomUUID();
            DerivedDelivery split = mock(DerivedDelivery.class);
            when(split.id()).thenReturn(splitId);
            when(split.status()).thenReturn(DerivedDeliveryStatus.PENDING);
            when(f.derived.get(f.tenant, splitId)).thenReturn(split);
            doThrow(new TechnicalDependencyException(TechnicalDependency.DELIVERY,
                    TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, "fake split answer")
                    .withLvCode(LvErrorCode.DLV_004).withDestinationCode(answer[0]))
                    .when(f.derived).autoSend(eq(f.tenant), eq(splitId), any());
            DocumentId source = f.documents.get(pkg.id()).get(0).id();
            f.queue.queueSplitExport(f.tenant, pkg.id(), source, splitId, IntegrationConnectionId.generate(), 1,
                    com.securedoc.platform.domain.identity.CorrelationId.generate());

            f.drain();

            PipelineJob job = f.jobs.all().stream().filter(j -> splitId.equals(j.itemId())).findFirst().orElseThrow();
            assertEquals(PipelineStage.SPLIT_EXPORT, job.stage());
            assertEquals(PipelineJobStatus.WAITING_FOR_YOU, job.status(), answer[0]);
            assertEquals(answer[1], job.lastDetail(), answer[0]);
            List<ExportJobService.JobView> views = f.service.jobsOfCase(f.tenant, pkg.id());
            assertTrue(views.stream().anyMatch(v -> answer[1].equals(v.detail())), answer[0]);
        }
    }

    @Test
    void aCodeThatWouldNotFitTheColumnIsDroppedAndTheReasonKept() {
        String reason = TechnicalFailureCode.DELIVERY_SENSITIVITY_REFUSED.name();
        String longest = "A".repeat(38); // 28 + 14 + 38 = 80: fits exactly
        assertEquals(reason + "; destination=" + longest, ExportJobService.withDestinationCode(reason, longest));
        assertEquals(reason, ExportJobService.withDestinationCode(reason, "A".repeat(39)), "81 characters: dropped");
        assertEquals(reason, ExportJobService.withDestinationCode(reason, null));
        assertEquals("DELIVERY_IO; destination=" + "B".repeat(40),
                ExportJobService.withDestinationCode("DELIVERY_IO", "B".repeat(40)));
    }

    @Test
    void theCodeSurvivesTheOtherMarksOnTheFailure() {
        TechnicalDependencyException failure = new TechnicalDependencyException(TechnicalDependency.DELIVERY,
                TechnicalFailureCode.DELIVERY_IO, true, "x").withDestinationCode("DS-ERR-1");
        assertEquals("DS-ERR-1", failure.withLvCode(LvErrorCode.DLV_002).destinationCode());
        assertEquals("DS-ERR-1", failure.withAttempts(3).destinationCode());
        assertEquals("DS-ERR-1", failure.asRateLimited(null).destinationCode());
        assertNull(failure.withDestinationCode("bad code").destinationCode());
    }
}

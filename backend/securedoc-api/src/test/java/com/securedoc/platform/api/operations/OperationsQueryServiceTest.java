package com.securedoc.platform.api.operations;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.operations.service.OperationsQueryService;
import com.securedoc.platform.application.port.out.OperationsReadPort;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class OperationsQueryServiceTest {

    private static final TenantId TENANT = TenantId.of(UUID.fromString("00000000-0000-0000-0000-000000000001"));
    private static final AuthenticatedPrincipal PRINCIPAL = AuthenticatedPrincipal.of(
            ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000099")),
            TENANT,
            "report-user",
            Set.of("REPORT_VIEWER"));

    @Test
    void validatesAndNormalizesReportInputsBeforeCallingTenantScopedPort() {
        OperationsReadPort port = mock(OperationsReadPort.class);
        OperationsQueryService service = new OperationsQueryService(port);
        Instant from = Instant.parse("2026-08-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-01T00:00:00Z");

        service.execute(PRINCIPAL, from, to, " manual_upload ", " completed ");

        verify(port).loadReports(eq(TENANT), any(Instant.class), eq(from), eq(to),
                eq("MANUAL_UPLOAD"), eq("COMPLETED"));
    }

    @Test
    void rejectsInvalidOrExcessiveRangesAndUnknownSources() {
        OperationsReadPort port = mock(OperationsReadPort.class);
        OperationsQueryService service = new OperationsQueryService(port);
        Instant to = Instant.parse("2026-09-01T00:00:00Z");

        assertThrows(IllegalArgumentException.class, () -> service.execute(PRINCIPAL, to, to, "ALL", "ALL"));
        assertThrows(IllegalArgumentException.class,
                () -> service.execute(PRINCIPAL, to.minusSeconds(367L * 24 * 60 * 60), to, "ALL", "ALL"));
        IllegalArgumentException sourceError = assertThrows(IllegalArgumentException.class,
                () -> service.execute(PRINCIPAL, to.minusSeconds(3600), to, "UNTRUSTED_SOURCE", "ALL"));
        assertTrue(sourceError.getMessage().contains("source"));
        IllegalArgumentException outcomeError = assertThrows(IllegalArgumentException.class,
                () -> service.execute(PRINCIPAL, to.minusSeconds(3600), to, "ALL", "UNTRUSTED_OUTCOME"));
        assertTrue(outcomeError.getMessage().contains("outcome"));
        verify(port, never()).loadReports(any(), any(), any(), any(), any(), any());
    }
}

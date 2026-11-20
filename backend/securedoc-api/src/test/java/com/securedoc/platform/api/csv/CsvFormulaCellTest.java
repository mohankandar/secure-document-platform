package com.securedoc.platform.api.csv;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.controller.AuditController;
import com.securedoc.platform.application.audit.query.AuditRecordSummary;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.port.in.ListAuditRecordsQuery;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 0017 A3 (0016 answer 3): a CSV cell that would start a spreadsheet formula gets a leading quote mark. */
class CsvFormulaCellTest {

    @Test
    void cellsStartingAFormulaGetALeadingQuoteAfterLeadingSpaces() {
        assertEquals("'=SUM(A1:A2)", CsvCells.neutralise("=SUM(A1:A2)"));
        assertEquals("'+1", CsvCells.neutralise("+1"));
        assertEquals("'-1", CsvCells.neutralise("-1"));
        assertEquals("'@cmd", CsvCells.neutralise("@cmd"));
        assertEquals("'   =1+1", CsvCells.neutralise("   =1+1"));
        assertEquals("Dana", CsvCells.neutralise("Dana"));
        assertEquals("a=b", CsvCells.neutralise("a=b"), "only the first character counts");
        assertEquals("", CsvCells.neutralise(""));
        assertEquals("   ", CsvCells.neutralise("   "));
        assertNull(CsvCells.neutralise(null));
        assertEquals("\"'=1\"\"\"", CsvCells.quoted("=1\""));
        assertEquals("", CsvCells.quoted(null));
    }

    @Test
    void auditCsvNeutralisesFormulaCellsButJsonIsUnchanged() {
        AuditRecordSummary row = new AuditRecordSummary(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "PACKAGE_APPROVED", "DocumentPackage", UUID.randomUUID().toString(), "SUCCESS",
                UUID.randomUUID().toString(), "@detail", Instant.parse("2026-11-01T10:00:00Z"),
                " =HYPERLINK(\"x\")", null);
        AuditController controller = controller(List.of(row));

        String csv = (String) controller.exportAuditRecords("csv", null, null, null, null, null, null, null, null, null,
                false, mock(HttpServletRequest.class)).getBody();
        String line = csv.split("\r\n")[1];
        assertTrue(line.endsWith(",\"'@detail\",\"' =HYPERLINK(\"\"x\"\")\","), line);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> json = (List<Map<String, Object>>) controller.exportAuditRecords("json", null, null,
                null, null, null, null, null, null, null, false, mock(HttpServletRequest.class)).getBody();
        assertEquals(" =HYPERLINK(\"x\")", json.get(0).get("actorDisplayName"));
        assertEquals("@detail", json.get(0).get("detail"));
    }

    private AuditController controller(List<AuditRecordSummary> rows) {
        ListAuditRecordsQuery query = mock(ListAuditRecordsQuery.class);
        when(query.execute(any())).thenReturn(rows);
        RequestContextResolver resolver = mock(RequestContextResolver.class);
        when(resolver.resolvePrincipal(any())).thenReturn(AuthenticatedPrincipal.of(ActorId.generate(),
                TenantId.generate(), "audit-admin", Set.of("TENANT_IT_ADMIN")));
        when(resolver.resolveCorrelationId(any())).thenReturn(CorrelationId.generate());
        return new AuditController(query, resolver, mock(AuditService.class));
    }
}

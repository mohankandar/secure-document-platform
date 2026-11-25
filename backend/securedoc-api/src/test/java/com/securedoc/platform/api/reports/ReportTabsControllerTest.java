package com.securedoc.platform.api.reports;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.exception.GlobalExceptionHandler;
import com.securedoc.platform.api.platform.PipelineQueueController;
import com.securedoc.platform.api.security.CapabilityAuthorizationInterceptor;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.operations.daily.DailyReport;
import com.securedoc.platform.application.operations.daily.DailyReportService;
import com.securedoc.platform.application.operations.model.OperationalReportSnapshot;
import com.securedoc.platform.application.operations.model.ReportScope;
import com.securedoc.platform.application.port.in.GetOperationalReportsQuery;
import com.securedoc.platform.application.port.out.DailyReportPort;
import com.securedoc.platform.application.port.out.EntityTimeZonePort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Task 0081: who may read each Reports tab, whose numbers they get, and each tab's CSV. */
class ReportTabsControllerTest {
    private static final String BASE = "/api/v1/operations/reports";
    private static final String DAY = "?from=2026-11-24T05:00:00Z&to=2026-11-25T05:00:00Z";

    private final TenantId tenant = TenantId.generate();
    private final List<AuditRecord> audits = new ArrayList<>();
    private final List<TenantId> askedFor = new ArrayList<>();
    private final List<ReportScope> scopes = new ArrayList<>();
    private final RequestContextResolver resolver = mock(RequestContextResolver.class);
    private final PipelineQueueController queue = mock(PipelineQueueController.class);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AuditService audit = new AuditService(audits::add);
        ProductAuthorizationPolicy policy = new ProductAuthorizationPolicy();
        GetOperationalReportsQuery reports = new GetOperationalReportsQuery() {
            @Override
            public OperationalReportSnapshot execute(AuthenticatedPrincipal p, Instant from, Instant to, String source, String outcome) {
                return execute(p, from, to, source, outcome, ReportScope.EVERYTHING);
            }

            @Override
            public OperationalReportSnapshot execute(AuthenticatedPrincipal p, Instant from, Instant to, String source, String outcome,
                                                     ReportScope scope) {
                askedFor.add(p.tenantId());
                scopes.add(scope);
                return snapshot(from, to);
            }
        };
        when(resolver.resolveCorrelationId(any())).thenReturn(CorrelationId.generate());
        when(queue.of(any())).thenAnswer(call -> {
            askedFor.add(call.getArgument(0));
            return new PipelineQueueController.Queue(true, false, 10, 60L,
                    List.of(new PipelineQueueController.Stage("FIND", 4, 1, 0, 2, 1, 2, 5L, 2.0, 60L)));
        });
        mvc = MockMvcBuilders.standaloneSetup(new ReportTabsController(
                        new DailyReportService(new Numbers(), EntityTimeZonePort.NONE, (t, s) -> true,
                                Clock.fixed(Instant.parse("2026-11-24T19:00:00Z"), ZoneOffset.UTC)),
                        reports, queue, policy, resolver, audit, EntityTimeZonePort.NONE))
                .addInterceptors(new CapabilityAuthorizationInterceptor(resolver, policy, audit))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    private void signedIn(String role) {
        when(resolver.resolvePrincipal(any())).thenReturn(AuthenticatedPrincipal.of(ActorId.generate(), tenant, "p", Set.of(role)));
    }

    private int statusOf(String role, String path) throws Exception {
        signedIn(role);
        return mvc.perform(get(BASE + path)).andReturn().getResponse().getStatus();
    }

    @Test
    void whoMayReadWhat() throws Exception {
        for (String path : List.of("/daily" + DAY, "/sources")) {
            assertEquals(200, statusOf("REPORT_VIEWER", path), path);
            assertEquals(200, statusOf("BUSINESS_ADMIN", path), path);
        }
        assertEquals(403, statusOf("REPORT_VIEWER", "/queue"));
        assertEquals(200, statusOf("BUSINESS_ADMIN", "/queue"));
        for (String tab : List.of("overview", "daily", "operations")) {
            assertEquals(403, statusOf("REPORT_VIEWER", "/" + tab + "/export" + DAY), tab);
            assertEquals(200, statusOf("BUSINESS_ADMIN", "/" + tab + "/export" + DAY), tab);
        }
        for (String role : List.of("DOCUMENT_PROCESSOR", "DOCUMENT_SUPERVISOR", "TENANT_IT_ADMIN", "PLATFORM_OPERATIONS_ADMIN")) {
            for (String path : List.of("/daily" + DAY, "/sources", "/queue", "/overview/export", "/daily/export", "/operations/export")) {
                assertEquals(403, statusOf(role, path), role + " " + path);
            }
        }
        // Whatever was asked, it was of the caller's own entity.
        assertEquals(Set.of(tenant), Set.copyOf(askedFor));
    }

    @Test
    void theCsvOfEachTabIsOfThatTabNeutralisedAndAudited() throws Exception {
        signedIn("BUSINESS_ADMIN");
        String daily = csv("/daily/export" + DAY);
        assertTrue(daily.startsWith("\"section\",\"item\",\"measure\",\"value\"\r\n"));
        assertTrue(daily.contains("\"Received by source system\",\"'=HYPERLINK(1)\",\"Received\",\"7\""), daily);
        assertTrue(daily.contains("\"Exported by split destination\",\"'@Title \"\"desk\"\", 2\",\"Exported documents\",\"3\""), daily);
        assertTrue(daily.contains("\"Needs attention by reason\",\"LV-INT-003\",\"Refused at intake\",\"2\""), daily);
        assertTrue(daily.contains("\"Not exported yet\",\"\",\"Waiting to retry\",\"1\""), daily);
        assertTrue(daily.contains("\"Documents by sensitivity level\",\"Highly Confidential\",\"Received\",\"1\""), daily);
        String overview = csv("/overview/export" + DAY);
        assertTrue(overview.contains("\"Headline\",\"\",\"Cases received\",\"11\""), overview);
        assertFalse(overview.contains("Backlog"), "only its own tab");
        String operations = csv("/operations/export" + DAY + "&level=CONFIDENTIAL&sourceSystem=type:MANUAL_UPLOAD");
        assertTrue(operations.contains("\"Backlog age\",\"\",\"Over 3 days\",\"5\""), operations);
        assertTrue(operations.contains("\"Queue now, every source system\",\"FIND\",\"Waiting\",\"4\""), operations);
        assertTrue(operations.contains("\"Report\",\"Operations\",\"Level\",\"Confidential\""), operations);
        assertTrue(operations.contains("\"Report\",\"Operations\",\"Source system\",\"Manual upload\""), operations);
        assertFalse(operations.contains("Headline"));
        assertEquals(new ReportScope("type:MANUAL_UPLOAD", "CONFIDENTIAL"), scopes.getLast());

        List<AuditRecord> exports = audits.stream().filter(a -> a.action() == AuditAction.REPORT_CSV_EXPORTED).toList();
        assertEquals(3, exports.size());
        assertTrue(exports.get(0).detail().contains("tab=daily"));
        assertTrue(exports.get(2).detail().contains("tab=operations; sourceSystem=type:MANUAL_UPLOAD; level=CONFIDENTIAL"));
        for (String text : List.of(daily, overview, operations)) {
            assertFalse(text.toLowerCase().contains("sending system"));
            for (String line : text.split("\r\n")) {
                for (String cell : line.split("\",\"")) {
                    assertFalse(cell.replace("\"", "").matches("^\\s*[=+@].*"), line);
                }
            }
        }
    }

    @Test
    void aFilterThatIsNotKnownIsRefused() throws Exception {
        signedIn("REPORT_VIEWER");
        mvc.perform(get(BASE + "/daily" + DAY + "&level=SECRET")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/daily" + DAY).param("sourceSystem", "x' or 1=1")).andExpect(status().isBadRequest());
        mvc.perform(get(BASE + "/daily?from=2026-11-25T00:00:00Z&to=2026-11-24T00:00:00Z")).andExpect(status().isBadRequest());
    }

    private String csv(String path) throws Exception {
        return mvc.perform(get(BASE + path)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private final class Numbers implements DailyReportPort {
        @Override
        public Counts counts(TenantId asked, Instant from, Instant to, ReportScope scope, Instant now) {
            askedFor.add(asked);
            return new Counts(new DailyReport.Received(7, 8, 9, 1, null), new DailyReport.Exported(5, 6, 7, 3, 0, null),
                    new DailyReport.NotExported(1, 0, 1, 0, 0, 0, 90L), new DailyReport.Handled(1, 1, 1, 1, 1, 1),
                    List.of(new DailyReport.Level("STANDARD", 1, 0), new DailyReport.Level("HIGHLY_CONFIDENTIAL", 1, 0)),
                    List.of(new DailyReport.Destination("d", "@Title \"desk\", 2", true, true, 3, 3, 4, 0, 0)),
                    List.of(new SourceCounts("s", 7, 5, 1, 0)), List.of(new Refused("s", false, "LV-INT-003", 2)),
                    Map.of(), List.of(), Map.of(), 0);
        }

        @Override
        public List<Long> receivedIn(TenantId asked, List<Span> spans, ReportScope scope) {
            return List.of();
        }

        @Override
        public List<Long> exportedIn(TenantId asked, List<Span> spans, ReportScope scope) {
            return List.of();
        }

        @Override
        public Instant firstReceived(TenantId asked) {
            return null;
        }

        @Override
        public List<Receipt> receipts(TenantId asked, List<Span> spans) {
            return List.of();
        }

        @Override
        public Map<String, Instant> lastReceived(TenantId asked) {
            askedFor.add(asked);
            return Map.of("type:MANUAL_UPLOAD", Instant.parse("2026-11-24T10:00:00Z"));
        }

        @Override
        public List<KnownSource> knownSources(TenantId asked) {
            askedFor.add(asked);
            return List.of(new KnownSource("s", "=HYPERLINK(1)", true));
        }
    }

    private static OperationalReportSnapshot snapshot(Instant from, Instant to) {
        Instant end = to == null ? Instant.parse("2026-11-25T00:00:00Z") : to;
        Instant start = from == null ? end.minus(Duration.ofDays(1)) : from;
        return new OperationalReportSnapshot(end, new OperationalReportSnapshot.Period(start, end, start, start, "ALL", "ALL"),
                new OperationalReportSnapshot.Executive(11, 0, 0, 0, 0, 0, 3, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                        List.of(new OperationalReportSnapshot.TrendPoint(start, 11, 3))),
                new OperationalReportSnapshot.Intake(0, 0, 0, 0, 0, 0, 0, 0), new OperationalReportSnapshot.Processing(0, 0, 0, 0, 0, 0),
                new OperationalReportSnapshot.Export(0, 0, 0), new OperationalReportSnapshot.Retention(0, 0, 0, 0, 0, 0),
                new OperationalReportSnapshot.Timing(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                new OperationalReportSnapshot.Backlog(1, 2, 3, 4, 5, 6), new OperationalReportSnapshot.Usage(0, 0, 0, 0, 0, 0, 0, 0),
                new OperationalReportSnapshot.ServiceLevel(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), List.of(),
                new OperationalReportSnapshot.TimeSaved(0, 0, null, 0, 0, 0, List.of(), 0, 1, 0));
    }
}

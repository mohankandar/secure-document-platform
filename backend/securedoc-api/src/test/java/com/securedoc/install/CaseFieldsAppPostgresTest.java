package com.securedoc.install;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.setup.SetupCommand;
import com.securedoc.platform.api.SecureDocumentPlatformApplication;
import com.securedoc.platform.application.casefields.CaseFieldService;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.domain.identity.BusinessRecordId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0061 in the running app on PostgreSQL: case fields through platform-fields. Synthetic values only. */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CaseFieldsAppPostgresTest {
    private static final UUID ENTITY = UUID.fromString("30216241-b724-5a3a-aef3-3034fd546d17");
    private static final Path DEMO = DemoStepTest.REPO.resolve("install/demo");
    private static final String LOGGING_SYSTEM = "org.springframework.boot.logging.LoggingSystem";
    private static final String MARKER = "Quillmarker";
    private static final String NAME = "Synthetic " + MARKER;
    private static final String STREET = "77 " + MARKER + " Road";
    private static final InMemoryKeyService KEYS = new InMemoryKeyService();
    private static final UUID PERSON = UUID.randomUUID();

    private static ThrowawayDatabase db;
    private static JdbcTemplate jdbc;
    private static ConfigurableApplicationContext app;
    private static int port;
    private static String loggingBefore;
    private static final ByteArrayOutputStream console = new ByteArrayOutputStream();
    private static PrintStream consoleBefore;
    private static PrintStream errorsBefore;
    private static final ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> log =
            new ch.qos.logback.core.read.ListAppender<>();
    @TempDir
    static Path work;

    /** Task 0094: the calls that open keys (one by one, or several in one call), and the app's other calls for the customer. */
    private static final AtomicInteger OPENS = new AtomicInteger();
    private static final AtomicInteger OTHER_CALLS = new AtomicInteger();

    private static KeyService counted(KeyService keys) {
        return (KeyService) Proxy.newProxyInstance(KeyService.class.getClassLoader(), new Class<?>[] {KeyService.class},
                (proxy, method, args) -> {
                    if (method.getName().startsWith("unwrap")) OPENS.incrementAndGet();
                    else if (method.getParameterCount() > 0 && method.getParameterTypes()[0] == CustomerId.class) OTHER_CALLS.incrementAndGet();
                    try {
                        return method.invoke(keys, args);
                    } catch (InvocationTargetException refused) {
                        throw refused.getCause();
                    }
                });
    }

    static class Keys {
        @Bean
        KeyService testKeyService() {
            return counted(KEYS);
        }
    }

    @BeforeAll
    static void start() throws Exception {
        loggingBefore = System.getProperty(LOGGING_SYSTEM);
        System.setProperty(LOGGING_SYSTEM, "none");
        KEYS.createKeys(CustomerId.fromTenantId("TEN-DEMO-0001"));
        db = ThrowawayDatabase.create("fields");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(db.url, db.user, db.password));
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(0, new SchemaStep(quiet, quiet).run(new String[] {"apply"}, db.env(SchemaStep.ENV_PREFIX)));
        assertEquals(0, new SetupCommand(db.env("PLATFORM_SETUP"), Clock.systemUTC(), TestBuild.development()).run(List.of("apply",
                "--licence", DEMO.resolve("licence-demo.json").toString(), "--signin", DEMO.resolve("signin-demo.json").toString(),
                "--product", "LEXAVAULT", "--product-roles", DEMO.resolve("product-roles-development.json").toString(),
                "--config-out", work.resolve("platform-tenant.properties").toString(),
                "--run-log", work.resolve("setup.log").toString(), "--no-customer-keys"), quiet, quiet));
        assertEquals(0, new DemoStep(quiet, quiet, Clock.systemUTC(), TestBuild::development).run(new String[] {"apply"},
                db.env(DemoStep.ENV_PREFIX)));
        Files.createDirectories(work.resolve("storage"));
        consoleBefore = System.out;
        errorsBefore = System.err;
        System.setOut(new PrintStream(console, true, StandardCharsets.UTF_8));
        System.setErr(System.out);
        log.start();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).addAppender(log);
        app = startApp("--platform.crypto.vault.address=https://key-service.example");
        port = ((WebServerApplicationContext) app).getWebServer().getPort();
    }

    private static ConfigurableApplicationContext startApp(String... more) {
        List<String> args = new ArrayList<>(List.of("--spring.datasource.url=" + db.url, "--spring.datasource.username=" + db.user,
                "--spring.datasource.password=" + db.password, "--server.port=0", "--securedoc.ai.provider=heuristic",
                "--securedoc.watched-folder.enabled=false", "--securedoc.watched-folder.scheduling-enabled=false",
                "--securedoc.lifecycle.purge-scheduler-enabled=false",
                "--securedoc.storage.local.root-path=" + work.resolve("storage"),
                "--platform.erase-log.location=" + work.resolve("erase-log")));
        args.addAll(List.of(more));
        Class<?>[] sources = more.length == 0
                ? new Class<?>[] {SecureDocumentPlatformApplication.class, FreshDatabaseStartPostgresTest.DevelopmentBuild.class}
                : new Class<?>[] {SecureDocumentPlatformApplication.class, Keys.class, FreshDatabaseStartPostgresTest.DevelopmentBuild.class};
        return new SpringApplicationBuilder(sources).profiles("local").run(args.toArray(String[]::new));
    }

    @AfterAll
    static void stop() throws SQLException {
        if (app != null) app.close();
        ((ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)).detachAppender(log);
        if (consoleBefore != null) {
            System.setOut(consoleBefore);
            System.setErr(errorsBefore);
        }
        if (loggingBefore == null) System.clearProperty(LOGGING_SYSTEM);
        else System.setProperty(LOGGING_SYSTEM, loggingBefore);
        if (db != null) db.close();
    }

    private static final String DEMO_FIELDS = """
            {"id":"%s","label":"Contract Number","required":true,"primary":true,"searchable":true,"extractable":true,"displayable":true,"searchMode":"PARTIAL"},
            {"id":"%s","label":"Member Number","required":true,"primary":false,"searchable":true,"extractable":true,"displayable":true,"searchMode":"PARTIAL"},
            {"id":"%s","label":"Service Entity","required":true,"primary":false,"searchable":true,"extractable":true,"displayable":true}""";

    private static String demoFields() {
        return DEMO_FIELDS.formatted(jdbc.queryForList("select definition_id from fld_field_definition where field_key in "
                + "('contract_number', 'member_number', 'service_entity') order by field_key", UUID.class).toArray());
    }

    private static HttpResponse<String> save(String... fields) throws Exception {
        return call("BUSINESS_ADMIN", "PUT", "/api/v1/business-admin/identifiers",
                "{\"identifiers\":[" + demoFields() + Stream.of(fields).map(f -> "," + f).reduce("", String::concat) + "]}");
    }

    private static String field(String label, String type, String more) {
        return "{\"label\":\"" + label + "\",\"required\":false,\"primary\":false,\"searchable\":true,\"extractable\":false,"
                + "\"displayable\":true,\"validationType\":\"" + type + "\"" + more + "}";
    }

    @Test
    @Order(1)
    void theAdminSetsUpFieldsAndTheRulesHoldInBothDirections() throws Exception {
        assertRefused(save(field("Borrower SSN", "TEXT", "")), "LV-CFG-013");
        assertRefused(save(field("Date of birth", "DATE", "")), "LV-CFG-014");
        HttpResponse<String> saved = save(field("Borrower name", "TEXT", ",\"sensitive\":true"),
                field("Property address", "ADDRESS", ""), field("Amount", "MONEY", ",\"currency\":\"USD\""),
                field("Emails", "EMAIL", ",\"multiValue\":true,\"sensitive\":false"));
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals("2", one("select count(*) from fld_field_definition where sensitive"));
        // The other way round: a kind can't be switched on while a field is that item.
        HttpResponse<String> policy = call("BUSINESS_ADMIN", "PUT", "/api/v1/business-admin/sensitive-data-policy",
                "{\"enabledEntityTypes\":[\"SSN\",\"PERSON_NAME\"],\"autoRedactThreshold\":0.8,\"reviewMode\":\"UNCERTAIN_ONLY\"}");
        assertRefused(policy, "LV-CFG-014");
        assertEquals("0", one("select count(*) from audit_records where detail like '%PERSON_NAME%'"));
        // A save that fails half-way leaves the list as it was.
        long before = Long.parseLong(one("select sum(version) from fld_field_definition"));
        assertRefused(call("BUSINESS_ADMIN", "PUT", "/api/v1/business-admin/identifiers", "{\"identifiers\":[" + demoFields()
                + "," + field("Broken", "MONEY", "") + "]}"), "LV-CFG-001");
        assertEquals(before, Long.parseLong(one("select sum(version) from fld_field_definition")));
        assertEquals(403, call("DOCUMENT_PROCESSOR", "PUT", "/api/v1/business-admin/identifiers", "{}").statusCode());
    }

    private static String caseBody(String contract, String more) {
        return "{\"packageType\":\"MANUAL_UPLOAD\",\"businessIdentifiers\":{\"contract_number\":\"" + contract
                + "\",\"member_number\":\"12345678901\",\"service_entity\":\"001\"" + more + "},\"metadata\":{}}";
    }

    private static final String SENSITIVE = ",\"borrower_name\":\"" + NAME + "\",\"property_address\":{\"street\":\"" + STREET
            + "\",\"city\":\"Springfield\",\"state\":\"IL\",\"zip\":\"62701\"},\"amount\":\"1,250.50\","
            + "\"emails\":[\"a@example.test\",\"b@example.test\"]";

    @Test
    @Order(2)
    void aSensitiveValueIsInNoTableLogFileOrAnswerOfARoleThatMayNotSeeIt() throws Exception {
        HttpResponse<String> made = call("DOCUMENT_PROCESSOR", "POST", "/api/v1/packages", caseBody("200000000001", SENSITIVE));
        assertEquals(201, made.statusCode(), made.body());
        String id = made.body().replaceAll("(?s).*\"packageId\":\"([^\"]+)\".*", "$1");

        for (String role : List.of("DOCUMENT_PROCESSOR", "DOCUMENT_SUPERVISOR")) {
            String detail = call(role, "GET", "/api/v1/packages/" + id, null).body();
            assertTrue(detail.contains(NAME) && detail.contains(STREET + ", Springfield, IL 62701"), detail);
            assertTrue(detail.contains("a@example.test; b@example.test"), detail);
        }
        // Lists and the dashboard show that there is one, to nobody what it is; the field list holds no values.
        for (String path : List.of("/api/v1/packages", "/api/v1/operations/dashboard", "/api/v1/intake/identifier-definitions")) {
            HttpResponse<String> list = call("DOCUMENT_SUPERVISOR", "GET", path, null);
            assertEquals(200, list.statusCode(), list.body());
            assertFalse(list.body().contains(MARKER), path);
        }
        for (String role : List.of("BUSINESS_ADMIN", "REPORT_VIEWER", "TENANT_IT_ADMIN", "PLATFORM_OPERATIONS_ADMIN")) {
            for (String path : List.of("/api/v1/packages/" + id, "/api/v1/packages", "/api/v1/operations/dashboard",
                    "/api/v1/operations/dashboard?search=" + URLEncoder.encode(NAME, StandardCharsets.UTF_8),
                    "/api/v1/business-admin/configuration", "/api/v1/support/incidents?businessIdentifier=" + MARKER,
                    "/api/v1/support/incidents", "/api/v1/operations/reports", "/api/v1/audit-records")) {
                HttpResponse<String> answer = call(role, "GET", path, null);
                assertFalse(answer.body().contains(MARKER), role + " " + path + " " + answer.statusCode());
            }
        }
        // Refusals name the field, never the value.
        HttpResponse<String> conflict = call("DOCUMENT_PROCESSOR", "POST", "/api/v1/packages",
                caseBody("200000000001", ",\"borrower_name\":\"" + NAME + " Two\""));
        assertRefused(conflict, "LV-INT-016");
        assertTrue(conflict.body().contains("Borrower name"), conflict.body());
        assertFalse(conflict.body().contains(MARKER), conflict.body());
        HttpResponse<String> invalid = call("DOCUMENT_PROCESSOR", "POST", "/api/v1/packages", caseBody("200000000002",
                ",\"property_address\":{\"street\":\"" + STREET + "\",\"city\":\"X\",\"state\":\"ZZ\",\"zip\":\"1\"}"));
        assertRefused(invalid, "LV-INT-015");
        assertFalse(invalid.body().contains(MARKER), invalid.body());

        assertNowhere();
    }

    /** Every column of every table as text, everything printed, and every file the app wrote. */
    private static void assertNowhere() throws Exception {
        for (String table : jdbc.queryForList("select table_name from information_schema.tables where table_schema = current_schema()"
                + " and table_type = 'BASE TABLE'", String.class)) {
            assertEquals(0, jdbc.queryForObject("select count(*) from \"" + table + "\" t where t::text ilike '%" + MARKER + "%'",
                    Integer.class), table);
        }
        String printed = console.toString(StandardCharsets.UTF_8);
        List<ch.qos.logback.classic.spi.ILoggingEvent> events;
        synchronized (log) {
            events = List.copyOf(log.list);
        }
        List<String> logged = events.stream().map(e -> e.getFormattedMessage() + e.getMDCPropertyMap()
                + e.getKeyValuePairs() + (e.getThrowableProxy() == null ? "" : ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(e.getThrowableProxy()))).toList();
        assertTrue(logged.stream().anyMatch(l -> l.contains("Request validation rejected")), "the log is read here");
        assertTrue(logged.stream().noneMatch(l -> l.contains(MARKER)), "logged");
        assertFalse(printed.contains(MARKER), "printed");
        try (Stream<Path> files = Files.walk(work)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(file, StandardCharsets.ISO_8859_1).contains(MARKER), file.toString());
            }
        }
    }

    @Test
    @Order(3)
    void searchFollowsEachFieldsModeAndSensitiveValuesAreFoundOnlyExactly() throws Exception {
        assertEquals(1, found("200000000001"));
        assertEquals(1, found("00000000001"), "a part of a field that is searched by part");
        assertEquals(1, found(" synthetic  " + MARKER.toUpperCase()), "capitals and extra spaces ignored");
        assertEquals(0, found(MARKER), "a part of a sensitive value");
        assertEquals(1, found("1250.50"));
        assertEquals(0, found("1250."), "a part of a field that is searched exactly only");
        assertEquals(0, found("%"));
    }

    private static int found(String term) throws Exception {
        String body = call("DOCUMENT_PROCESSOR", "GET", "/api/v1/operations/dashboard?search="
                + URLEncoder.encode(term, StandardCharsets.UTF_8), null).body();
        return Integer.parseInt(body.replaceAll("(?s).*\"totalItems\":(\\d+).*", "$1"));
    }

    @Test
    @Order(4)
    void casesOfTheSamePrimaryValueAtTheSameTimeJoinOneRecord() throws Exception {
        int writers = 8;
        CyclicBarrier together = new CyclicBarrier(writers);
        Callable<HttpResponse<String>> make = () -> {
            together.await();
            return call("DOCUMENT_PROCESSOR", "POST", "/api/v1/packages", caseBody("200000000777", ",\"borrower_name\":\"" + NAME + "\""));
        };
        try (var pool = Executors.newFixedThreadPool(writers)) {
            for (var result : pool.invokeAll(Collections.nCopies(writers, make))) {
                assertEquals(201, result.get().statusCode(), result.get().body());
            }
        }
        assertEquals("1", one("select count(distinct r.record_uuid) from fld_record r join fld_value v on v.record_uuid = r.record_uuid"
                + " where v.search_value = '200000000777'"));
        assertEquals("8", one("select count(*) from document_packages p join fld_record r on r.record_id = p.business_record_id::text"
                + " join fld_value v on v.record_uuid = r.record_uuid where v.search_value = '200000000777'"));
    }

    @Test
    @Order(5)
    void theValuesAndTheCaseAreStoredTogetherOrNotAtAll() {
        CaseFieldService service = app.getBean(CaseFieldService.class);
        TransactionTemplate tx = new TransactionTemplate(app.getBean(PlatformTransactionManager.class));
        Map<String, String> given = Map.of("contract_number", "200000000888", "member_number", "12345678901", "service_entity", "001");
        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            service.resolveOrCreate(TenantId.of(ENTITY), given);
            throw new IllegalStateException("the case could not be stored");
        }));
        assertEquals("0", one("select count(*) from fld_value where search_value = '200000000888'"));
        assertTrue(service.resolveOrCreate(TenantId.of(ENTITY), given).createdNew());
    }

    @Test
    @Order(6)
    void aLockedFieldAndAReportBreakdown() throws Exception {
        String name = one("select definition_id from fld_field_definition where field_key = 'borrower_name'");
        assertRefused(save(field("Borrower name", "TEXT", ",\"id\":\"" + name + "\",\"sensitive\":false")), "LV-CFG-015");
        String list = one("select definition_id from fld_field_definition where field_key = 'service_entity'");
        String reporting = "{\"enabledDimensionDefinitionIds\":[\"%s\"],\"deliveryTargetMinutes\":240,\"deliveryTargetPercent\":95,"
                + "\"straightThroughTargetPercent\":90,\"reviewWaitTargetMinutes\":60,\"reviewWaitTargetPercent\":80}";
        assertEquals(200, call("BUSINESS_ADMIN", "PUT", "/api/v1/business-admin/reporting-configuration", reporting.formatted(list)).statusCode());
        assertEquals("001", one("select distinct dimension_value from reporting_package_dimensions"));
        assertRefused(call("BUSINESS_ADMIN", "PUT", "/api/v1/business-admin/reporting-configuration", reporting.formatted(name)), "LV-CFG-001");
        assertEquals(200, call("BUSINESS_ADMIN", "GET", "/api/v1/operations/reports", null).statusCode());
    }

    /**
     * Task 0094: many cases with sensitive values. No list, search or report opens any of them; only the case itself does,
     * and the delivery of one case, each with one call. Values that can't be opened are hidden on their own case only.
     */
    @Test
    @Order(7)
    void aListOpensNoSensitiveValueAndACaseOrADeliveryOpensItsOwnWithOneCall() throws Exception {
        List<String> cases = new ArrayList<>();
        for (int i = 0; i < 25; i++) {
            HttpResponse<String> made = call("DOCUMENT_PROCESSOR", "POST", "/api/v1/packages", caseBody("3000000000" + (10 + i),
                    SENSITIVE.replace(",\"amount\":\"1,250.50\"", "")));
            assertEquals(201, made.statusCode(), made.body());
            cases.add(made.body().replaceAll("(?s).*\"packageId\":\"([^\"]+)\".*", "$1"));
        }
        for (String path : List.of("/api/v1/packages?limit=100", "/api/v1/packages?offset=0&limit=25",
                "/api/v1/operations/dashboard", "/api/v1/operations/dashboard?stage=NEEDS_REVIEW",
                "/api/v1/operations/dashboard?stage=NEEDS_APPROVAL",
                "/api/v1/export-jobs/waiting-for-you", "/api/v1/derived-deliveries/attention",
                "/api/v1/support/incidents", "/api/v1/support/export-jobs/needs-it", "/api/v1/operations/reports",
                "/api/v1/operations/reports/export", "/api/v1/operations/reports/daily", "/api/v1/operations/reports/queue",
                "/api/v1/operations/reports/overview/export", "/api/v1/operations/reports/daily/export",
                "/api/v1/operations/reports/operations/export", "/api/v1/audit-records", "/api/v1/audit-records/export",
                "/api/v1/business-admin/configuration", "/api/v1/intake/identifier-definitions")) {
            boolean answered = false;
            int before = OPENS.get();
            for (String role : List.of("DOCUMENT_PROCESSOR", "DOCUMENT_SUPERVISOR", "BUSINESS_ADMIN", "REPORT_VIEWER", "TENANT_IT_ADMIN",
                    "PLATFORM_OPERATIONS_ADMIN")) {
                HttpResponse<String> answer = call(role, "GET", path, null);
                answered |= answer.statusCode() == 200;
                if (role.equals("DOCUMENT_PROCESSOR") && (path.startsWith("/api/v1/packages") || path.endsWith("/dashboard"))) {
                    assertTrue(answer.body().contains("•••"), path);
                }
                assertFalse(answer.body().contains(MARKER), role + " " + path);
            }
            assertEquals(before, OPENS.get(), path);
            assertTrue(answered, "no role is answered: " + path);
        }
        // A search by a sensitive value asks for its fingerprint, however many cases it finds, and opens none.
        int opens = OPENS.get();
        int others = OTHER_CALLS.get();
        assertTrue(found(NAME) >= 25);
        int calls = OTHER_CALLS.get() - others;
        assertTrue(calls >= 1 && calls <= 3, "key service calls of one search: " + calls);
        for (String role : List.of("BUSINESS_ADMIN", "REPORT_VIEWER", "TENANT_IT_ADMIN")) {
            assertFalse(call(role, "GET", "/api/v1/packages/" + cases.getFirst(), null).body().contains(MARKER));
        }
        assertEquals(opens, OPENS.get());

        // Two sensitive fields, one of several parts: still one call, because a record has one envelope.
        assertEquals(1, opens(() -> {
            String shown = detail(cases.getFirst());
            assertTrue(shown.contains(NAME) && shown.contains(STREET), shown);
        }));
        CaseFieldService service = app.getBean(CaseFieldService.class);
        assertEquals(1, opens(() -> assertEquals(NAME,
                service.forDelivery(recordOf(cases.getFirst()), TenantId.of(ENTITY)).texts().get("borrower_name"))));

        // One case whose values can't be opened: hidden there and not delivered; every other case and the list as before.
        assertEquals(1, jdbc.update("update fld_record set envelope = (select envelope from fld_record where record_id = ?)"
                + " where record_id = ?", recordOf(cases.get(1)).toString(), recordOf(cases.get(2)).toString()));
        String hidden = detail(cases.get(2));
        assertTrue(hidden.contains("•••") && !hidden.contains(MARKER), hidden);
        assertThrows(TechnicalDependencyException.class, () -> service.forDelivery(recordOf(cases.get(2)), TenantId.of(ENTITY)));
        assertTrue(detail(cases.get(1)).contains(NAME));
        assertTrue(detail(cases.get(3)).contains(NAME));
        HttpResponse<String> page = call("DOCUMENT_SUPERVISOR", "GET", "/api/v1/packages?limit=100", null);
        assertEquals(200, page.statusCode(), page.body());
        assertTrue(cases.stream().allMatch(page.body()::contains), page.body());
        assertNowhere();
    }

    private interface Step {
        void run() throws Exception;
    }

    private static int opens(Step step) throws Exception {
        int before = OPENS.get();
        step.run();
        return OPENS.get() - before;
    }

    private static String detail(String caseId) throws Exception {
        HttpResponse<String> answer = call("DOCUMENT_PROCESSOR", "GET", "/api/v1/packages/" + caseId, null);
        assertEquals(200, answer.statusCode(), answer.body());
        return answer.body();
    }

    private static BusinessRecordId recordOf(String caseId) {
        return BusinessRecordId.fromString(one("select business_record_id from document_packages where id = '" + caseId + "'"));
    }

    @Test
    @Order(8)
    void aSwitchedOffFieldIsKeptAndItsValuesComeBack() throws Exception {
        assertEquals(200, save().statusCode());
        assertEquals("4", one("select count(*) from fld_field_definition where not active"));
        assertEquals("1", one("select count(*) from fld_value where field_key = 'amount'"));
        assertRefused(call("DOCUMENT_PROCESSOR", "POST", "/api/v1/packages", caseBody("200000000999", ",\"amount\":\"1\"")), "LV-INT-014");
    }

    /** Last: the same database in a LOCAL start without a key service. */
    @Test
    @Order(9)
    void aLocalStartWithoutAKeyServiceRefusesSensitiveFieldsAndValuesAndServesTheRest() throws Exception {
        app.close();
        app = startApp();
        port = ((WebServerApplicationContext) app).getWebServer().getPort();
        assertRefused(save(field("Agent name", "TEXT", ",\"sensitive\":true")), "LV-CFG-012");
        String name = one("select definition_id from fld_field_definition where field_key = 'borrower_name'");
        assertEquals(200, save(field("Borrower name", "TEXT", ",\"id\":\"" + name + "\",\"sensitive\":true")).statusCode());
        assertEquals(201, call("DOCUMENT_PROCESSOR", "POST", "/api/v1/packages", caseBody("200000001000", "")).statusCode());
        HttpResponse<String> refused = call("DOCUMENT_PROCESSOR", "POST", "/api/v1/packages",
                caseBody("200000001001", ",\"borrower_name\":\"" + NAME + "\""));
        assertRefused(refused, "LV-SYS-011");
        assertEquals("0", one("select count(*) from fld_value where search_value = '200000001001'"));
        String id = one("select p.id from document_packages p join fld_record r on r.record_id = p.business_record_id::text"
                + " where r.envelope is not null limit 1");
        HttpResponse<String> opened = call("DOCUMENT_PROCESSOR", "GET", "/api/v1/packages/" + id, null);
        assertEquals(200, opened.statusCode());
        assertTrue(opened.body().contains("•••") && !opened.body().contains(MARKER), opened.body());
        assertNowhere();
    }

    private static void assertRefused(HttpResponse<String> answer, String code) {
        assertTrue(answer.statusCode() >= 400, answer.body());
        assertTrue(answer.body().contains("\"" + code + "\""), answer.body());
    }

    private static HttpResponse<String> call(String roles, String method, String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", ENTITY.toString()).header("X-Actor-Id", PERSON.toString()).header("X-Actor-Roles", roles)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String one(String sql) {
        return String.valueOf(jdbc.queryForObject(sql, Object.class));
    }
}

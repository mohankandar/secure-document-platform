package com.securedoc.install;

import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.GrantChange;
import com.documentplatform.setup.SetupCommand;
import com.securedoc.platform.api.SecureDocumentPlatformApplication;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.identity.PlatformIdentityAdminReads;
import com.securedoc.platform.infrastructure.identity.PlatformIdentityReads;
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
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0037 step 5 on PostgreSQL: a fresh database brought up only by the install steps, in order, then the app:
 * schema step -> setup with the demo files -> LexaVault demo step -> app start. Nothing is inserted at startup. Each
 * step is safe to run again. Throwaway server only; the database is made and dropped by the test.
 */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class FreshDatabaseStartPostgresTest {

    private static final UUID DEMO_ENTITY = UUID.fromString("30216241-b724-5a3a-aef3-3034fd546d17");
    private static final Path DEMO = DemoStepTest.REPO.resolve("install/demo");
    private static final String LOGGING_SYSTEM = "org.springframework.boot.logging.LoggingSystem";

    private static ThrowawayDatabase db;
    @TempDir
    static Path work;

    private static String loggingSystemBefore;

    @BeforeAll
    static void create() throws SQLException {
        // The demo step's and the app's Spring contexts run in this test JVM: they must not reconfigure logging for
        // the whole JVM, because other tests in the same run capture log lines.
        loggingSystemBefore = System.getProperty(LOGGING_SYSTEM);
        System.setProperty(LOGGING_SYSTEM, "none");
        db = ThrowawayDatabase.create("fresh");
    }

    @AfterAll
    static void drop() throws SQLException {
        if (loggingSystemBefore == null) System.clearProperty(LOGGING_SYSTEM);
        else System.setProperty(LOGGING_SYSTEM, loggingSystemBefore);
        if (db != null) db.close();
    }

    private record Run(int exit, String output) {
    }

    private static Run setup(String command) {
        return setup(command, DEMO.resolve("signin-demo.json"));
    }

    private static Run setup(String command, Path signin, String... extra) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>(List.of(command,
                "--licence", DEMO.resolve("licence-demo.json").toString(),
                "--signin", signin.toString(),
                "--product", "LEXAVAULT",
                "--product-roles", DemoStepTest.REPO.resolve("install/product-roles.json").toString(),
                "--config-out", work.resolve("platform-tenant.properties").toString(),
                "--run-log", work.resolve("platform-setup-runs.log").toString(),
                "--no-customer-keys"));
        args.addAll(List.of(extra));
        int exit = new SetupCommand(db.env("PLATFORM_SETUP"), Clock.systemUTC(), TestBuild.development()).run(args, print, print);
        return checked(exit, out);
    }

    private static Run demoStep(String... extra) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>(List.of("apply"));
        args.addAll(List.of(extra));
        int exit = new DemoStep(print, print, Clock.systemUTC(), TestBuild::development).run(args.toArray(String[]::new), db.env(DemoStep.ENV_PREFIX));
        return checked(exit, out);
    }

    private static Run checked(int exit, ByteArrayOutputStream out) {
        String text = out.toString(StandardCharsets.UTF_8);
        assertFalse(text.contains(db.password), "the password is never printed");
        return new Run(exit, text);
    }

    @Test
    @Order(1)
    void theDemoStepRefusesUntilSetupHasRun() {
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(0, new SchemaStep(quiet, quiet).run(new String[] {"apply"}, db.env(SchemaStep.ENV_PREFIX)));
        Run early = demoStep();
        assertEquals(2, early.exit(), early.output());
        assertTrue(early.output().contains("NO_CUSTOMER"), early.output());
        assertEquals(4, installed(), "fresh: the demo step has not run");
    }

    /** The start path's check (task 0037 fix 1): 0 when the demo step has made the demo entity, 4 when not. */
    private static int installed() {
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return new DemoStep(quiet, quiet, Clock.systemUTC()).run(new String[] {"installed"}, db.env(DemoStep.ENV_PREFIX));
    }

    @Test
    @Order(2)
    void setupThenTheDemoStepMakeTheDemoEntityAndARerunChangesNothing() throws SQLException {
        Run first = setup("apply");
        assertEquals(0, first.exit(), first.output());
        assertTrue(first.output().contains("create      entity slot ENT-DEMO-0001 (NOT_ACTIVATED)"), first.output());
        // Task 0054: the platform role, granted platform-wide (the demo mapping's * row), never to an entity.
        assertTrue(first.output().contains("platform grant LEXAVAULT (every entity): group \"PlatformOperationsAdmins\" -> "
                + "PLATFORM_OPERATIONS_ADMIN"), first.output());
        assertEquals("PlatformOperationsAdmins|PLATFORM_OPERATIONS_ADMIN", one("select external_group || '|' || role from idn_platform_grant"));
        assertEquals("0", one("select count(*) from idn_group_grant where role = 'PLATFORM_OPERATIONS_ADMIN'"));
        assertEquals("1", one("select count(*) from idn_grant_history where scope = 'PLATFORM'"));

        Run demo = demoStep();
        assertEquals(0, demo.exit(), demo.output());
        for (String made : new String[] {"create      activation of ENT-DEMO-0001 as \"Local Demo Tenant\"",
                "create      entity row " + DEMO_ENTITY, "create      identifier rules", "create      sensitive-data policy",
                "create      scanner source \"Local Scanner Bridge\""}) {
            assertTrue(demo.output().contains(made), demo.output());
        }
        assertEquals("ACTIVE|Local Demo Tenant|lexavault-demo-step", one("select status || '|' || display_name || '|' || "
                + "activated_by from idn_entity where slot_id = 'ENT-DEMO-0001'"));
        assertEquals("NOT_ACTIVATED", one("select status from idn_entity where slot_id = 'ENT-DEMO-0002'"));
        assertEquals("ACTIVE|local-demo", one("select status || '|' || slug from tenants where id = '" + DEMO_ENTITY + "'"));
        assertEquals("7", one("select count(*) from tenant_capabilities where tenant_id = '" + DEMO_ENTITY + "' and status = 'ENABLED'"));
        assertEquals("3", one("select count(*) from business_identifier_definitions where tenant_id = '" + DEMO_ENTITY + "' and active_flag"));
        assertEquals("1", one("select count(*) from tenant_redaction_policies where tenant_id = '" + DEMO_ENTITY + "'"));
        assertEquals("1", one("select count(*) from integration_connections where tenant_id = '" + DEMO_ENTITY
                + "' and provider_type = 'SCANNER' and client_principal = 'scanner-bridge-local'"));
        assertEquals("1", one("select count(*) from tenants"), "only the demo entity: no fixed demo tenant");
        assertEquals(0, installed(), "the demo step has run");
        assertFalse(demo.output().contains("  note "), "a fresh database keeps the plain name and slug: " + demo.output());

        Run setupAgain = setup("apply");
        assertEquals(0, setupAgain.exit(), setupAgain.output());
        assertTrue(setupAgain.output().contains("0 created, 0 updated, 0 removed"), setupAgain.output());
        Run demoAgain = demoStep();
        assertEquals(0, demoAgain.exit(), demoAgain.output());
        assertFalse(demoAgain.output().contains("  create "), demoAgain.output());
    }

    @Test
    @Order(3)
    void theAppStartsOnItWithoutInsertingAnythingAndServesTheDemoEntity() throws Exception {
        String tablesBefore = rowCounts();
        Path storage = Files.createDirectories(work.resolve("storage"));
        try (ConfigurableApplicationContext app = start(storage, DevelopmentBuild.class)) {
            int port = ((WebServerApplicationContext) app).getWebServer().getPort();
            HttpResponse<String> me = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                            URI.create("http://localhost:" + port + "/api/v1/me"))
                    .header("X-Tenant-Id", DEMO_ENTITY.toString())
                    .header("X-Actor-Id", "00000000-0000-0000-0000-000000000099").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, me.statusCode(), me.body());
            assertTrue(me.body().contains("\"tenantName\":\"Local Demo Tenant\""), me.body());
            // The LOCAL default entity (no header) is the demo entity too.
            HttpResponse<String> byDefault = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/v1/me")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(byDefault.body().contains("\"tenantName\":\"Local Demo Tenant\""), byDefault.body());
            licensingInTheRunningApp(app, port);
            supportStatusInTheRunningApp(app, port);
            entitiesInTheRunningApp(port);
        }
        assertEquals(tablesBefore, rowCounts(), "startup inserted nothing into the identity, entity or set-up tables");
    }

    /** Task 0057: the licence keys of a development or a production build, whatever profile the tests run with. */
    static class DevelopmentBuild {
        @org.springframework.context.annotation.Bean
        com.documentplatform.licensing.LicenceBuild testLicenceBuild() {
            return TestBuild.development();
        }
    }

    static class ProductionBuild {
        @org.springframework.context.annotation.Bean
        com.documentplatform.licensing.LicenceBuild testLicenceBuild() {
            return TestBuild.production();
        }
    }

    private static ConfigurableApplicationContext start(Path storage, Class<?> build, String... more) {
        List<String> args = new ArrayList<>(List.of(
                // Command-line arguments: they outrank application-local.yml, so the app can only reach this database.
                "--spring.datasource.url=" + db.url, "--spring.datasource.username=" + db.user,
                "--spring.datasource.password=" + db.password, "--server.port=0",
                "--securedoc.ai.provider=heuristic", "--securedoc.watched-folder.enabled=false",
                "--securedoc.watched-folder.scheduling-enabled=false",
                "--securedoc.storage.local.root-path=" + storage));
        args.addAll(List.of(more));
        return new SpringApplicationBuilder(SecureDocumentPlatformApplication.class, build).profiles("local")
                .run(args.toArray(String[]::new));
    }

    private static HttpResponse<String> call(int port, String method, String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", DEMO_ENTITY.toString()).header("X-Actor-Id", "00000000-0000-0000-0000-000000000099")
                .header("X-Actor-Roles", "BUSINESS_ADMIN,DOCUMENT_PROCESSOR").header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Task 0058: the entities, their time zone, and a suspended entity, in the running app. */
    private static void entitiesInTheRunningApp(int port) throws Exception {
        String entities = call(port, "GET", "/api/v1/admin/entities", null).body();
        assertTrue(entities.contains("ENT-DEMO-0001") && entities.contains("ENT-DEMO-0002"), entities);
        assertEquals(403, callAs(port, "TENANT_IT_ADMIN", "/api/v1/admin/entities").statusCode());
        // Task 0058: a second entity is activated and named while the app runs; it has its own row, and the first is unchanged.
        HttpResponse<String> second = call(port, "POST", "/api/v1/admin/entities/ENT-DEMO-0002/activate", "{\"name\":\"Second Entity\"}");
        assertTrue(second.body().contains("\"code\":\"ACTIVATED\""), second.body());
        assertEquals("ACTIVE", one("select status from idn_entity where slot_id = 'ENT-DEMO-0002'"));
        assertEquals("Second Entity", one("select name from tenants where slug = 'ent-demo-0002'"));
        assertTrue(call(port, "POST", "/api/v1/admin/entities/ENT-DEMO-0002/suspend", null).body().contains("\"code\":\"SUSPENDED\""));
        assertEquals("SUSPENDED", one("select status from tenants where slug = 'ent-demo-0002'"));
        // Put back as it was: the tests after this one count rows and read that slot.
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            String second2 = "(select id from tenants where slug = 'ent-demo-0002')";
            for (String table : List.of("audit_records", "tenant_capabilities", "tenant_redaction_kinds", "tenant_redaction_policies")) {
                s.execute("delete from " + table + " where tenant_id = " + second2);
            }
            s.execute("delete from tenants where slug = 'ent-demo-0002'");
            s.execute("update idn_entity set status = 'NOT_ACTIVATED', display_name = null, activated_by = null, activated_at = null"
                    + " where slot_id = 'ENT-DEMO-0002'");
        }
        assertEquals(403, call(port, "POST", "/api/v1/admin/entities/ENT-NOWHERE/activate", "{\"name\":\"x\"}").statusCode());

        assertTrue(call(port, "GET", "/api/v1/me", null).body().contains("\"timeZone\":null"));
        assertTrue(call(port, "PUT", "/api/v1/admin/entities/ENT-DEMO-0001/time-zone", "{\"timeZone\":\"America/New_York\"}")
                .body().contains("\"done\":true"));
        assertTrue(call(port, "GET", "/api/v1/me", null).body().contains("\"timeZone\":\"America/New_York\""));
        // The trend's days are the entity's: March 8, 2026 is 23 hours long there.
        String report = call(port, "GET", "/api/v1/operations/reports?from=2026-03-07T05:00:00Z&to=2026-03-10T04:00:00Z", null).body();
        for (String day : List.of("2026-03-07T05:00:00Z", "2026-03-08T05:00:00Z", "2026-03-09T04:00:00Z")) {
            assertTrue(report.contains("\"bucket\":\"" + day), day + " in " + report);
        }
        assertEquals(3, report.split("\"bucket\"", -1).length - 1, report);
        String csv = call(port, "GET", "/api/v1/operations/reports/export?from=2026-03-07T05:00:00Z&to=2026-03-10T04:00:00Z", null).body();
        assertTrue(csv.contains("Time zone of the days,America/New_York"), csv);
        assertTrue(call(port, "PUT", "/api/v1/admin/entities/ENT-DEMO-0001/time-zone", "{\"timeZone\":\"\"}")
                .body().contains("\"done\":true"));

        String newCase = "{\"packageType\":\"MANUAL_UPLOAD\",\"businessIdentifiers\":{\"contract_number\":\"123456789012\"},\"metadata\":{}}";
        String cases = one("select count(*) from document_packages");
        try {
            change("update idn_entity set status = 'SUSPENDED' where slot_id = 'ENT-DEMO-0001'");
            HttpResponse<String> refused = call(port, "POST", "/api/v1/packages", newCase);
            assertEquals(403, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("LV-AUT-005"), refused.body());
            change("update tenants set status = 'SUSPENDED' where id = '" + DEMO_ENTITY + "'");
            for (String path : List.of("/api/v1/packages", "/api/v1/me", "/api/v1/admin/entities")) {
                assertEquals(403, call(port, "GET", path, null).statusCode(), path);
            }
        } finally {
            change("update idn_entity set status = 'ACTIVE' where slot_id = 'ENT-DEMO-0001'");
            change("update tenants set status = 'ACTIVE' where id = '" + DEMO_ENTITY + "'");
        }
        assertEquals(cases, one("select count(*) from document_packages"), "nothing half-made");
        assertEquals(200, call(port, "GET", "/api/v1/packages", null).statusCode());
    }

    /** Task 0057: the Licensing read, and intake stopped by a licence that is no longer valid while the app runs. */
    private static void licensingInTheRunningApp(ConfigurableApplicationContext app, int port) throws Exception {
        HttpResponse<String> view = call(port, "GET", "/api/v1/licensing", null);
        assertEquals(200, view.statusCode(), view.body());
        for (String part : List.of("\"intakeAllowed\":true", "\"licenceId\":\"LIC-LV-DEMO-0001\"", "\"kind\":\"NON_PRODUCTION\"",
                "\"testEnvironment\":true", "\"slotId\":\"ENT-DEMO-0001\"", "\"limit\":1000")) {
            assertTrue(view.body().contains(part), part + " in " + view.body());
        }
        assertFalse(view.body().contains("signature"), view.body());

        var licence = app.getBean(com.securedoc.platform.api.licensing.PlatformLicence.class);
        String newCase = "{\"packageType\":\"MANUAL_UPLOAD\",\"businessIdentifiers\":{\"contract_number\":\"123456789012\"},\"metadata\":{}}";
        try {
            change("update lic_licence set file = replace(file, 'LexaVault Demo Customer', 'Someone Else')");
            licence.read();
            HttpResponse<String> refused = call(port, "POST", "/api/v1/packages", newCase);
            assertEquals(403, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("LV-LIC-003"), refused.body());
            assertEquals("0", one("select count(*) from document_packages"), "nothing half-made");
            assertEquals("1", one("select count(*) from audit_records where action = 'LICENSE_ENFORCEMENT_DENIED'"));
            assertEquals(200, call(port, "GET", "/api/v1/packages", null).statusCode(), "viewing goes on");
        } finally {
            change("update lic_licence set file = replace(file, 'Someone Else', 'LexaVault Demo Customer')");
            licence.read();
        }
        assertTrue(call(port, "GET", "/api/v1/licensing", null).body().contains("\"intakeAllowed\":true"));
    }

    /** Task 0057: Support, Status: the checks in the real app, the references, and a bundle without secrets or data. */
    private static void supportStatusInTheRunningApp(ConfigurableApplicationContext app, int port) throws Exception {
        app.getBean(com.documentplatform.monitoring.DependencyStatus.class).runAll();
        HttpResponse<String> status = callAs(port, "TENANT_IT_ADMIN", "/api/v1/support/status");
        assertEquals(200, status.statusCode(), status.body());
        for (String check : List.of("\"name\":\"database\"", "\"name\":\"licence\"", "\"name\":\"storage\"", "\"name\":\"login-system\"")) {
            assertTrue(status.body().contains(check), check + " in " + status.body());
        }
        assertFalse(status.body().contains("\"name\":\"database\",\"group\":\"platform\",\"state\":\"DOWN\""), status.body());
        assertTrue(status.body().contains("LV-LIC-003"), "the refused case is among the last failures: " + status.body());
        assertEquals(403, callAs(port, "BUSINESS_ADMIN", "/api/v1/support/status").statusCode());

        HttpResponse<String> refused = callAs(port, "BUSINESS_ADMIN", "/api/v1/support/diagnostic-bundle");
        assertEquals(403, refused.statusCode(), refused.body());
        assertEquals(403, callAs(port, "PLATFORM_OPERATIONS_ADMIN", "/api/v1/support/diagnostic-bundle").statusCode());
        String reference = refused.headers().firstValue("X-Request-Id").orElseThrow();
        assertTrue(refused.body().contains("\"reference\":\"" + reference + "\""), refused.body());
        assertTrue(refused.body().contains("\"whatToDo\":\""), refused.body());

        HttpResponse<byte[]> bundle = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/api/v1/support/diagnostic-bundle"))
                .header("X-Tenant-Id", DEMO_ENTITY.toString()).header("X-Actor-Id", "00000000-0000-0000-0000-000000000099")
                .header("X-Actor-Roles", "TENANT_IT_ADMIN").GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, bundle.statusCode());
        StringBuilder all = new StringBuilder();
        java.util.Set<String> entries = new java.util.TreeSet<>();
        try (var zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bundle.body()))) {
            for (var e = zip.getNextEntry(); e != null; e = zip.getNextEntry()) {
                entries.add(e.getName());
                all.append(new String(zip.readAllBytes(), StandardCharsets.UTF_8)).append('\n');
            }
        }
        assertTrue(entries.containsAll(Set.of("versions.txt", "checks.txt", "failures.txt", "configuration.properties")), entries.toString());
        assertTrue(all.toString().contains("LV-LIC-003"), "the failures with their codes");
        for (String never : List.of(db.password, "spring.datasource", "securedoc_dev_password", "123456789012",
                "Local Demo Tenant", "Demo Customer", "signature")) {
            assertFalse(all.toString().contains(never), never);
        }
        assertEquals("1", one("select count(*) from audit_records where action = 'DIAGNOSTIC_BUNDLE_DOWNLOADED'"));

        // The open health address says nothing about the checks.
        HttpResponse<String> health = callAs(port, "BUSINESS_ADMIN", "/actuator/health");
        assertFalse(health.body().contains("database") || health.body().contains("MON_"), health.body());
    }

    private static HttpResponse<String> callAs(int port, String role, String path) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", DEMO_ENTITY.toString()).header("X-Actor-Id", "00000000-0000-0000-0000-000000000099")
                .header("X-Actor-Roles", role).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void change(String sql) throws SQLException {
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            assertEquals(1, s.executeUpdate(sql));
        }
    }

    /** Task 0057: the start refusals that the licence brings, each with its code. */
    @Test
    @Order(6)
    void theAppRefusesToStartWithoutAValidLicence() throws Exception {
        Path storage = Files.createDirectories(work.resolve("storage"));
        // Task 0057: a storage that is gone, where it is required; then the folder is back.
        change("update processing_storage_providers set root_path = root_path || '-gone'");
        assertStartRefused(storage, "MON_PATH_MISSING", DevelopmentBuild.class, "--securedoc.monitoring.storage-required=true");
        change("update processing_storage_providers set root_path = replace(root_path, '-gone', '')");
        assertStartRefused(storage, "LICENCE_UNKNOWN_KEY_ID", ProductionBuild.class);
        assertStartRefused(storage, "LICENCE_WRONG_CUSTOMER", DevelopmentBuild.class, "--platform.licensing.tenant-id=TEN-OTHER-0001");
        assertStartRefused(storage, "platform.licensing.tenant-id", DevelopmentBuild.class, "--platform.licensing.tenant-id=");
        change("update lic_licence set file = replace(file, 'LexaVault Demo Customer', 'Someone Else')");
        assertStartRefused(storage, "LICENCE_TAMPERED", DevelopmentBuild.class);
        change("delete from lic_licence");
        assertStartRefused(storage, "LICENCE_MISSING", DevelopmentBuild.class);
    }

    private static void assertStartRefused(Path storage, String code, Class<?> build, String... more) {
        RuntimeException refused = org.junit.jupiter.api.Assertions.assertThrows(RuntimeException.class,
                () -> start(storage, build, more).close());
        StringBuilder messages = new StringBuilder();
        for (Throwable t = refused; t != null && t != t.getCause(); t = t.getCause()) messages.append(t.getMessage()).append('\n');
        assertTrue(messages.toString().contains(code), code + " in " + messages);
    }

    @Test
    @Order(4)
    void signInFindsTheSingleActiveDemoEntityThroughTheIdnTables() {
        PlatformIdentityReads reads = new PlatformIdentityReads(new JdbcTemplate(new IdnRows(db).ds));
        URI issuer = URI.create("https://login.demo.example/oauth2/default");
        assertEquals(Set.of(TenantId.of(DEMO_ENTITY)), reads.findCandidates("demo-okta", issuer, "api://lexavault-demo"));
        assertEquals(Set.of(TenantId.of(DEMO_ENTITY)), reads.activeGrants("demo-okta", issuer, "api://lexavault-demo").keySet());

        // Task 0041: the local OIDC start runs setup with the demo files and the development login system put in
        // (LocalOidcSignin): platform-core 0.2.0 takes the http issuer on localhost under the development-signed demo
        // licence. The demo step no longer writes the connection. A rerun changes nothing.
        URI keycloak = URI.create(LocalOidcSigninTest.KEYCLOAK);
        Path signin = LocalOidcSigninTest.write(work.resolve("oidc-signin"));
        Run oidc = setup("apply", signin, "--replace-connection");
        // Task 0054 (platform-core 0.7.0, SEC-17): done; exit 3 because the placeholder's grants are kept and listed.
        assertEquals(3, oidc.exit(), oidc.output());
        assertTrue(oidc.output().contains("Setup done except 8 item(s)"), oidc.output());
        assertTrue(oidc.output().contains("login connection LEXAVAULT (version 1)"), oidc.output());
        assertEquals(Set.of(TenantId.of(DEMO_ENTITY)), reads.findCandidates("customer-oidc", keycloak, "lexavault-api"));
        assertTrue(reads.findCandidates("demo-okta", issuer, "api://lexavault-demo").isEmpty(), "the placeholder no longer signs in");
        Run again = setup("apply", signin, "--replace-connection");
        assertEquals(0, again.exit(), again.output());
        assertTrue(again.output().contains("0 created, 0 updated, 0 removed"), again.output());
        Run demo = demoStep();
        assertFalse(demo.output().contains("login connection"), demo.output());

        // The admin views (task 0041): the Sign-in screen's connection, and the grant history setup wrote with its run.
        PlatformIdentityAdminReads admin = new PlatformIdentityAdminReads(new JdbcTemplate(new IdnRows(db).ds));
        var active = admin.activeConnection(DEMO_ENTITY).orElseThrow();
        assertEquals(keycloak, active.issuer());
        assertEquals("lexavault-web", active.browserClientId());
        var history = admin.grantHistory(DEMO_ENTITY, Instant.now().minusSeconds(3600), Instant.now().plusSeconds(60));
        // Grants are per login system (issuer): the demo file's 5 for ENT-DEMO-0001, then the same 5 for the local one.
        assertEquals(10, history.size(), history.toString());
        assertEquals(5, history.stream().filter(c -> c.issuer().equals(keycloak)).count(), history.toString());
        assertTrue(history.stream().allMatch(c -> c.change() == GrantChange.Kind.ADDED && c.source() == ChangeSource.SETUP
                && c.setupRunId() != null), history.toString());
        assertEquals(Set.of("BusinessUsers", "ElevatedUsers", "BusinessAdmins", "ReportViewers", "TenantITAdmins"),
                history.stream().filter(c -> c.issuer().equals(keycloak)).map(GrantChange::group)
                        .collect(java.util.stream.Collectors.toSet()));
        // Stored as the role, never as an alias (product-roles.json aliases, platform-core 0.2.0).
        assertTrue(history.stream().allMatch(c -> com.securedoc.platform.infrastructure.identity.LexaVaultProduct.ENTITY_ROLES
                .contains(c.roleAfter())), history.toString());
        assertTrue(admin.activeConnection(UUID.fromString("00000000-0000-0000-0000-000000000001")).isEmpty());
        // Task 0054: the platform-wide grant under the issuer in use.
        assertEquals(java.util.Map.of("PlatformOperationsAdmins", Set.of("PLATFORM_OPERATIONS_ADMIN")),
                reads.activePlatformGrants("customer-oidc", keycloak, "lexavault-api"));
        assertEquals(java.util.Map.of(), reads.activePlatformGrants("demo-okta", issuer, "api://lexavault-demo"));
    }

    /**
     * Task 0054 decision 2: on the same database a build that does not trust the development key does nothing: setup refuses
     * the demo licence and the demo step refuses to activate under it.
     */
    @Test
    @Order(5)
    void aProductionBuildRefusesTheStoredAndTheGivenDevelopmentLicence() throws SQLException {
        String before = rowCounts();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        int setup = new SetupCommand(db.env("PLATFORM_SETUP"), Clock.systemUTC(), TestBuild.production()).run(List.of("apply",
                "--licence", DEMO.resolve("licence-demo.json").toString(), "--signin", DEMO.resolve("signin-demo.json").toString(),
                "--product", "LEXAVAULT", "--product-roles", DemoStepTest.REPO.resolve("install/product-roles.json").toString(),
                "--config-out", work.resolve("platform-tenant.properties").toString(),
                "--run-log", work.resolve("platform-setup-runs.log").toString(), "--no-customer-keys"), print, print);
        Run refused = checked(setup, out);
        assertEquals(2, refused.exit(), refused.output());
        assertTrue(refused.output().contains("LICENCE_UNKNOWN_KEY_ID"), refused.output());

        out.reset();
        int demo = new DemoStep(print, print, Clock.systemUTC(), TestBuild::production).run(
                new String[] {"apply", "--slot", "ENT-DEMO-0002", "--name", "Second", "--slug", "second"}, db.env(DemoStep.ENV_PREFIX));
        refused = checked(demo, out);
        assertEquals(2, refused.exit(), refused.output());
        assertTrue(refused.output().contains("DEMO_ACTIVATION_REFUSED"), refused.output());
        assertEquals("NOT_ACTIVATED", one("select status from idn_entity where slot_id = 'ENT-DEMO-0002'"));
        assertEquals(before, rowCounts());

        // The app's answer to "is the stored licence a development one" (http login systems on this machine).
        var ds = new IdnRows(db).ds;
        assertTrue(com.securedoc.platform.api.identity.StoredLicenceKeyAccess.of(ds, TestBuild::development).signedByDevelopmentKey());
        assertFalse(com.securedoc.platform.api.identity.StoredLicenceKeyAccess.of(ds, TestBuild::production).signedByDevelopmentKey());
    }

    private static String rowCounts() throws SQLException {
        return one("select (select count(*) from tenants) || '/' || (select count(*) from tenant_capabilities) || '/' || "
                + "(select count(*) from business_identifier_definitions) || '/' || (select count(*) from integration_connections) "
                + "|| '/' || (select count(*) from idn_entity) || '/' || (select count(*) from idn_login_connection) || '/' || "
                + "(select count(*) from enterprise_identity_connection_history)");
    }

    private static String one(String sql) throws SQLException {
        try (Connection c = db.connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}

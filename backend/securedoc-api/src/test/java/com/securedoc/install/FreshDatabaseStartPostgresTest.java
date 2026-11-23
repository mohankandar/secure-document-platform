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
import org.springframework.boot.web.context.WebServerApplicationContext;
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
                "--keys", DEMO.resolve("development-trusted-keys.json").toString(),
                "--product-roles", DemoStepTest.REPO.resolve("install/product-roles.json").toString(),
                "--config-out", work.resolve("platform-tenant.properties").toString(),
                "--run-log", work.resolve("platform-setup-runs.log").toString(),
                "--no-customer-keys"));
        args.addAll(List.of(extra));
        int exit = new SetupCommand(db.env("PLATFORM_SETUP"), Clock.systemUTC()).run(args, print, print);
        return checked(exit, out);
    }

    private static Run demoStep(String... extra) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>(List.of("apply", "--keys", DEMO.resolve("development-trusted-keys.json").toString()));
        args.addAll(List.of(extra));
        int exit = new DemoStep(print, print, Clock.systemUTC()).run(args.toArray(String[]::new), db.env(DemoStep.ENV_PREFIX));
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
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(SecureDocumentPlatformApplication.class)
                .profiles("local")
                // Command-line arguments: they outrank application-local.yml, so the app can only reach this database.
                .run("--spring.datasource.url=" + db.url, "--spring.datasource.username=" + db.user,
                        "--spring.datasource.password=" + db.password, "--server.port=0",
                        "--securedoc.ai.provider=heuristic", "--securedoc.watched-folder.enabled=false",
                        "--securedoc.watched-folder.scheduling-enabled=false",
                        "--securedoc.storage.local.root-path=" + storage)) {
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
        }
        assertEquals(tablesBefore, rowCounts(), "startup inserted nothing into the identity, entity or set-up tables");
    }

    @Test
    @Order(4)
    void signInFindsTheSingleActiveDemoEntityThroughTheIdnTables() {
        PlatformIdentityReads reads = new PlatformIdentityReads(new JdbcTemplate(new IdnRows(db).ds));
        URI issuer = URI.create("https://login.demo.example/oauth2/default");
        assertEquals(DEMO_ENTITY, reads.findActive("demo-okta", issuer, "api://lexavault-demo").orElseThrow().tenantId());
        assertEquals(Set.of(TenantId.of(DEMO_ENTITY)), reads.activeGrants("demo-okta", issuer, "api://lexavault-demo").keySet());

        // Task 0041: the local OIDC start runs setup with the demo files and the development login system put in
        // (LocalOidcSignin): platform-core 0.2.0 takes the http issuer on localhost under the development-signed demo
        // licence. The demo step no longer writes the connection. A rerun changes nothing.
        URI keycloak = URI.create(LocalOidcSigninTest.KEYCLOAK);
        Path signin = LocalOidcSigninTest.write(work.resolve("oidc-signin"));
        Run oidc = setup("apply", signin, "--replace-connection");
        assertEquals(0, oidc.exit(), oidc.output());
        assertTrue(oidc.output().contains("login connection LEXAVAULT (version 1)"), oidc.output());
        assertEquals(DEMO_ENTITY, reads.findActive("customer-oidc", keycloak, "lexavault-api").orElseThrow().tenantId());
        assertTrue(reads.findActive("demo-okta", issuer, "api://lexavault-demo").isEmpty(), "the placeholder no longer signs in");
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
    }

    private static String rowCounts() throws SQLException {
        return one("select (select count(*) from tenants) || '/' || (select count(*) from tenant_capabilities) || '/' || "
                + "(select count(*) from business_identifier_definitions) || '/' || (select count(*) from integration_connections) "
                + "|| '/' || (select count(*) from idn_entity) || '/' || (select count(*) from idn_login_connection) || '/' || "
                + "(select count(*) from enterprise_identity_connections)");
    }

    private static String one(String sql) throws SQLException {
        try (Connection c = db.connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}

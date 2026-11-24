package com.securedoc.install;

import com.documentplatform.identity.jdbc.PostgresLoginConnectionStore;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.LoginConnection;
import com.documentplatform.setup.SetupCommand;
import com.securedoc.platform.infrastructure.identity.PlatformIdentityReads;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0037 fix 1 on PostgreSQL: the start path on a database from before task 0037. It holds the fixed demo tenant
 * {@code 0000...0001} that {@code LocalDemoBootstrap} made (slug {@code local-demo}, name "Local Demo Tenant") and its
 * data. The owner's run: setup ran, the demo step activated the slot and then failed on the slug. This test brings the
 * database to that state, then runs the start path again: the demo step must finish, leave the old tenant as it was,
 * and a further run must change nothing. Task 0041: the OIDC start runs setup with the local login system each time
 * (platform-core 0.2.0 takes http on localhost under the development-signed demo licence). Throwaway server only.
 */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExistingDatabaseStartPostgresTest {

    private static final UUID OLD_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DEMO_ENTITY = UUID.fromString("30216241-b724-5a3a-aef3-3034fd546d17");
    private static final Path DEMO = DemoStepTest.REPO.resolve("install/demo");
    private static final String KEYCLOAK = "http://localhost:8181/realms/document-platform-local";
    private static final String DEMO_STEP_PART = "\n--- demo step ---\n";

    private static ThrowawayDatabase db;
    @TempDir
    static Path work;

    private static String oldTenantBefore;

    private static final String LOGGING_SYSTEM = "org.springframework.boot.logging.LoggingSystem";
    private static String loggingSystemBefore;

    @BeforeAll
    static void create() throws SQLException {
        // The demo step's Spring context runs in this test JVM: it must not reconfigure logging for the whole JVM,
        // because other tests in the same run capture log lines (as in FreshDatabaseStartPostgresTest).
        loggingSystemBefore = System.getProperty(LOGGING_SYSTEM);
        System.setProperty(LOGGING_SYSTEM, "none");
        db = ThrowawayDatabase.create("existing");
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(0, new SchemaStep(quiet, quiet).run(new String[] {"apply"}, db.env(SchemaStep.ENV_PREFIX)));
        // What LocalDemoBootstrap left in a database from before 0037: the fixed demo tenant and some of its data.
        exec("insert into tenants (id, name, slug, status, created_at, updated_at) values ('" + OLD_TENANT
                + "', 'Local Demo Tenant', 'local-demo', 'ACTIVE', now(), now())");
        exec("insert into tenant_capabilities (id, tenant_id, capability, status, created_at, updated_at) values ('"
                + UUID.randomUUID() + "', '" + OLD_TENANT + "', 'DOCUMENT_UPLOAD', 'ENABLED', now(), now())");
        oldTenantBefore = oldTenant();
    }

    @AfterAll
    static void drop() throws SQLException {
        if (loggingSystemBefore == null) System.clearProperty(LOGGING_SYSTEM);
        else System.setProperty(LOGGING_SYSTEM, loggingSystemBefore);
        if (db != null) db.close();
    }

    private record Run(int exit, String output) {
    }

    private static Run setup() {
        return setup(DEMO.resolve("signin-demo.json"));
    }

    private static Run setup(Path signin, String... extra) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        List<String> args = new ArrayList<>(List.of("apply",
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

    private static Run demoStep(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        int exit = new DemoStep(print, print, Clock.systemUTC(), TestBuild::development).run(args, db.env(DemoStep.ENV_PREFIX));
        return checked(exit, out);
    }

    /** The local OIDC start (task 0041): setup with the local login system put in the demo sign-in file, then the demo step. */
    private static Run oidcStart() {
        Path signin = LocalOidcSigninTest.write(work.resolve("oidc-signin"));
        Run setup = setup(signin, "--replace-connection");
        // Task 0054 (platform-core 0.7.0, SEC-17): a run that moves the customer to another login system is done, with exit 3,
        // and lists the grants kept under the old one. The local start takes that, and only that (LexaVaultTools.ps1).
        if (setup.exit() != 0) {
            assertEquals(3, setup.exit(), setup.output());
            assertFalse(setup.output().contains("  conflict "), setup.output());
            assertTrue(setup.output().lines().filter(l -> l.startsWith("  not removed "))
                    .allMatch(l -> l.contains("under the replaced login system")), setup.output());
        }
        Run demo = demoStep("apply");
        return new Run(demo.exit(), setup.output() + DEMO_STEP_PART + demo.output());
    }

    private static Run checked(int exit, ByteArrayOutputStream out) {
        String text = out.toString(StandardCharsets.UTF_8);
        assertFalse(text.contains(db.password), "the password is never printed");
        return new Run(exit, text);
    }

    @Test
    @Order(1)
    void theOldDemoTenantDoesNotCountAsTheDemoStepHavingRun() {
        Run installed = demoStep("installed");
        assertEquals(4, installed.exit(), installed.output());
        assertTrue(installed.output().contains("not done yet"), installed.output());
    }

    @Test
    @Order(2)
    void afterTheOwnersHalfFailedRunTheNextStartFinishes() throws SQLException {
        Run first = setup();
        assertEquals(0, first.exit(), first.output());
        // The owner's database after the failed demo step: the slot was activated with the clashing name, then the
        // entity row failed on the slug, so nothing else of the demo step was written.
        exec("update idn_entity set status = 'ACTIVE', display_name = 'Local Demo Tenant', activated_by = '"
                + DemoStep.ACTOR + "', activated_at = now(), updated_at = now() where slot_id = 'ENT-DEMO-0001'");
        assertEquals(4, demoStep("installed").exit(), "still not done: the next start runs setup again");

        // The next start (the OIDC start, task 0041): setup with the local login system put in the demo sign-in file
        // (it switches the connection from the placeholder to the local issuer and adds that issuer's grants), then the
        // demo step finishes.
        Run start = oidcStart();
        assertEquals(0, start.exit(), start.output());
        for (String line : new String[] {
                "login connection LEXAVAULT (version 1)",
                "note        slug local-demo is held by entity " + OLD_TENANT + " (left as it is); this entity uses local-demo-ent-demo-0001",
                "note        name \"Local Demo Tenant\" is held by entity " + OLD_TENANT,
                "update      name of ENT-DEMO-0001 from \"Local Demo Tenant\" to \"Local Demo Tenant (ENT-DEMO-0001)\"",
                "create      entity row " + DEMO_ENTITY + " \"Local Demo Tenant (ENT-DEMO-0001)\"",
                "create      identifier rules", "create      sensitive-data policy",
                "create      scanner source \"Local Scanner Bridge\""}) {
            assertTrue(start.output().contains(line), line + "\n" + start.output());
        }
        String demoPart = start.output().substring(start.output().indexOf(DEMO_STEP_PART));
        assertFalse(demoPart.contains("login connection"), "the demo step no longer writes it: " + demoPart);
        assertEquals("ACTIVE|Local Demo Tenant (ENT-DEMO-0001)|local-demo-ent-demo-0001",
                one("select status || '|' || name || '|' || slug from tenants where id = '" + DEMO_ENTITY + "'"));
        assertEquals("Local Demo Tenant (ENT-DEMO-0001)", one("select display_name from idn_entity where slot_id = 'ENT-DEMO-0001'"));
        assertEquals(oldTenantBefore, oldTenant(), "the old demo tenant and its data are unchanged");
        assertEquals(0, demoStep("installed").exit());
    }

    @Test
    @Order(3)
    void signInGoesThroughTheIssuerTheOidcStartWasGiven() {
        PlatformIdentityReads reads = new PlatformIdentityReads(new JdbcTemplate(new IdnRows(db).ds));
        assertEquals(DEMO_ENTITY, reads.findActive("customer-oidc", URI.create(KEYCLOAK), "lexavault-api").orElseThrow().tenantId());
        assertTrue(reads.findActive("demo-okta", URI.create("https://login.demo.example/oauth2/default"),
                "api://lexavault-demo").isEmpty(), "the demo files' placeholder login system is no longer the active one");
    }

    @Test
    @Order(4)
    void theStartAfterThatChangesNothing() throws SQLException {
        Run start = oidcStart();
        assertEquals(0, start.exit(), start.output());
        assertTrue(start.output().contains("0 created, 0 updated, 0 removed"), start.output());
        assertFalse(start.output().contains("create      "), start.output());
        assertFalse(start.output().contains("  note "), start.output());
        assertEquals(oldTenantBefore, oldTenant());
    }

    /**
     * The owner's database today (after 0037 fix 1): the 0037 demo step wrote the local connection itself, as a change
     * in the app. Setup with the same values finds nothing to change; with other values the OIDC start's issuer wins
     * (--replace-connection), so sign-in always goes through the issuer the start was given.
     */
    @Test
    @Order(5)
    void aConnectionTheOldDemoStepWroteIsKeptWhenEqualAndReplacedWhenNot() throws SQLException {
        PostgresLoginConnectionStore store = new PostgresLoginConnectionStore(new IdnRows(db).ds);
        String customer = one("select tenant_id from idn_customer");
        LoginConnection now = store.active(customer, "LEXAVAULT").orElseThrow();
        // As the 0037 demo step did: an update in the app, same values.
        assertTrue(store.update(now, now.configurationVersion(), ChangeSource.APP, DemoStep.ACTOR).isPresent());
        Run same = oidcStart();
        assertTrue(same.output().contains("0 created, 0 updated, 0 removed"), same.output());

        // Changed in the app to something else: the OIDC start replaces it.
        LoginConnection current = store.active(customer, "LEXAVAULT").orElseThrow();
        LoginConnection other = new LoginConnection(current.connectionId(), customer, "LEXAVAULT", "customer-oidc",
                current.providerType(), URI.create("http://localhost:9999/realms/other"), current.browserClientId(),
                current.apiAudience(), null, current.status(), current.configurationVersion());
        assertTrue(store.update(other, current.configurationVersion(), ChangeSource.APP, DemoStep.ACTOR).isPresent());
        Run replaced = oidcStart();
        assertEquals(0, replaced.exit(), replaced.output());
        assertTrue(replaced.output().contains("replaces the change made in the app"), replaced.output());
        assertEquals(URI.create(KEYCLOAK), store.active(customer, "LEXAVAULT").orElseThrow().issuer());
        assertEquals(oldTenantBefore, oldTenant());
    }

    private static String oldTenant() throws SQLException {
        return one("select t.name || '|' || t.slug || '|' || t.status || '|' || t.updated_at || '|' || "
                + "(select count(*) from tenant_capabilities c where c.tenant_id = t.id) from tenants t where t.id = '" + OLD_TENANT + "'");
    }

    private static void exec(String sql) throws SQLException {
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static String one(String sql) throws SQLException {
        try (Connection c = db.connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            rs.next();
            return rs.getString(1);
        }
    }
}

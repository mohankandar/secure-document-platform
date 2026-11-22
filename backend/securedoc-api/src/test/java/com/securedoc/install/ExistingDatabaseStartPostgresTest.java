package com.securedoc.install;

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
 * and a further run must change nothing. Throwaway server only.
 */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ExistingDatabaseStartPostgresTest {

    private static final UUID OLD_TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID DEMO_ENTITY = UUID.fromString("30216241-b724-5a3a-aef3-3034fd546d17");
    private static final Path DEMO = DemoStepTest.REPO.resolve("install/demo");
    private static final String KEYCLOAK = "http://localhost:8181/realms/document-platform-local";

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
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        int exit = new SetupCommand(db.env("PLATFORM_SETUP"), Clock.systemUTC()).run(List.of("apply",
                "--licence", DEMO.resolve("licence-demo.json").toString(),
                "--signin", DEMO.resolve("signin-demo.json").toString(),
                "--product", "LEXAVAULT",
                "--keys", DEMO.resolve("development-trusted-keys.json").toString(),
                "--product-roles", DemoStepTest.REPO.resolve("install/product-roles.json").toString(),
                "--config-out", work.resolve("platform-tenant.properties").toString(),
                "--run-log", work.resolve("platform-setup-runs.log").toString(),
                "--no-customer-keys"), print, print);
        return checked(exit, out);
    }

    private static Run demoStep(String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        List<String> all = new ArrayList<>(List.of(args));
        if ("apply".equals(all.get(0))) all.addAll(1, List.of("--keys", DEMO.resolve("development-trusted-keys.json").toString()));
        int exit = new DemoStep(print, print, Clock.systemUTC()).run(all.toArray(String[]::new), db.env(DemoStep.ENV_PREFIX));
        return checked(exit, out);
    }

    private static Run oidcDemoStep() {
        return demoStep("apply", "--oidc-issuer", KEYCLOAK, "--oidc-provider-id", "customer-oidc",
                "--oidc-audience", "lexavault-api", "--oidc-browser-client-id", "lexavault-web");
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

        // The next start: setup again (changes nothing), then the demo step (OIDC start) finishes.
        Run again = setup();
        assertEquals(0, again.exit(), again.output());
        assertTrue(again.output().contains("0 created, 0 updated, 0 removed"), again.output());

        Run demo = oidcDemoStep();
        assertEquals(0, demo.exit(), demo.output());
        for (String line : new String[] {
                "note        slug local-demo is held by entity " + OLD_TENANT + " (left as it is); this entity uses local-demo-ent-demo-0001",
                "note        name \"Local Demo Tenant\" is held by entity " + OLD_TENANT,
                "update      name of ENT-DEMO-0001 from \"Local Demo Tenant\" to \"Local Demo Tenant (ENT-DEMO-0001)\"",
                "create      entity row " + DEMO_ENTITY + " \"Local Demo Tenant (ENT-DEMO-0001)\"",
                "create      identifier rules", "create      sensitive-data policy",
                "create      scanner source \"Local Scanner Bridge\"",
                "update      login connection LEXAVAULT: provider customer-oidc, issuer " + KEYCLOAK}) {
            assertTrue(demo.output().contains(line), line + "\n" + demo.output());
        }
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
        // The demo step has run, so the start does not run setup again; the demo step changes nothing.
        Run demo = oidcDemoStep();
        assertEquals(0, demo.exit(), demo.output());
        assertFalse(demo.output().contains("  create "), demo.output());
        assertFalse(demo.output().contains("  update "), demo.output());
        assertFalse(demo.output().contains("  note "), demo.output());
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

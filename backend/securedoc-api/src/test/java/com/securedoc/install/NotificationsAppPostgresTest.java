package com.securedoc.install;

import com.documentplatform.licensing.spring.notifications.LicensingEvents;
import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.NotificationEvent;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.RecordIdentifier;
import com.documentplatform.setup.SetupCommand;
import com.securedoc.platform.api.SecureDocumentPlatformApplication;
import com.securedoc.platform.api.notifications.AppSecretResolver;
import com.securedoc.platform.api.notifications.DeliveryApprovals;
import com.securedoc.platform.api.notifications.FakeMailServer;
import com.securedoc.platform.api.notifications.NotificationPeople;
import com.securedoc.platform.api.notifications.OperationalNotices;
import com.securedoc.platform.domain.identity.ActorId;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0059 in the running app on PostgreSQL, with a fake mail server on this machine (nothing leaves it): settings, people,
 * messages and approval links of two entities.
 */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NotificationsAppPostgresTest {

    private static final UUID A = UUID.fromString("30216241-b724-5a3a-aef3-3034fd546d17");
    private static final Path DEMO = DemoStepTest.REPO.resolve("install/demo");
    private static final String LOGGING_SYSTEM = "org.springframework.boot.logging.LoggingSystem";
    private static final String LINK_KEY = "0123456789abcdef0123456789abcdef0123456789";

    private static final UUID ANN = UUID.randomUUID();      // Supervisor in A
    private static final UUID BOB = UUID.randomUUID();      // Supervisor in A, submits cases
    private static final UUID EVE = UUID.randomUUID();      // Supervisor in B
    private static final UUID ITA = UUID.randomUUID();      // IT Admin in A
    private static final UUID ITB = UUID.randomUUID();      // IT Admin in B
    private static final UUID BUS = UUID.randomUUID();      // Business Admin in A

    private static ThrowawayDatabase db;
    private static JdbcTemplate jdbc;
    private static FakeMailServer smtp;
    private static ConfigurableApplicationContext app;
    private static int port;
    private static UUID b;
    private static String loggingBefore;
    @TempDir
    static Path work;

    @BeforeAll
    static void start() throws Exception {
        loggingBefore = System.getProperty(LOGGING_SYSTEM);
        System.setProperty(LOGGING_SYSTEM, "none");
        db = ThrowawayDatabase.create("notify");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(db.url, db.user, db.password));
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(0, new SchemaStep(quiet, quiet).run(new String[] {"apply"}, db.env(SchemaStep.ENV_PREFIX)));
        assertEquals(0, new SetupCommand(db.env("PLATFORM_SETUP"), Clock.systemUTC(), TestBuild.development()).run(List.of("apply",
                "--licence", DEMO.resolve("licence-demo.json").toString(), "--signin", DEMO.resolve("signin-demo.json").toString(),
                "--product", "LEXAVAULT", "--product-roles", DemoStepTest.REPO.resolve("install/product-roles.json").toString(),
                "--config-out", work.resolve("platform-tenant.properties").toString(),
                "--run-log", work.resolve("platform-setup-runs.log").toString(), "--no-customer-keys"), quiet, quiet));
        assertEquals(0, new DemoStep(quiet, quiet, Clock.systemUTC(), TestBuild::development).run(new String[] {"apply"},
                db.env(DemoStep.ENV_PREFIX)));

        smtp = new FakeMailServer(null);
        Path secrets = Files.createDirectories(work.resolve("secrets"));
        Files.writeString(secrets.resolve("LEXAVAULT_SECRET_INSTALL_LINK_KEY"), LINK_KEY);
        app = new SpringApplicationBuilder(SecureDocumentPlatformApplication.class, FreshDatabaseStartPostgresTest.DevelopmentBuild.class)
                .profiles("local").run("--spring.datasource.url=" + db.url, "--spring.datasource.username=" + db.user,
                        "--spring.datasource.password=" + db.password, "--server.port=0", "--securedoc.ai.provider=heuristic",
                        "--securedoc.watched-folder.enabled=false", "--securedoc.watched-folder.scheduling-enabled=false",
                        "--securedoc.storage.local.root-path=" + Files.createDirectories(work.resolve("storage")),
                        "--platform.outbound.allow-internal-addresses=true",
                        "--securedoc.notifications.app-base-url=https://lexavault.example",
                        "--securedoc.notifications.mail-ports=" + smtp.port(),
                        // The rounds are run by the tests.
                        "--securedoc.notifications.check-interval-ms=3600000",
                        "--platform.notifications.sender.poll-interval=1s", "--platform.notifications.tick-interval=1s",
                        "--platform.licensing.notifications.enabled=true",
                        "--securedoc.secrets.directory=" + secrets,
                        "--platform.notifications.approvals.link-key-secret=file:LEXAVAULT_SECRET_INSTALL_LINK_KEY");
        port = ((WebServerApplicationContext) app).getWebServer().getPort();
        assertEquals(200, call(A, BUS, "BUSINESS_ADMIN", "POST", "/api/v1/admin/entities/ENT-DEMO-0002/activate", "{\"name\":\"Second\"}").statusCode());
        b = jdbc.queryForObject("select id from tenants where slug = 'ent-demo-0002'", UUID.class);
        NotificationPeople people = app.getBean(NotificationPeople.class);
        people.signedIn(ActorId.of(ANN), "ann@a.example", Map.of(TenantId.of(A), Set.of("DOCUMENT_SUPERVISOR")));
        people.signedIn(ActorId.of(BOB), "bob@a.example", Map.of(TenantId.of(A), Set.of("DOCUMENT_SUPERVISOR", "DOCUMENT_PROCESSOR")));
        people.signedIn(ActorId.of(EVE), "eve@b.example", Map.of(TenantId.of(b), Set.of("DOCUMENT_SUPERVISOR")));
        people.signedIn(ActorId.of(ITA), "it@a.example", Map.of(TenantId.of(A), Set.of("TENANT_IT_ADMIN")));
        people.signedIn(ActorId.of(ITB), "it@b.example", Map.of(TenantId.of(b), Set.of("TENANT_IT_ADMIN")));
        people.signedIn(ActorId.of(BUS), "bus@a.example", Map.of(TenantId.of(A), Set.of("BUSINESS_ADMIN")));
    }

    @AfterAll
    static void stop() throws Exception {
        if (app != null) app.close();
        if (smtp != null) smtp.close();
        if (loggingBefore == null) System.clearProperty(LOGGING_SYSTEM);
        else System.setProperty(LOGGING_SYSTEM, loggingBefore);
        if (db != null) db.close();
    }

    private static HttpResponse<String> call(UUID entity, UUID actor, String roles, String method, String path, String body) {
        try {
            return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                    .header("X-Tenant-Id", entity.toString()).header("X-Actor-Id", actor.toString()).header("X-Actor-Roles", roles)
                    .header("Content-Type", "application/json")
                    .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static HttpResponse<String> it(UUID entity, String method, String path, String body) {
        return call(entity, entity.equals(A) ? ITA : ITB, "TENANT_IT_ADMIN", method, "/api/v1/admin/notifications/it" + path, body);
    }

    private static String relay(boolean enabled, String from) {
        return "{\"enabled\":" + enabled + ",\"mode\":\"OWN_SERVER\",\"host\":\"127.0.0.1\",\"port\":" + smtp.port()
                + ",\"security\":\"NONE\",\"fromAddress\":\"" + from + "\",\"fromName\":\"LexaVault\"}";
    }

    private static int audits(String action) {
        return jdbc.queryForObject("select count(*) from audit_records where action = ?", Integer.class, action);
    }

    private static UUID waitingCase(UUID entity, UUID submitter) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into document_packages (id, tenant_id, package_type, status, created_by, created_at, updated_at,"
                + " version, metadata_json, sensitivity_level) values (?, ?, 'MANUAL_UPLOAD', 'PENDING_FINAL_APPROVAL', ?, now(), now(), 3,"
                + " '{\"borrower\":\"Jane Customer 123-45-6789\"}', 'STANDARD')", id, entity, submitter);
        return id;
    }

    private static String status(UUID caseId) {
        return jdbc.queryForObject("select status from document_packages where id = ?", String.class, caseId);
    }

    /** One message asking for a decision on the case: the links by person. */
    private static Map<String, String> ask(UUID caseId, int people) throws Exception {
        int before = smtp.mails().size();
        app.getBean(DeliveryApprovals.class).askForWaitingCases();
        Map<String, String> approve = new LinkedHashMap<>();
        for (FakeMailServer.Mail mail : mails(before, people, caseId.toString(), "/approvals/link/")) {
            approve.put(mail.to().get(0), mail.link("Approve"));
        }
        return approve;
    }

    /** The messages after the first {@code before} that hold all the texts; waits until there are so many. */
    private static List<FakeMailServer.Mail> mails(int before, int count, String... texts) throws Exception {
        List<FakeMailServer.Mail> found = List.of();
        for (int i = 0; i < 300 && found.size() < count; i++) {
            if (i > 0) Thread.sleep(100);
            List<FakeMailServer.Mail> all = List.copyOf(smtp.mails());
            found = all.subList(before, all.size()).stream().filter(m -> {
                String text = m.text().replace("=\r\n", "");
                return java.util.Arrays.stream(texts).allMatch(text::contains);
            }).toList();
        }
        return found;
    }

    private static String token(String link) {
        assertNotNull(link);
        assertTrue(link.startsWith("https://lexavault.example/approvals/link/"), link);
        return link.substring(link.lastIndexOf('/') + 1);
    }

    private static String link(UUID entity, UUID actor, String roles, String what, String token, String reason) {
        HttpResponse<String> r = call(entity, actor, roles, "POST", "/api/v1/approvals/link/" + what,
                "{\"token\":\"" + token + "\",\"action\":\"" + (reason == null ? "APPROVE" : "DECLINE") + "\"" + (reason == null ? "" : ",\"reason\":\"" + reason + "\"") + "}");
        assertEquals(200, r.statusCode(), r.body());
        return r.body();
    }

    @Test
    @Order(1)
    void theSettingsAreTheItAdminsAndOfTheirOwnEntityOnly() {
        assertEquals(403, call(A, BUS, "BUSINESS_ADMIN", "GET", "/api/v1/admin/notifications/it", null).statusCode());
        assertEquals(403, call(A, ANN, "DOCUMENT_SUPERVISOR", "PUT", "/api/v1/admin/notifications/it/mail", relay(true, "x@y.example")).statusCode());
        assertEquals(403, call(A, ITA, "TENANT_IT_ADMIN", "GET", "/api/v1/admin/notifications/business", null).statusCode());

        HttpResponse<String> refused = it(A, "PUT", "/mail", relay(true, "n@a.example").replace("127.0.0.1", "169.254.169.254"));
        assertEquals(400, refused.statusCode(), refused.body());
        assertTrue(refused.body().contains("LV-CFG-001") && refused.body().contains("host"), refused.body());
        assertFalse(refused.body().contains("169.254.169.254"), refused.body());
        String secret = "env:" + AppSecretResolver.entityPrefix(TenantId.of(b)) + "MAIL";
        refused = it(A, "PUT", "/mail", relay(true, "n@a.example").replace("\"NONE\"", "\"TLS\"").replace("\"enabled\"",
                "\"username\":\"svc\",\"secretReference\":\"" + secret + "\",\"enabled\""));
        assertEquals(400, refused.statusCode(), refused.body());
        assertFalse(refused.body().contains("MAIL"), refused.body());
        assertEquals("0", jdbc.queryForObject("select count(*)::text from notification_mail_settings", String.class));

        // A login with a secret of its own: named, kept, and never given back.
        String own = "env:" + AppSecretResolver.entityPrefix(TenantId.of(A)) + "MAIL";
        String withLogin = relay(false, "n@a.example").replace("\"NONE\"", "\"TLS\"").replace("\"enabled\"",
                "\"username\":\"svc\",\"secretReference\":\"" + own + "\",\"enabled\"");
        HttpResponse<String> saved = it(A, "PUT", "/mail", withLogin);
        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(saved.body().contains("\"secretNamed\":true"), saved.body());
        assertFalse(saved.body().contains(own), saved.body());
        // Kept when not typed again, but never for another user, server or port.
        assertEquals(200, it(A, "PUT", "/mail", withLogin.replace("\"secretReference\":\"" + own + "\",", "")).statusCode());
        assertEquals(400, it(A, "PUT", "/mail", withLogin.replace("\"secretReference\":\"" + own + "\",", "")
                .replace("\"svc\"", "\"other\"")).statusCode());
        assertEquals(400, it(A, "PUT", "/mail", withLogin.replace("\"secretReference\":\"" + own + "\",", "")
                .replace("127.0.0.1", "localhost")).statusCode());

        assertEquals(200, it(A, "PUT", "/mail", relay(true, "notices@a.example")).statusCode());
        assertTrue(it(b, "GET", "", null).body().contains("\"mail\":null"), "nothing of entity A in entity B");
        assertEquals(200, it(b, "PUT", "/mail", relay(true, "notices@b.example")).statusCode());
        assertTrue(it(A, "GET", "", null).body().contains("notices@a.example"));
        assertFalse(it(A, "GET", "", null).body().contains("b.example"));
        String overview = it(A, "GET", "", null).body();
        assertTrue(overview.contains("{\"role\":\"DOCUMENT_SUPERVISOR\",\"people\":2}"), overview);
        assertFalse(overview.contains("ann@"), overview);
        assertTrue(audits("NOTIFICATION_SETTINGS_CHANGED") >= 4);
        for (String detail : jdbc.queryForList("select detail from audit_records where action like 'NOTIFICATION%'", String.class)) {
            for (String never : List.of("svc", "@", "127.0.0.1", "SECRET_")) assertFalse(detail.contains(never), detail);
        }
    }

    @Test
    @Order(2)
    void aTestMessageGoesToTheOneWhoAsksAndNobodyElse() throws Exception {
        int before = smtp.mails().size();
        assertEquals(202, it(A, "POST", "/test", null).statusCode());
        FakeMailServer.Mail mail = smtp.await(before + 1).get(before);
        assertEquals(List.of("it@a.example"), mail.to());
        assertTrue(mail.text().contains("notices@a.example"));
        assertEquals(400, call(A, UUID.randomUUID(), "TENANT_IT_ADMIN", "POST", "/api/v1/admin/notifications/it/test", null).statusCode(),
                "somebody whose sign-in gave no address");
        for (int i = 0; i < 50 && !it(A, "GET", "", null).body().contains("\"state\":\"SENT\""); i++) Thread.sleep(100);
        assertTrue(it(A, "GET", "", null).body().contains("\"event\":\"Test message\",\"state\":\"SENT\""));
        assertFalse(it(b, "GET", "", null).body().contains("Test message"), "another entity's messages are not listed");
    }

    @Test
    @Order(3)
    void theEventChoicesAreTheAdminsWithinThePlatformsRules() {
        String path = "/events/export.destination-paused";
        assertEquals(400, it(A, "PUT", path, "{\"enabled\":false,\"frequency\":\"IMMEDIATELY\"}").statusCode(), "critical: always on");
        assertEquals(400, it(A, "PUT", path, "{\"enabled\":true,\"frequency\":\"ONCE_A_DAY\"}").statusCode());
        assertEquals(200, it(A, "PUT", path, "{\"enabled\":true,\"frequency\":\"EVERY_HOUR\"}").statusCode());
        assertTrue(it(A, "GET", "", null).body().contains("\"id\":\"export.destination-paused\",\"name\":\"Destination paused\",\"enabled\":true,\"alwaysOn\":true,\"frequency\":\"EVERY_HOUR\""));
        assertTrue(it(b, "GET", "", null).body().contains("\"id\":\"export.destination-paused\",\"name\":\"Destination paused\",\"enabled\":true,\"alwaysOn\":true,\"frequency\":\"IMMEDIATELY\""));
        assertEquals(200, it(A, "PUT", path, "{\"enabled\":true,\"frequency\":\"IMMEDIATELY\"}").statusCode());
        assertEquals(400, it(A, "PUT", "/events/approval.delivery-waiting", "{\"enabled\":false,\"frequency\":\"IMMEDIATELY\"}").statusCode(),
                "a business message is not the IT Admin's");
        String business = "/api/v1/admin/notifications/business";
        assertEquals(200, call(A, BUS, "BUSINESS_ADMIN", "PUT", business + "/events/licensing.licence-expiring",
                "{\"enabled\":false,\"frequency\":\"ONCE_A_DAY\"}").statusCode());
        assertEquals(400, call(A, BUS, "BUSINESS_ADMIN", "PUT", business + "/events/export.destination-paused",
                "{\"enabled\":true,\"frequency\":\"EVERY_HOUR\"}").statusCode());
    }

    @Test
    @Order(4)
    void approvingThroughTheLinkIsTheNormalApprovalOnceAndOnlyForThatPerson() throws Exception {
        UUID caseId = waitingCase(A, BOB);
        jdbc.update("insert into audit_records (id, tenant_id, actor_id, action, resource_type, resource_id, outcome, correlation_id,"
                + " occurred_at, created_at, actor_display_name, case_id) values (?, ?, ?, 'PACKAGE_SUBMITTED', 'DocumentPackage', ?,"
                + " 'SUCCESS', ?, now(), now(), 'Bob Submitter', ?)", UUID.randomUUID(), A, BOB, caseId.toString(), UUID.randomUUID(), caseId.toString());
        Map<String, String> links = ask(caseId, 1);
        assertEquals(Set.of("ann@a.example"), links.keySet(), "the submitter and the other entity's Supervisor get none");
        FakeMailServer.Mail mail = mails(0, 1, caseId.toString(), "/approvals/link/").get(0);
        for (String never : List.of("Jane", "6789", "borrower", LINK_KEY)) assertFalse(mail.text().contains(never), never);
        assertEquals(0, jdbc.queryForObject("select count(*) from notif_outbox where array_to_string(body_lines, ' ') like '%approvals/link%'",
                Integer.class), "no link is stored");
        String token = token(links.get("ann@a.example"));

        // A mail scanner fetching the link: nothing on this server answers it, and nothing is decided.
        for (String path : List.of("/approvals/link/" + token, "/api/v1/approvals/link/open", "/api/v1/approvals/link/decide")) {
            assertFalse(call(A, ANN, "DOCUMENT_SUPERVISOR", "GET", path, null).statusCode() == 200, path);
        }
        // Opening it, also many times, decides nothing.
        for (int i = 0; i < 3; i++) {
            String opened = link(A, ANN, "DOCUMENT_SUPERVISOR", "open", token, null);
            assertTrue(opened.contains("\"state\":\"READY\"") && opened.contains(caseId.toString()), opened);
            assertTrue(opened.contains("\"askedBy\":\"Bob Submitter\""), "who asked, for the Supervisor on the page: " + opened);
        }
        assertEquals("PENDING_FINAL_APPROVAL", status(caseId));

        // Refused: other people, other roles, the other entity, the submitter, a changed token.
        assertTrue(link(A, BOB, "DOCUMENT_SUPERVISOR", "decide", token, null).contains("LINK_NOT_FOR_YOU"));
        assertEquals(403, call(A, ANN, "DOCUMENT_PROCESSOR", "POST", "/api/v1/approvals/link/decide", "{\"token\":\"" + token + "\"}").statusCode());
        assertEquals(403, call(A, ANN, "BUSINESS_ADMIN", "POST", "/api/v1/approvals/link/decide", "{\"token\":\"" + token + "\"}").statusCode());
        assertTrue(link(b, EVE, "DOCUMENT_SUPERVISOR", "decide", token, null).contains("LINK_INVALID"));
        assertTrue(link(b, ANN, "DOCUMENT_SUPERVISOR", "decide", token, null).contains("LINK_INVALID"));
        assertTrue(link(A, ANN, "DOCUMENT_SUPERVISOR", "decide", token.substring(0, token.length() - 2) + "AA", null).contains("LINK_INVALID"));
        jdbc.update("update tenants set status = 'SUSPENDED' where id = ?", A);
        assertEquals(403, call(A, ANN, "DOCUMENT_SUPERVISOR", "POST", "/api/v1/approvals/link/decide", "{\"token\":\"" + token + "\"}").statusCode());
        jdbc.update("update tenants set status = 'ACTIVE' where id = ?", A);
        assertEquals("PENDING_FINAL_APPROVAL", status(caseId));
        assertEquals(0, jdbc.queryForObject("select count(*) from notif_approval_request where decision is not null", Integer.class));
        assertTrue(audits("DELIVERY_APPROVAL_LINK_REFUSED") >= 4);

        String done = link(A, ANN, "DOCUMENT_SUPERVISOR", "decide", token, null);
        assertTrue(done.contains("\"state\":\"APPROVED\""), done);
        assertFalse("PENDING_FINAL_APPROVAL".equals(status(caseId)), "the case goes on");
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_records where action = 'PACKAGE_FINAL_APPROVED' and actor_id = ?"
                + " and resource_id = ?", Integer.class, ANN, caseId.toString()), "the normal approval's own record");
        assertEquals(1, audits("DELIVERY_APPROVAL_LINK_USED"));
        assertTrue(link(A, ANN, "DOCUMENT_SUPERVISOR", "decide", token, null).contains("ALREADY_DECIDED"), "single use");
        String later = link(A, ANN, "DOCUMENT_SUPERVISOR", "open", token, null);
        assertTrue(later.matches("(?s).*Already approved by demo-user at \\d{4}-\\d\\d-\\d\\d \\d\\d:\\d\\d .*"), "who decided and when: " + later);
        assertEquals(1, audits("DELIVERY_APPROVAL_LINK_USED"));
        // The submitter, and only they, is told.
        assertEquals(List.of("bob@a.example"), mails(0, 1, "Delivery approved", caseId.toString()).get(0).to());
        for (String detail : jdbc.queryForList("select detail from audit_records where action like 'DELIVERY_APPROVAL%'", String.class)) {
            assertFalse(detail.contains(token) || detail.contains("@"), detail);
        }
    }

    @Test
    @Order(5)
    void aLinkIsForOneVersionOfTheCase() throws Exception {
        UUID caseId = waitingCase(A, BOB);
        String token = token(ask(caseId, 1).get("ann@a.example"));
        jdbc.update("update document_packages set version = version + 1 where id = ?", caseId);
        assertTrue(link(A, ANN, "DOCUMENT_SUPERVISOR", "open", token, null).contains("CASE_CHANGED"));
        assertTrue(link(A, ANN, "DOCUMENT_SUPERVISOR", "decide", token, null).contains("CASE_CHANGED"));
        assertEquals("PENDING_FINAL_APPROVAL", status(caseId));
        assertEquals(0, jdbc.queryForObject("select count(*) from notif_approval_request where identifier = ? and decision is not null",
                Integer.class, caseId.toString()), "the decision is undone with the refusal");
        // The changed case is asked about again, with new links; the old one stays refused.
        String fresh = token(ask(caseId, 1).get("ann@a.example"));
        assertTrue(link(A, ANN, "DOCUMENT_SUPERVISOR", "decide", token, null).contains("CASE_CHANGED"));
        assertTrue(link(A, ANN, "DOCUMENT_SUPERVISOR", "decide", fresh, null).contains("APPROVED"));
        // Approved on the case itself: the link is refused afterwards.
        UUID second = waitingCase(A, BOB);
        String other = token(ask(second, 1).get("ann@a.example"));
        assertEquals(200, call(A, BOB, "DOCUMENT_SUPERVISOR", "POST", "/api/v1/packages/" + second + "/approve-delivery", null).statusCode());
        String afterwards = link(A, ANN, "DOCUMENT_SUPERVISOR", "decide", other, null);
        assertTrue(afterwards.contains("ALREADY_DECIDED") && afterwards.contains("Already approved by "), afterwards);
        // Its requester approved it themselves here; nobody is told. With someone else as requester, once.
        int told = smtp.mails().size();
        UUID third = waitingCase(A, BUS);
        assertEquals(2, ask(third, 2).size());
        assertEquals(200, call(A, BOB, "DOCUMENT_SUPERVISOR", "POST", "/api/v1/packages/" + third + "/approve-delivery", null).statusCode());
        for (int i = 0; i < 3; i++) app.getBean(DeliveryApprovals.class).tellApprovedOnTheCase();
        List<FakeMailServer.Mail> result = mails(told, 1, "Delivery approved", third.toString());
        Thread.sleep(2000);
        assertEquals(1, mails(told, 1, "Delivery approved", third.toString()).size());
        assertEquals(List.of("bus@a.example"), result.get(0).to());
        assertEquals(0, mails(told, 0, "Delivery approved", second.toString()).size());
    }

    @Test
    @Order(6)
    void aLinkExpiresAndAFailingApprovalUndoesTheDecision() throws Exception {
        UUID caseId = waitingCase(A, BOB);
        String token = token(ask(caseId, 1).get("ann@a.example"));
        // The case can't be approved (its lifecycle is purged): everything is undone, the refusal is audited.
        jdbc.update("insert into package_lifecycles (package_id, tenant_id, successful_export_cycles, purged_at, final_approval_granted, updated_at)"
                + " values (?, ?, 0, now(), false, now())", caseId, A);
        assertTrue(link(A, ANN, "DOCUMENT_SUPERVISOR", "decide", token, null).contains("APPROVAL_REFUSED"));
        assertEquals("PENDING_FINAL_APPROVAL", status(caseId));
        assertEquals(0, jdbc.queryForObject("select count(*) from notif_approval_request where identifier = ? and decision is not null",
                Integer.class, caseId.toString()));
        jdbc.update("delete from package_lifecycles where package_id = ?", caseId);
        jdbc.update("update notif_approval_request set created_at = created_at - interval '2 days', expires_at = expires_at - interval '2 days'"
                + " where identifier = ?", caseId.toString());
        assertTrue(link(A, ANN, "DOCUMENT_SUPERVISOR", "open", token, null).contains("REQUEST_EXPIRED"));
        assertTrue(link(A, ANN, "DOCUMENT_SUPERVISOR", "decide", token, null).contains("REQUEST_EXPIRED"));
        assertEquals("PENDING_FINAL_APPROVAL", status(caseId));
        jdbc.update("delete from delivery_approval_requests where package_id = ?", caseId);
        jdbc.update("delete from document_packages where id = ?", caseId);
    }

    @Test
    @Order(7)
    void twoAtTheSameMomentApproveOnceAndTwoServersAskOnce() throws Exception {
        UUID caseId = waitingCase(A, BUS);
        int before = smtp.mails().size();
        DeliveryApprovals approvals = app.getBean(DeliveryApprovals.class);
        ExecutorService pool = Executors.newFixedThreadPool(4);
        List<Callable<Object>> rounds = new ArrayList<>();
        for (int i = 0; i < 4; i++) rounds.add(Executors.callable(approvals::askForWaitingCases));
        for (Future<Object> f : pool.invokeAll(rounds)) f.get();
        assertEquals(1, jdbc.queryForObject("select count(*) from delivery_approval_requests where package_id = ?", Integer.class, caseId));
        Map<String, String> links = new LinkedHashMap<>();
        for (FakeMailServer.Mail m : mails(before, 2, caseId.toString(), "/approvals/link/")) links.put(m.to().get(0), m.link("Approve"));
        assertEquals(Set.of("ann@a.example", "bob@a.example"), links.keySet());
        List<Callable<String>> clicks = List.of(
                () -> link(A, ANN, "DOCUMENT_SUPERVISOR", "decide", token(links.get("ann@a.example")), null),
                () -> link(A, BOB, "DOCUMENT_SUPERVISOR", "decide", token(links.get("bob@a.example")), null));
        List<String> answers = new ArrayList<>();
        for (Future<String> f : pool.invokeAll(clicks)) answers.add(f.get());
        pool.shutdown();
        assertEquals(1, answers.stream().filter(a -> a.contains("\"state\":\"APPROVED\"")).count(), answers.toString());
        assertEquals(1, jdbc.queryForObject("select count(*) from audit_records where action = 'PACKAGE_FINAL_APPROVED' and resource_id = ?",
                Integer.class, caseId.toString()));
    }

    @Test
    @Order(8)
    void aDeclineNeedsAFixedReasonAndLeavesTheCaseWhereItIs() throws Exception {
        UUID caseId = waitingCase(A, BOB);
        int before = smtp.mails().size();
        app.getBean(DeliveryApprovals.class).askForWaitingCases();
        String token = token(mails(before, 1, caseId.toString(), "/approvals/link/").get(0).link("Decline"));
        assertTrue(link(A, ANN, "DOCUMENT_SUPERVISOR", "decide", token, "").contains("REASON_REQUIRED"));
        assertTrue(link(A, ANN, "DOCUMENT_SUPERVISOR", "decide", token, "Jane Customer").contains("REASON_INVALID"));
        assertTrue(link(A, ANN, "DOCUMENT_SUPERVISOR", "decide", token, "CHECK_REDACTION").contains("\"state\":\"DECLINED\""));
        assertEquals("PENDING_FINAL_APPROVAL", status(caseId));
        assertEquals("The redaction needs another look", jdbc.queryForObject("select reason from notif_approval_request where identifier = ?",
                String.class, caseId.toString()));
        assertEquals(200, call(A, BOB, "DOCUMENT_SUPERVISOR", "POST", "/api/v1/packages/" + caseId + "/approve-delivery", null).statusCode());
    }

    @Test
    @Order(8)
    void casesNobodyCanBeAskedAboutNeverHoldUpAnotherEntitysOrSubmitters() throws Exception {
        // Entity B's only known Supervisor submitted 60 cases, all older than entity A's one.
        for (int i = 0; i < 60; i++) {
            jdbc.update("update document_packages set updated_at = now() - interval '1 day' where id = ?", waitingCase(b, EVE));
        }
        UUID caseId = waitingCase(A, BOB);
        assertEquals(Set.of("ann@a.example"), ask(caseId, 1).keySet());
        assertEquals(0, jdbc.queryForObject("select count(*) from delivery_approval_requests where tenant_id = ?", Integer.class, b));
        jdbc.update("delete from document_packages where tenant_id = ? and created_by = ?", b, EVE);
    }

    @Test
    @Order(8)
    void aCaseRejectedMeanwhileCannotBeApprovedByItsLink() throws Exception {
        UUID caseId = waitingCase(A, BOB);
        String token = token(ask(caseId, 1).get("ann@a.example"));
        HttpResponse<String> rejected = call(A, ANN, "DOCUMENT_SUPERVISOR", "POST", "/api/v1/packages/" + caseId + "/approval/reject",
                "{\"reasonCode\":\"LV-REV-013\"}");
        assertEquals(200, rejected.statusCode(), rejected.body());
        assertTrue(link(A, ANN, "DOCUMENT_SUPERVISOR", "open", token, null).contains("CASE_CHANGED"));
        assertTrue(link(A, ANN, "DOCUMENT_SUPERVISOR", "decide", token, null).contains("CASE_CHANGED"));
        assertEquals("REJECTED", status(caseId));
    }

    @Test
    @Order(9)
    void aNoticeGoesToItsOwnEntitysItAdminOnceAndIsFollowedByRecovered() throws Exception {
        UUID connection = jdbc.queryForList("select id from integration_connections where tenant_id = ?", UUID.class, A).get(0);
        jdbc.update("insert into export_destination_states (connection_id, tenant_id, state, consecutive_failures, updated_at, worker_reduction)"
                + " values (?, ?, 'PAUSED', 0, now(), 0) on conflict (connection_id) do update set state = 'PAUSED'", connection, A);
        int before = smtp.mails().size();
        OperationalNotices notices = app.getBean(OperationalNotices.class);
        for (int i = 0; i < 3; i++) notices.check();
        Thread.sleep(2500);
        assertEquals(0, mails(before, 0, "Destination paused").size(), "not before it lasted 15 minutes");
        lasted();
        for (int i = 0; i < 3; i++) notices.check();
        mails(before, 1, "Destination paused");
        Thread.sleep(2500);
        List<FakeMailServer.Mail> sent = mails(before, 1, "Destination paused");
        assertEquals(1, sent.size(), "one alert, not one per check");
        assertEquals(List.of("it@a.example"), sent.get(0).to());
        assertTrue(sent.get(0).text().replace("=\r\n", "").contains("https://lexavault.example/support/export"));
        jdbc.update("update export_destination_states set state = 'WORKING' where connection_id = ?", connection);
        notices.check();
        assertEquals(1, mails(before, 1, "Destination paused", "ecovered").size());

        // Busy: nothing in the first 30 minutes.
        jdbc.update("update export_destination_states set rate_limited_until = now() + interval '1 minute' where connection_id = ?", connection);
        int busy = smtp.mails().size();
        notices.check();
        Thread.sleep(2500);
        assertEquals(0, mails(busy, 0, "30 minutes").size());
        jdbc.update("update notif_condition set since = since - interval '31 minutes' where event_type = 'export.destination-busy'");
        notices.check();
        assertEquals(1, mails(busy, 1, "30 minutes").size());
        jdbc.update("delete from export_destination_states where connection_id = ?", connection);
        notices.check();

        // The others, from what LexaVault stores about them.
        int others = smtp.mails().size();
        UUID job = UUID.randomUUID();
        UUID stuck = waitingCase(A, BUS);
        jdbc.update("insert into pipeline_jobs (id, tenant_id, stage, package_id, export_cycle, priority, status, attempts,"
                + " next_attempt_at, claim_token, last_lv_code, created_at, updated_at) values (?, ?, 'EXPORT', ?, 1, 'NORMAL',"
                + " 'NEEDS_IT', 1, now(), 0, 'LV-DLV-005', now(), now())", job, A, stuck);
        jdbc.update("insert into export_reachability_alerts (tenant_id, state, raised_at, updated_at) values (?, 'RAISED', now(), now())"
                + " on conflict (tenant_id) do update set state = 'RAISED'", A);
        jdbc.update("update processing_storage_providers set root_path = root_path || '-gone'");
        for (int i = 0; i < 20 && mails(others, 0, "storage not usable").isEmpty(); i++) {
            Thread.sleep(1000);
            notices.check();
            lasted();
            notices.check();
        }
        mails(others, 1, "Cases waiting for IT");
        for (String name : List.of("Cases waiting for IT", "Destinations can not be reached", "storage not usable")) {
            List<FakeMailServer.Mail> of = mails(others, 1, name);
            assertFalse(of.isEmpty(), name);
            // The storage is the installation's, so both entities stopped; the others are entity A's only.
            for (FakeMailServer.Mail m : of) {
                assertTrue(m.to().equals(List.of("it@a.example")) || name.startsWith("storage") && m.to().equals(List.of("it@b.example")), name);
                assertFalse(m.text().contains("Jane"), name);
            }
        }
        jdbc.update("update processing_storage_providers set root_path = replace(root_path, '-gone', '')");
        jdbc.update("delete from pipeline_jobs where id = ?", job);
        jdbc.update("delete from export_reachability_alerts where tenant_id = ?", A);
        jdbc.update("delete from document_packages where id = ?", stuck);
    }

    /** As if what is going on had started 16 minutes earlier. */
    private static void lasted() {
        jdbc.update("update notif_condition set since = since - interval '16 minutes' where event_type <> 'export.destination-busy'");
    }

    @Test
    @Order(9)
    void theOtherAgreedEventsComeFromWhatIsStoredOfTheirOwnEntity() throws Exception {
        OperationalNotices notices = app.getBean(OperationalNotices.class);
        String ago = "now() - interval '1 day'";
        UUID review = UUID.randomUUID();
        UUID slow = UUID.randomUUID();
        for (Object[] c : new Object[][] {{review, "PENDING_REVIEW"}, {slow, "PROCESSING"}}) {
            jdbc.update("insert into document_packages (id, tenant_id, package_type, status, created_by, created_at, updated_at, version,"
                    + " sensitivity_level) values (?, ?, 'MANUAL_UPLOAD', ?, ?, " + ago + ", " + ago + ", 0, 'STANDARD')", c[0], A, c[1], BUS);
        }
        jdbc.update("update reporting_package_facts set received_at = " + ago + ", first_review_created_at = " + ago
                + " where package_id in (?, ?)", review, slow);
        UUID connection = jdbc.queryForList("select id from integration_connections where tenant_id = ?", UUID.class, A).get(0);
        UUID run = UUID.randomUUID();
        jdbc.update("insert into polling_runs (id, tenant_id, integration_connection_id, trigger_type, status, started_at, completed_at)"
                + " values (?, ?, ?, 'SCHEDULED', 'FAILED', now(), now())", run, A, connection);
        for (String action : List.of("INTAKE_REJECTED", "SENSITIVE_DATA_POLICY_CHANGED")) {
            jdbc.update("insert into audit_records (id, tenant_id, actor_id, action, resource_type, resource_id, outcome, correlation_id, detail,"
                    + " occurred_at, created_at) values (?, ?, ?, ?, 'X', 'x', 'SUCCESS', ?, 'drop=Jane Customer', now(), now())",
                    UUID.randomUUID(), A, BUS, action, UUID.randomUUID());
        }
        int before = smtp.mails().size();
        jdbc.update("update processing_incidents set last_observed_at = last_observed_at - interval '2 hours'");
        for (int i = 0; i < 3; i++) notices.check();
        List<FakeMailServer.Mail> policy = mails(before, 1, "Redaction policy changed");
        Thread.sleep(2000);
        policy = mails(before, 1, "Redaction policy changed");
        assertEquals(1, policy.size());
        assertEquals(List.of("bus@a.example"), policy.get(0).to());
        assertFalse(policy.get(0).text().contains("Jane"));
        assertEquals(1, jdbc.queryForObject("select count(*) from notif_summary_item where event_type = 'intake.files-rejected'", Integer.class),
                "in the 4-hour summary, once");
        Set<String> going = Set.copyOf(jdbc.queryForList("select event_type from notif_condition where tenant_id = ?", String.class, A.toString()));
        for (String type : List.of("work.review-waiting", "work.review-past-target", "processing.over-target", "intake.source-unreachable")) {
            assertTrue(going.contains(type), type + " in " + going);
        }
        assertEquals(0, jdbc.queryForObject("select count(*) from notif_condition where tenant_id = ? and (event_type like 'work.%'"
                + " or event_type like 'processing.%' or event_type like 'intake.%')", Integer.class, b.toString()), "nothing of A in B");
        // Gone again: the source is recovered; the waiting work ends without a message.
        lasted();
        notices.check();
        assertEquals(1, mails(before, 1, "Intake source can not be reached").size());
        jdbc.update("delete from polling_runs where id = ?", run);
        jdbc.update("update document_packages set status = 'COMPLETED' where id in (?, ?)", review, slow);
        notices.check();
        assertEquals(1, mails(before, 1, "ecovered", "Intake source can not be reached").size());
        assertEquals(0, jdbc.queryForObject("select count(*) from notif_condition where tenant_id = ? and event_type in"
                + " ('work.review-waiting', 'work.review-past-target')", Integer.class, A.toString()));
        assertEquals(0, mails(before, 0, "ecovered", "review").size());

        // Repeated failures: from the number the admin chose.
        String path = "/events/processing.repeated-failures";
        assertEquals(400, it(A, "PUT", path, "{\"enabled\":true,\"frequency\":\"IMMEDIATELY\",\"thresholds\":{\"failuresInAnHour\":0}}").statusCode());
        assertEquals(400, it(A, "PUT", path, "{\"enabled\":true,\"frequency\":\"IMMEDIATELY\",\"repeatMinutes\":5}").statusCode());
        assertEquals(200, it(A, "PUT", path, "{\"enabled\":true,\"frequency\":\"IMMEDIATELY\",\"alertAfterMinutes\":0,\"repeatMinutes\":120,\"thresholds\":{\"failuresInAnHour\":2}}").statusCode());
        UUID failed = waitingCase(A, BUS);
        // The empty test cases that were approved above failed in the running app: not counted here.
        jdbc.update("update processing_incidents set last_observed_at = last_observed_at - interval '2 hours'");
        notices.check();
        int counted = smtp.mails().size();
        for (int i = 0; i < 2; i++) {
            jdbc.update("insert into processing_incidents (id, tenant_id, package_id, stage, component, failure_category, reason_code, retryable,"
                    + " attempt_count, correlation_id, status, first_observed_at, last_observed_at, safe_summary) values (?, ?, ?, 'DETECTION', 'AI', 'X', 'X',"
                    + " false, 1, ?, 'OPEN', now(), now(), 'x')", UUID.randomUUID(), A, failed, UUID.randomUUID());
            notices.check();
            assertEquals(i, jdbc.queryForObject("select count(*) from notif_condition where event_type = 'processing.repeated-failures'", Integer.class));
        }
        assertEquals(1, mails(counted, 1, "Repeated processing failures").stream().filter(m -> !m.text().contains("ecovered")).count());
        jdbc.update("delete from processing_incidents where package_id = ?", failed);
        jdbc.update("delete from document_packages where id = ?", failed);
        notices.check();
        // A critical one can't be told later than an hour; the time of a daily summary is the admin's.
        assertEquals(400, it(A, "PUT", "/events/export.destination-paused", "{\"enabled\":true,\"frequency\":\"IMMEDIATELY\",\"alertAfterMinutes\":61}").statusCode());
        assertEquals(200, it(A, "PUT", "/events/processing.over-target", "{\"enabled\":true,\"frequency\":\"ONCE_A_DAY\",\"dailyAt\":\"06:30\"}").statusCode());
        assertTrue(it(A, "GET", "", null).body().contains("\"dailyAt\":\"06:30\""));
        assertTrue(it(A, "GET", "", null).body().contains("\"alertAfterMinutes\":15,\"repeatMinutes\":240,\"alertAfterMax\":60"), "the agreed defaults");
    }

    @Test
    @Order(10)
    void licenceNoticesReachEachEntitysOwnPeopleOnly() throws Exception {
        Notifier notifier = app.getBean(Notifier.class);
        var customer = com.documentplatform.notifications.TenantId.of("TEN-DEMO-0001");
        int before = smtp.mails().size();
        notifier.conditionFailing(NotificationEvent.builder(LicensingEvents.INTAKE_STOPPED, customer)
                .entity(EntityId.of(b.toString())).link(AppLink.of("/admin/licensing")).build());
        mails(before, 1, "Intake stopped");
        Thread.sleep(2000);
        List<FakeMailServer.Mail> stopped = mails(before, 1, "Intake stopped");
        // The platform's own hourly check may find the licence valid meanwhile, and then says so once.
        stopped = stopped.stream().filter(m -> !m.text().contains("ecovered")).toList();
        assertEquals(1, stopped.size());
        assertEquals(List.of("it@b.example"), stopped.get(0).to(), "entity B has no Business Admin who signed in");
        // The licence as a whole: every connected entity, each to its own IT Admin.
        before = smtp.mails().size();
        notifier.conditionFailing(NotificationEvent.builder(LicensingEvents.PRODUCT_NOT_COVERED, customer)
                .identifier(RecordIdentifier.of("LEXAVAULT")).build());
        List<FakeMailServer.Mail> both = mails(before, 2, "does not cover this product");
        assertEquals(Set.of(List.of("it@a.example"), List.of("it@b.example")), Set.of(both.get(0).to(), both.get(1).to()));
        assertEquals(0, jdbc.queryForObject("select count(*) from notif_outbox where tenant_id like 'TEN-%'", Integer.class));
    }

    @Test
    @Order(11)
    void peopleAreKnownOnlyFromAFreshSignInAndSwitchingOffEndsEverything() throws Exception {
        NotificationPeople people = app.getBean(NotificationPeople.class);
        var tenant = com.documentplatform.notifications.TenantId.of(A.toString());
        var role = com.documentplatform.notifications.RoleId.of("DOCUMENT_SUPERVISOR");
        assertEquals(2, people.resolve(tenant, EntityId.of(A.toString()), role).size());
        assertTrue(people.resolve(tenant, EntityId.of(b.toString()), role).isEmpty(), "never another entity's");
        assertTrue(people.resolve(com.documentplatform.notifications.TenantId.of("TEN-DEMO-0001"), null, role).isEmpty());
        people.signedIn(ActorId.of(ANN), "ann@a.example", Map.of(TenantId.of(A), Set.of("DOCUMENT_PROCESSOR")));
        assertEquals(1, people.resolve(tenant, null, role).size(), "the role is gone with the next sign-in");
        people.signedIn(ActorId.of(BOB), null, Map.of(TenantId.of(A), Set.of("DOCUMENT_SUPERVISOR")));
        assertTrue(people.resolve(tenant, null, role).isEmpty(), "no address, no messages");
        assertTrue(people.person(tenant, EVE.toString()).isEmpty(), "known in the other entity only");
        jdbc.update("update notification_people set seen_at = now() - interval '15 days'");
        assertTrue(people.resolve(tenant, null, com.documentplatform.notifications.RoleId.of("TENANT_IT_ADMIN")).isEmpty());
        assertTrue(people.purge() > 0);

        assertEquals(200, it(A, "PUT", "/mail", relay(false, "x")).statusCode(), "switching off always works");
        assertTrue(it(A, "GET", "", null).body().contains("\"enabled\":false"));
        waitingCase(A, BUS);
        int requests = jdbc.queryForObject("select count(*) from delivery_approval_requests", Integer.class);
        app.getBean(DeliveryApprovals.class).askForWaitingCases();
        assertEquals(requests, jdbc.queryForObject("select count(*) from delivery_approval_requests", Integer.class));
    }
}

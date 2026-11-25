package com.securedoc.platform.api.notifications;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.EventKind;
import com.documentplatform.notifications.EventType;
import com.documentplatform.notifications.Message;
import com.documentplatform.notifications.OutgoingMessage;
import com.documentplatform.notifications.Recipient;
import com.documentplatform.notifications.SendResult;
import com.documentplatform.notifications.email.EmailChannel;
import com.documentplatform.notifications.email.MailSecurity;
import com.documentplatform.notifications.email.MicrosoftOAuth;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.outbound.AddressGuard;
import com.documentplatform.outbound.Installation;
import com.securedoc.platform.domain.identity.TenantId;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0059: an entity's mail server and secrets: what is refused, and that both kinds of sign-in really send. */
class MailAndSecretsTest {

    private static final TenantId ENTITY = TenantId.of(UUID.fromString("30216241-b724-5a3a-aef3-3034fd546d17"));
    private static final TenantId OTHER = TenantId.of(UUID.fromString("11111111-2222-3333-4444-555555555555"));
    private static final String REF = "env:" + AppSecretResolver.entityPrefix(ENTITY) + "MAIL";
    private static final String GUID = "00000000-1111-2222-3333-444444444444";

    private static MailSettings own(String host, Integer port, MailSecurity security, String username, String secret) {
        return new MailSettings(ENTITY, true, MailSettings.Mode.OWN_SERVER, host, port, security, username,
                "notices@mail.example", "Notices", null, null, null, secret);
    }

    private static MailSettings microsoft(String secret) {
        return new MailSettings(ENTITY, true, MailSettings.Mode.MICROSOFT_365, null, null, null, null, "notices@mail.example",
                null, GUID, GUID, MicrosoftOAuth.CredentialType.SECRET, secret);
    }

    private static EntityMailServers servers(Installation installation, MailSettings settings, Set<Integer> ports,
                                             EntityMailServers.MicrosoftEndpoints microsoft) {
        AddressGuard.Resolver names = host -> switch (host) {
            case "public.example" -> new InetAddress[] {InetAddress.getByName("203.0.113.10")};
            case "exchange.corp" -> new InetAddress[] {InetAddress.getByName("10.0.0.5")};
            case "metadata.corp" -> new InetAddress[] {InetAddress.getByName("169.254.169.254")};
            default -> InetAddress.getAllByName(host);
        };
        return new EntityMailServers(e -> e.equals(ENTITY) ? Optional.of(settings) : Optional.empty(), installation,
                new AddressGuard(installation.allowInternalAddresses(), names), ports, microsoft);
    }

    private static EntityMailServers servers(Installation installation) {
        return servers(installation, microsoft(REF), Set.of(25, 465, 587), EntityMailServers.MicrosoftEndpoints.at(
                MicrosoftOAuth.GLOBAL));
    }

    private static String refusedField(Installation installation, MailSettings s) {
        EntityMailServers.Refused refused = assertThrows(EntityMailServers.Refused.class, () -> servers(installation).server(s));
        for (String typed : new String[] {s.host(), s.username(), s.secretReference()}) {
            if (typed != null) assertFalse(refused.getMessage().contains(typed), "what was typed is never repeated");
        }
        return refused.field();
    }

    @Test
    void theAddressRulesAreTheInstallations() {
        Installation strict = Installation.onPremises();
        Installation internal = strict.withInternalAddresses(true);
        assertEquals("public.example", servers(strict).server(own("public.example", 587, MailSecurity.STARTTLS, "svc", REF)).host());
        assertEquals("host", refusedField(strict, own("exchange.corp", 587, MailSecurity.STARTTLS, "svc", REF)));
        assertEquals("host", refusedField(strict, own("127.0.0.1", 587, MailSecurity.STARTTLS, "svc", REF)));
        assertEquals("exchange.corp", servers(internal).server(own("exchange.corp", 587, MailSecurity.STARTTLS, "svc", REF)).host());
        assertEquals("host", refusedField(internal, own("metadata.corp", 587, MailSecurity.STARTTLS, "svc", REF)));
        assertEquals("host", refusedField(internal, own("http://x/y", 587, MailSecurity.STARTTLS, "svc", REF)));
        assertEquals("port", refusedField(internal, own("exchange.corp", 5432, MailSecurity.STARTTLS, "svc", REF)));
    }

    @Test
    void aLoginIsAlwaysEncryptedAndARelayWithoutOneIsInternalOnly() {
        Installation internal = Installation.onPremises().withInternalAddresses(true);
        assertEquals("security", refusedField(internal, own("exchange.corp", 25, MailSecurity.NONE, "svc", REF)));
        assertEquals("security", refusedField(Installation.onPremises(), own("public.example", 25, MailSecurity.NONE, null, null)));
        assertEquals(25, servers(internal).server(own("exchange.corp", 25, MailSecurity.NONE, null, null)).port());
    }

    @Test
    void onlyThisEntitysOwnSecretsCanBeNamed() {
        Installation internal = Installation.onPremises().withInternalAddresses(true);
        for (String ref : new String[] {null, "env:SPRING_DATASOURCE_PASSWORD", "env:LEXAVAULT_SECRET_INSTALL_LINK_KEY",
                "env:" + AppSecretResolver.entityPrefix(OTHER) + "MAIL", "file:../" + AppSecretResolver.entityPrefix(ENTITY) + "MAIL",
                "sys:" + AppSecretResolver.entityPrefix(ENTITY) + "MAIL", REF + "/x", "hunter2"}) {
            assertEquals("secretReference", refusedField(internal, own("exchange.corp", 587, MailSecurity.STARTTLS, "svc", ref)));
            assertEquals("secretReference", refusedField(internal, microsoft(ref)));
        }
        // A stored row that breaks a rule (changed by hand) is not used either.
        EntityMailServers byHand = servers(internal, microsoft("env:" + AppSecretResolver.entityPrefix(OTHER) + "MAIL"),
                Set.of(587), EntityMailServers.MicrosoftEndpoints.at(MicrosoftOAuth.GLOBAL));
        assertTrue(byHand.find(EntityNotifications.tenant(ENTITY), EntityNotifications.EMAIL).isEmpty());
        assertTrue(servers(internal).find(EntityNotifications.tenant(OTHER), EntityNotifications.EMAIL).isEmpty());
        assertTrue(servers(internal).find(com.documentplatform.notifications.TenantId.of("TEN-DEMO-0001"),
                EntityNotifications.EMAIL).isEmpty(), "the customer as a whole has no mail server");
    }

    @Test
    void theResolverReadsOnlyItsOwnNames(@TempDir Path dir) throws Exception {
        Map<String, String> env = new HashMap<>(Map.of("LEXAVAULT_SECRET_INSTALL_PROXY", " s3cret \n", "PATH", "x"));
        AppSecretResolver secrets = new AppSecretResolver(env::get, dir.toString());
        assertEquals("s3cret", secrets.resolve(SecretRef.of("env:LEXAVAULT_SECRET_INSTALL_PROXY")).orElseThrow().reveal());
        assertTrue(secrets.resolve(SecretRef.of("env:PATH")).isEmpty());
        assertTrue(secrets.resolve(SecretRef.of("notifications/mail-password")).isEmpty());
        Files.writeString(dir.resolve("LEXAVAULT_SECRET_INSTALL_KEY"), "from-file\n");
        Files.createDirectory(dir.resolve("LEXAVAULT_SECRET_INSTALL_DIR"));
        Files.writeString(dir.resolve("other.txt"), "no");
        assertEquals("from-file", secrets.resolve(SecretRef.of("file:LEXAVAULT_SECRET_INSTALL_KEY")).orElseThrow().reveal());
        assertTrue(secrets.resolve(SecretRef.of("file:LEXAVAULT_SECRET_INSTALL_DIR")).isEmpty());
        assertTrue(secrets.resolve(SecretRef.of("file:other.txt")).isEmpty());
        assertTrue(new AppSecretResolver(env::get, "").resolve(SecretRef.of("file:LEXAVAULT_SECRET_INSTALL_KEY")).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> AppSecretResolver.installRef(REF));
    }

    @Test
    void anEmailAddressIsTakenOnlyWhenItIsOne() {
        assertTrue(NotificationPeople.usable("ann@example.com"));
        for (String bad : new String[] {null, "", "ann", "a@b@c", "ann@example.com,eve@example.com", "Ann <ann@example.com>", "a b@c.d"}) {
            assertFalse(NotificationPeople.usable(bad), String.valueOf(bad));
        }
    }

    @Test
    void theCatalogueFollowsTheContentRuleAndTheRoles() {
        var catalogue = LexaVaultEvents.catalogue();
        for (EventType t : catalogue.all()) {
            assertTrue(t.critical() ? t.defaults().recipientRoles().contains(LexaVaultEvents.IT_ADMIN)
                    : !t.defaults().recipientRoles().isEmpty(), t.id().value());
        }
        assertTrue(LexaVaultEvents.OPERATIONAL.stream().allMatch(id -> catalogue.require(id).continuous()
                && catalogue.require(id).kind() == EventKind.TECHNICAL));
        assertEquals(30, catalogue.require(LexaVaultEvents.DESTINATION_BUSY).defaults().conditionTiming().alertAfter().toMinutes());
    }

    private static OutgoingMessage message() throws Exception {
        // Made by the platform's own builder, as a real one is.
        return new OutgoingMessage(EntityNotifications.tenant(ENTITY), EntityNotifications.EMAIL, ChannelKind.EMAIL,
                Set.of(new Recipient("u1", "ann@example.com")), testMessage(), "key-1");
    }

    private static Message testMessage() throws Exception {
        AtomicReference<Message> seen = new AtomicReference<>();
        var store = new com.documentplatform.notifications.OutboxStore() {
            @Override public boolean enqueue(com.documentplatform.notifications.OutboxEntry e) { seen.set(e.message()); return true; }
            @Override public Optional<com.documentplatform.notifications.OutboxEntry> claim(String k, java.time.Instant n, java.time.Instant l) { return Optional.empty(); }
            @Override public java.util.List<com.documentplatform.notifications.OutboxEntry> claimDue(java.time.Instant n, java.time.Instant l, int limit) { return java.util.List.of(); }
            @Override public void markSent(String k, int a, java.time.Instant at) { }
            @Override public void markRetry(String k, int a, java.time.Instant n, com.documentplatform.notifications.FailureCode f) { }
            @Override public void markFailed(String k, int a, com.documentplatform.notifications.FailureCode f, java.time.Instant at) { }
            @Override public Optional<com.documentplatform.notifications.OutboxEntry> find(String k) { return Optional.empty(); }
            @Override public int purgeFinishedBefore(java.time.Instant b) { return 0; }
        };
        var settingsStore = new com.documentplatform.notifications.SettingsStore() {
            @Override public Optional<com.documentplatform.notifications.TenantSettings> tenant(com.documentplatform.notifications.TenantId t) {
                return Optional.of(new com.documentplatform.notifications.TenantSettings(URI.create("https://app.example"),
                        java.time.ZoneOffset.UTC, Map.of(EntityNotifications.EMAIL, ChannelKind.EMAIL)));
            }
            @Override public void saveTenant(com.documentplatform.notifications.TenantId t, com.documentplatform.notifications.TenantSettings s) { }
            @Override public Optional<com.documentplatform.notifications.EventSettings> technical(com.documentplatform.notifications.TenantId t, com.documentplatform.notifications.EventTypeId e) { return Optional.empty(); }
            @Override public void saveTechnical(com.documentplatform.notifications.TenantId t, com.documentplatform.notifications.EventTypeId e, com.documentplatform.notifications.EventSettings s) { }
            @Override public Optional<com.documentplatform.notifications.EventSettings> business(com.documentplatform.notifications.TenantId t, com.documentplatform.notifications.EntityId i, com.documentplatform.notifications.EventTypeId e) { return Optional.empty(); }
            @Override public void saveBusiness(com.documentplatform.notifications.TenantId t, com.documentplatform.notifications.EntityId i, com.documentplatform.notifications.EventTypeId e, com.documentplatform.notifications.EventSettings s) { }
        };
        var summaries = new com.documentplatform.notifications.SummaryStore() {
            @Override public void add(com.documentplatform.notifications.SummaryItem i) { }
            @Override public java.util.List<com.documentplatform.notifications.SummaryItem> due(java.time.Instant n) { return java.util.List.of(); }
            @Override public void remove(java.util.Collection<String> k) { }
            @Override public int pending() { return 0; }
        };
        var conditions = (com.documentplatform.notifications.ConditionStore) java.lang.reflect.Proxy.newProxyInstance(
                MailAndSecretsTest.class.getClassLoader(), new Class<?>[] {com.documentplatform.notifications.ConditionStore.class},
                (p, m, a) -> m.getReturnType() == java.util.List.class ? java.util.List.of() : null);
        com.documentplatform.notifications.Notifier.builder(
                new com.documentplatform.notifications.NotificationSettings(LexaVaultEvents.catalogue(), settingsStore),
                (t, e, r) -> Set.of()).outbox(store).summaries(summaries).conditions(conditions).build()
                .publishToPerson(com.documentplatform.notifications.NotificationEvent.builder(LexaVaultEvents.TEST_MESSAGE,
                        EntityNotifications.tenant(ENTITY)).entity(EntityNotifications.entity(ENTITY)).build(), "u1");
        return seen.get();
    }

    @Test
    void microsoft365SendsWithATokenAndNeverThePassword() throws Exception {
        AtomicReference<String> asked = new AtomicReference<>();
        HttpServer tokens = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        tokens.createContext("/", x -> {
            asked.set(x.getRequestURI().getPath() + " " + new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"token_type\":\"Bearer\",\"expires_in\":3600,\"access_token\":\"fake-token-1\"}".getBytes(StandardCharsets.UTF_8);
            x.getResponseHeaders().add("Content-Type", "application/json");
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        tokens.start();
        try (FakeMailServer smtp = new FakeMailServer(null)) {
            Installation internal = Installation.onPremises().withInternalAddresses(true);
            var endpoints = new EntityMailServers.MicrosoftEndpoints(
                    URI.create("http://127.0.0.1:" + tokens.getAddress().getPort()), "127.0.0.1", smtp.port(), MailSecurity.NONE);
            EntityMailServers servers = servers(internal, microsoft(REF), Set.of(587), endpoints);
            AppSecretResolver secrets = new AppSecretResolver(
                    Map.of(REF.substring(4), "client-secret-value")::get, "");
            SendResult result = new EmailChannel(servers, secrets).send(message());
            assertTrue(result.sent(), String.valueOf(result.failure()));
            assertTrue(asked.get().startsWith("/" + GUID + "/oauth2/v2.0/token "), asked.get());
            assertTrue(smtp.logins().get(0).contains("user=notices@mail.example") && smtp.logins().get(0).contains("Bearer fake-token-1"));
            assertFalse(smtp.logins().get(0).contains("client-secret-value"));
            assertEquals(1, smtp.await(1).size());
            // The same address over plain http is refused where internal addresses are not allowed.
            assertTrue(servers(Installation.onPremises(), microsoft(REF), Set.of(587), endpoints)
                    .find(EntityNotifications.tenant(ENTITY), EntityNotifications.EMAIL).isEmpty());
        } finally {
            tokens.stop(0);
        }
    }

    @Test
    void thePasswordComesFromTheSecretAndIsNotInTheMessage() throws Exception {
        try (FakeMailServer smtp = new FakeMailServer(null)) {
            // Straight to the sender, without the rule that a login needs encryption: what is sent and where the password is from.
            var server = new com.documentplatform.notifications.email.MailServer("127.0.0.1", smtp.port(), MailSecurity.NONE,
                    "svc-mail", AppSecretResolver.entityRef(ENTITY, REF), "notices@mail.example", "Notices",
                    java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5));
            AppSecretResolver secrets = new AppSecretResolver(Map.of(REF.substring(4), "mail-password-value")::get, "");
            assertTrue(new EmailChannel((t, d) -> Optional.of(server), secrets).send(message()).sent());
            assertTrue(smtp.logins().get(0).endsWith("svc-mail\u0000mail-password-value"), "signed in with the stored secret");
            assertFalse(smtp.await(1).get(0).text().contains("mail-password-value"));
            assertEquals(java.util.List.of("ann@example.com"), smtp.mails().get(0).to());
        }
    }

    @Test
    void theCustomersOwnServerSendsWithAPasswordOverTlsTrustedByThePrivateCa(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("k.p12");
        String tool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process p = new ProcessBuilder(tool, "-genkeypair", "-alias", "a", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit")
                .redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor());
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(ks, "changeit".toCharArray());
        SSLContext server = SSLContext.getInstance("TLS");
        server.init(keys.getKeyManagers(), null, null);
        KeyStore trusted = KeyStore.getInstance("PKCS12");
        trusted.load(null, null);
        trusted.setCertificateEntry("a", ks.getCertificate("a"));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        SSLContext client = SSLContext.getInstance("TLS");
        client.init(null, trust.getTrustManagers(), null);
        try (FakeMailServer smtp = new FakeMailServer(server.getServerSocketFactory())) {
            Installation internal = Installation.onPremises().withInternalAddresses(true);
            EntityMailServers servers = servers(internal, own("localhost", smtp.port(), MailSecurity.TLS, "svc-mail", REF),
                    Set.of(smtp.port()), EntityMailServers.MicrosoftEndpoints.at(MicrosoftOAuth.GLOBAL));
            AppSecretResolver secrets = new AppSecretResolver(Map.of(REF.substring(4), "mail-password-value")::get, "");
            // Some desktop antivirus answers mail connections itself with its own certificate; then this can't be tested here.
            try (var probe = (javax.net.ssl.SSLSocket) client.getSocketFactory().createSocket("localhost", smtp.port())) {
                probe.startHandshake();
            } catch (javax.net.ssl.SSLException intercepted) {
                org.junit.jupiter.api.Assumptions.abort("TLS to the fake mail server is intercepted on this machine");
            }
            SendResult result = new EmailChannel(servers, secrets, client.getSocketFactory()).send(message());
            assertTrue(result.sent(), String.valueOf(result.failure()));
            assertEquals("svc-mail\u0000mail-password-value", smtp.logins().get(0).replaceFirst("^\u0000", ""));
            // Without that certificate authority the certificate is not trusted, and no login is made.
            assertFalse(new EmailChannel(servers, secrets).send(message()).sent());
            // The secret is gone: a specific code, nothing sent.
            assertEquals(com.documentplatform.notifications.FailureCode.SECRET_NOT_FOUND,
                    new EmailChannel(servers, new AppSecretResolver(n -> null, ""), client.getSocketFactory()).send(message()).failure());
            assertEquals(1, smtp.logins().size());
        }
    }
}

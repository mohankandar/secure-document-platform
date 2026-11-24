package com.securedoc.install;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0041: the local OIDC start's sign-in file, written from the demo one, as setup reads it. */
class LocalOidcSigninTest {

    static final Path DEMO = DemoStepTest.REPO.resolve("install/demo");
    static final String KEYCLOAK = "http://localhost:8181/realms/document-platform-local";

    private static int run(String... args) {
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return new LocalOidcSignin(quiet, quiet).run(args);
    }

    static Path write(Path out) {
        return LocalOidcSignin.write(DEMO.resolve("signin-demo.json"), out, URI.create(KEYCLOAK), "customer-oidc",
                "lexavault-api", "lexavault-web");
    }

    @Test
    void theDemoFileWithTheLocalLoginSystemPutIn(@TempDir Path out) throws Exception {
        Path file = write(out);
        assertEquals(out.resolve(LocalOidcSignin.FILE_NAME), file);
        byte[] bytes = Files.readAllBytes(file);
        assertFalse(bytes.length >= 3 && bytes[0] == (byte) 0xEF, "no byte order mark");

        // Task 0054: platform-setup's SigninFile is internal since 0.6.0; setup itself reads this file in
        // FreshDatabaseStartPostgresTest. Everything but these four values is the demo file's (the customer, the mode, the
        // role mapping, which is copied next to it).
        ObjectMapper json = JsonMapper.builderWithJackson2Defaults().build();
        ObjectNode expected = (ObjectNode) json.readTree(Files.readString(DEMO.resolve("signin-demo.json")));
        ((ObjectNode) expected.get("loginSystem")).put("issuer", KEYCLOAK).put("providerId", "customer-oidc");
        ((ObjectNode) expected.get("products").get("LEXAVAULT")).put("browserClientId", "lexavault-web").put("apiAudience", "lexavault-api");
        assertEquals(expected, json.readTree(new String(bytes, StandardCharsets.UTF_8)));
        String mapping = expected.get("products").get("LEXAVAULT").get("roleMappingFile").asString();
        assertArrayEquals(Files.readAllBytes(DEMO.resolve(mapping)), Files.readAllBytes(out.resolve(mapping)));
        // Written again: the same file.
        assertEquals(Files.readString(file), Files.readString(write(out)));
    }

    @Test
    void everyOptionIsRequiredAndChecked(@TempDir Path out) {
        String from = DEMO.resolve("signin-demo.json").toString();
        assertEquals(0, run("--from", from, "--out", out.toString(), "--issuer", KEYCLOAK, "--provider-id", "p",
                "--audience", "a", "--browser-client-id", "b"));
        assertTrue(Files.exists(out.resolve(LocalOidcSignin.FILE_NAME)));
        assertEquals(2, run());
        assertEquals(2, run("--from", from, "--out", out.toString(), "--issuer", KEYCLOAK, "--provider-id", "p",
                "--audience", "a"));
        assertEquals(2, run("--from", from, "--out", out.toString(), "--issuer", "not an address", "--provider-id", "p",
                "--audience", "a", "--browser-client-id", "b"));
        assertEquals(2, run("--from", from, "--out", out.toString(), "--issuer", "/relative", "--provider-id", "p",
                "--audience", "a", "--browser-client-id", "b"));
        assertEquals(2, run("--from", out.resolve("missing.json").toString(), "--out", out.toString(), "--issuer", KEYCLOAK,
                "--provider-id", "p", "--audience", "a", "--browser-client-id", "b"));
        assertEquals(2, run("--from", from, "--from", from));
    }
}

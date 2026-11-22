package com.securedoc.install;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.TrustedKeys;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0037 step 5 without a database: the demo step's options, and the shipped demo and product files. */
class DemoStepTest {

    static final Path REPO = Path.of("..", "..").toAbsolutePath().normalize();

    private static int run(String... args) {
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        return new DemoStep(quiet, quiet, Clock.systemUTC()).run(args, Map.of("LEXAVAULT_DEMO_DB_URL", "jdbc:postgresql://h/db",
                "LEXAVAULT_DEMO_DB_USER", "u", "LEXAVAULT_DEMO_DB_PASSWORD", "p"));
    }

    @Test
    void optionsAreCheckedBeforeAnythingIsRead() {
        assertEquals(2, run());
        assertEquals(2, run("activate"));
        assertEquals(2, run("apply"));  // --keys is required
        assertEquals(2, run("apply", "--keys", "k.json", "--slot", "not-a-slot"));
        assertEquals(2, run("apply", "--keys", "k.json", "--oidc-issuer", "http://localhost:8181/realms/x"));
        assertEquals(2, run("apply", "--keys", "k.json", "--oidc-audience", "a"));
        assertEquals(2, run("apply", "--keys", "k.json", "--unknown", "x"));
        assertEquals(2, run("apply", "--keys", "k.json", "--keys", "again.json"));
        assertEquals(2, run("apply", "--keys", Path.of("no-such-file.json").toString()));  // unreadable keys file
        // installed (task 0037 fix 1) takes only --slot.
        assertEquals(2, run("installed", "--keys", "k.json"));
        assertEquals(2, run("installed", "--slot", "not-a-slot"));
        assertEquals(2, run("installed", "--slot"));
        assertEquals("ENT-DEMO-0001", DemoStep.parseInstalled(new String[] {"installed"}));
        assertEquals("ENT-DEMO-0002", DemoStep.parseInstalled(new String[] {"installed", "--slot", "ENT-DEMO-0002"}));
    }

    @Test
    void theDefaultsAreTheDemoEntityOfTheDemoFiles() {
        DemoStep.Options o = DemoStep.parse(new String[] {"apply", "--keys", "k.json"});
        assertEquals("ENT-DEMO-0001", o.slot());
        assertEquals("Local Demo Tenant", o.name());
        assertEquals("local-demo", o.slug());
        assertEquals("scanner-bridge-local", o.scannerPrincipal());
        assertEquals(null, o.oidcIssuer());
        // The demo entity's UUID everywhere (scripts, config, the UI's LOCAL default) is the one derived from its slot.
        assertEquals(UUID.fromString("30216241-b724-5a3a-aef3-3034fd546d17"), EntityUuid.fromSlotId("ENT-DEMO-0001"));
        assertEquals(UUID.fromString("2db66f56-f595-5144-8ca7-78831ddd8002"), EntityUuid.fromSlotId("ENT-DEMO-0002"));
    }

    @Test
    void theDemoLicenceVerifiesOnlyWithTheDevelopmentKey() throws Exception {
        String licence = Files.readString(REPO.resolve("install/demo/licence-demo.json"));
        TrustedKeys dev = TrustedKeys.parse(Files.readString(REPO.resolve("install/demo/development-trusted-keys.json")));
        var verified = new LicenceVerifier(dev).verify(licence, "TEN-DEMO-0001");
        assertEquals("TEN-DEMO-0001", verified.tenantId());
        assertTrue(verified.products().contains("LEXAVAULT"));
        assertEquals("NON_PRODUCTION", verified.kind().name());
        // No production keys file may list the development key.
        assertTrue(!Files.exists(REPO.resolve("install/trusted-keys.json"))
                || !Files.readString(REPO.resolve("install/trusted-keys.json")).contains("dev-lexavault-demo-1"));
    }

    @Test
    void productRolesListTheRolesTheAliasesAndThePlatformRole() throws Exception {
        String roles = Files.readString(REPO.resolve("install/product-roles.json"));
        for (String role : new String[] {"DOCUMENT_PROCESSOR", "DOCUMENT_SUPERVISOR", "BUSINESS_ADMIN", "REPORT_VIEWER",
                "TENANT_IT_ADMIN", "USER", "REVIEWER", "APPROVER", "PLATFORM_OPERATIONS_ADMIN", "\"LEXAVAULT\""}) {
            assertTrue(roles.contains(role), role);
        }
        assertThrows(Exception.class, () -> DemoStep.parse(new String[] {"apply"}));
    }
}

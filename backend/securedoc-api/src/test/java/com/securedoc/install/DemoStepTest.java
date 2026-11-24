package com.securedoc.install;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.licensing.LicenceVerifier;
import com.documentplatform.licensing.TrustedKeys;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.securedoc.platform.infrastructure.identity.LexaVaultProduct;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals(2, run("apply", "--keys", "k.json"));  // task 0054: gone, the keys come from the build
        assertEquals(2, run("apply", "--slot", "not-a-slot"));
        // Task 0041: the --oidc-* options are gone (setup takes the local login system itself).
        assertEquals(2, run("apply", "--oidc-issuer", "http://localhost:8181/realms/x"));
        assertEquals(2, run("apply", "--oidc-audience", "a"));
        assertEquals(2, run("apply", "--unknown", "x"));
        assertEquals(2, run("apply", "--slug", "a", "--slug", "b"));
        assertEquals(2, run("apply", "--slug"));
        // installed (task 0037 fix 1) takes only --slot.
        assertEquals(2, run("installed", "--keys", "k.json"));
        assertEquals(2, run("installed", "--slot", "not-a-slot"));
        assertEquals(2, run("installed", "--slot"));
        assertEquals("ENT-DEMO-0001", DemoStep.parseInstalled(new String[] {"installed"}));
        assertEquals("ENT-DEMO-0002", DemoStep.parseInstalled(new String[] {"installed", "--slot", "ENT-DEMO-0002"}));
    }

    @Test
    void theDefaultsAreTheDemoEntityOfTheDemoFiles() {
        DemoStep.Options o = DemoStep.parse(new String[] {"apply"});
        assertEquals("ENT-DEMO-0001", o.slot());
        assertEquals("Local Demo Tenant", o.name());
        assertEquals("local-demo", o.slug());
        assertEquals("scanner-bridge-local", o.scannerPrincipal());
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
        // Task 0041: the demo key is marked as a development key, so setup takes an http issuer on localhost under the
        // demo licence (platform-core 0.2.0 local test login servers).
        assertTrue(dev.isDevelopmentKey(verified.keyId()));
        // No production keys file may list the development key.
        assertTrue(!Files.exists(REPO.resolve("install/trusted-keys.json"))
                || !Files.readString(REPO.resolve("install/trusted-keys.json")).contains("dev-lexavault-demo-1"));
    }

    @Test
    void productRolesHoldTheRolesTheAliasesAndThePlatformRole() throws Exception {
        // Task 0041 (platform-core 0.2.0): USER and REVIEWER stand for Document Processor, APPROVER keeps Document
        // Supervisor; they are aliases, no longer roles of their own. Customer keys are optional for LexaVault.
        // Task 0054: platform-setup's ProductRoles is internal since 0.6.0, so the file is read here; setup itself reads
        // it in FreshDatabaseStartPostgresTest and LicenceBuildTest.
        ObjectMapper json = JsonMapper.builderWithJackson2Defaults().build();
        JsonNode file = json.readTree(Files.readString(REPO.resolve("install/product-roles.json")));
        assertEquals("LEXAVAULT", file.get("productCode").asString());
        Set<String> roles = Set.of(json.convertValue(file.get("roles"), String[].class));
        Map<?, ?> aliases = json.convertValue(file.get("aliases"), Map.class);
        assertEquals(Set.of("DOCUMENT_PROCESSOR", "DOCUMENT_SUPERVISOR", "BUSINESS_ADMIN", "REPORT_VIEWER", "TENANT_IT_ADMIN"), roles);
        assertEquals(Map.of("USER", "DOCUMENT_PROCESSOR", "REVIEWER", "DOCUMENT_PROCESSOR", "APPROVER", "DOCUMENT_SUPERVISOR"),
                aliases);
        assertEquals(Set.of("PLATFORM_OPERATIONS_ADMIN"), Set.of(json.convertValue(file.get("platformRoles"), String[].class)));
        assertEquals("optional", file.get("customerKeys").asString());
        // The same names LexaVault accepts when it reads a stored grant.
        assertEquals(LexaVaultProduct.ENTITY_ROLES, roles);
        assertEquals(LexaVaultProduct.ROLE_ALIASES, aliases);
    }

    @Test
    void theDemoMappingGrantsThePlatformRoleOnlyPlatformWide() throws Exception {
        // Task 0054: the group Start-LexaVault-OIDC.ps1 names for platform operations.
        var rows = Files.readAllLines(REPO.resolve("install/demo/role-mapping-lexavault-demo.csv"));
        assertEquals(java.util.List.of("*,,PlatformOperationsAdmins,PLATFORM_OPERATIONS_ADMIN"),
                rows.stream().filter(r -> r.startsWith("*") || r.contains("PLATFORM_OPERATIONS_ADMIN")).toList());
    }
}

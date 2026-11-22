package com.securedoc.install;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0037 step 3: the schema step's refusals (no database needed), and that startup never runs Flyway. */
class SchemaStepTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();

    private int run(Map<String, String> env, String... args) {
        return new SchemaStep(new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8))
                .run(args, env);
    }

    private String output() {
        return out.toString(StandardCharsets.UTF_8) + err.toString(StandardCharsets.UTF_8);
    }

    @Test
    void missingDatabaseSettingsAreNamedNeverGuessed() {
        assertEquals(2, run(Map.of(), "apply"));
        assertTrue(output().contains("DB_SETTINGS_MISSING"));
        assertTrue(output().contains("LEXAVAULT_SCHEMA_DB_URL"));
        assertTrue(output().contains("LEXAVAULT_SCHEMA_DB_PASSWORD"));
    }

    @Test
    void aPasswordInTheUrlIsRefusedAndNotRepeated() {
        assertEquals(2, run(Map.of("LEXAVAULT_SCHEMA_DB_URL", "jdbc:postgresql://h/db?password=Sekr1tValue",
                "LEXAVAULT_SCHEMA_DB_USER", "u", "LEXAVAULT_SCHEMA_DB_PASSWORD", "p"), "apply"));
        assertTrue(output().contains("must not hold the password"));
        assertFalse(output().contains("Sekr1tValue"));
    }

    @Test
    void anUnreachableDatabaseGivesTheSqlStateNeverThePassword() {
        assertEquals(1, run(Map.of("LEXAVAULT_SCHEMA_DB_URL", "jdbc:postgresql://127.0.0.1:1/nothing?connectTimeout=2",
                "LEXAVAULT_SCHEMA_DB_USER", "u", "LEXAVAULT_SCHEMA_DB_PASSWORD", "Sekr1tValue"), "status"));
        assertTrue(output().contains("DB_UNREACHABLE"));
        assertFalse(output().contains("Sekr1tValue"));
    }

    @Test
    void badCommandsAndOptionsAreRefused() {
        Map<String, String> env = Map.of("LEXAVAULT_SCHEMA_DB_URL", "jdbc:postgresql://h/db",
                "LEXAVAULT_SCHEMA_DB_USER", "u", "LEXAVAULT_SCHEMA_DB_PASSWORD", "p");
        assertEquals(2, run(env));
        assertEquals(2, run(env, "drop"));
        assertEquals(2, run(env, "apply", "--runtime-user", "x; drop table y"));
        assertEquals(2, run(env, "status", "--runtime-user", "app"));
        assertTrue(output().contains("BAD_OPTION"));
    }

    @Test
    void thePlatformRunsHaveTheirOwnHistoryTables() {
        assertEquals("idn_schema_history", SchemaStep.PLATFORM_RUNS.get(0).historyTable());
        assertEquals("classpath:db/platform/identity", SchemaStep.PLATFORM_RUNS.get(0).location());
        assertEquals("lic_schema_history", SchemaStep.PLATFORM_RUNS.get(1).historyTable());
        assertEquals("classpath:db/platform/licensing", SchemaStep.PLATFORM_RUNS.get(1).location());
    }

    @Test
    void startupNeverRunsFlyway() throws Exception {
        String yml = Files.readString(Path.of("src/main/resources/application.yml"));
        assertTrue(yml.matches("(?s).*\\n  flyway:\\n    enabled: false\\n.*"), "spring.flyway.enabled must be false");
        for (String profile : new String[] {"application-local.yml", "application-oidc-local.yml"}) {
            assertFalse(Files.readString(Path.of("src/main/resources", profile)).contains("flyway"), profile);
        }
    }
}

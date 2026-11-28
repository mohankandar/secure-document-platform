package com.securedoc.install;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0118, the CI check: every migration on a fresh database, then every table and column must have a comment and
 * the committed docs/data files must be what the live schema gives. Then the check is shown to fail on a table and a
 * column added without a comment. Runs where {@code LEXAVAULT_TEST_PG_URL} names a throwaway server (CI does).
 */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class DataDictionaryPostgresTest {

    /** The repository's docs/data, from the module directory Maven runs the tests in. */
    private static final Path DOCS_DATA = Path.of("..", "..", "docs", "data");

    private static ThrowawayDatabase db;

    @BeforeAll
    static void migrate() throws SQLException {
        db = ThrowawayDatabase.create("dict");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        int exit = new SchemaStep(print, print).run(new String[] {"apply"}, db.env(SchemaStep.ENV_PREFIX));
        assertEquals(0, exit, out.toString(StandardCharsets.UTF_8));
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("platform-core comment(s) set"));
    }

    @AfterAll
    static void drop() throws SQLException {
        if (db != null) db.close();
    }

    private static String[] check(int expectedExit) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        int exit = DataDictionary.run(new String[] {"check", DOCS_DATA.toString()}, db.env(SchemaStep.ENV_PREFIX), print, print);
        String text = out.toString(StandardCharsets.UTF_8);
        assertEquals(expectedExit, exit, text);
        assertFalse(text.contains(db.password), "the password is never printed");
        return text.split("\\R");
    }

    @Test
    @Order(1)
    void everyTableAndColumnHasACommentAndTheCommittedDictionaryIsUpToDate() throws Exception {
        assertTrue(Files.exists(DOCS_DATA.resolve(DataDictionary.DICTIONARY)), "run " + DataDictionary.SCRIPT + " and commit docs/data");
        check(0);
    }

    @Test
    @Order(2)
    void aRerunOfTheSchemaStepChangesNoComment() throws Exception {
        List<DataDictionary.Table> before;
        try (Connection c = db.connect()) {
            before = DataDictionary.read(c);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        assertEquals(0, new SchemaStep(print, print).run(new String[] {"apply"}, db.env(SchemaStep.ENV_PREFIX)),
                out.toString(StandardCharsets.UTF_8));
        try (Connection c = db.connect()) {
            assertEquals(before, DataDictionary.read(c));
        }
    }

    @Test
    @Order(3)
    void aTableOrColumnAddedWithoutACommentFailsTheCheck() throws Exception {
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            s.execute("create table lv_0118_uncommented (id uuid primary key)");
            s.execute("comment on column lv_0118_uncommented.id is 'An id.'");
            s.execute("alter table documents add column lv_0118_new_column integer");
        }
        String[] lines = check(1);
        List<String> problems = List.of(lines);
        assertTrue(problems.contains("DATA_DICTIONARY: table lv_0118_uncommented has no comment"), String.join("\n", lines));
        assertTrue(problems.contains("DATA_DICTIONARY: column documents.lv_0118_new_column has no comment"), String.join("\n", lines));
        assertTrue(problems.stream().anyMatch(p -> p.contains("differs from the live schema")), String.join("\n", lines));
        assertTrue(problems.stream().anyMatch(p -> p.contains("Update-LexaVault-DataDictionary")), String.join("\n", lines));
    }
}

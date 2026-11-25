package com.securedoc.install;

import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldKey;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.jdbc.PostgresFieldStore;
import com.documentplatform.fields.jdbc.PostgresValueStore;
import com.documentplatform.setup.SetupCommand;
import com.securedoc.platform.application.casefields.CaseFieldService;
import com.securedoc.platform.domain.identity.TenantId;
import liquibase.Contexts;
import liquibase.LabelExpression;
import liquibase.Liquibase;
import liquibase.changelog.ChangeSet;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0061: a database with case fields and cases of before it; the schema step moves them, then drops the old tables. */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
class CaseFieldCarryOverPostgresTest {
    private static final UUID TENANT = UUID.randomUUID();
    private static final Path DEMO = DemoStepTest.REPO.resolve("install/demo");
    private static UUID entity = TENANT;
    private static ThrowawayDatabase db;
    private static JdbcTemplate jdbc;
    @TempDir
    static Path work;

    @BeforeAll
    static void aDatabaseOfBefore() throws Exception {
        db = ThrowawayDatabase.create("carry");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(db.url, db.user, db.password));
        try (Connection c = db.connect()) {
            Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.xml", new ClassLoaderResourceAccessor(),
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c)));
            List<ChangeSet> todo = liquibase.listUnrunChangeSets(new Contexts(), new LabelExpression());
            int before = 0;
            while (!todo.get(before).getId().startsWith("066-")) before++;
            liquibase.update(before, new Contexts(), new LabelExpression());
        }
        assertEquals("f", one("select to_regclass('business_records') is null"));
        jdbc.update("insert into tenants (id, name, slug, status, created_at, updated_at) values (?, 'Old', 'old', 'ACTIVE', now(), now())", TENANT);
    }

    @AfterAll
    static void drop() throws SQLException {
        if (db != null) db.close();
    }

    private static UUID definition(String key, String label, String type, boolean required, boolean primary, boolean active,
                                   String more) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into business_identifier_definitions (id, tenant_id, identifier_key, label, required_flag, primary_flag,"
                + " searchable_flag, extractable_flag, displayable_flag, display_order, active_flag, created_at, updated_at,"
                + " validation_type, case_normalization" + (more.isEmpty() ? "" : "," + more.split("=")[0]) + ") values (?, ?, ?, ?, ?, ?,"
                + " true, true, true, 1, ?, now(), now(), ?, 'PRESERVE'" + (more.isEmpty() ? "" : ",'" + more.split("=")[1] + "'") + ")",
                id, entity, key, label, required, primary, active, type);
        return id;
    }

    private static UUID record(Object... definitionAndValue) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into business_records (id, tenant_id, created_at, updated_at) values (?, ?, now(), now())", id, entity);
        for (int i = 0; i < definitionAndValue.length; i += 2) {
            jdbc.update("insert into business_record_identifiers (id, tenant_id, business_record_id, definition_id, identifier_value,"
                    + " normalized_value, created_at) values (?, ?, ?, ?, ?, ?, now())", UUID.randomUUID(), entity, id,
                    definitionAndValue[i], definitionAndValue[i + 1], definitionAndValue[i + 1]);
        }
        return id;
    }

    private static UUID aCase(UUID record) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into document_packages (id, tenant_id, package_type, status, created_by, created_at, updated_at,"
                + " business_record_id) values (?, ?, 'MANUAL_UPLOAD', 'PROCESSING', ?, now(), now(), ?)", id, entity, UUID.randomUUID(), record);
        return id;
    }

    @Test
    void fieldsValuesAndLinksAreMovedAndTheOldTablesAreGone() throws Exception {
        UUID contract = definition("contract_number", "Contract", "NUMERIC", true, true, true, "exact_length=6");
        UUID branch = definition("branch", "Branch", "ALPHANUMERIC", true, false, true, "");
        UUID state = definition("state", "State", "ALLOW_LIST", false, false, true, "allowed_values=CA\nNV");
        UUID name = definition("borrower_name", "Borrower name", "TEXT", false, false, true, "");
        UUID old = definition("old_ref", "Old reference", "TEXT", false, false, false, "");
        UUID first = record(contract, "000123", branch, "North", state, "CA", name, "Synthetic Quill", old, "was here");
        UUID second = record(contract, "000124", state, "XX"); // no Branch, and a value the list no longer holds
        UUID unused = record(contract, "000125");
        UUID a = aCase(first);
        UUID b = aCase(first);
        UUID c = aCase(second);
        UUID none = aCase(null);
        jdbc.update("insert into reporting_dimension_definitions (tenant_id, definition_id, dimension_key, dimension_label,"
                + " enabled_flag, display_order, updated_at) values (?, ?, 'state', 'State', true, 1, now())", TENANT, state);

        // Two records whose main IDs differ only by capitals become one; both cases point to it.
        UUID otherEntity = UUID.randomUUID();
        jdbc.update("insert into tenants (id, name, slug, status, created_at, updated_at) values (?, 'Two', 'two', 'ACTIVE', now(), now())", otherEntity);
        entity = otherEntity;
        UUID matter = definition("matter", "Matter", "TEXT", true, true, true, "");
        UUID lower = aCase(record(matter, "ab-1"));
        UUID upper = aCase(record(matter, "AB-1"));
        entity = TENANT;

        // Anything but the schema step that runs the changelog (an app start) is refused and drops nothing.
        try (Connection c2 = db.connect()) {
            Liquibase liquibase = new Liquibase("db/changelog/db.changelog-master.xml", new ClassLoaderResourceAccessor(),
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(c2)));
            org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                    () -> liquibase.update(new Contexts(), new LabelExpression()));
        }
        assertEquals("5", one("select count(*) from business_records"));

        // Without a stored customer nothing is moved and nothing dropped.
        ByteArrayOutputStream refusal = new ByteArrayOutputStream();
        assertEquals(2, schemaStep(refusal));
        assertTrue(refusal.toString(StandardCharsets.UTF_8).contains("CASE_FIELDS_NO_CUSTOMER"), refusal.toString(StandardCharsets.UTF_8));
        assertEquals("5", one("select count(*) from business_records"));
        for (var run : SchemaStep.PLATFORM_RUNS) SchemaStep.flyway(new InstallDb(db.url, db.user, db.password), run).migrate();
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(0, new SetupCommand(db.env("PLATFORM_SETUP"), Clock.systemUTC(), TestBuild.development()).run(List.of("apply",
                "--licence", DEMO.resolve("licence-demo.json").toString(), "--signin", DEMO.resolve("signin-demo.json").toString(),
                "--product", "LEXAVAULT", "--product-roles", DEMO.resolve("product-roles-development.json").toString(),
                "--config-out", work.resolve("platform-tenant.properties").toString(),
                "--run-log", work.resolve("setup.log").toString(), "--no-customer-keys"), quiet, quiet));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertEquals(0, schemaStep(out), out.toString(StandardCharsets.UTF_8));
        String printed = out.toString(StandardCharsets.UTF_8);
        assertTrue(printed.contains("moved 6 field(s), the values of 4 business record(s); 1 value(s) no longer allowed were left out"), printed);
        assertFalse(printed.contains("Quill") || printed.contains("000123"), printed);

        assertEquals("t", one("select to_regclass('business_records') is null and to_regclass('business_record_identifiers') is null"
                + " and to_regclass('business_identifier_definitions') is null"));
        var ds = new DriverManagerDataSource(db.url, db.user, db.password);
        FieldDefinitions definitions = new FieldDefinitions(new PostgresFieldStore(ds), new PostgresValueStore(ds));
        var scope = CaseFieldService.scope(TenantId.of(TENANT));
        FieldSpec number = definitions.find(scope, FieldKey.of("contract_number")).orElseThrow().spec();
        assertEquals(List.of(FieldType.NUMBER, "[0-9]+", 6, true, true), List.of(number.type(), number.pattern(), number.exactLength(),
                number.primary(), number.required()));
        assertEquals("[A-Za-z0-9]+", definitions.find(scope, FieldKey.of("branch")).orElseThrow().spec().pattern());
        assertTrue(definitions.find(scope, FieldKey.of("branch")).orElseThrow().spec().required());
        var list = definitions.find(scope, FieldKey.of("state")).orElseThrow();
        assertEquals(List.of("CA", "NV"), list.spec().options());
        assertEquals(list.id().toString(), one("select definition_id from reporting_dimension_definitions"));
        assertFalse(definitions.find(scope, FieldKey.of("borrower_name")).orElseThrow().spec().sensitive(), "kept as it was");
        assertFalse(definitions.find(scope, FieldKey.of("old_ref")).orElseThrow().active());
        assertEquals("true", definitions.extensions(scope, FieldKey.of("branch")).get("extractable"));

        for (UUID id : List.of(a, b)) assertEquals(first.toString(), one("select business_record_id from document_packages where id = '" + id + "'"));
        assertEquals(second.toString(), one("select business_record_id from document_packages where id = '" + c + "'"));
        assertEquals("null", one("select business_record_id from document_packages where id = '" + none + "'"));
        assertEquals("5", one("select count(*) from fld_value v join fld_record r using (record_uuid) where r.record_id = '" + first + "'"));
        assertEquals("1", one("select count(*) from fld_value v join fld_record r using (record_uuid) where r.record_id = '" + second + "'"));
        assertEquals("0", one("select count(*) from fld_record where record_id = '" + unused + "'"));

        assertEquals(one("select business_record_id from document_packages where id = '" + lower + "'"),
                one("select business_record_id from document_packages where id = '" + upper + "'"));
        assertEquals("1", one("select count(*) from fld_record where entity_uuid = '" + otherEntity + "'"));

        // Again, and the rollback makes the old tables again, empty, without losing the cases' links.
        assertEquals(0, schemaStep(new ByteArrayOutputStream()));
        assertEquals("6", one("select count(*) from fld_field_definition"));
    }

    private static int schemaStep(ByteArrayOutputStream out) {
        PrintStream print = new PrintStream(out, true, StandardCharsets.UTF_8);
        return new SchemaStep(print, print).run(new String[] {"apply"}, db.env(SchemaStep.ENV_PREFIX));
    }

    private static String one(String sql) {
        Object value = jdbc.queryForObject(sql, Object.class);
        return value instanceof Boolean flag ? (flag ? "t" : "f") : String.valueOf(value);
    }
}

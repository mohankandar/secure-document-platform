package com.securedoc.install;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.eraselog.spring.EraseReplayRunner;
import com.documentplatform.monitoring.DependencyStatus;
import com.documentplatform.setup.SetupCommand;
import com.securedoc.platform.api.SecureDocumentPlatformApplication;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.lifecycle.command.PurgeDueRetentionCommand;
import com.securedoc.platform.application.lifecycle.service.UnrecordedFileSweepService;
import com.securedoc.platform.application.port.in.PurgeDueRetentionUseCase;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ErasurePort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.port.out.WorkGate;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.vo.MimeType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0060: the restore-and-replay drill, with the real app on a real PostgreSQL and a real storage folder. Database and
 * storage are backed up, every kind of file is erased, both are restored: the app takes no work; the replay erases again;
 * nothing erased can be read; a second replay is harmless; work goes on and what was never erased is still there.
 */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
class EraseReplayDrillPostgresTest {
    private static final TenantId ENTITY = TenantId.of(UUID.fromString("30216241-b724-5a3a-aef3-3034fd546d17"));
    private static final ActorId SYSTEM = ActorId.of(new UUID(0, 0));
    private static final ActorId PERSON = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000099"));
    private static final String MARKER = "SYNTHETIC-PLAINTEXT-MARKER-0060";
    private static final Path DEMO = DemoStepTest.REPO.resolve("install/demo");
    private static final String LOGGING_SYSTEM = "org.springframework.boot.logging.LoggingSystem";
    private static final InMemoryKeyService KEYS = new InMemoryKeyService();

    private static ThrowawayDatabase db;
    private static String loggingSystemBefore;
    @TempDir
    static Path work;

    private final Map<String, Artifact> files = new LinkedHashMap<>();
    private Path unrecorded;

    static class SharedKeys {
        @Bean
        KeyService testKeyService() {
            return KEYS;
        }
    }

    @BeforeAll
    static void install() throws Exception {
        loggingSystemBefore = System.getProperty(LOGGING_SYSTEM);
        System.setProperty(LOGGING_SYSTEM, "none");
        KEYS.createKeys(CustomerId.fromTenantId("TEN-DEMO-0001"));
        db = ThrowawayDatabase.create("drill");
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(0, new SchemaStep(quiet, quiet).run(new String[]{"apply"}, db.env(SchemaStep.ENV_PREFIX)));
        assertEquals(0, new SetupCommand(db.env("PLATFORM_SETUP"), Clock.systemUTC(), TestBuild.development()).run(List.of("apply",
                "--licence", DEMO.resolve("licence-demo.json").toString(), "--signin", DEMO.resolve("signin-demo.json").toString(),
                "--product", "LEXAVAULT", "--product-roles", DEMO.resolve("product-roles-development.json").toString(),
                "--config-out", work.resolve("platform-tenant.properties").toString(),
                "--run-log", work.resolve("setup.log").toString(), "--no-customer-keys"), quiet, quiet));
        assertEquals(0, new DemoStep(quiet, quiet, Clock.systemUTC(), TestBuild::development).run(new String[]{"apply"},
                db.env(DemoStep.ENV_PREFIX)));
        Files.createDirectories(storage());
    }

    @AfterAll
    static void drop() throws SQLException {
        if (loggingSystemBefore == null) System.clearProperty(LOGGING_SYSTEM);
        else System.setProperty(LOGGING_SYSTEM, loggingSystemBefore);
        if (db != null) {
            db.onServer("drop database if exists " + db.name + "_backup with (force)");
            db.close();
        }
    }

    private static Path storage() {
        return work.resolve("storage");
    }

    private static ConfigurableApplicationContext start(String... more) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + db.url, "--spring.datasource.username=" + db.user,
                "--spring.datasource.password=" + db.password, "--server.port=0", "--securedoc.ai.provider=heuristic",
                "--securedoc.watched-folder.enabled=false", "--securedoc.watched-folder.scheduling-enabled=false",
                "--securedoc.lifecycle.purge-scheduler-enabled=false",
                "--securedoc.storage.local.root-path=" + storage(),
                // Names a key service, so files are encrypted; the KeyService bean of this test takes its place.
                "--platform.crypto.vault.address=https://key-service.example",
                "--platform.erase-log.recheck=5s",
                "--platform.erase-log.location=" + work.resolve("erase-log")));
        args.addAll(List.of(more));
        return new SpringApplicationBuilder(SecureDocumentPlatformApplication.class, SharedKeys.class,
                FreshDatabaseStartPostgresTest.DevelopmentBuild.class).profiles("local").run(args.toArray(String[]::new));
    }

    private String ownRecord;
    private String sharedRecord;

    private static String record(ConfigurableApplicationContext app, String contract, PackageId... cases) throws SQLException {
        String id = app.getBean(com.securedoc.platform.application.casefields.CaseFieldService.class).resolveOrCreate(ENTITY,
                Map.of("contract_number", contract, "member_number", "12345678901", "service_entity", "001")).businessRecordId().toString();
        for (PackageId pkg : cases) {
            change("update document_packages set business_record_id = '" + id + "' where id = '" + pkg + "'");
        }
        return id;
    }

    private ArtifactStorageReference instructions;

    private void assertInstructions(boolean there) throws SQLException {
        assertEquals(there ? "1" : "0", one("select count(*) from case_instructions"));
        assertEquals(there ? "1" : "0", one("select count(*) from stored_files where storage_key = '" + instructions.key() + "'"));
        assertEquals(there, Files.exists(storage().resolve(instructions.key())));
    }

    private static void assertValues(String record, boolean there) throws SQLException {
        assertEquals(there ? "3" : "0", one("select count(*) from fld_value v join fld_record r on r.record_uuid = v.record_uuid"
                + " where r.record_id = '" + record + "'"));
        assertEquals(there ? "1" : "0", one("select count(*) from fld_record where record_id = '" + record + "'"));
    }

    @Test
    void theDrill() throws Exception {
        PackageId redactedDue = PackageId.generate();
        PackageId finalDue = PackageId.generate();
        PackageId rejected = PackageId.generate();
        PackageId others = PackageId.generate();
        PackageId live = PackageId.generate();
        try (ConfigurableApplicationContext app = start()) {
            assertTrue(app.getBean(WorkGate.class).mayTakeWork(), app.getBean(com.documentplatform.eraselog.EraseGuard.class).state().toString());
            for (PackageId pkg : List.of(redactedDue, finalDue, rejected, others, live)) {
                DocumentId document = newCase(pkg, pkg.equals(rejected) ? "REJECTED" : "COMPLETED");
                for (ArtifactType type : List.of(ArtifactType.ORIGINAL, ArtifactType.EXTRACTED_TEXT, ArtifactType.REDACTED)) {
                    add(app, pkg, document, type);
                }
                if (pkg.equals(others)) add(app, pkg, document, ArtifactType.DERIVED_REDACTED);
                if (pkg.equals(others)) {
                    unrecorded = storage().resolve(ENTITY + "/" + pkg + "/" + document + "/REDACTED/" + UUID.randomUUID());
                    Files.writeString(unrecorded, "synthetic, no record");
                    Files.setLastModifiedTime(unrecorded, FileTime.from(Instant.now().minus(Duration.ofDays(2))));
                }
            }
            // Task 0065: the case's instructions are a file of the case.
            byte[] instructed = (MARKER + " instructions").getBytes(StandardCharsets.UTF_8);
            instructions = app.getBean(StoragePort.class).storeForPackage(ENTITY, finalDue, ENTITY + "/" + finalDue
                    + "/CASE_INSTRUCTIONS/" + UUID.randomUUID(), new ByteArrayInputStream(instructed), instructed.length, "text/plain");
            app.getBean(com.securedoc.platform.application.port.out.CaseInstructionsRepository.class).save(ENTITY, finalDue,
                    new com.securedoc.platform.application.port.out.CaseInstructionsRepository.Stored(UUID.randomUUID(), instructions));
            // Nothing of the documents can be found on the disk.
            try (Stream<Path> all = Files.walk(storage())) {
                for (Path file : all.filter(Files::isRegularFile).toList()) {
                    assertFalse(Files.readString(file, StandardCharsets.ISO_8859_1).contains(MARKER), file.toString());
                }
            }
            // Task 0061: one business record only the case due for purging points to, one it shares with a live case.
            app.getBean(com.securedoc.platform.application.casefields.CaseFieldSettings.class);
            ownRecord = record(app, "100000000001", finalDue);
            sharedRecord = record(app, "100000000002", rejected, live);
            lifecycle(redactedDue, 1, "null", "now() - interval '1 hour'", "null");
            lifecycle(finalDue, 1, "now() - interval '1 hour'", "now() + interval '9 hours'", "null");
            lifecycle(rejected, 0, "null", "null", "now() - interval '1 hour'");
            lifecycle(live, 1, "now() + interval '9 hours'", "now() + interval '9 hours'", "null");
        }

        // ------------------------------------------------------------------------------- the backup
        db.onServer("create database " + db.name + "_backup template " + db.name);
        copy(storage(), work.resolve("storage-backup"));

        // ------------------------------------------------------------------------------ the erasures
        List<String> erased = List.of(redactedDue + "REDACTED", finalDue + "ORIGINAL", finalDue + "EXTRACTED_TEXT",
                finalDue + "REDACTED", rejected + "ORIGINAL", rejected + "EXTRACTED_TEXT", rejected + "REDACTED",
                others + "ORIGINAL", others + "EXTRACTED_TEXT", others + "DERIVED_REDACTED");
        List<String> kept = List.of(redactedDue + "ORIGINAL", live + "ORIGINAL", live + "REDACTED", others + "REDACTED");
        try (ConfigurableApplicationContext app = start()) {
            for (String file : erased) assertReadable(app, file);
            var result = app.getBean(PurgeDueRetentionUseCase.class).execute(new PurgeDueRetentionCommand(Instant.now(), 100,
                    SYSTEM, CorrelationId.generate()));
            assertEquals(1, result.redactedCachesPurged());
            assertEquals(2, result.packagesFullyPurged());
            ErasurePort erasure = app.getBean(ErasurePort.class);
            erasure.eraseArtifact(files.get(others + "ORIGINAL"), ErasurePort.Reason.rule("intake-expired"));
            erasure.eraseArtifact(files.get(others + "EXTRACTED_TEXT"), ErasurePort.Reason.by(PERSON, "reprocess"));
            erasure.eraseArtifact(files.get(others + "DERIVED_REDACTED"), ErasurePort.Reason.rule("retention-split-copy"));
            assertEquals(1, app.getBean(UnrecordedFileSweepService.class).run(Instant.now(), SYSTEM, CorrelationId.generate()));
            assertGone(app, erased);
            for (String file : kept) assertReadable(app, file);
        }
        assertValues(ownRecord, false);
        assertValues(sharedRecord, true); // a live case still needs it
        assertInstructions(false);
        assertEquals("7", one("select count(*) from audit_records where action = 'FILES_ERASED'"));
        assertEquals("1", one("select count(*) from audit_records where action = 'CASE_FIELDS_ERASED' and resource_id = '" + ownRecord + "'"));
        assertEquals("8", one("select applied_seq from erl_position"));
        String log = Files.readString(work.resolve("erase-log/erase-log.jsonl"));
        for (String kind : List.of("case-files", "redacted-copy", "document", "working-file", "export-copy", "unrecorded-file", "case-fields")) {
            assertTrue(log.contains("\"" + kind + "\""), kind + " in " + log);
        }
        assertTrue(log.contains("\"mac\""), "keyed");
        assertFalse(log.contains(MARKER) || log.contains(".pdf"), "ids only");

        // ------------------------------------------------------------------------------ the restore
        restore();
        try (ConfigurableApplicationContext app = start()) {
            WorkGate gate = app.getBean(WorkGate.class);
            assertFalse(gate.mayTakeWork(), "blocked");
            for (String file : erased) assertReadable(app, file); // the restore brought them back
            assertValues(ownRecord, true);
            assertInstructions(true);
            int port = ((WebServerApplicationContext) app).getWebServer().getPort();
            HttpResponse<String> refused = call(port, "POST", "/api/v1/packages", newCaseBody());
            assertEquals(503, refused.statusCode(), refused.body());
            assertTrue(refused.body().contains("LV-SYS-013"), refused.body());
            assertEquals(503, call(port, "PUT", "/api/v1/admin/entities/ENT-DEMO-0001/time-zone", "{\"timeZone\":\"\"}").statusCode());
            assertEquals(200, call(port, "GET", "/api/v1/packages", null).statusCode(), "reading goes on");
            app.getBean(DependencyStatus.class).runAll();
            String status = call(port, "GET", "/api/v1/support/status", null).body();
            assertTrue(status.contains("\"name\":\"erase-log\",\"group\":\"services\",\"state\":\"DOWN\",\"code\":\"LV-SYS-013\""), status);
            assertEquals(503, call(port, "GET", "/actuator/health", null).statusCode());
            TechnicalDependencyException stopped = assertThrows(TechnicalDependencyException.class, () -> app
                    .getBean(ErasurePort.class).eraseCaseFiles(ENTITY, live, ErasurePort.Reason.rule("test")));
            assertEquals(TechnicalFailureCode.ERASE_REPLAY_REQUIRED, stopped.failureCode());
            assertReadable(app, live + "ORIGINAL");
            assertEquals("t", one("select replay_required from erl_position"));
            String heartbeat = one("select max(heartbeat_at) from processing_storage_nodes");
            for (int i = 0; i < 40 && heartbeat.equals(one("select max(heartbeat_at) from processing_storage_nodes")); i++) Thread.sleep(250);
            assertFalse(heartbeat.equals(one("select max(heartbeat_at) from processing_storage_nodes")), "the signs of life go on");
            // Nothing scheduled ran: the recovery scan, the polling and the purge take no work.
            assertEquals("0", one("select count(*) from package_processing_leases"));
        }

        // ------------------------------------------------------------------------------- the replay
        var report = replay();
        assertTrue(report.passed(), report.describe());
        assertEquals(8, report.appliedCount(), report.describe());
        assertInstructions(false);
        assertValues(ownRecord, false);
        assertValues(sharedRecord, true);
        try (ConfigurableApplicationContext app = start()) {
            assertTrue(app.getBean(WorkGate.class).mayTakeWork(), "work resumes");
            assertGone(app, erased);
            for (String file : kept) assertReadable(app, file);
            int port = ((WebServerApplicationContext) app).getWebServer().getPort();
            HttpResponse<String> made = call(port, "POST", "/api/v1/packages", newCaseBody());
            assertTrue(made.statusCode() == 200 || made.statusCode() == 201, made.body());
            assertEquals("7", one("select count(*) from audit_records where action = 'FILES_ERASED'"), "written again, under their ids");
            assertEquals("1", one("select count(*) from audit_records where action = 'CASE_FIELDS_ERASED'"));
            // The case itself is still there and has no values any more; the same number makes a new record.
            assertEquals(200, call(port, "GET", "/api/v1/packages", null).statusCode());
            assertFalse(ownRecord.equals(record(app, "100000000001")));
            // The next purge finds the cases due and nothing left to erase.
            var again = app.getBean(PurgeDueRetentionUseCase.class).execute(new PurgeDueRetentionCommand(Instant.now(), 100,
                    SYSTEM, CorrelationId.generate()));
            assertEquals(2, again.packagesFullyPurged());
        }

        // ----------------------------------------------------------------------------- again, harmless
        var second = replay();
        assertTrue(second.passed(), second.describe());
        assertEquals(0, second.appliedCount());
        try (ConfigurableApplicationContext app = start()) {
            assertTrue(app.getBean(WorkGate.class).mayTakeWork());
            assertGone(app, erased);
            for (String file : kept) assertReadable(app, file);
        }

        // ------------------------------------------- only the storage restored: no database says so
        copy(work.resolve("storage-backup"), storage());
        try (ConfigurableApplicationContext app = start()) {
            assertGoneFromTheApp(app, erased);
        }
        var storageOnly = replay();
        assertTrue(storageOnly.passed(), storageOnly.describe());
        assertInstructions(false);
        try (ConfigurableApplicationContext app = start()) {
            assertGone(app, erased);
            for (String file : kept) assertReadable(app, file);
        }
    }

    // ------------------------------------------------------------------------------------------ steps

    private static com.documentplatform.eraselog.EraseReplay.Report replay() {
        try (ConfigurableApplicationContext app = start("--erase-replay", "--platform.erase-log.exit-after-replay=false")) {
            assertFalse(app.getBean(WorkGate.class).mayTakeWork(), "the replay's own process takes no work, also after it");
            return app.getBean(EraseReplayRunner.class).lastReport();
        }
    }

    private void restore() throws Exception {
        db.onServer("drop database " + db.name + " with (force)");
        db.onServer("create database " + db.name + " template " + db.name + "_backup");
        delete(storage());
        copy(work.resolve("storage-backup"), storage());
    }

    private void add(ConfigurableApplicationContext app, PackageId pkg, DocumentId document, ArtifactType type) {
        byte[] content = (MARKER + " " + pkg + " " + type).getBytes(StandardCharsets.UTF_8);
        Artifact artifact = Artifact.create(ENTITY, document, type, MimeType.pdf(), content.length,
                app.getBean(com.securedoc.platform.application.port.out.ChecksumPort.class).compute(new ByteArrayInputStream(content)));
        String path = type == ArtifactType.DERIVED_REDACTED ? "derived/" + pkg + "/cycle-1/" + document + "/" + artifact.id() + ".pdf"
                : ENTITY + "/" + pkg + "/" + document + "/" + type + "/" + artifact.id();
        ArtifactStorageReference stored = app.getBean(StoragePort.class).storeForPackage(ENTITY, pkg, path,
                new ByteArrayInputStream(content), content.length, "application/pdf");
        artifact.markAvailable(stored);
        app.getBean(ArtifactRepository.class).save(artifact);
        files.put(pkg + type.name(), artifact);
    }

    private void assertReadable(ConfigurableApplicationContext app, String file) throws Exception {
        Artifact artifact = files.get(file);
        assertEquals("AVAILABLE", one("select status from artifacts where id = '" + artifact.id() + "'"), file);
        try (InputStream in = app.getBean(StoragePort.class).retrieve(artifact.storageReference())) {
            String pkg = file.substring(0, 36);
            assertArrayEquals((MARKER + " " + pkg + " " + file.substring(36)).getBytes(StandardCharsets.UTF_8), in.readAllBytes(), file);
        }
    }

    private void assertGone(ConfigurableApplicationContext app, List<String> erased) throws Exception {
        assertGoneFromTheApp(app, erased);
        for (String file : erased) {
            assertFalse(Files.exists(storage().resolve(files.get(file).storageReference().key())), file);
        }
        assertFalse(Files.exists(unrecorded), "the file without a record");
    }

    private void assertGoneFromTheApp(ConfigurableApplicationContext app, List<String> erased) throws Exception {
        for (String file : erased) {
            Artifact artifact = files.get(file);
            assertEquals("DELETED", one("select status from artifacts where id = '" + artifact.id() + "'"), file);
            assertEquals("0", one("select count(*) from stored_files where storage_key = '" + artifact.storageReference().key() + "'"),
                    "the wrapped key is gone: " + file);
            assertThrows(TechnicalDependencyException.class, () -> app.getBean(StoragePort.class)
                    .retrieve(artifact.storageReference()), file);
        }
    }

    private static String newCaseBody() {
        return "{\"packageType\":\"MANUAL_UPLOAD\",\"businessIdentifiers\":{\"contract_number\":\"123456789012\",\"member_number\":\"12345678901\",\"service_entity\":\"001\"},\"metadata\":{}}";
    }

    private static HttpResponse<String> call(int port, String method, String path, String body) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-Tenant-Id", ENTITY.toString()).header("X-Actor-Id", PERSON.toString())
                .header("X-Actor-Roles", path.contains("support") ? "TENANT_IT_ADMIN" : "BUSINESS_ADMIN,DOCUMENT_PROCESSOR")
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    // ------------------------------------------------------------------------------------------- rows

    private static DocumentId newCase(PackageId pkg, String status) throws SQLException {
        DocumentId document = DocumentId.generate();
        change("insert into document_packages (id, tenant_id, package_type, status, created_by, created_at, updated_at)"
                + " values ('" + pkg + "', '" + ENTITY + "', 'MANUAL_UPLOAD', '" + status + "', '" + PERSON + "', now(), now())");
        change("insert into documents (id, tenant_id, package_id, file_name, mime_type, size_bytes, checksum_algorithm,"
                + " checksum_value, document_type, status, created_at, updated_at) values ('" + document + "', '" + ENTITY
                + "', '" + pkg + "', 'a.pdf', 'application/pdf', 1, 'SHA-256', 'x', 'PDF', 'PROCESSED', now(), now())");
        return document;
    }

    private static void lifecycle(PackageId pkg, int cycles, String originalDue, String redactedDue, String hold) throws SQLException {
        change("insert into package_lifecycles (package_id, tenant_id, successful_export_cycles, original_delete_at,"
                + " redacted_delete_at, technical_hold_until, final_approval_granted, updated_at, version) values ('" + pkg
                + "', '" + ENTITY + "', " + cycles + ", " + originalDue + ", " + redactedDue + ", " + hold + ", false, now(), 0)");
    }

    private static void change(String sql) throws SQLException {
        try (Connection c = db.connect(); Statement s = c.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private static String one(String sql) throws SQLException {
        try (Connection c = db.connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            assertTrue(rs.next(), sql);
            return rs.getString(1);
        }
    }

    private static void copy(Path from, Path to) throws Exception {
        try (Stream<Path> all = Files.walk(from)) {
            for (Path source : all.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
    }

    private static void delete(Path folder) throws Exception {
        try (Stream<Path> all = Files.walk(folder)) {
            for (Path path : all.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}

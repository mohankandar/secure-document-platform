package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.lifecycle.service.UnrecordedFileSweepService;
import com.securedoc.platform.application.port.out.ProcessingStorageConfigurationRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.storage.ProcessingStorageProvider;
import com.securedoc.platform.domain.storage.ProcessingStorageProviderType;
import com.securedoc.platform.infrastructure.storage.UnrecordedFileSweepAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0049, step 5: only text and redacted files that no record points to, and that are old enough, are removed. */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class UnrecordedFileSweepPostgresTest {
    private static final ActorId SYSTEM = ActorId.of(new UUID(0, 0));
    @TempDir Path root;
    private com.documentplatform.eraselog.testkit.InMemoryEraseLogStore logStore;

    @Test
    void removesOnlyWhatNoRecordPointsTo() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("m050sweep")) {
            NamedParameterJdbcTemplate jdbc = db.jdbc();
            TenantId tenant = TenantId.generate();
            PackageId pkg = PackageId.generate();
            UUID document = UUID.randomUUID();
            TestRows.tenant(jdbc, tenant);
            TestRows.packageRow(jdbc, tenant, pkg);
            jdbc.update("""
                    INSERT INTO documents (id, tenant_id, package_id, file_name, mime_type, size_bytes,
                        checksum_algorithm, checksum_value, document_type, status, created_at, updated_at)
                    VALUES (:d, :t, :p, 'a.pdf', 'application/pdf', 1, 'SHA-256', 'x', 'PDF', 'UPLOADED', now(), now())""",
                    new MapSqlParameterSource("d", document).addValue("t", tenant.value()).addValue("p", pkg.value()));
            String folder = tenant + "/" + pkg + "/" + document + "/";

            List<Path> removed = new ArrayList<>();
            for (String kind : List.of("EXTRACTED_TEXT", "EXTRACTED_TEXT_BATCH", "FINDINGS_BATCH", "REDACTED")) {
                removed.add(file(folder + kind + "/" + UUID.randomUUID(), 25));
            }
            removed.add(file("derived/" + pkg + "/cycle-1/" + document + "/" + UUID.randomUUID() + ".pdf", 25));
            UUID deleted = UUID.randomUUID();
            // Its record says deleted: the server stopped before deleting the file.
            removed.add(file(folder + "EXTRACTED_TEXT_BATCH/" + deleted, 25));
            record(jdbc, tenant, document, deleted, "DELETED", folder + "EXTRACTED_TEXT_BATCH/" + deleted);

            List<Path> kept = new ArrayList<>();
            kept.add(file(folder + "REDACTED/" + UUID.randomUUID(), 20)); // younger than the delay
            for (String status : List.of("AVAILABLE", "GENERATING", "FAILED")) {
                UUID id = UUID.randomUUID();
                kept.add(file(folder + "EXTRACTED_TEXT/" + id, 1000));
                record(jdbc, tenant, document, id, status, folder + "EXTRACTED_TEXT/" + id);
            }
            UUID keyOnly = UUID.randomUUID();
            kept.add(file(folder + "REDACTED/" + keyOnly, 1000));
            record(jdbc, tenant, document, UUID.randomUUID(), "AVAILABLE", folder + "REDACTED/" + keyOnly);
            kept.add(file(folder + "ORIGINAL/" + UUID.randomUUID(), 1000)); // originals are never looked at
            kept.add(file(folder + "REDACTED/notes.txt", 1000)); // not LexaVault's name
            // A document that is not in this database (another installation's), another entity, another case.
            kept.add(file(tenant + "/" + pkg + "/" + UUID.randomUUID() + "/REDACTED/" + UUID.randomUUID(), 1000));
            kept.add(file(TenantId.generate() + "/" + pkg + "/" + document + "/REDACTED/" + UUID.randomUUID(), 1000));
            kept.add(file("derived/" + UUID.randomUUID() + "/cycle-1/" + document + "/" + UUID.randomUUID() + ".pdf", 1000));
            kept.add(file("other/place/" + UUID.randomUUID(), 1000));

            List<AuditRecord> audits = new ArrayList<>();
            UnrecordedFileSweepService service = service(jdbc, audits);
            Instant now = Instant.now();
            assertEquals(removed.size(), service.run(now, SYSTEM, CorrelationId.generate()));
            removed.forEach(p -> assertFalse(Files.exists(p), p.toString()));
            kept.forEach(p -> assertTrue(Files.exists(p), p.toString()));
            assertEquals(1, audits.size());
            assertEquals(AuditAction.UNRECORDED_FILES_CLEANED, audits.get(0).action());
            assertEquals("Files without a record removed: text=4, redacted=2", audits.get(0).detail());
            assertEquals(6, logStore.size(), "each in the erase log");
            assertEquals(6, jdbc.getJdbcTemplate().queryForObject("select count(*) from audit_records where action ="
                    + " 'FILES_ERASED'", Integer.class));
            logStore.down(true);
            Path kept2 = file(folder + "REDACTED/" + UUID.randomUUID(), 1000);
            assertEquals(0, service.run(now.plus(Duration.ofHours(1)), SYSTEM, CorrelationId.generate()));
            assertTrue(Files.exists(kept2), "the erase log is down: nothing is erased");
            logStore.down(false);
            assertEquals(1, service.run(now.plus(Duration.ofHours(2)), SYSTEM, CorrelationId.generate()), "done later");

            Path later = file(folder + "REDACTED/" + UUID.randomUUID(), 1000);
            assertEquals(0, service.run(now.plusSeconds(900), SYSTEM, CorrelationId.generate()), "at most once an hour");
            assertEquals(1, service.run(now.plus(Duration.ofHours(3)), SYSTEM, CorrelationId.generate()));
            assertFalse(Files.exists(later));
        }
    }

    @Test
    void anImpossibleDelayIsRefused() {
        for (int hours : new int[]{0, 8761}) {
            assertThrows(IllegalArgumentException.class, () -> new UnrecordedFileSweepService(
                    (olderThan, limit) -> Map.of(), new AuditService(a -> { }), hours));
        }
    }

    private UnrecordedFileSweepService service(NamedParameterJdbcTemplate jdbc, List<AuditRecord> audits) {
        ProcessingStorageProvider provider = ProcessingStorageProvider.create("test",
                ProcessingStorageProviderType.LOCAL_FILESYSTEM, root.toString(), UUID.randomUUID(), Instant.now());
        ProcessingStorageConfigurationRepository configuration = (ProcessingStorageConfigurationRepository)
                Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{ProcessingStorageConfigurationRepository.class}, (p, method, args) -> {
                            if (method.getName().equals("findAllProviders")) return List.of(provider);
                            throw new UnsupportedOperationException(method.getName());
                        });
        // Task 0060: through the erase log, with the real handlers.
        var legacy = new com.securedoc.platform.infrastructure.storage.LocalStorageProperties();
        legacy.setRootPath(root.toString());
        var files = new com.securedoc.platform.infrastructure.erasure.StoredFileErasure(
                jdbc.getJdbcTemplate().getDataSource(), configuration, legacy);
        logStore = new com.documentplatform.eraselog.testkit.InMemoryEraseLogStore();
        var eraser = new com.documentplatform.eraselog.Eraser(
                new com.documentplatform.eraselog.EraseLog(logStore, java.time.Clock.systemUTC()),
                new com.documentplatform.eraselog.testkit.InMemoryErasePosition(),
                com.documentplatform.eraselog.EraseHandlers.of(
                        new com.securedoc.platform.infrastructure.erasure.LexaVaultEraseHandlers().unrecordedFileEraseHandler(files)));
        return new UnrecordedFileSweepService(new UnrecordedFileSweepAdapter(configuration, jdbc,
                new com.securedoc.platform.infrastructure.erasure.LexaVaultErasures(eraser, jdbc.getJdbcTemplate())),
                new AuditService(audits::add), 24);
    }

    private Path file(String key, int hoursOld) throws Exception {
        Path path = root.resolve(key);
        Files.createDirectories(path.getParent());
        Files.writeString(path, "synthetic");
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(Duration.ofHours(hoursOld))));
        return path;
    }

    private static void record(NamedParameterJdbcTemplate jdbc, TenantId tenant, UUID document, UUID id, String status,
                               String key) {
        jdbc.update("""
                INSERT INTO artifacts (id, tenant_id, document_id, artifact_type, status, storage_provider,
                    storage_bucket, storage_key, mime_type, size_bytes, checksum_algorithm, checksum_value, created_at,
                    updated_at)
                VALUES (:id, :t, :d, 'EXTRACTED_TEXT', :s, 'p', 'b', :k, 'text/plain', 1, 'SHA-256', 'x', now(), now())""",
                new MapSqlParameterSource("id", id).addValue("t", tenant.value()).addValue("d", document)
                        .addValue("s", status).addValue("k", key));
    }
}

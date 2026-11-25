package com.securedoc.platform.infrastructure.pipeline;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.envelope.StreamCipher;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.storage.ProcessingStorageProviderType;
import com.securedoc.platform.infrastructure.storage.FileEncryption;
import com.securedoc.platform.infrastructure.storage.JdbcProcessingStorageConfigurationRepository;
import com.securedoc.platform.infrastructure.storage.LocalFileStorageAdapter;
import com.securedoc.platform.infrastructure.storage.LocalStorageProperties;
import com.securedoc.platform.infrastructure.storage.StoredFileRecords;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0060: the record in the database, and nothing in the file, decides how a stored file is opened. */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class StoredFilesPostgresTest {
    private static final CustomerId CUSTOMER = CustomerId.fromTenantId("TEN-TEST-0001");
    private static final byte[] CONTENT = "SYNTHETIC-PLAINTEXT-MARKER-0060".getBytes(StandardCharsets.UTF_8);

    @TempDir Path root;

    private final InMemoryKeyService keys = new InMemoryKeyService();

    private LocalFileStorageAdapter adapter(JdbcTemplate jdbc, boolean plain) {
        keys.createKeys(CUSTOMER);
        LocalStorageProperties legacy = new LocalStorageProperties();
        legacy.setRootPath(root.toString());
        return new LocalFileStorageAdapter(legacy, new JdbcProcessingStorageConfigurationRepository(jdbc),
                plain ? FileEncryption.developmentPlain() : FileEncryption.with(new StreamCipher(keys), CUSTOMER),
                new StoredFileRecords(jdbc));
    }

    private void provider(JdbcTemplate jdbc) {
        UUID provider = UUID.randomUUID();
        new JdbcProcessingStorageConfigurationRepository(jdbc).saveProvider(
                com.securedoc.platform.domain.storage.ProcessingStorageProvider.create("test",
                        ProcessingStorageProviderType.SHARED_FILESYSTEM, root.toString(), provider, Instant.now())
        );
        UUID id = jdbc.queryForObject("select id from processing_storage_providers", UUID.class);
        jdbc.update("update processing_storage_providers set status = 'AVAILABLE'");
        new JdbcProcessingStorageConfigurationRepository(jdbc).activateDeploymentDefault(id, provider, Instant.now());
    }

    private static TechnicalFailureCode refused(org.junit.jupiter.api.function.Executable call) {
        return assertThrows(TechnicalDependencyException.class, call).failureCode();
    }

    private static byte[] all(InputStream stream) throws Exception {
        try (stream) {
            return stream.readAllBytes();
        }
    }

    @Test
    void whatIsStoredIsEncryptedAndOnlyItsRecordOpensIt() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("storedfiles")) {
            JdbcTemplate jdbc = db.jdbc().getJdbcTemplate();
            TenantId tenant = TenantId.generate();
            TestRows.tenant(db.jdbc(), tenant);
            provider(jdbc);
            LocalFileStorageAdapter adapter = adapter(jdbc, false);

            String key = tenant + "/p/d/ORIGINAL/" + UUID.randomUUID();
            ArtifactStorageReference stored = adapter.store(tenant, key, new ByteArrayInputStream(CONTENT), CONTENT.length,
                    "application/pdf");
            Path file = root.resolve(key);
            assertFalse(new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1).contains("SYNTHETIC"));
            assertArrayEquals(CONTENT, all(adapter.retrieve(stored)));
            assertEquals("ENCRYPTED", jdbc.queryForObject("select protection from stored_files", String.class));
            assertEquals(CUSTOMER.value(), jdbc.queryForObject("select customer_id from stored_files", UUID.class));

            byte[] encrypted = Files.readAllBytes(file);
            Files.write(file, CONTENT);
            assertEquals(TechnicalFailureCode.STORED_FILE_REFUSED, refused(() -> adapter.retrieve(stored)),
                    "a plain file put in its place is not opened");
            Files.write(file, encrypted);

            // The development-only mode has no keys: the file stays closed.
            assertEquals(TechnicalFailureCode.KEY_SERVICE_REFUSED, refused(() -> adapter(jdbc, true).retrieve(stored)));

            keys.down(true);
            assertEquals(TechnicalFailureCode.KEY_SERVICE_UNAVAILABLE, refused(() -> adapter.retrieve(stored)));
            String second = tenant + "/p/d/ORIGINAL/" + UUID.randomUUID();
            assertEquals(TechnicalFailureCode.KEY_SERVICE_UNAVAILABLE, refused(() -> adapter.store(tenant, second,
                    new ByteArrayInputStream(CONTENT), CONTENT.length, "application/pdf")));
            assertFalse(Files.exists(root.resolve(second)));
            keys.down(false);

            jdbc.update("delete from stored_files");
            assertEquals(TechnicalFailureCode.STORED_FILE_REFUSED, refused(() -> adapter.retrieve(stored)), "no record");
            Files.write(file, CONTENT);
            assertEquals(TechnicalFailureCode.STORED_FILE_REFUSED, refused(() -> adapter.retrieve(stored)),
                    "no record: a plain file is not opened either");
        }
    }

    @Test
    void aRecordGoesWithItsFileAndWithItsTransaction() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("storedfilestx")) {
            JdbcTemplate jdbc = db.jdbc().getJdbcTemplate();
            TenantId tenant = TenantId.generate();
            TestRows.tenant(db.jdbc(), tenant);
            provider(jdbc);
            LocalFileStorageAdapter adapter = adapter(jdbc, false);
            ArtifactStorageReference stored = adapter.store(tenant, tenant + "/p/d/ORIGINAL/a",
                    new ByteArrayInputStream(CONTENT), CONTENT.length, "application/pdf");
            adapter.delete(stored);
            assertFalse(Files.exists(root.resolve(tenant + "/p/d/ORIGINAL/a")));
            assertEquals(0, jdbc.queryForObject("select count(*) from stored_files", Integer.class));

            new TransactionTemplate(new DataSourceTransactionManager(jdbc.getDataSource())).executeWithoutResult(status -> {
                adapter.store(tenant, tenant + "/p/d/ORIGINAL/b", new ByteArrayInputStream(CONTENT), CONTENT.length, "x");
                assertEquals(1, jdbc.queryForObject("select count(*) from stored_files", Integer.class));
                status.setRollbackOnly();
            });
            assertEquals(0, jdbc.queryForObject("select count(*) from stored_files", Integer.class));
            assertThrows(Exception.class, () -> jdbc.update("insert into stored_files (storage_provider, storage_key,"
                    + " tenant_id, protection, wrapped_key, created_at) values ('p', 'k', ?, 'PLAIN', 'w', now())", tenant.value()),
                    "a record is either plain or complete");
        }
    }

    @Test
    void theFilesOfBeforeGetTheirRecordOnceAndStayReadable() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("storedfilesold")) {
            NamedParameterJdbcTemplate named = db.jdbc();
            JdbcTemplate jdbc = named.getJdbcTemplate();
            // Back to before 065, with files stored plain.
            try (Connection c = db.connection()) {
                Integer later = jdbc.queryForObject("select count(*) from databasechangelog where orderexecuted >="
                        + " (select orderexecuted from databasechangelog where id = '065-01-stored-files')", Integer.class);
                PostgresTestDatabase.rollback(PostgresTestDatabase.liquibase(c), c, later);
            }
            assertEquals(0, jdbc.queryForObject("select count(*) from information_schema.tables where table_name ="
                    + " 'stored_files'", Integer.class), "the rollback removes it");
            TenantId tenant = TenantId.generate();
            PackageId pkg = PackageId.generate();
            UUID document = UUID.randomUUID();
            TestRows.tenant(named, tenant);
            TestRows.packageRow(named, tenant, pkg);
            jdbc.update("""
                    INSERT INTO documents (id, tenant_id, package_id, file_name, mime_type, size_bytes,
                        checksum_algorithm, checksum_value, document_type, status, created_at, updated_at)
                    VALUES (?, ?, ?, 'a.pdf', 'application/pdf', 1, 'SHA-256', 'x', 'PDF', 'UPLOADED', now(), now())""",
                    document, tenant.value(), pkg.value());
            provider(jdbc);
            String provider = jdbc.queryForObject("select id::text from processing_storage_providers", String.class);
            artifact(named, tenant, document, "AVAILABLE", provider, "SHARED_FILESYSTEM", "old/plain");
            artifact(named, tenant, document, "DELETED", provider, "SHARED_FILESYSTEM", "old/deleted");
            artifact(named, tenant, document, "AVAILABLE", "local", root.toString(), "old/legacy");
            Files.createDirectories(root.resolve("old"));
            Files.write(root.resolve("old/plain"), CONTENT);
            Files.write(root.resolve("old/legacy"), CONTENT);

            try (Connection c = db.connection()) {
                PostgresTestDatabase.liquibase(c).update("");
            }
            assertEquals(2, jdbc.queryForObject("select count(*) from stored_files where protection = 'PLAIN'", Integer.class));
            LocalFileStorageAdapter adapter = adapter(jdbc, false);
            assertArrayEquals(CONTENT, all(adapter.retrieve(ArtifactStorageReference.of(provider, "SHARED_FILESYSTEM", "old/plain"))));
            assertArrayEquals(CONTENT, all(adapter.retrieve(ArtifactStorageReference.of("local", root.toString(), "old/legacy"))));
            assertEquals(TechnicalFailureCode.STORED_FILE_REFUSED, refused(() -> adapter.retrieve(
                    ArtifactStorageReference.of(provider, "SHARED_FILESYSTEM", "old/unknown"))));

            // The start that takes the old local files over to a provider takes their records along.
            new JdbcProcessingStorageConfigurationRepository(jdbc).claimLegacyLocalArtifacts(UUID.fromString(provider), root.toString());
            assertArrayEquals(CONTENT, all(adapter.retrieve(ArtifactStorageReference.of(provider, "SHARED_FILESYSTEM", "old/legacy"))));
            // New files are encrypted beside them.
            adapter.store(tenant, "new/file", new ByteArrayInputStream(CONTENT), CONTENT.length, "application/pdf");
            assertFalse(new String(Files.readAllBytes(root.resolve("new/file")), StandardCharsets.ISO_8859_1).contains("SYNTHETIC"));
            assertTrue(jdbc.queryForObject("select count(*) from stored_files where protection = 'ENCRYPTED'", Integer.class) == 1);
        }
    }

    private static void artifact(NamedParameterJdbcTemplate jdbc, TenantId tenant, UUID document, String status,
                                 String provider, String bucket, String key) {
        jdbc.update("""
                INSERT INTO artifacts (id, tenant_id, document_id, artifact_type, status, storage_provider,
                    storage_bucket, storage_key, mime_type, size_bytes, checksum_algorithm, checksum_value, created_at,
                    updated_at)
                VALUES (:id, :t, :d, 'ORIGINAL', :s, :p, :b, :k, 'application/pdf', 1, 'SHA-256', 'x', now(), now())""",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("t", tenant.value()).addValue("d", document)
                        .addValue("s", status).addValue("p", provider).addValue("b", bucket).addValue("k", key));
    }
}

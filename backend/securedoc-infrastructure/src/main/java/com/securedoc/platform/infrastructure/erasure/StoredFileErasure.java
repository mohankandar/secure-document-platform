package com.securedoc.platform.infrastructure.erasure;

import com.documentplatform.eraselog.EraseEntry;
import com.documentplatform.eraselog.EraseHandler;
import com.documentplatform.eraselog.EraseHandlerException;
import com.documentplatform.eraselog.EraseRequest;
import com.documentplatform.eraselog.ErasedBy;
import com.securedoc.platform.application.port.out.ProcessingStorageConfigurationRepository;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.infrastructure.storage.LocalStorageProperties;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Task 0060: what the erase handlers do with stored files: find them (by the artifact's record and in LexaVault's own
 * places, so a file is found also when a restore left the records and the folders apart), delete them, and in one
 * transaction with the log's position mark the records deleted, drop the files' protection records (with the wrapped keys)
 * and write the audit record. Doing it again is harmless. Links are never followed.
 */
@Component
public class StoredFileErasure {
    static final String STORAGE_UNAVAILABLE = "LV_SYS_002";
    private static final String DATABASE_FAILED = "LV_SYS_003";
    private static final int LOCK_WAIT_SECONDS = 10;

    private final DataSource database;
    private final ProcessingStorageConfigurationRepository storage;
    private final LocalStorageProperties legacy;

    public StoredFileErasure(DataSource database, ProcessingStorageConfigurationRepository storage,
                             LocalStorageProperties legacy) {
        this.database = Objects.requireNonNull(database);
        this.storage = Objects.requireNonNull(storage);
        this.legacy = Objects.requireNonNull(legacy);
    }

    record Stored(UUID id, String status, String provider, String key) {
        boolean deleted() {
            return "DELETED".equals(status);
        }
    }

    @FunctionalInterface
    private interface Work<T> {
        T on(Connection connection) throws SQLException, IOException;
    }

    /** Before anything is logged: the folder of every file still stored must be there. */
    void requireReachable(EraseRequest request, boolean wholeCase) {
        read(c -> {
            for (Stored stored : wholeCase ? ofCase(c, request) : ofFile(c, request)) {
                if (stored.provider() == null || stored.key() == null) continue;
                Path root = stored.deleted() ? rootOrNull(stored) : root(stored);
                if (root != null) within(root, stored.key());
            }
            return null;
        });
    }

    /** True when a record that is not deleted has this id. */
    boolean recorded(EraseRequest request) {
        return read(c -> ofFile(c, request).stream().anyMatch(s -> !s.deleted()));
    }

    EraseHandler.Applied eraseFile(EraseEntry entry, EraseHandler.Context context) {
        EraseRecord.FileId file = EraseRecord.FileId.of(entry.request());
        return inTransaction(context, c -> erase(c, entry, ofFile(c, entry.request()),
                places(entry.request().entity(), file), file.packageId()));
    }

    EraseHandler.Applied eraseCase(EraseEntry entry, EraseHandler.Context context) {
        UUID packageId = EraseRecord.caseId(entry.request());
        return inTransaction(context, c -> erase(c, entry, ofCase(c, entry.request()), newerFiles(c, entry.request())
                ? Set.of() : caseFolderFiles(entry.request().entity(), packageId), packageId));
    }

    private EraseHandler.Applied erase(Connection c, EraseEntry entry, List<Stored> records, Set<Path> places, UUID packageId)
            throws SQLException, IOException {
        boolean any = false;
        Set<String> keys = new LinkedHashSet<>();
        for (Stored stored : records) {
            if (stored.provider() != null && stored.key() != null) {
                Path root = stored.deleted() ? rootOrNull(stored) : root(stored);
                if (root != null) any |= Files.deleteIfExists(within(root, stored.key()));
                keys.add(stored.key());
            }
            any |= !stored.deleted();
        }
        Set<Path> roots = roots();
        for (Path file : places) {
            any |= Files.deleteIfExists(file);
            roots.stream().filter(file::startsWith)
                    .forEach(root -> keys.add(root.relativize(file).toString().replace('\\', '/')));
        }
        try (PreparedStatement mark = c.prepareStatement("update artifacts set status = 'DELETED', updated_at = ?,"
                + " version = version + 1 where id = ? and status <> 'DELETED'");
             PreparedStatement drop = c.prepareStatement("delete from stored_files where storage_key = ?")) {
            for (Stored stored : records) {
                mark.setTimestamp(1, Timestamp.from(Instant.now()));
                mark.setObject(2, stored.id());
                mark.executeUpdate();
            }
            for (String key : keys) {
                drop.setString(1, key);
                drop.executeUpdate();
            }
        }
        audit(c, entry, packageId);
        return any ? EraseHandler.Applied.ERASED : EraseHandler.Applied.ALREADY_GONE;
    }

    /** One audit record per erasure, under the id in the entry; written again when a replay follows a restore. */
    private static void audit(Connection c, EraseEntry entry, UUID packageId) throws SQLException {
        EraseRequest request = entry.request();
        UUID id = UUID.fromString(request.auditId());
        boolean person = request.by().type() == ErasedBy.Type.PERSON;
        boolean wholeCase = EraseRecord.CASE.equals(request.recordType());
        try (PreparedStatement insert = c.prepareStatement("""
                insert into audit_records (id, tenant_id, actor_id, action, resource_type, resource_id, outcome,
                        correlation_id, detail, occurred_at, created_at, case_id)
                select ?, t.id, ?, ?, ?, ?, 'SUCCESS', ?, ?, ?, ?, ?
                  from tenants t where t.id = ?
                on conflict (id) do nothing
                """)) {
            insert.setObject(1, id);
            insert.setObject(2, person ? UUID.fromString(request.by().id()) : new UUID(0, 0));
            insert.setString(3, AuditAction.FILES_ERASED.name());
            insert.setString(4, wholeCase ? "DocumentPackage" : "Artifact");
            insert.setString(5, wholeCase ? request.recordId() : EraseRecord.FileId.of(request).fileId().toString());
            insert.setObject(6, id);
            insert.setString(7, "Erased: kind=" + request.kind() + (person ? "" : ", rule=" + request.by().id())
                    + ", eraseLogEntry=" + entry.seq());
            insert.setTimestamp(8, Timestamp.from(request.erasedAt()));
            insert.setTimestamp(9, Timestamp.from(Instant.now()));
            insert.setString(10, packageId.toString());
            insert.setObject(11, request.entity());
            insert.executeUpdate();
        }
    }

    EraseHandler.Verified verifyFile(EraseEntry entry) {
        EraseRecord.FileId file = EraseRecord.FileId.of(entry.request());
        return read(c -> gone(ofFile(c, entry.request()), places(entry.request().entity(), file)));
    }

    EraseHandler.Verified verifyCase(EraseEntry entry) {
        UUID packageId = EraseRecord.caseId(entry.request());
        return read(c -> gone(ofCase(c, entry.request()), newerFiles(c, entry.request()) ? Set.of()
                : caseFolderFiles(entry.request().entity(), packageId)));
    }

    /**
     * Erased means: no record that is not deleted, and no file. A file that is there although the database already says it
     * was erased came back with a storage backup older than the database's; its erasure is finished here. A folder that is
     * not there says nothing about its files, so it is not "gone".
     */
    private EraseHandler.Verified gone(List<Stored> records, Set<Path> places) throws IOException {
        if (records.stream().anyMatch(s -> !s.deleted())) return EraseHandler.Verified.READABLE;
        Set<Path> files = new LinkedHashSet<>(places);
        for (Stored stored : records) {
            if (stored.provider() != null && stored.key() != null) files.add(within(root(stored), stored.key()));
        }
        for (Path file : files) {
            Files.deleteIfExists(file);
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return EraseHandler.Verified.READABLE;
        }
        return EraseHandler.Verified.GONE;
    }

    // ------------------------------------------------------------------------------------------ records

    private static List<Stored> ofFile(Connection c, EraseRequest request) throws SQLException {
        EraseRecord.FileId file = EraseRecord.FileId.of(request);
        return stored(c, "a.id = ? and a.tenant_id = ?", file.fileId(), request.entity());
    }

    /** The case's files of that time; its split copies have their own entries. */
    private static List<Stored> ofCase(Connection c, EraseRequest request) throws SQLException {
        return stored(c, "a.document_id in (select id from documents where package_id = ?) and a.tenant_id = ?"
                        + " and a.artifact_type <> 'DERIVED_REDACTED' and a.created_at <= ?",
                EraseRecord.caseId(request), request.entity(), Timestamp.from(request.erasedAt()));
    }

    private static boolean newerFiles(Connection c, EraseRequest request) throws SQLException {
        return !stored(c, "a.document_id in (select id from documents where package_id = ?) and a.tenant_id = ?"
                        + " and a.artifact_type <> 'DERIVED_REDACTED' and a.created_at > ?",
                EraseRecord.caseId(request), request.entity(), Timestamp.from(request.erasedAt())).isEmpty();
    }

    private static List<Stored> stored(Connection c, String where, Object... values) throws SQLException {
        try (PreparedStatement select = c.prepareStatement("select a.id, a.status, a.storage_provider, a.storage_key"
                + " from artifacts a where " + where + " order by a.id")) {
            for (int i = 0; i < values.length; i++) select.setObject(i + 1, values[i]);
            List<Stored> found = new ArrayList<>();
            try (ResultSet rs = select.executeQuery()) {
                while (rs.next()) {
                    found.add(new Stored(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)));
                }
            }
            return found;
        }
    }

    // -------------------------------------------------------------------------------------------- files

    private Path root(Stored stored) {
        Path root = rootOrNull(stored);
        if (root == null) {
            throw new EraseHandlerException(STORAGE_UNAVAILABLE, "a document storage folder is not available");
        }
        return root;
    }

    private Path rootOrNull(Stored stored) {
        Path root;
        if ("local".equalsIgnoreCase(stored.provider())) {
            root = legacyRoot();
        } else {
            try {
                root = storage.findProvider(UUID.fromString(stored.provider())).map(p -> path(p.rootPath())).orElse(null);
            } catch (IllegalArgumentException notAnId) {
                root = null;
            }
        }
        return root != null && Files.isDirectory(root) ? root : null;
    }

    private Set<Path> roots() {
        Set<Path> roots = new LinkedHashSet<>();
        storage.findAllProviders().forEach(p -> roots.add(path(p.rootPath())));
        roots.add(legacyRoot());
        roots.removeIf(root -> !Files.isDirectory(root));
        return roots;
    }

    private Path legacyRoot() {
        return path(legacy.getRootPath());
    }

    private static Path path(String text) {
        return Path.of(text).toAbsolutePath().normalize();
    }

    private static Path within(Path root, String key) {
        Path file = root.resolve(key.replace('\\', '/').replaceFirst("^/+", "")).normalize();
        if (!file.startsWith(root) || file.equals(root)) {
            throw new EraseHandlerException("LV_ERASE_PATH_REFUSED", "a stored key leaves its folder");
        }
        return file;
    }

    /** Where LexaVault puts this file: {@code <entity>/<case>/<document>/<kind>/<file>} and the split copies' place. */
    private Set<Path> places(UUID entity, EraseRecord.FileId file) throws IOException {
        Set<Path> found = new LinkedHashSet<>();
        for (Path root : roots()) {
            for (Path kind : folders(root.resolve(entity.toString()).resolve(file.packageId().toString())
                    .resolve(file.documentId().toString()))) {
                addFile(found, kind.resolve(file.fileId().toString()));
            }
            for (Path cycle : folders(root.resolve("derived").resolve(file.packageId().toString()))) {
                addFile(found, cycle.resolve(file.documentId().toString()).resolve(file.fileId() + ".pdf"));
            }
        }
        return found;
    }

    private Set<Path> caseFolderFiles(UUID entity, UUID packageId) throws IOException {
        Set<Path> found = new LinkedHashSet<>();
        for (Path root : roots()) {
            Path folder = root.resolve(entity.toString()).resolve(packageId.toString());
            if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) continue;
            try (Stream<Path> all = Files.walk(folder)) {
                all.filter(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS)).forEach(found::add);
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
        return found;
    }

    private static void addFile(Set<Path> found, Path file) {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) found.add(file);
    }

    private static List<Path> folders(Path folder) throws IOException {
        if (!Files.isDirectory(folder, LinkOption.NOFOLLOW_LINKS)) return List.of();
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            entries.forEach(found::add);
        } catch (DirectoryIteratorException e) {
            throw e.getCause();
        }
        found.removeIf(p -> !Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS));
        return found;
    }

    // ------------------------------------------------------------------------------------- transactions

    /** On the position's own connection where there is one, so the erasure and the position are one transaction. */
    private EraseHandler.Applied inTransaction(EraseHandler.Context context, Work<EraseHandler.Applied> work) {
        Connection given = context.connection().orElse(null);
        return guarded(() -> {
            if (given != null) {
                boundWait(given);
                return work.on(given);
            }
            try (Connection own = database.getConnection()) {
                own.setAutoCommit(false);
                try {
                    boundWait(own);
                    EraseHandler.Applied applied = work.on(own);
                    own.commit();
                    return applied;
                } catch (SQLException | IOException | RuntimeException failed) {
                    own.rollback();
                    throw failed;
                } finally {
                    own.setAutoCommit(true);
                }
            }
        });
    }

    /** A record someone else holds is waited for a short time only: never a wait without end while the log is locked. */
    private static void boundWait(Connection c) throws SQLException {
        try (var s = c.createStatement()) {
            s.execute("set local lock_timeout = '" + LOCK_WAIT_SECONDS + "s'");
        }
    }

    private <T> T read(Work<T> work) {
        return guarded(() -> {
            try (Connection c = database.getConnection()) {
                return work.on(c);
            }
        });
    }

    @FunctionalInterface
    private interface Guarded<T> {
        T run() throws SQLException, IOException;
    }

    /** Codes only: an exception's own text can hold a path. */
    private static <T> T guarded(Guarded<T> work) {
        try {
            return work.run();
        } catch (IOException e) {
            throw new EraseHandlerException(STORAGE_UNAVAILABLE, "document storage: " + e.getClass().getSimpleName());
        } catch (SQLException e) {
            throw new EraseHandlerException(DATABASE_FAILED, "database: SQL state " + e.getSQLState());
        }
    }
}

package com.securedoc.platform.infrastructure.storage;

import com.securedoc.platform.application.port.out.ProcessingStorageConfigurationRepository;
import com.securedoc.platform.application.port.out.UnrecordedFileSweepPort;
import com.securedoc.platform.domain.identity.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Task 0049, step 5: finds files without a record in the processing storage folders. Only LexaVault's own places are
 * looked at: {@code <entity>/<case>/<document>/<kind>/<artifact>} for text and redacted kinds, and
 * {@code derived/<case>/cycle-<n>/<document>/<artifact>.pdf} for split redacted copies. A file is removed only when
 * <ul>
 *   <li>its document exists in this database, in that case and entity (a folder shared with another installation
 *       never loses that installation's files);</li>
 *   <li>no artifact record that is not DELETED has its key or its artifact id;</li>
 *   <li>it was last written before the delay.</li>
 * </ul>
 * Originals are never looked at. Links are never followed. Anything unreadable or unexpected is kept.
 */
@Component
public class UnrecordedFileSweepAdapter implements UnrecordedFileSweepPort {
    private static final Logger log = LoggerFactory.getLogger(UnrecordedFileSweepAdapter.class);
    private static final Set<String> TEXT_KINDS = Set.of("EXTRACTED_TEXT", "EXTRACTED_TEXT_BATCH", "FINDINGS_BATCH");
    private static final String REDACTED_KIND = "REDACTED";
    private static final Pattern CYCLE = Pattern.compile("cycle-\\d+");
    private static final Pattern UUID_NAME =
            Pattern.compile("([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(\\.pdf)?");

    private final ProcessingStorageConfigurationRepository configuration;
    private final NamedParameterJdbcTemplate jdbc;

    public UnrecordedFileSweepAdapter(ProcessingStorageConfigurationRepository configuration,
                                      NamedParameterJdbcTemplate jdbc) {
        this.configuration = configuration;
        this.jdbc = jdbc;
    }

    private static final class Run {
        private final Instant olderThan;
        private final int limit;
        private final Map<TenantId, Removed> removed = new HashMap<>();
        private int total;

        private Run(Instant olderThan, int limit) {
            this.olderThan = olderThan;
            this.limit = limit;
        }

        private boolean full() {
            return total >= limit;
        }
    }

    @Override
    public Map<TenantId, Removed> removeUnrecorded(Instant olderThan, int limit) {
        Run run = new Run(olderThan, limit);
        Set<Path> roots = new HashSet<>();
        configuration.findAllProviders().forEach(p -> roots.add(Path.of(p.rootPath()).toAbsolutePath().normalize()));
        for (Path root : roots) {
            if (!Files.isDirectory(root)) continue;
            for (Path first : folders(root)) {
                if (first.getFileName().toString().equals("derived")) {
                    sweepDerived(run, root, first);
                    continue;
                }
                UUID tenantId = id(first);
                if (tenantId == null) continue;
                for (Path pkg : folders(first)) {
                    for (Path document : folders(pkg)) {
                        if (run.full()) return run.removed;
                        List<Path> files = new ArrayList<>();
                        for (Path kind : folders(document)) {
                            String name = kind.getFileName().toString();
                            if (TEXT_KINDS.contains(name) || REDACTED_KIND.equals(name)) {
                                files.addAll(oldFiles(run, kind));
                            }
                        }
                        sweepDocument(run, root, tenantId, id(pkg), id(document), files);
                    }
                }
            }
        }
        return run.removed;
    }

    private void sweepDerived(Run run, Path root, Path derived) {
        for (Path pkg : folders(derived)) {
            for (Path cycle : folders(pkg)) {
                if (!CYCLE.matcher(cycle.getFileName().toString()).matches()) continue;
                for (Path document : folders(cycle)) {
                    if (run.full()) return;
                    sweepDocument(run, root, null, id(pkg), id(document), oldFiles(run, document));
                }
            }
        }
    }

    private void sweepDocument(Run run, Path root, UUID tenantId, UUID packageId, UUID documentId, List<Path> files) {
        if (files.isEmpty() || packageId == null || documentId == null) return;
        MapSqlParameterSource params = new MapSqlParameterSource("documentId", documentId)
                .addValue("packageId", packageId)
                .addValue("ids", files.stream().map(UnrecordedFileSweepAdapter::id).toList());
        List<UUID> owner = jdbc.queryForList("SELECT tenant_id FROM documents WHERE id = :documentId"
                + " AND package_id = :packageId", params, UUID.class);
        if (owner.size() != 1 || (tenantId != null && !owner.get(0).equals(tenantId))) return;
        Set<String> keys = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        jdbc.query("SELECT id, storage_key FROM artifacts WHERE (document_id = :documentId"
                        + " OR id IN (:ids)) AND status <> 'DELETED'",
                params, rs -> {
                    ids.add(rs.getObject("id", UUID.class));
                    keys.add(rs.getString("storage_key"));
                });
        for (Path file : files) {
            if (run.full()) return;
            String key = root.relativize(file).toString().replace('\\', '/');
            if (keys.contains(key) || ids.contains(id(file))) continue;
            try {
                if (!Files.deleteIfExists(file)) continue;
            } catch (IOException e) {
                log.warn("UNRECORDED_FILE_NOT_REMOVED document={} type={}", documentId, e.getClass().getSimpleName());
                continue;
            }
            boolean text = TEXT_KINDS.contains(file.getParent().getFileName().toString());
            run.total++;
            run.removed.merge(TenantId.of(owner.get(0)), new Removed(text ? 1 : 0, text ? 0 : 1),
                    (a, b) -> new Removed(a.textFiles() + b.textFiles(), a.redactedFiles() + b.redactedFiles()));
        }
    }

    private static List<Path> oldFiles(Run run, Path folder) {
        return list(folder).stream().filter(file -> {
            try {
                return id(file) != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                        && Files.getLastModifiedTime(file, LinkOption.NOFOLLOW_LINKS).toInstant()
                                .isBefore(run.olderThan);
            } catch (IOException unreadable) {
                return false;
            }
        }).toList();
    }

    private static List<Path> folders(Path folder) {
        return list(folder).stream().filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).toList();
    }

    private static List<Path> list(Path folder) {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(folder)) {
            entries.forEach(found::add);
        } catch (IOException | DirectoryIteratorException unreadable) {
            log.warn("UNRECORDED_FILE_SWEEP_FOLDER_SKIPPED type={}", unreadable.getClass().getSimpleName());
            return List.of();
        }
        return found;
    }

    private static UUID id(Path path) {
        Matcher name = UUID_NAME.matcher(path.getFileName().toString());
        return name.matches() ? UUID.fromString(name.group(1)) : null;
    }
}

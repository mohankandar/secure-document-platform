package com.securedoc.platform.infrastructure.delivery;

import com.securedoc.platform.application.port.out.DeliveryLeftoverPort;
import com.securedoc.platform.application.port.out.ErasurePort;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.erasure.LexaVaultErasures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0097: finds staging folders a killed server left ({@link StagingFolder}). One is removed only when
 * <ul>
 *   <li>its case exists in this database, in that entity (the location may be shared with another installation);</li>
 *   <li>the case's export job is not running, and its processing lease (export without jobs) has run out by the
 *       database's clock;</li>
 *   <li>it holds nothing but document folders with files, and nothing in it was written for the quiet time;</li>
 *   <li>it can be set aside (on Windows not while a file in it is open).</li>
 * </ul>
 * Anything else is kept, and so are the destination check's own test files (no case data). Links are never followed.
 */
@Component
@ConditionalOnProperty(name = "securedoc.delivery.type", havingValue = "local")
public class DeliveryLeftoverAdapter implements DeliveryLeftoverPort {
    private static final Logger log = LoggerFactory.getLogger(DeliveryLeftoverAdapter.class);

    private static final String IDLE_SQL = """
            SELECT EXISTS (SELECT 1 FROM document_packages WHERE id = :packageId AND tenant_id = :tenantId)
               AND NOT EXISTS (SELECT 1 FROM pipeline_jobs
                                WHERE package_id = :packageId AND stage = 'EXPORT' AND status = 'RUNNING')
               AND NOT EXISTS (SELECT 1 FROM package_processing_leases
                                WHERE package_id = :packageId AND lease_expires_at > clock_timestamp())
            """;

    private final Path root;
    private final NamedParameterJdbcTemplate jdbc;
    private final LexaVaultErasures erasures;

    public DeliveryLeftoverAdapter(LocalDeliveryProperties properties, NamedParameterJdbcTemplate jdbc,
                                   LexaVaultErasures erasures) {
        this.root = Path.of(properties.getOutputPath()).toAbsolutePath().normalize();
        this.jdbc = jdbc;
        this.erasures = erasures;
    }

    @Override
    public Map<TenantId, Integer> removeLeftovers(Duration quiet, int limit) {
        Map<TenantId, Integer> removed = new HashMap<>();
        if (!Files.isDirectory(root)) return removed;
        Instant quietSince = earlier(databaseTime(), Instant.now()).minus(quiet);
        int total = 0;
        for (Path entity : list(root)) {
            if (!StagingFolder.isId(entity.getFileName().toString())) continue;
            TenantId tenantId = TenantId.of(UUID.fromString(entity.getFileName().toString()));
            Optional<Path> staging;
            try {
                staging = StagingFolder.staging(root, tenantId.value());
            } catch (IOException unsure) {
                skipped(unsure);
                continue;
            }
            if (staging.isEmpty()) continue;
            for (Path candidate : list(staging.get())) {
                if (total >= limit) return removed;
                Optional<StagingFolder> folder = StagingFolder.parse(candidate.getFileName().toString());
                if (folder.isPresent() && remove(tenantId, staging.get(), candidate, folder.get(), quietSince)) {
                    total++;
                    removed.merge(tenantId, 1, Integer::sum);
                }
            }
        }
        return removed;
    }

    private boolean remove(TenantId tenantId, Path staging, Path candidate, StagingFolder folder, Instant quietSince) {
        try {
            if (!isOwnAndQuiet(candidate, quietSince) || !isIdle(tenantId, folder)) return false;
            folder.setAside(staging);
            return erasures.eraseDeliveryLeftover(tenantId, folder, ErasurePort.Reason.rule("delivery-leftover-sweep"));
        } catch (IOException | RuntimeException e) {
            log.warn("DELIVERY_LEFTOVER_NOT_REMOVED package={} cycle={} type={}", folder.packageId(), folder.exportCycle(),
                    e.getClass().getSimpleName());
            return false;
        }
    }

    private boolean isIdle(TenantId tenantId, StagingFolder folder) {
        return Boolean.TRUE.equals(jdbc.queryForObject(IDLE_SQL, new MapSqlParameterSource("packageId", folder.packageId())
                .addValue("tenantId", tenantId.value()), Boolean.class));
    }

    /** Real folders named by a document's id with regular files in them, and none of it written since. */
    private static boolean isOwnAndQuiet(Path folder, Instant quietSince) throws IOException {
        if (!isQuietFolder(folder, quietSince)) return false;
        for (Path document : StagingFolder.children(folder)) {
            if (!StagingFolder.isId(document.getFileName().toString()) || !isQuietFolder(document, quietSince)) return false;
            for (Path file : StagingFolder.children(document)) {
                Optional<BasicFileAttributes> found = StagingFolder.look(file);
                if (found.isEmpty() || !found.get().isRegularFile() || !isBefore(found.get(), quietSince)) return false;
            }
        }
        return true;
    }

    private static boolean isQuietFolder(Path path, Instant quietSince) throws IOException {
        Optional<BasicFileAttributes> found = StagingFolder.look(path);
        return found.isPresent() && StagingFolder.isRealFolder(found.get()) && isBefore(found.get(), quietSince);
    }

    private static boolean isBefore(BasicFileAttributes attributes, Instant time) {
        return attributes.lastModifiedTime().toInstant().isBefore(time);
    }

    private Instant databaseTime() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Map.of(), Timestamp.class).toInstant();
    }

    private static Instant earlier(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static List<Path> list(Path folder) {
        try {
            return StagingFolder.children(folder);
        } catch (IOException unreadable) {
            skipped(unreadable);
            return List.of();
        }
    }

    private static void skipped(IOException e) {
        log.warn("DELIVERY_LEFTOVER_FOLDER_SKIPPED type={}", e.getClass().getSimpleName());
    }
}

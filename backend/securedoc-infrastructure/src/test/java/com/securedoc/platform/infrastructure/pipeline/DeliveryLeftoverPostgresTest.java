package com.securedoc.platform.infrastructure.pipeline;

import com.documentplatform.eraselog.EraseHandler;
import com.documentplatform.eraselog.EraseHandlers;
import com.documentplatform.eraselog.EraseLog;
import com.documentplatform.eraselog.EraseReplay;
import com.documentplatform.eraselog.Eraser;
import com.documentplatform.eraselog.testkit.InMemoryEraseLogStore;
import com.documentplatform.eraselog.testkit.InMemoryErasePosition;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.lifecycle.service.DeliveryLeftoverService;
import com.securedoc.platform.application.port.out.CaseFieldLock;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.application.port.out.ProcessingStorageConfigurationRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.audit.AuditRecord;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.delivery.DeliveryLeftoverAdapter;
import com.securedoc.platform.infrastructure.delivery.LocalDeliveryProperties;
import com.securedoc.platform.infrastructure.delivery.LocalFolderDeliveryAdapter;
import com.securedoc.platform.infrastructure.erasure.CaseFieldErasure;
import com.securedoc.platform.infrastructure.erasure.DeliveryLeftoverErasure;
import com.securedoc.platform.infrastructure.erasure.LexaVaultEraseHandlers;
import com.securedoc.platform.infrastructure.erasure.LexaVaultErasures;
import com.securedoc.platform.infrastructure.erasure.StoredFileErasure;
import com.securedoc.platform.infrastructure.storage.LocalStorageProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0097: which staging folders of the folder delivery are removed, which are kept, and how. */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class DeliveryLeftoverPostgresTest {
    private static final ActorId SYSTEM = ActorId.of(new UUID(0, 0));
    /** The claim time (2 minutes) and the margin (10 minutes) are over, and not over. */
    private static final Duration OLD = Duration.ofMinutes(13);
    private static final Duration YOUNG = Duration.ofMinutes(11);

    @TempDir Path work;
    private Path root;
    private PostgresTestDatabase db;
    private NamedParameterJdbcTemplate jdbc;
    private final TenantId tenant = TenantId.generate();
    private final InMemoryEraseLogStore logStore = new InMemoryEraseLogStore();
    private final InMemoryErasePosition position = new InMemoryErasePosition();
    private final List<AuditRecord> audits = new ArrayList<>();

    @BeforeEach
    void database() throws Exception {
        root = Files.createDirectories(work.resolve("export"));
        db = PostgresTestDatabase.migrated("m097left");
        jdbc = db.jdbc();
        TestRows.tenant(jdbc, tenant);
    }

    @AfterEach
    void drop() throws Exception {
        db.close();
    }

    // ------------------------------------------------------------------------------------------- what goes

    @Test
    void onlyOldLeftoversOfThisDatabasesOwnIdleCasesAreRemoved() throws Exception {
        TenantId other = TenantId.generate();
        TestRows.tenant(jdbc, other);
        List<Path> removed = new ArrayList<>();
        removed.add(leftover(tenant, newCase(tenant), OLD));
        PackageId twice = newCase(tenant);
        removed.add(leftover(tenant, twice, OLD));
        removed.add(leftover(tenant, twice, OLD));
        removed.add(leftover(other, newCase(other), OLD));
        // Killed before it wrote anything.
        removed.add(Files.createDirectories(staging(tenant).resolve(newCase(tenant) + "-cycle-3-" + UUID.randomUUID())));
        age(removed.get(4), OLD);
        // Not running: every state but RUNNING, and a processing lease that ran out.
        for (String status : List.of("READY", "WAITING_RETRY", "NEEDS_IT", "DONE", "STOPPED")) {
            PackageId pkg = newCase(tenant);
            exportJob(pkg, "EXPORT", status);
            removed.add(leftover(tenant, pkg, OLD));
        }
        PackageId expired = newCase(tenant);
        lease(expired, "- interval '1 second'");
        removed.add(leftover(tenant, expired, OLD));

        List<Path> kept = new ArrayList<>();
        // Age: the newest entry counts, wherever it is.
        kept.add(leftover(tenant, newCase(tenant), YOUNG));
        Path youngFile = leftover(tenant, newCase(tenant), OLD);
        touch(files(youngFile).get(0), YOUNG);
        kept.add(youngFile);
        Path youngFolder = leftover(tenant, newCase(tenant), OLD);
        touch(youngFolder, YOUNG);
        kept.add(youngFolder);
        Path future = leftover(tenant, newCase(tenant), OLD);
        touch(files(future).get(0), Duration.ofDays(-1));
        kept.add(future);
        // Running: an export job, also with a claim that ran out and was not given back yet; a processing lease.
        for (String until : List.of("+ interval '1 minute'", "- interval '1 hour'")) {
            PackageId pkg = newCase(tenant);
            exportJob(pkg, "EXPORT", "RUNNING");
            jdbc.update("update pipeline_jobs set claim_until = clock_timestamp() " + until + " where package_id = :p",
                    Map.of("p", pkg.value()));
            kept.add(leftover(tenant, pkg, OLD));
        }
        PackageId leased = newCase(tenant);
        lease(leased, "+ interval '30 seconds'");
        kept.add(leftover(tenant, leased, OLD));
        // Another installation's entity or case, or a case of another entity.
        kept.add(leftover(TenantId.generate(), newCase(tenant), OLD));
        kept.add(leftover(tenant, PackageId.generate(), OLD));
        kept.add(leftover(other, newCase(tenant), OLD));
        // Not LexaVault's shape.
        PackageId pkg = newCase(tenant);
        for (String name : List.of(pkg + "-cycle-0-" + UUID.randomUUID(), pkg + "-cycle-1", pkg + "-cycle-1-" + UUID.randomUUID() + "x",
                pkg.toString(), "notes", pkg.toString().toUpperCase() + "-cycle-1-" + UUID.randomUUID())) {
            kept.add(aged(staging(tenant).resolve(name), UUID.randomUUID() + "/a.pdf"));
        }
        for (String content : List.of("a.pdf", "notes/a.pdf", UUID.randomUUID() + "/sub/a.pdf")) {
            kept.add(aged(staging(tenant).resolve(newCase(tenant) + "-cycle-1-" + UUID.randomUUID()), content));
        }
        kept.add(aged(root.resolve("elsewhere/.staging/" + pkg + "-cycle-1-" + UUID.randomUUID()), UUID.randomUUID() + "/a.pdf"));
        kept.add(aged(root.resolve(tenant + "/" + pkg + "-cycle-1-" + UUID.randomUUID()), UUID.randomUUID() + "/a.pdf"));
        kept.add(aged(root.resolve(tenant + "/" + pkg + "/cycle-1"), UUID.randomUUID() + "/a.pdf"));
        // A file in place of the folder, and the destination check's own test file.
        for (String name : List.of(pkg + "-cycle-1-" + UUID.randomUUID(), "lexavault-check-" + UUID.randomUUID() + ".tmp")) {
            Path file = Files.writeString(staging(tenant).resolve(name), "ok");
            touch(file, OLD);
            kept.add(file);
        }

        assertEquals(removed.size(), run());

        removed.forEach(p -> assertFalse(there(p), p.toString()));
        kept.forEach(p -> assertTrue(there(p), p.toString()));
        assertEquals(removed.size(), logStore.size(), "each in the erase log");
        assertEquals(removed.size(), count("select count(*) from audit_records where action = 'FILES_ERASED'"
                + " and resource_type = 'FolderDelivery'"));
        assertEquals(1, count("select count(*) from audit_records where action = 'FILES_ERASED' and case_id = '" + expired + "'"));
        assertEquals(2, audits.size(), "one per entity");
        audits.forEach(a -> assertEquals(AuditAction.DELIVERY_LEFTOVERS_CLEANED, a.action()));
        assertEquals(List.of("Folder delivery leftovers removed: deliveries=1",
                "Folder delivery leftovers removed: deliveries=" + (removed.size() - 1)),
                audits.stream().map(AuditRecord::detail).sorted(Comparator.comparingInt(String::length)).toList());
        assertEquals(0, run(), "nothing left to do");
        assertEquals(2, audits.size());
    }

    @Test
    void noLinkIsFollowedAndOneWithALinkInItIsKept() throws Exception {
        Path outside = Files.createDirectories(work.resolve("outside"));
        Path precious = Files.writeString(outside.resolve("a.pdf"), "not LexaVault's");
        touch(precious, OLD);
        touch(outside, OLD);
        // In place of a document's folder, of the staging folder, of the staging place and of the entity's folder.
        Path inside = leftover(tenant, newCase(tenant), OLD);
        linkToFolder(inside.resolve(UUID.randomUUID().toString()), outside);
        touch(inside, OLD);
        Path instead = staging(tenant).resolve(newCase(tenant) + "-cycle-1-" + UUID.randomUUID());
        linkToFolder(instead, outside);
        TenantId stagingLinked = TenantId.generate();
        TestRows.tenant(jdbc, stagingLinked);
        PackageId linkedCase = newCase(stagingLinked);
        Path elsewhere = aged(work.resolve("elsewhere").resolve(linkedCase + "-cycle-1-" + UUID.randomUUID()), UUID.randomUUID() + "/a.pdf");
        Files.createDirectories(root.resolve(stagingLinked.toString()));
        linkToFolder(root.resolve(stagingLinked.toString()).resolve(".staging"), work.resolve("elsewhere"));
        TenantId entityLinked = TenantId.generate();
        TestRows.tenant(jdbc, entityLinked);
        Path far = aged(work.resolve("far/.staging").resolve(newCase(entityLinked) + "-cycle-1-" + UUID.randomUUID()), UUID.randomUUID() + "/a.pdf");
        linkToFolder(root.resolve(entityLinked.toString()), work.resolve("far"));
        Path own = leftover(tenant, newCase(tenant), OLD);

        assertEquals(1, run(), "the other one is still removed");

        assertFalse(there(own));
        for (Path kept : List.of(precious, inside, instead, elsewhere, far)) assertTrue(there(kept), kept.toString());
        // An erasure that has to be done (a replay) removes a link itself, and never what it points to.
        Path again = leftover(tenant, newCase(tenant), OLD);
        assertEquals(1, run());
        Files.createDirectories(again);
        linkToFolder(again.resolve("link"), outside);
        assertTrue(replay("local").passed());
        assertFalse(there(again));
        assertTrue(there(precious));
    }

    // --------------------------------------------------------------------------------------- the erase log

    @Test
    void whenTheEraseLogCantBeWrittenNothingIsErasedAndItIsDoneLater() throws Exception {
        Path leftover = leftover(tenant, newCase(tenant), OLD);
        List<Path> before = files(leftover).stream().map(leftover::relativize).toList();
        logStore.down(true);
        assertEquals(0, run());
        assertEquals(0, run(), "again");
        Path aside = leftover.resolveSibling(leftover.getFileName() + ".erasing");
        assertEquals(before, files(aside).stream().map(aside::relativize).toList(), "set aside, every file there");
        assertTrue(audits.isEmpty());
        logStore.down(false);
        assertEquals(1, run());
        assertFalse(there(aside) || there(leftover));
        assertEquals(1, logStore.size());
    }

    @Test
    void anErasureThatStoppedHalfWayIsFinishedByTheReplay() throws Exception {
        Path leftover = leftover(tenant, newCase(tenant), OLD);
        assertEquals(1, run());
        // Both names came back, one of them a part.
        write(leftover.resolve(UUID.randomUUID() + "/a.pdf"));
        write(leftover.resolveSibling(leftover.getFileName() + ".erasing").resolve(UUID.randomUUID() + "/a.pdf"));
        EraseReplay.Report report = replay("local");
        assertTrue(report.passed(), report.describe());
        assertEquals(0, children(staging(tenant)).size());
        assertTrue(replay("local").passed(), "again, harmless");
    }

    @Test
    void aMissingExportLocationIsNeverCalledErasedAndStopsNobodyOnceDeliveriesGoElsewhere() throws Exception {
        leftover(tenant, newCase(tenant), OLD);
        assertEquals(1, run());
        Path away = work.resolve("away");
        Files.move(root, away);
        EraseReplay.Report missing = replay("local");
        assertFalse(missing.passed());
        assertEquals("LV_SYS_002", missing.handlerCode());
        assertTrue(replay("document-store").passed());
        assertEquals(0, run(), "the clean-up itself does nothing then");
        Files.move(away, root);
        // Deliveries go elsewhere, and the location is there with what a restore brought back: it is erased all the same.
        Path back = leftover(tenant, newCase(tenant), OLD);
        assertEquals(1, run());
        write(back.resolve(UUID.randomUUID() + "/a.pdf"));
        assertTrue(replay("document-store").passed());
        assertFalse(there(back));
    }

    // ------------------------------------------------------------------------------------ never half a delivery

    /** Every check above wrong at once (a stalled delivery without a claim): it is tried again, and no part is published. */
    @Test
    void aDeliveryWhoseFolderIsTakenIsTriedAgainAndNeverPublishedInPart() throws Exception {
        PackageId pkg = newCase(tenant);
        LocalFolderDeliveryAdapter adapter = new LocalFolderDeliveryAdapter(properties());
        for (boolean betweenTwoDocuments : new boolean[]{true, false}) {
            int[] swept = {0};
            DeliveryPort.ContentSource first = () -> new ByteArrayInputStream(new byte[70_000]) {
                @Override
                public void close() throws IOException {
                    if (betweenTwoDocuments) swept[0] += sweepWhileStalled();
                }
            };
            DeliveryPort.ContentSource second = () -> new ByteArrayInputStream(new byte[70_000]) {
                @Override
                public void close() throws IOException {
                    if (!betweenTwoDocuments) swept[0] += sweepWhileStalled();
                }
            };
            TechnicalDependencyException failed = assertThrows(TechnicalDependencyException.class,
                    () -> adapter.deliverBatch(batch(pkg, first, second)));
            assertTrue(failed.retryable());
            assertEquals(1, swept[0]);
            assertFalse(there(root.resolve(tenant + "/" + pkg + "/cycle-1")), "nothing published");
        }
        assertEquals(0, children(staging(tenant)).size());
        DeliveryPort.ContentSource whole = () -> new ByteArrayInputStream(new byte[70_000]);
        assertEquals(2, adapter.deliverBatch(batch(pkg, whole, whole)).documents().size());
        assertEquals(2, files(root.resolve(tenant + "/" + pkg + "/cycle-1")).size());
    }

    private int sweepWhileStalled() throws IOException {
        for (Path folder : children(staging(tenant))) age(folder, OLD);
        return run();
    }

    private DeliveryPort.DeliveryBatch batch(PackageId pkg, DeliveryPort.ContentSource... contents) {
        List<DeliveryPort.DeliveryItem> items = new ArrayList<>();
        for (DeliveryPort.ContentSource content : contents) {
            items.add(new DeliveryPort.DeliveryItem(DocumentId.generate(), "source", "a.pdf", content, 70_000, "application/pdf", null, null));
        }
        return new DeliveryPort.DeliveryBatch(tenant, pkg, 1, "submission", null, Map.of(), Map.of(), items, DeliveryPort.NOOP_PROGRESS);
    }

    // ------------------------------------------------------------------------------------------------ set-up

    private LocalDeliveryProperties properties() {
        LocalDeliveryProperties properties = new LocalDeliveryProperties();
        properties.setOutputPath(root.toString());
        return properties;
    }

    private EraseHandlers handlers(String deliveryType) {
        ProcessingStorageConfigurationRepository none = (ProcessingStorageConfigurationRepository) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{ProcessingStorageConfigurationRepository.class}, (p, method, args) -> List.of());
        LocalStorageProperties storage = new LocalStorageProperties();
        storage.setRootPath(work.resolve("storage").toString());
        StoredFileErasure files = new StoredFileErasure(jdbc.getJdbcTemplate().getDataSource(), none, storage);
        EraseHandler handler = new LexaVaultEraseHandlers().deliveryLeftoverEraseHandler(
                new DeliveryLeftoverErasure(files, properties(), deliveryType));
        return EraseHandlers.of(handler);
    }

    private EraseReplay.Report replay(String deliveryType) {
        return new EraseReplay(new EraseLog(logStore, Clock.systemUTC()), position, handlers(deliveryType), Clock.systemUTC()).run();
    }

    private int run() {
        Eraser eraser = new Eraser(new EraseLog(logStore, Clock.systemUTC()), position, handlers("local"));
        LexaVaultErasures erasures = new LexaVaultErasures(eraser, jdbc.getJdbcTemplate(),
                new CaseFieldErasure(jdbc.getJdbcTemplate()), CaseFieldLock.NONE,
                new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource()));
        return new DeliveryLeftoverService(Optional.of(new DeliveryLeftoverAdapter(properties(), jdbc, erasures)),
                new AuditService(audits::add), Duration.ofMinutes(2)).run(SYSTEM, CorrelationId.generate());
    }

    private PackageId newCase(TenantId entity) {
        PackageId pkg = PackageId.generate();
        TestRows.packageRow(jdbc, entity, pkg);
        return pkg;
    }

    private void exportJob(PackageId pkg, String stage, String status) {
        jdbc.update("""
                insert into pipeline_jobs (id, tenant_id, stage, package_id, export_cycle, priority, status, attempts,
                    next_attempt_at, claim_token, claim_owner, claim_until, created_at, updated_at)
                values (:id, :t, :stage, :p, 1, 'NORMAL', :s, 0, now(), 0,
                    case when :s = 'RUNNING' then 'o' end, case when :s = 'RUNNING' then now() end, now(), now())""",
                new MapSqlParameterSource("id", UUID.randomUUID()).addValue("t", tenant.value()).addValue("stage", stage)
                        .addValue("p", pkg.value()).addValue("s", status));
    }

    private void lease(PackageId pkg, String expires) {
        jdbc.update("insert into package_processing_leases (package_id, tenant_id, owner_id, fencing_token, attempt_count,"
                + " acquired_at, heartbeat_at, lease_expires_at) values (:p, :t, 'node', 1, 1, now(), now(), clock_timestamp() "
                + expires + ")", Map.of("p", pkg.value(), "t", tenant.value()));
    }

    private int count(String sql) {
        return jdbc.queryForObject(sql, Map.of(), Integer.class);
    }

    private Path staging(TenantId entity) {
        return root.resolve(entity.toString()).resolve(".staging");
    }

    private Path leftover(TenantId entity, PackageId pkg, Duration old) throws IOException {
        Path folder = staging(entity).resolve(pkg + "-cycle-1-" + UUID.randomUUID());
        write(folder.resolve(UUID.randomUUID() + "/a.pdf"));
        write(folder.resolve(UUID.randomUUID() + "/b.pdf"));
        age(folder, old);
        return folder;
    }

    private static Path aged(Path folder, String content) throws IOException {
        write(folder.resolve(content));
        age(folder, OLD);
        return folder;
    }

    private static void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "synthetic");
    }

    private static void age(Path folder, Duration old) throws IOException {
        try (Stream<Path> all = Files.walk(folder)) {
            for (Path path : all.sorted(Comparator.reverseOrder()).toList()) touch(path, old);
        }
    }

    private static void touch(Path path, Duration old) throws IOException {
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(old)));
    }

    private static boolean there(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static List<Path> children(Path folder) throws IOException {
        try (Stream<Path> all = Files.list(folder)) {
            return all.toList();
        }
    }

    private static List<Path> files(Path folder) throws IOException {
        try (Stream<Path> all = Files.walk(folder)) {
            return all.filter(Files::isRegularFile).sorted().toList();
        }
    }

    /** A link, or where the machine's user may not make one (Windows), a junction. */
    private static void linkToFolder(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (IOException noRight) {
            assertEquals(0, new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start().waitFor(), "junction");
        }
    }
}

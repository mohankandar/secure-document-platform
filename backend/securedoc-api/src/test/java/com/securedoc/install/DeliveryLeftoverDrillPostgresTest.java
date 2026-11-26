package com.securedoc.install;

import com.documentplatform.eraselog.EraseReplay;
import com.documentplatform.eraselog.spring.EraseReplayRunner;
import com.documentplatform.setup.SetupCommand;
import com.securedoc.platform.api.SecureDocumentPlatformApplication;
import com.securedoc.platform.application.pipeline.ExportJobQueue;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.domain.artifact.Artifact;
import com.securedoc.platform.domain.artifact.ArtifactType;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.vo.MimeType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0097: the real app in a JVM of its own, on a real PostgreSQL, stopped dead ({@code Runtime.halt}) while its export
 * worker writes the second document of a folder delivery. It is started again: the case is delivered, the redacted copies
 * left under {@code .staging} are erased by the start's clean-up once they are old enough, and the erase log has them. A
 * restore that brings them back is undone by the replay.
 */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
class DeliveryLeftoverDrillPostgresTest {
    private static final TenantId ENTITY = TenantId.of(UUID.fromString("30216241-b724-5a3a-aef3-3034fd546d17"));
    private static final int KILLED = 77;
    private static final Path DEMO = DemoStepTest.REPO.resolve("install/demo");
    private static final String LOGGING_SYSTEM = "org.springframework.boot.logging.LoggingSystem";

    private static ThrowawayDatabase db;
    private static String loggingSystemBefore;
    @TempDir
    static Path work;

    @BeforeAll
    static void install() throws Exception {
        loggingSystemBefore = System.getProperty(LOGGING_SYSTEM);
        System.setProperty(LOGGING_SYSTEM, "none");
        db = ThrowawayDatabase.create("leftover");
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8);
        assertEquals(0, new SchemaStep(quiet, quiet).run(new String[]{"apply"}, db.env(SchemaStep.ENV_PREFIX)));
        assertEquals(0, new SetupCommand(db.env("PLATFORM_SETUP"), Clock.systemUTC(), TestBuild.development()).run(List.of("apply",
                "--licence", DEMO.resolve("licence-demo.json").toString(), "--signin", DEMO.resolve("signin-demo.json").toString(),
                "--product", "LEXAVAULT", "--product-roles", DEMO.resolve("product-roles-development.json").toString(),
                "--config-out", work.resolve("platform-tenant.properties").toString(),
                "--run-log", work.resolve("setup.log").toString(), "--no-customer-keys"), quiet, quiet));
        assertEquals(0, new DemoStep(quiet, quiet, Clock.systemUTC(), TestBuild::development).run(new String[]{"apply"},
                db.env(DemoStep.ENV_PREFIX)));
        Files.createDirectories(work.resolve("storage"));
        Files.createDirectories(work.resolve("export"));
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

    // ------------------------------------------------------------------------------- the server that is killed

    /** The app in a JVM of its own; the second stored copy it reads for the delivery stops the JVM half-way. */
    public static final class ServerToKill {
        public static void main(String[] args) {
            new SpringApplicationBuilder(SecureDocumentPlatformApplication.class, KillWhileDelivering.class,
                    FreshDatabaseStartPostgresTest.DevelopmentBuild.class).profiles("local").run(args);
        }
    }

    static class KillWhileDelivering {
        @Bean
        static BeanPostProcessor killOnTheSecondCopy() {
            AtomicInteger copies = new AtomicInteger();
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof StoragePort)) return bean;
                    return Proxy.newProxyInstance(bean.getClass().getClassLoader(), new Class<?>[]{StoragePort.class}, (p, method, args) -> {
                        try {
                            Object result = method.invoke(bean, args);
                            return method.getName().equals("retrieve") && copies.incrementAndGet() == 2
                                    ? new HaltHalfWay((InputStream) result) : result;
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    });
                }
            };
        }
    }

    private static final class HaltHalfWay extends FilterInputStream {
        private int blocks;

        HaltHalfWay(InputStream in) {
            super(in);
        }

        @Override
        public int read(byte[] to, int from, int length) throws IOException {
            if (++blocks > 2) Runtime.getRuntime().halt(KILLED);
            return super.read(to, from, length);
        }

        @Override
        public long transferTo(java.io.OutputStream to) throws IOException {
            to.write(in.readNBytes(1000));
            to.flush();
            Runtime.getRuntime().halt(KILLED);
            return 0;
        }
    }

    private static List<String> settings(String... more) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + db.url, "--spring.datasource.username=" + db.user, "--server.port=0",
                "--securedoc.ai.provider=heuristic", "--securedoc.watched-folder.enabled=false",
                "--securedoc.watched-folder.scheduling-enabled=false",
                "--securedoc.storage.local.root-path=" + work.resolve("storage"),
                "--securedoc.delivery.type=local", "--securedoc.delivery.local.output-path=" + work.resolve("export"),
                "--securedoc.processing.export-mode=job", "--securedoc.processing.export-jobs.claim-lease-ms=3000",
                "--platform.erase-log.location=" + work.resolve("erase-log")));
        args.addAll(List.of(more));
        return args;
    }

    private static ConfigurableApplicationContext start(String... more) {
        List<String> args = settings(more);
        if (args.stream().noneMatch(a -> a.contains("poll-interval-ms"))) {
            args.add("--securedoc.processing.export-jobs.poll-interval-ms=250");
        }
        args.add("--spring.datasource.password=" + db.password);
        return new SpringApplicationBuilder(SecureDocumentPlatformApplication.class,
                FreshDatabaseStartPostgresTest.DevelopmentBuild.class).profiles("local").run(args.toArray(String[]::new));
    }

    private static void startAndBeKilled() throws Exception {
        Path argFile = work.resolve("java-args");
        Files.writeString(argFile, "-cp \"" + System.getProperty("java.class.path").replace("\\", "\\\\") + "\"");
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "@" + argFile, ServerToKill.class.getName()));
        command.addAll(settings("--securedoc.processing.export-jobs.poll-interval-ms=250", "--logging.file.name="));
        Path output = work.resolve("killed-server.txt");
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(output.toFile());
        builder.environment().put("SPRING_DATASOURCE_PASSWORD", db.password); // never on a command line
        Process server = builder.start();
        try {
            assertTrue(server.waitFor(4, TimeUnit.MINUTES), () -> "the server was not killed in its delivery: " + tail(output));
            assertEquals(KILLED, server.exitValue(), () -> tail(output));
        } finally {
            server.destroyForcibly();
        }
    }

    private static String tail(Path output) {
        try {
            String text = Files.readString(output, StandardCharsets.ISO_8859_1);
            return text.substring(Math.max(0, text.length() - 3000)).replace(db.password, "***");
        } catch (IOException e) {
            return "no output";
        }
    }

    // --------------------------------------------------------------------------------------------- the drill

    @Test
    void aServerKilledWhileItDeliversLeavesNothingBehindAfterARestart() throws Exception {
        PackageId pkg = PackageId.generate();
        change("insert into document_packages (id, tenant_id, package_type, status, created_by, created_at, updated_at)"
                + " values ('" + pkg + "', '" + ENTITY + "', 'MANUAL_UPLOAD', 'PROCESSING', '" + UUID.randomUUID() + "', now(), now())");
        try (ConfigurableApplicationContext app = start("--securedoc.processing.export-jobs.poll-interval-ms=600000")) {
            for (int i = 0; i < 2; i++) document(app, pkg);
            app.getBean(ExportJobQueue.class).queueCaseExport(ENTITY, pkg, 1, CorrelationId.generate());
        }
        assertEquals("READY", one("select status from pipeline_jobs where stage = 'EXPORT'"), "left for the server to be killed");

        startAndBeKilled();

        assertEquals("RUNNING", one("select status from pipeline_jobs where stage = 'EXPORT'"), "killed in its claim");
        Path leftover = onlyChild(staging());
        assertTrue(leftover.getFileName().toString().matches(pkg + "-cycle-1-[0-9a-f-]{36}"), leftover.toString());
        assertEquals(2, files(leftover).size(), "one copy and a part of the second");
        assertFalse(Files.exists(finished(pkg)), "nothing was published");

        // Started again: the case is delivered; what is left is younger than the claim time and the margin, so it stays.
        try (ConfigurableApplicationContext app = start()) {
            until(() -> "DONE".equals(one("select status from pipeline_jobs where stage = 'EXPORT'")), "the case is delivered");
        }
        assertEquals(2, files(finished(pkg)).size());
        assertTrue(Files.exists(leftover), "too young to be told from a delivery that still runs");
        assertEquals("0", one("select count(*) from audit_records where action = 'DELIVERY_LEFTOVERS_CLEANED'"));

        age(leftover);
        db.onServer("create database " + db.name + "_backup template " + db.name);
        Path kept = work.resolve("leftover-backup");
        copy(leftover, kept);

        // Started again, later: the clean-up of the start erases them.
        try (ConfigurableApplicationContext app = start()) {
            until(() -> !Files.exists(leftover), "the leftovers are gone");
            until(() -> "1".equals(one("select count(*) from audit_records where action = 'DELIVERY_LEFTOVERS_CLEANED'")), "audited");
        }
        assertEquals(List.of(), children(staging()), "nothing else stays in its place");
        assertEquals(2, files(finished(pkg)).size(), "the delivery is untouched");
        assertEquals("Folder delivery leftovers removed: deliveries=1",
                one("select detail from audit_records where action = 'DELIVERY_LEFTOVERS_CLEANED' and tenant_id = '" + ENTITY + "'"));
        assertEquals("1", one("select count(*) from audit_records where action = 'FILES_ERASED' and case_id = '" + pkg + "'"));
        assertEquals("1", one("select applied_seq from erl_position"));
        String log = Files.readString(work.resolve("erase-log/erase-log.jsonl"));
        assertTrue(log.contains("\"delivery-leftover\"") && log.contains(pkg.toString()), log);
        assertFalse(log.contains(".pdf") || log.contains(".staging"), log);

        // A restore brings the database and the export location back to before the erasure: the replay erases again.
        db.onServer("drop database " + db.name + " with (force)");
        db.onServer("create database " + db.name + " template " + db.name + "_backup");
        copy(kept, leftover);
        EraseReplay.Report report = replay();
        assertTrue(report.passed(), report.describe());
        assertEquals(1, report.appliedCount(), report.describe());
        assertFalse(Files.exists(leftover));
        assertEquals("1", one("select count(*) from audit_records where action = 'FILES_ERASED'"));
        // Only the export location restored.
        copy(kept, leftover);
        EraseReplay.Report locationOnly = replay();
        assertTrue(locationOnly.passed(), locationOnly.describe());
        assertFalse(Files.exists(leftover));
        // Again, harmless.
        EraseReplay.Report again = replay();
        assertTrue(again.passed() && again.appliedCount() == 0, again.describe());
        // The export location is not there: nothing can be said about its files, so nothing is called erased.
        Files.move(work.resolve("export"), work.resolve("export-away"));
        EraseReplay.Report away = replay();
        assertFalse(away.passed(), away.describe());
        assertEquals("LV_SYS_002", away.handlerCode(), away.describe());
        Files.move(work.resolve("export-away"), work.resolve("export"));
        assertTrue(replay().passed());
        assertEquals(2, files(finished(pkg)).size(), "the delivery is untouched");
    }

    private static EraseReplay.Report replay() {
        try (ConfigurableApplicationContext app = start("--erase-replay", "--platform.erase-log.exit-after-replay=false")) {
            return app.getBean(EraseReplayRunner.class).lastReport();
        }
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private static void document(ConfigurableApplicationContext app, PackageId pkg) throws SQLException {
        DocumentId document = DocumentId.generate();
        change("insert into documents (id, tenant_id, package_id, file_name, mime_type, size_bytes, checksum_algorithm,"
                + " checksum_value, document_type, status, created_at, updated_at) values ('" + document + "', '" + ENTITY
                + "', '" + pkg + "', 'synthetic-0097.pdf', 'application/pdf', 1, 'SHA-256', 'x', 'PDF', 'PROCESSED', now(), now())");
        byte[] content = new byte[200_000];
        new Random(97).nextBytes(content);
        Artifact artifact = Artifact.create(ENTITY, document, ArtifactType.REDACTED, MimeType.pdf(), content.length,
                app.getBean(ChecksumPort.class).compute(new ByteArrayInputStream(content)));
        artifact.markAvailable(app.getBean(StoragePort.class).storeForPackage(ENTITY, pkg,
                ENTITY + "/" + pkg + "/" + document + "/REDACTED/" + artifact.id(), new ByteArrayInputStream(content),
                content.length, "application/pdf"));
        app.getBean(ArtifactRepository.class).save(artifact);
    }

    private static Path staging() {
        return work.resolve("export").resolve(ENTITY.value().toString()).resolve(".staging");
    }

    private static Path finished(PackageId pkg) {
        return work.resolve("export").resolve(ENTITY.value().toString()).resolve(pkg.value().toString()).resolve("cycle-1");
    }

    private static List<Path> children(Path folder) throws IOException {
        try (Stream<Path> all = Files.list(folder)) {
            return all.toList();
        }
    }

    private static Path onlyChild(Path folder) throws IOException {
        List<Path> found = children(folder);
        assertEquals(1, found.size(), found.toString());
        return found.get(0);
    }

    private static List<Path> files(Path folder) throws IOException {
        try (Stream<Path> all = Files.walk(folder)) {
            return all.filter(Files::isRegularFile).toList();
        }
    }

    /** As if the server had been killed an hour ago. */
    private static void age(Path folder) throws IOException {
        try (Stream<Path> all = Files.walk(folder)) {
            for (Path path : all.sorted(Comparator.reverseOrder()).toList()) {
                Files.setLastModifiedTime(path, FileTime.from(Instant.now().minus(Duration.ofHours(1))));
            }
        }
    }

    private static void copy(Path from, Path to) throws IOException {
        try (Stream<Path> all = Files.walk(from)) {
            for (Path source : all.toList()) {
                Path target = to.resolve(from.relativize(source).toString());
                if (Files.isDirectory(source)) Files.createDirectories(target);
                else Files.copy(source, target);
            }
        }
    }

    private static void until(Callable<Boolean> done, String what) throws Exception {
        for (int i = 0; i < 240; i++) {
            if (done.call()) return;
            Thread.sleep(250);
        }
        assertTrue(done.call(), what);
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
}

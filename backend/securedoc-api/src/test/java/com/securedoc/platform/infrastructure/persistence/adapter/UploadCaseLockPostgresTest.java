package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.intake.command.UploadContent;
import com.securedoc.platform.application.intake.command.UploadDocumentCommand;
import com.securedoc.platform.application.licensing.TenantLicensingService;
import com.securedoc.platform.application.port.out.ArtifactRepository;
import com.securedoc.platform.application.port.out.ChecksumPort;
import com.securedoc.platform.application.port.out.DomainEventPublisher;
import com.securedoc.platform.application.port.out.PdfIntakeValidationPort;
import com.securedoc.platform.application.port.out.StoragePort;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.application.service.CapabilityEnforcementService;
import com.securedoc.platform.application.service.TenantContextService;
import com.securedoc.platform.application.service.UploadDocumentService;
import com.securedoc.platform.domain.artifact.ArtifactStorageReference;
import com.securedoc.platform.domain.document.CaseLimits;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageMetadata;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.tenant.Tenant;
import com.securedoc.platform.domain.vo.Checksum;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataDocumentPackageRepository;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataDocumentRepository;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 0039: two (or many) uploads at the same moment to a case holding 49 files can't make 51. The upload locks the
 * case row ({@code SELECT … FOR UPDATE}) before it counts the files, and holds it until its transaction ends, so
 * exactly one upload adds the 50th file and every other one is refused with LV-INT-029.
 *
 * <p>Real PostgreSQL, the real JPA adapters and the real upload service; each upload runs in its own transaction, as the
 * upload endpoint does. Only storage, audit sinks and the tenant/licence checks are stand-ins. Like the task 0038
 * classes, it creates its own database ({@code lv_t_uploadlock_<random>}) on the throwaway server named by
 * {@code LEXAVAULT_TEST_PG_URL}, {@code LEXAVAULT_TEST_PG_USER} and {@code LEXAVAULT_TEST_PG_PASSWORD}, runs the master
 * changelog into it and drops it afterwards.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class UploadCaseLockPostgresTest {

    private static final int AT_ONCE = 12;

    private static String adminUrl;
    private static String user;
    private static String password;
    private static String dbName;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static UploadDocumentService uploads;
    private static Tenant tenant;
    private static AuthenticatedPrincipal principal;

    @BeforeAll
    static void start() throws Exception {
        adminUrl = System.getenv("LEXAVAULT_TEST_PG_URL");
        user = System.getenv("LEXAVAULT_TEST_PG_USER");
        password = System.getenv("LEXAVAULT_TEST_PG_PASSWORD");
        dbName = "lv_t_uploadlock_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); Statement s = admin.createStatement()) {
            s.execute("CREATE DATABASE " + dbName);
        }
        String url = adminUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + dbName + "$1");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            new Liquibase("db/changelog/db.changelog-master.xml", new ClassLoaderResourceAccessor(),
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)))
                    .update(new Contexts());
        }

        JpaSetup.url = url;
        context = new AnnotationConfigApplicationContext(JpaSetup.class);
        jdbc = new JdbcTemplate(context.getBean(DataSource.class));
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));

        tenant = Tenant.create("Upload lock tenant", "upload-lock-" + UUID.randomUUID().toString().substring(0, 8));
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO tenants (id, name, slug, status, created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
                tenant.id().value(), tenant.name(), tenant.slug(), now, now);
        principal = AuthenticatedPrincipal.of(ActorId.generate(), tenant.id(), "uploader", Set.of());

        JpaPackageRepositoryAdapter packages = new JpaPackageRepositoryAdapter(
                context.getBean(SpringDataDocumentPackageRepository.class), context.getBean(SpringDataDocumentRepository.class));
        JpaDocumentRepositoryAdapter documents = new JpaDocumentRepositoryAdapter(context.getBean(SpringDataDocumentRepository.class));
        TenantContextService tenants = mock(TenantContextService.class);
        when(tenants.resolveAndValidate(any())).thenReturn(tenant);
        StoragePort storage = mock(StoragePort.class);
        when(storage.storeForPackage(any(), any(), anyString(), any(), anyLong(), anyString()))
                .thenReturn(ArtifactStorageReference.local("t/p/d/ORIGINAL/a"));
        ChecksumPort checksum = in -> Checksum.sha256("a".repeat(64));
        PdfIntakeValidationPort onePage = in -> new PdfIntakeValidationPort.PdfInfo(1);
        uploads = new UploadDocumentService(tenants, mock(CapabilityEnforcementService.class),
                mock(TenantLicensingService.class), packages, documents, mock(ArtifactRepository.class), storage, checksum,
                mock(DomainEventPublisher.class), new AuditService(r -> { }), onePage);
        // The package is saved through the adapter as the service saves it.
        packagesForSetup = packages;
    }

    private static JpaPackageRepositoryAdapter packagesForSetup;

    @AfterAll
    static void stop() throws Exception {
        if (context != null) context.close();
        if (dbName != null) {
            try (Connection admin = DriverManager.getConnection(adminUrl, user, password); Statement s = admin.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
            }
        }
    }

    @RepeatedTest(3)
    void manyUploadsAtOnceToACaseAt49FilesAddExactlyOneAndRefuseTheRestWithLvInt029() throws Exception {
        PackageId caseId = caseWith(CaseLimits.MAX_FILES_PER_CASE - 1);

        List<Object> outcomes = uploadAtOnce(caseId, AT_ONCE);

        long added = outcomes.stream().filter(o -> o instanceof String s && s.equals("ADDED")).count();
        List<Object> refused = outcomes.stream().filter(o -> !"ADDED".equals(o)).toList();
        assertEquals(1, added, "exactly one upload adds the 50th file: " + outcomes);
        for (Object o : refused) {
            if (!(o instanceof ValidationException v) || v.lvCode() != LvErrorCode.INT_029) {
                fail("every other upload is refused with LV-INT-029, got " + o);
            }
        }
        assertEquals(CaseLimits.MAX_FILES_PER_CASE, documentsIn(caseId));
    }

    @Test
    void uploadsAtOnceToDifferentCasesDoNotWaitForEachOtherAndAllSucceed() throws Exception {
        List<PackageId> cases = new ArrayList<>();
        for (int i = 0; i < 4; i++) cases.add(caseWith(CaseLimits.MAX_FILES_PER_CASE - 1));

        ExecutorService pool = Executors.newFixedThreadPool(cases.size());
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (PackageId id : cases) futures.add(pool.submit(attempt(id, go)));
            go.countDown();
            for (Future<Object> f : futures) assertEquals("ADDED", f.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
        for (PackageId id : cases) assertEquals(CaseLimits.MAX_FILES_PER_CASE, documentsIn(id));
    }

    @Test
    void theLockIsHeldUntilTheUploadCommitsAndThenReleased() throws Exception {
        PackageId caseId = caseWith(0);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holder = pool.submit(() -> tx.executeWithoutResult(status -> {
                packagesForSetup.lockForUpdate(caseId, tenant.id());
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(locked.await(30, TimeUnit.SECONDS));
            // While another transaction holds the case, a lock attempt that may not wait finds it taken.
            Map<String, Object> busy = tx.execute(status -> jdbc.queryForMap(
                    "SELECT count(*) AS n FROM (SELECT id FROM document_packages WHERE id = ? FOR UPDATE SKIP LOCKED) t",
                    caseId.value()));
            assertEquals(0L, ((Number) busy.get("n")).longValue(), "the case row is locked");
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            Map<String, Object> free = tx.execute(status -> jdbc.queryForMap(
                    "SELECT count(*) AS n FROM (SELECT id FROM document_packages WHERE id = ? FOR UPDATE SKIP LOCKED) t",
                    caseId.value()));
            assertEquals(1L, ((Number) free.get("n")).longValue(), "released at commit");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void withoutATransactionTheLockDoesNothingAndNeverFails() {
        PackageId caseId = caseWith(0);
        packagesForSetup.lockForUpdate(caseId, tenant.id());
    }

    private static List<Object> uploadAtOnce(PackageId caseId, int count) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(count);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Object>> futures = new ArrayList<>();
            for (int i = 0; i < count; i++) futures.add(pool.submit(attempt(caseId, go)));
            go.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> f : futures) outcomes.add(f.get(120, TimeUnit.SECONDS));
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    /** One upload in its own transaction, as the upload endpoint runs it. Returns "ADDED" or the failure. */
    private static Callable<Object> attempt(PackageId caseId, CountDownLatch go) {
        return () -> {
            go.await();
            try {
                tx.executeWithoutResult(status -> uploads.execute(command(caseId), principal));
                return "ADDED";
            } catch (RuntimeException e) {
                return e;
            }
        };
    }

    private static PackageId caseWith(int files) {
        DocumentPackage pkg = DocumentPackage.create(tenant.id(), PackageType.MANUAL_UPLOAD, principal.actorId(),
                PackageMetadata.empty());
        tx.executeWithoutResult(status -> packagesForSetup.save(pkg));
        for (int i = 0; i < files; i++) {
            tx.executeWithoutResult(status -> uploads.execute(command(pkg.id()), principal));
        }
        assertEquals(files, documentsIn(pkg.id()));
        return pkg.id();
    }

    private static int documentsIn(PackageId id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM documents WHERE package_id = ?", Integer.class, id.value());
        return n == null ? 0 : n;
    }

    private static UploadDocumentCommand command(PackageId caseId) {
        byte[] bytes = "synthetic".getBytes();
        UploadContent content = new UploadContent() {
            @Override public InputStream openStream() { return new ByteArrayInputStream(bytes); }
            @Override public long contentLength() { return bytes.length; }
        };
        return UploadDocumentCommand.of(caseId, "file.pdf", "application/pdf", content,
                com.securedoc.platform.domain.identity.CorrelationId.generate());
    }

    /** The JPA part of the application, without the rest of it: the entities, the Spring Data repositories, one transaction manager. */
    @Configuration
    @EnableJpaRepositories(basePackageClasses = SpringDataDocumentPackageRepository.class)
    static class JpaSetup {
        static String url;

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(url, user, password);
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.securedoc.platform.infrastructure.persistence.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto", "none",
                    "hibernate.physical_naming_strategy",
                    "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                    "hibernate.implicit_naming_strategy",
                    "org.springframework.boot.hibernate.SpringImplicitNamingStrategy"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
    }
}

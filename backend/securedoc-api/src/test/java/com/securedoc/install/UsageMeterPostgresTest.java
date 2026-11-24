package com.securedoc.install;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.LicenceKind;
import com.documentplatform.licensing.LicenceStatus;
import com.documentplatform.licensing.jdbc.PostgresUsageLedger;
import com.securedoc.platform.api.licensing.PlatformLicence;
import com.securedoc.platform.api.licensing.PlatformUsageMeter;
import com.securedoc.platform.api.licensing.TestLicence;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0057: a document's pages are counted exactly once, on a real PostgreSQL with the real ledger: repeats, two servers at
 * once, and a caller whose transaction is undone.
 */
@EnabledIfEnvironmentVariable(named = ThrowawayDatabase.URL_VARIABLE, matches = "jdbc:postgresql:.*")
class UsageMeterPostgresTest {

    private static final TenantId ENTITY = TenantId.of(EntityUuid.fromSlotId(TestLicence.SLOT));

    private static ThrowawayDatabase db;
    private static DriverManagerDataSource ds;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactions;
    private static PostgresUsageLedger ledger;

    @BeforeAll
    static void create() throws Exception {
        db = ThrowawayDatabase.create("meter");
        PrintStream quiet = new PrintStream(new ByteArrayOutputStream());
        assertEquals(0, new SchemaStep(quiet, quiet).run(new String[] {"apply"}, db.env(SchemaStep.ENV_PREFIX)));
        ds = new DriverManagerDataSource(db.url, db.user, db.password);
        jdbc = new JdbcTemplate(ds);
        transactions = new DataSourceTransactionManager(ds);
        ledger = new PostgresUsageLedger(ds);
    }

    @AfterAll
    static void drop() throws Exception {
        if (db != null) db.close();
    }

    @BeforeEach
    void empty() {
        jdbc.update("delete from licence_metered_documents");
        jdbc.update("delete from lic_usage");
        jdbc.update("delete from lic_chain_head");
    }

    private static LicenceStatus now(LicenceKind kind) {
        return TestLicence.status(kind, LocalDate.now(ZoneOffset.UTC).plusDays(100), Instant.now());
    }

    private static PlatformUsageMeter meter(LicenceKind kind) {
        return new PlatformUsageMeter(new PlatformLicence(() -> now(kind), s -> true, new AuditService(r -> { })), ds,
                transactions);
    }

    private static long used() {
        return ledger.usage(TestLicence.SLOT, "LEXAVAULT", AllowanceType.PAGES_PER_MONTH, YearMonth.now(ZoneOffset.UTC));
    }

    @Test
    void repeatsCountOnceAndOtherDocumentsAdd() {
        PlatformUsageMeter meter = meter(LicenceKind.PRODUCTION);
        PackageId pkg = PackageId.generate();
        DocumentId first = DocumentId.generate();
        for (int i = 0; i < 5; i++) meter.documentProcessed(ENTITY, pkg, first, 7);
        meter.documentProcessed(ENTITY, pkg, DocumentId.generate(), 3);
        assertEquals(10, used());
        assertEquals(10, ledger.billableUsage(TestLicence.SLOT, "LEXAVAULT", AllowanceType.PAGES_PER_MONTH,
                YearMonth.now(ZoneOffset.UTC)));
        assertEquals(2, ledger.records().size());
        assertTrue(ledger.verifyChain().intact(), "the ledger's chain is whole");
        assertEquals(0, count("recorded_at is null"));
    }

    @Test
    void aTestLicenceCountsButNeverBills() {
        meter(LicenceKind.NON_PRODUCTION).documentProcessed(ENTITY, PackageId.generate(), DocumentId.generate(), 4);
        assertEquals(4, used());
        assertFalse(ledger.records().get(0).billable());
    }

    @Test
    void manyServersAtOnceCountEachDocumentOnce() throws Exception {
        PackageId pkg = PackageId.generate();
        List<DocumentId> documents = new ArrayList<>();
        for (int i = 0; i < 10; i++) documents.add(DocumentId.generate());
        ExecutorService servers = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<?>> running = new ArrayList<>();
            for (int s = 0; s < 8; s++) {
                PlatformUsageMeter meter = meter(LicenceKind.PRODUCTION);
                running.add(servers.submit(() -> {
                    go.await();
                    for (DocumentId document : documents) meter.documentProcessed(ENTITY, pkg, document, 2);
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : running) f.get();
        } finally {
            servers.shutdownNow();
        }
        assertEquals(20, used());
        assertEquals(10, ledger.records().size());
        assertTrue(ledger.verifyChain().intact());
    }

    @Test
    void aResultThatIsNotSavedIsNotCountedAndTheNextTryIs() {
        PlatformUsageMeter meter = meter(LicenceKind.PRODUCTION);
        PackageId pkg = PackageId.generate();
        DocumentId document = DocumentId.generate();
        TransactionTemplate step = new TransactionTemplate(transactions);
        assertThrows(IllegalStateException.class, () -> step.executeWithoutResult(status -> {
            meter.documentProcessed(ENTITY, pkg, document, 9);
            throw new IllegalStateException("the claim was lost");
        }));
        assertEquals(0, used());
        assertEquals(0, count("true"));
        assertTrue(ledger.records().isEmpty());
        step.executeWithoutResult(status -> meter.documentProcessed(ENTITY, pkg, document, 9));
        assertEquals(9, used());
        assertTrue(ledger.verifyChain().intact());
    }

    @Test
    void pagesOfAnEntityNotInTheLicenceWaitAndNeverStopTheDocument() {
        PlatformUsageMeter meter = meter(LicenceKind.PRODUCTION);
        meter.documentProcessed(TenantId.generate(), PackageId.generate(), DocumentId.generate(), 5);
        assertEquals(1, count("recorded_at is null"));
        assertTrue(ledger.records().isEmpty());
        meter.recordWaiting();
        assertEquals(1, count("recorded_at is null"), "still not in the licence");
        // The same pages, once the entity is in the licence (its row now names that entity).
        jdbc.update("update licence_metered_documents set tenant_id = ?", ENTITY.value());
        meter.recordWaiting();
        meter.recordWaiting();
        assertEquals(0, count("recorded_at is null"));
        assertEquals(5, used());
        assertEquals(1, ledger.records().size());
    }

    private static int count(String where) {
        return jdbc.queryForObject("select count(*) from licence_metered_documents where " + where, Integer.class);
    }
}

package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.operations.daily.DailyReport;
import com.securedoc.platform.application.operations.daily.DailyReport.Where;
import com.securedoc.platform.application.operations.daily.DailyReportService;
import com.securedoc.platform.application.operations.model.ReportScope;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.intake.JdbcIntakeRefusals;
import com.securedoc.platform.infrastructure.operations.JdbcDailyReport;
import com.securedoc.platform.infrastructure.operations.JpaOperationsReadAdapter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0081: every number of "Daily intake and delivery" against one seeded day, on a real, throwaway PostgreSQL. */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class JdbcDailyReportPostgresTest {
    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    /** A Tuesday. */
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);
    private static final Instant NOW = at("14:00");

    private NamedParameterJdbcTemplate jdbc;
    private final TenantId mine = TenantId.generate();
    private final TenantId other = TenantId.generate();
    private final IntegrationConnectionId push = IntegrationConnectionId.generate();
    private final IntegrationConnectionId scanner = IntegrationConnectionId.generate();
    private final IntegrationConnectionId store = IntegrationConnectionId.generate();
    private final IntegrationConnectionId split = IntegrationConnectionId.generate();

    @Test
    void everyNumberOfTheDay() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("dailyreport")) {
            seed(db);
            DailyReport r = report(mine, ReportScope.EVERYTHING);

            assertTrue(r.includesNow());
            assertEquals(new DailyReport.Received(11, 4, 13, 2, new DailyReport.Usual(9, 10)), r.received());
            assertEquals(new DailyReport.Exported(3, 4, 22, 2, 1, new DailyReport.Usual(0, 1)), r.exported(),
                    "received yesterday, exported today, counts today");
            assertEquals(new DailyReport.NotExported(4, 1, 1, 1, 1, 1, 9000L), r.notExported());
            assertEquals(new DailyReport.Attention(4, 1, 1, 1), r.attention());
            assertEquals(6, r.attention().total());
            assertEquals(Set.of(reason("LV-INT-003", Where.REFUSED_AT_INTAKE, 2), reason("LV-INT-004", Where.REFUSED_AT_INTAKE, 1),
                            reason(com.securedoc.platform.application.error.LvErrorCodes.forDetail("MISSING_FILE").orElseThrow().code(),
                                    Where.REFUSED_AT_INTAKE, 1),
                            reason("LV-PRC-008", Where.STOPPED_IN_PROCESSING, 1), reason("LV-DLV-005", Where.EXPORT_FAILED, 1),
                            reason("LV-REV-013", Where.REJECTED, 1)), Set.copyOf(r.reasons()));
            assertEquals(List.of(
                    new DailyReport.Destination(store.value().toString(), "Records store", false, false, 3, 4, 22, 1, 1),
                    new DailyReport.Destination(split.value().toString(), "Title desk", true, true, 2, 2, 4, 1, 0)), r.destinations());
            assertEquals(List.of(
                    new DailyReport.Source(scanner.value().toString(), "Branch scanners", at("09:12"), 1, 0, 0, 0, 0, 1, true),
                    new DailyReport.Source(push.value().toString(), "Loan system", at("11:50"), 9, 1, 2, 2, 6, 0, false),
                    new DailyReport.Source("type:MANUAL_UPLOAD", "Manual upload", at("10:00"), 1, 0, 1, 0, 0, 0, false),
                    new DailyReport.Source("folder:installed", "Watched folder", null, 0, 1, 1, 0, 0, 0, false)), r.sources());
            assertEquals(List.of(new DailyReport.Quiet(scanner.value().toString(), "Branch scanners", at("09:12"), 60)), r.quiet());
            assertEquals(new DailyReport.Handled(1, 1, 1, 6, 1, 1), r.handled());
            assertEquals(List.of(new DailyReport.Level("STANDARD", 1, 1), new DailyReport.Level("CONFIDENTIAL", 2, 2),
                    new DailyReport.Level("HIGHLY_CONFIDENTIAL", 1, 1)), r.levels());
            assertEquals(1, r.waitingForTrigger());

            onlyOneSourceSystem();
            onlyOneLevel();
            anotherDay();
            theOtherEntityIsNeverCounted();
            aDisabledSourceOrAClosedWindowIsNotQuiet();
            theOtherTabsHonourSourceSystemAndLevel(db);
            aRefusalIsCountedAlthoughTheRequestIsRolledBack(db);
        }
    }


    /**
     * Task 0104, owner 2026-11-28 09:55: a folder drop of files received a month ago, for a case whose files were deleted
     * 24 days ago, is refused on the existing duplicate path (the watched folder's DUPLICATE record with
     * DUPLICATE_INTAKE, LV-INT-017, as {@code WatchedFolderBatchTest} proves) and counted in the day's "Duplicates" of the
     * watched folder, not as a refusal.
     */
    @Test
    void aFolderResendAMonthAfterTheFilesWereDeletedIsCountedAsADuplicate() throws Exception {
        try (PostgresTestDatabase db = PostgresTestDatabase.migrated("dailyresend")) {
            jdbc = db.jdbc();
            TestRows.tenant(jdbc, mine);
            UUID kase = UUID.randomUUID();
            Instant monthAgo = NOW.minus(java.time.Duration.ofDays(31));
            jdbc.update("""
                    insert into document_packages (id, tenant_id, package_type, status, created_by, created_at, updated_at, dashboard_hidden_at)
                    values (:id, :t, 'FOLDER_INTAKE', 'COMPLETED', :actor, :at, :at, :purged)
                    """, new MapSqlParameterSource("id", kase).addValue("t", mine.value()).addValue("actor", UUID.randomUUID())
                    .addValue("at", Timestamp.from(monthAgo)).addValue("purged", Timestamp.from(NOW.minus(java.time.Duration.ofDays(24)))));
            jdbc.update("""
                    insert into package_lifecycles (package_id, tenant_id, successful_export_cycles, purged_at, updated_at)
                    values (:p, :t, 1, :purged, :purged)
                    """, new MapSqlParameterSource("p", kase).addValue("t", mine.value())
                    .addValue("purged", Timestamp.from(NOW.minus(java.time.Duration.ofDays(24)))));
            jdbc.update("""
                    insert into intake_observations (id, tenant_id, source_reference, status, reason_code, package_id,
                                                     first_seen_at, visible_after, updated_at)
                    values (gen_random_uuid(), :t, 'drop-month-later', 'DUPLICATE', 'DUPLICATE_INTAKE', :p, :at, :at, :at)
                    """, new MapSqlParameterSource("t", mine.value()).addValue("p", kase).addValue("at", Timestamp.from(at("09:15"))));

            DailyReport r = report(mine, ReportScope.EVERYTHING);

            DailyReport.Source folder = r.sources().stream().filter(s -> s.key().equals("folder:installed")).findFirst().orElseThrow();
            assertEquals(1, folder.duplicates(), "counted under Duplicates");
            assertEquals(0, folder.refused());
        }
    }
    private void onlyOneSourceSystem() {
        DailyReport r = report(mine, new ReportScope(push.value().toString(), "ALL"));
        assertEquals(9, r.received().cases());
        assertEquals(2, r.attention().refused());
        assertEquals(0, r.attention().stopped());
        assertEquals(1, r.sources().size());
        assertTrue(r.quiet().isEmpty());
        DailyReport manual = report(mine, new ReportScope("type:MANUAL_UPLOAD", "ALL"));
        assertEquals(new DailyReport.Handled(0, 0, 1, 0, 0, 0), manual.handled());
        assertEquals(0, manual.exported().cases());
        assertEquals(0, report(mine, new ReportScope("folder:installed", "ALL")).received().cases());
        assertThrows(IllegalArgumentException.class, () -> new ReportScope("x' or 1=1", "ALL"));
        assertThrows(IllegalArgumentException.class, () -> new ReportScope("ALL", "SECRET"));
    }

    private void onlyOneLevel() {
        DailyReport r = report(mine, new ReportScope("ALL", "HIGHLY_CONFIDENTIAL"));
        assertEquals(1, r.received().cases());
        assertEquals(1, r.exported().cases());
        assertEquals(5, r.exported().pages());
        assertFalse(r.refusalsCounted(), "a refusal has no case, so no level");
        assertEquals(0, r.attention().refused());
        assertEquals(new DailyReport.Level("HIGHLY_CONFIDENTIAL", 1, 1), r.levels().get(2));
        assertEquals(0, report(mine, new ReportScope("ALL", "CONFIDENTIAL")).attention().stopped());
        assertEquals(1, report(mine, new ReportScope("ALL", "STANDARD")).attention().stopped());
    }

    private void anotherDay() {
        DailyReport yesterday = service().report(principal(mine), day(TODAY.minusDays(1)), day(TODAY), ReportScope.EVERYTHING);
        assertFalse(yesterday.includesNow());
        assertEquals(1, yesterday.received().cases());
        assertEquals(0, yesterday.exported().cases());
        assertEquals(1, yesterday.attention().refused());
        assertTrue(yesterday.quiet().isEmpty());
        DailyReport week = service().report(principal(mine), day(TODAY.minusDays(6)), day(TODAY.plusDays(1)), ReportScope.EVERYTHING);
        assertEquals(12, week.received().cases());
        assertNull(week.received().usual(), "only for one day");
    }

    private void theOtherEntityIsNeverCounted() {
        DailyReport r = report(other, ReportScope.EVERYTHING);
        assertEquals(1, r.received().cases());
        assertEquals(1, r.attention().refused());
        assertEquals(1, r.exported().cases());
        assertEquals(0, r.notExported().cases());
        assertTrue(r.sources().stream().noneMatch(s -> s.name().equals("Loan system")));
        assertEquals(0, report(other, new ReportScope(push.value().toString(), "ALL")).received().cases());
        assertTrue(service().sources(principal(other)).stream().noneMatch(s -> s.name().equals("Branch scanners")));
        assertTrue(service().sources(principal(mine)).stream().anyMatch(s -> s.name().equals("Branch scanners")));
    }

    private void aDisabledSourceOrAClosedWindowIsNotQuiet() {
        assertTrue(new DailyReportService(new JdbcDailyReport(jdbc), t -> Optional.of(ZONE), (t, s) -> false, Clock.fixed(NOW, ZONE))
                .report(principal(mine), day(TODAY), day(TODAY.plusDays(1)), ReportScope.EVERYTHING).quiet().isEmpty());
        jdbc.update("update integration_connections set enabled = false where id = :id", new MapSqlParameterSource("id", scanner.value()));
        assertTrue(report(mine, ReportScope.EVERYTHING).quiet().isEmpty());
        jdbc.update("update integration_connections set enabled = true where id = :id", new MapSqlParameterSource("id", scanner.value()));
    }

    private void theOtherTabsHonourSourceSystemAndLevel(PostgresTestDatabase db) throws Exception {
        // platform-core's field definitions are not in this changelog; none is needed here.
        jdbc.getJdbcTemplate().execute("create table if not exists fld_field_definition"
                + " (entity_uuid uuid, product_code text, field_key text, options text[])");
        var factory = new LocalContainerEntityManagerFactoryBean();
        factory.setDataSource(jdbc.getJdbcTemplate().getDataSource());
        factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        factory.setPackagesToScan("com.securedoc.nothing");
        factory.setPersistenceUnitName("reports");
        factory.afterPropertiesSet();
        try (var entities = factory.getObject().createEntityManager()) {
            JpaOperationsReadAdapter adapter = new JpaOperationsReadAdapter();
            var field = JpaOperationsReadAdapter.class.getDeclaredField("entityManager");
            field.setAccessible(true);
            field.set(adapter, entities);
            var all = adapter.loadReports(mine, NOW, day(TODAY), day(TODAY.plusDays(1)), "ALL", "ALL", ReportScope.EVERYTHING);
            assertEquals(11, all.executive().batches());
            var one = adapter.loadReports(mine, NOW, day(TODAY), day(TODAY.plusDays(1)), "ALL", "ALL",
                    new ReportScope(push.value().toString(), "ALL"));
            assertEquals(9, one.executive().batches());
            assertEquals(3, one.executive().delivered());
            assertEquals(6, one.backlog().underOneHour() + one.backlog().oneToFourHours() + one.backlog().fourToTwentyFourHours()
                    + one.backlog().oneToThreeDays() + one.backlog().overThreeDays());
            assertEquals(0, one.processing().technicalAttention());
            assertEquals(0, one.intake().rejectedObservations());
            var high = adapter.loadReports(mine, NOW, day(TODAY), day(TODAY.plusDays(1)), "ALL", "ALL",
                    new ReportScope("ALL", "HIGHLY_CONFIDENTIAL"));
            assertEquals(1, high.executive().batches());
            assertEquals(1, high.timeSaved().casesDelivered());
            assertEquals(0, adapter.loadReports(other, NOW, day(TODAY), day(TODAY.plusDays(1)), "ALL", "ALL",
                    new ReportScope(push.value().toString(), "ALL")).executive().batches());
        } finally {
            factory.destroy();
        }
    }

    private void aRefusalIsCountedAlthoughTheRequestIsRolledBack(PostgresTestDatabase db) {
        var transactions = new DataSourceTransactionManager(jdbc.getJdbcTemplate().getDataSource());
        JdbcIntakeRefusals refusals = new JdbcIntakeRefusals(jdbc, transactions);
        TenantId tenant = TenantId.generate();
        TestRows.tenant(jdbc, tenant);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            refusals.record(tenant, "type:MANUAL_UPLOAD", LvErrorCode.INT_004);
            refusals.record(tenant, "type:MANUAL_UPLOAD", LvErrorCode.INT_017);
            status.setRollbackOnly();
        });
        assertEquals(List.of("DUPLICATE", "REFUSED"), jdbc.queryForList("select kind from intake_refusals where tenant_id = :t order by kind",
                new MapSqlParameterSource("t", tenant.value()), String.class));
        // Best effort: an entity that is not there never reaches the caller.
        refusals.record(TenantId.generate(), "type:MANUAL_UPLOAD", LvErrorCode.INT_004);
    }

    // ---------------------------------------------------------------- the day

    private void seed(PostgresTestDatabase db) {
        jdbc = db.jdbc();
        TestRows.tenant(jdbc, mine);
        TestRows.tenant(jdbc, other);
        connection(push, "Loan system", "INGESTION_SOURCE", "INBOUND");
        connection(scanner, "Branch scanners", "INGESTION_SOURCE", "INBOUND");
        connection(store, "Records store", "DOCUMENT_STORE_DELIVERY", "OUTBOUND");
        connection(split, "Title desk", "DERIVED_DOCUMENT_DELIVERY", "OUTBOUND");
        jdbc.update("insert into export_destination_states (connection_id, tenant_id, state, consecutive_failures, updated_at, worker_reduction)"
                + " values (:id, :t, 'PAUSED', 1, now(), 0)", new MapSqlParameterSource("id", split.value()).addValue("t", mine.value()));

        // The four Tuesdays before: the scanner every hour from 8 to 4; one more case and an export on one of them.
        for (int week = 1; week <= 4; week++) {
            for (int hour = 8; hour <= 16; hour++) {
                caseOf(mine, "SCANNER_INTAKE", "REJECTED", TODAY.minusWeeks(week).atTime(hour, 0).atZone(ZONE).toInstant(), scanner, null);
            }
        }
        UUID earlier = caseOf(mine, "API_SUBMISSION", "COMPLETED", TODAY.minusWeeks(1).atTime(9, 30).atZone(ZONE).toInstant(), push, null);
        exported(mine, earlier, TODAY.minusWeeks(1).atTime(11, 0).atZone(ZONE).toInstant(), store);

        UUID yesterday = caseOf(mine, "API_SUBMISSION", "COMPLETED", TODAY.minusDays(1).atTime(15, 0).atZone(ZONE).toInstant(), push, null);
        UUID source = document(mine, yesterday, 10, null);
        exported(mine, yesterday, at("08:30"), store);
        derived(yesterday, source, "1,2,3", "DELIVERED", at("08:40"));
        derived(yesterday, source, "4", "ATTENTION_REQUIRED", null);

        UUID noPerson = caseOf(mine, "API_SUBMISSION", "COMPLETED", at("09:00"), push, "URGENT");
        level(noPerson, "CONFIDENTIAL");
        document(mine, noPerson, 3, null);
        document(mine, noPerson, 4, "STANDARD");
        exported(mine, noPerson, at("10:00"), store);
        derived(noPerson, source, "2", "DELIVERED", at("10:05"));

        UUID reviewed = caseOf(mine, "API_SUBMISSION", "COMPLETED", at("09:30"), null, null);
        jdbc.update("""
                insert into external_intakes (package_id, tenant_id, integration_connection_id, channel, external_source_package_id,
                    source_revision, attempt_number, request_fingerprint, status, created_at, expires_at, updated_at)
                values (:p, :t, :c, 'PUSH_API', 'S-1', '1', 1, 'f', 'ACCEPTED_FOR_PROCESSING', now(), now(), now())
                """, new MapSqlParameterSource("p", reviewed).addValue("t", mine.value()).addValue("c", push.value()));
        document(mine, reviewed, 5, "HIGHLY_CONFIDENTIAL");
        jdbc.update("update reporting_package_facts set reviewed_flag = true where package_id = :p", new MapSqlParameterSource("p", reviewed));
        exported(mine, reviewed, at("11:00"), store);

        UUID byHand = caseOf(mine, "MANUAL_UPLOAD", "COMPLETED", at("10:00"), null, null);
        document(mine, byHand, 1, null);
        jdbc.update("""
                insert into audit_records (id, tenant_id, actor_id, action, resource_type, resource_id, outcome, correlation_id, occurred_at, created_at)
                values (gen_random_uuid(), :t, gen_random_uuid(), 'BUSINESS_MANUAL_FALLBACK_SUBMITTED', 'DocumentPackage', :p, 'SUCCESS',
                    gen_random_uuid(), now(), now())
                """, new MapSqlParameterSource("t", mine.value()).addValue("p", byHand.toString()));

        caseOf(mine, "API_SUBMISSION", "PROCESSING", at("10:30"), push, null);
        UUID stopped = caseOf(mine, "SCANNER_INTAKE", "FAILED", at("09:12"), scanner, null);
        incident(stopped, "OLD_REASON", "LV-SYS-001", at("09:15"));
        incident(stopped, "OLD_REASON", "LV-PRC-008", at("09:20"));
        UUID rejected = caseOf(mine, "API_SUBMISSION", "REJECTED", at("11:00"), push, null);
        jdbc.update("insert into package_rejections (package_id, tenant_id, reason_code, rejected_by, rejected_at)"
                + " values (:p, :t, 'LV-REV-013', gen_random_uuid(), :at)",
                new MapSqlParameterSource("p", rejected).addValue("t", mine.value()).addValue("at", Timestamp.from(at("12:00"))));

        job(caseOf(mine, "API_SUBMISSION", "APPROVED", at("11:10"), push, null), "EXPORT", "WAITING_RETRY", store, at("11:30"), null);
        job(caseOf(mine, "API_SUBMISSION", "APPROVED", at("11:20"), push, null), "SPLIT_EXPORT", "READY", split, at("12:00"), null);
        job(caseOf(mine, "API_SUBMISSION", "APPROVED", at("11:30"), push, null), "EXPORT", "WAITING_FOR_YOU", store, at("11:40"), "LV-DLV-003");
        job(caseOf(mine, "API_SUBMISSION", "APPROVED", at("11:40"), push, null), "EXPORT", "NEEDS_IT", store, at("11:45"), "LV-DLV-005");
        job(caseOf(mine, "API_SUBMISSION", "APPROVED", at("11:50"), push, "URGENT"), "EXPORT", "READY", store, at("13:00"), null);

        refusal(mine, push.value().toString(), "LV-INT-003", "REFUSED", at("08:00"));
        refusal(mine, push.value().toString(), "LV-INT-003", "REFUSED", at("08:01"));
        refusal(mine, push.value().toString(), "LV-INT-017", "DUPLICATE", at("08:02"));
        refusal(mine, "type:MANUAL_UPLOAD", "LV-INT-004", "REFUSED", at("08:03"));
        refusal(mine, push.value().toString(), "LV-INT-003", "REFUSED", at("08:00").minusSeconds(86_400));
        observation("REJECTED", "MISSING_FILE");
        observation("DUPLICATE", "DUPLICATE_INTAKE");
        observation("WAITING_FOR_TRIGGER", null);

        UUID theirs = caseOf(other, "API_SUBMISSION", "COMPLETED", at("09:00"), null, "URGENT");
        document(other, theirs, 100, "HIGHLY_CONFIDENTIAL");
        exported(other, theirs, at("09:30"), store);
        refusal(other, "type:MANUAL_UPLOAD", "LV-INT-004", "REFUSED", at("08:03"));
    }

    private DailyReportService service() {
        return new DailyReportService(new JdbcDailyReport(jdbc), t -> Optional.of(ZONE), (t, s) -> true, Clock.fixed(NOW, ZONE));
    }

    private DailyReport report(TenantId tenant, ReportScope scope) {
        return service().report(principal(tenant), day(TODAY), day(TODAY.plusDays(1)), scope);
    }

    private static AuthenticatedPrincipal principal(TenantId tenant) {
        return AuthenticatedPrincipal.of(ActorId.generate(), tenant, "reports", Set.of("REPORT_VIEWER"));
    }

    private static Instant at(String time) {
        return TODAY.atTime(LocalTime.parse(time)).atZone(ZONE).toInstant();
    }

    private static Instant day(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }

    private static DailyReport.Reason reason(String code, Where where, long cases) {
        return new DailyReport.Reason(code, where, cases);
    }

    private void connection(IntegrationConnectionId id, String name, String purpose, String direction) {
        jdbc.update("""
                insert into integration_connections (id, tenant_id, name, purpose, direction, provider_type, authentication_type,
                    timeout_seconds, retry_max_attempts, acknowledgement_mode, enabled, configuration_version, health_status, created_at, updated_at)
                values (:id, :t, :name, :purpose, :direction, 'HTTP_API', 'NONE', 5, 0, 'SUBMISSION_ACK', true, 1, 'UNKNOWN', now(), now())
                """, new MapSqlParameterSource("id", id.value()).addValue("t", mine.value()).addValue("name", name)
                .addValue("purpose", purpose).addValue("direction", direction));
    }

    private UUID caseOf(TenantId tenant, String type, String status, Instant received, IntegrationConnectionId source, String priority) {
        UUID id = PackageId.generate().value();
        jdbc.update("""
                insert into document_packages (id, tenant_id, package_type, status, created_by, created_at, updated_at)
                values (:id, :t, :type, :status, gen_random_uuid(), :at, :at)
                """, new MapSqlParameterSource("id", id).addValue("t", tenant.value()).addValue("type", type)
                .addValue("status", status).addValue("at", Timestamp.from(received)));
        if (source != null || priority != null) {
            jdbc.update("insert into case_priorities (package_id, tenant_id, priority, changed_at, source_key) values (:id, :t, :p, now(), :s)",
                    new MapSqlParameterSource("id", id).addValue("t", tenant.value()).addValue("p", priority == null ? "NORMAL" : priority)
                            .addValue("s", source == null ? null : source.value().toString()));
        }
        return id;
    }

    private void level(UUID pkg, String level) {
        jdbc.update("update document_packages set sensitivity_level = :l where id = :p", new MapSqlParameterSource("l", level).addValue("p", pkg));
    }

    private UUID document(TenantId tenant, UUID pkg, int pages, String level) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into documents (id, tenant_id, package_id, document_type, status, file_name, mime_type, size_bytes,
                    checksum_algorithm, checksum_value, page_count, created_at, updated_at, version, sensitivity_level)
                values (:id, :t, :p, 'PDF', 'PROCESSED', 'case.pdf', 'application/pdf', 3, 'SHA-256', :sum, :pages, now(), now(), 0,
                    coalesce(cast(:level as text), 'STANDARD'))
                """, new MapSqlParameterSource("id", id).addValue("t", tenant.value()).addValue("p", pkg).addValue("pages", pages)
                .addValue("sum", "a".repeat(64)).addValue("level", level));
        return id;
    }

    private void exported(TenantId tenant, UUID pkg, Instant at, IntegrationConnectionId destination) {
        UUID submission = UUID.randomUUID();
        var params = new MapSqlParameterSource("t", tenant.value()).addValue("p", pkg).addValue("at", Timestamp.from(at))
                .addValue("s", submission).addValue("c", destination.value());
        jdbc.update("insert into package_export_records (id, tenant_id, package_id, cycle_number, destination_type, exported_at, correlation_id)"
                + " values (gen_random_uuid(), :t, :p, 1, 'document-store', :at, gen_random_uuid())", params);
        jdbc.update("""
                insert into delivery_submissions (id, tenant_id, package_id, export_cycle, source_submission_id, destination_type, status,
                    attempt_count, created_at, updated_at) values (:s, :t, :p, 1, :sub, 'document-store', 'DELIVERED', 1, :at, :at)
                """, params.addValue("sub", "sub-" + submission));
        jdbc.update("""
                insert into delivery_attempts (id, submission_id, attempt_number, connection_id, status, started_at)
                values (gen_random_uuid(), :s, 1, :c, 'SUCCEEDED', :at)
                """, params);
    }

    private void derived(UUID pkg, UUID source, String pages, String status, Instant delivered) {
        jdbc.update("""
                insert into derived_deliveries (id, tenant_id, package_id, source_document_id, export_cycle, destination_connection_id,
                    destination_label, page_numbers, source_derived_document_id, status, attempt_count, retryable, created_at, updated_at, delivered_at)
                values (gen_random_uuid(), :t, :p, :d, 1, :c, 'Title', :pages, :sd, :status, 1, true, now(), now(), :at)
                """, new MapSqlParameterSource("t", mine.value()).addValue("p", pkg).addValue("d", source).addValue("c", split.value())
                .addValue("pages", pages).addValue("sd", UUID.randomUUID().toString()).addValue("status", status)
                .addValue("at", delivered == null ? null : Timestamp.from(delivered), java.sql.Types.TIMESTAMP));
    }

    private void incident(UUID pkg, String reason, String lvCode, Instant at) {
        jdbc.update("""
                insert into processing_incidents (id, tenant_id, package_id, stage, component, failure_category, reason_code, retryable,
                    attempt_count, correlation_id, status, safe_summary, safe_metadata, first_observed_at, last_observed_at)
                values (gen_random_uuid(), :t, :p, 'REDACTION', 'REDACTION', 'X', :r, false, 1, gen_random_uuid(), 'OPEN', 'x',
                    jsonb_build_object('lvCode', cast(:c as text)), :at, :at)
                """, new MapSqlParameterSource("t", mine.value()).addValue("p", pkg).addValue("r", reason).addValue("c", lvCode)
                .addValue("at", Timestamp.from(at)));
    }

    private void job(UUID pkg, String stage, String status, IntegrationConnectionId destination, Instant created, String code) {
        jdbc.update("""
                insert into pipeline_jobs (id, tenant_id, stage, package_id, destination_id, export_cycle, priority, status, attempts,
                    next_attempt_at, claim_token, last_lv_code, created_at, updated_at)
                values (gen_random_uuid(), :t, :stage, :p, :d, 1, 'NORMAL', :status, 0, now(), 0, :code, :at, :at)
                """, new MapSqlParameterSource("t", mine.value()).addValue("stage", stage).addValue("p", pkg)
                .addValue("d", destination.value()).addValue("status", status).addValue("code", code).addValue("at", Timestamp.from(created)));
    }

    private void refusal(TenantId tenant, String source, String code, String kind, Instant at) {
        jdbc.update("insert into intake_refusals (id, tenant_id, source_key, lv_code, kind, occurred_at) values (gen_random_uuid(), :t, :s, :c, :k, :at)",
                new MapSqlParameterSource("t", tenant.value()).addValue("s", source).addValue("c", code).addValue("k", kind)
                        .addValue("at", Timestamp.from(at)));
    }

    private void observation(String status, String reason) {
        jdbc.update("""
                insert into intake_observations (id, tenant_id, source_reference, status, reason_code, first_seen_at, visible_after, updated_at)
                values (gen_random_uuid(), :t, :ref, :s, :r, :at, :at, :at)
                """, new MapSqlParameterSource("t", mine.value()).addValue("ref", UUID.randomUUID().toString()).addValue("s", status)
                .addValue("r", reason).addValue("at", Timestamp.from(at("08:30"))));
    }
}

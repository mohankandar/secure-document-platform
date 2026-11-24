package com.securedoc.platform.api.licensing;

import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.LicenceStatus;
import com.documentplatform.licensing.jdbc.PostgresUsageLedger;
import com.documentplatform.licensing.usage.UsageEntry;
import com.documentplatform.licensing.usage.UsageLedger;
import com.securedoc.platform.application.licensing.UsageMeter;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.identity.LexaVaultProduct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0057: counts a document's pages once ({@code licence_metered_documents}, one row per document) and puts them into
 * the licence's usage ledger. The row and the ledger entry are one transaction, the caller's own when there is one
 * ({@link TransactionConnections}): both are there or neither is, and a second server waits on the row's key and then finds
 * it taken.
 *
 * <p>Counting never stops a document. When the licence can't be read, or the entity is not in it, the row waits without a
 * ledger entry and {@link #recordWaiting} puts it in later.
 */
public class PlatformUsageMeter implements UsageMeter {

    private static final Logger log = LoggerFactory.getLogger(PlatformUsageMeter.class);
    private static final int WAITING_PER_ROUND = 200;

    private final PlatformLicence licence;
    private final JdbcTemplate jdbc;
    private final UsageLedger ledger;
    private final TransactionTemplate transaction;
    private final Clock clock;

    public PlatformUsageMeter(PlatformLicence licence, DataSource dataSource, PlatformTransactionManager transactions) {
        this(licence, dataSource, transactions, Clock.systemUTC());
    }

    PlatformUsageMeter(PlatformLicence licence, DataSource dataSource, PlatformTransactionManager transactions,
                       Clock clock) {
        this.licence = Objects.requireNonNull(licence);
        this.jdbc = new JdbcTemplate(dataSource);
        this.ledger = new PostgresUsageLedger(new TransactionConnections(dataSource));
        this.transaction = new TransactionTemplate(transactions);
        this.clock = clock;
    }

    @Override
    public void documentProcessed(TenantId tenantId, PackageId packageId, DocumentId documentId, int pages) {
        transaction.executeWithoutResult(status -> {
            Instant now = clock.instant();
            int claimed = jdbc.update("insert into licence_metered_documents (document_id, tenant_id, package_id, pages,"
                    + " processed_at) values (?, ?, ?, ?, ?) on conflict (document_id) do nothing",
                    documentId.value(), tenantId.value(), packageId.value(), pages, Timestamp.from(now));
            if (claimed == 1) record(documentId.value(), tenantId, pages, now);
        });
    }

    private record Waiting(UUID documentId, UUID tenantId, int pages, Instant processedAt) {
    }

    /** Puts pages that still wait into the ledger. Two servers never take the same row. */
    public void recordWaiting() {
        if (licence.status().isEmpty()) return;
        List<Waiting> waiting = jdbc.query("select document_id, tenant_id, pages, processed_at from"
                + " licence_metered_documents where recorded_at is null order by processed_at limit ?",
                (rs, i) -> new Waiting(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3),
                        rs.getTimestamp(4).toInstant()), WAITING_PER_ROUND);
        for (Waiting w : waiting) {
            if (licence.slot(TenantId.of(w.tenantId())).isEmpty()) continue;
            transaction.executeWithoutResult(status -> {
                List<UUID> mine = jdbc.queryForList("select document_id from licence_metered_documents where"
                        + " document_id = ? and recorded_at is null for update skip locked", UUID.class, w.documentId());
                if (!mine.isEmpty()) record(w.documentId(), TenantId.of(w.tenantId()), w.pages(), w.processedAt());
            });
        }
    }

    private void record(UUID documentId, TenantId tenantId, int pages, Instant processedAt) {
        Optional<LicenceStatus> status = licence.status();
        Optional<EntitySlot> slot = licence.slot(tenantId);
        boolean productLicensed = status.isPresent() && status.get().licence().products().contains(LexaVaultProduct.CODE);
        if (status.isEmpty() || slot.isEmpty() || !productLicensed) {
            log.warn("LICENCE_USAGE_WAITING document={} tenant={} pages={} reason={}", documentId, tenantId, pages,
                    status.isEmpty() ? "LICENCE_NOT_READABLE" : slot.isEmpty() ? "ENTITY_NOT_IN_LICENCE"
                            : "PRODUCT_NOT_IN_LICENCE");
            return;
        }
        // Dated by the licence's own time: never before the latest time the licence has seen.
        Instant seen = status.get().clock().effective();
        // Whole microseconds, as PostgreSQL keeps them: the ledger's chain covers the time as it is read back.
        Instant at = (processedAt.isAfter(seen) ? processedAt : seen).truncatedTo(ChronoUnit.MICROS);
        ledger.append(new UsageEntry(slot.get().slotId(), LexaVaultProduct.CODE, AllowanceType.PAGES_PER_MONTH,
                YearMonth.from(at.atZone(ZoneOffset.UTC)), pages, status.get().licence().kind().usageBillable(), at));
        jdbc.update("update licence_metered_documents set recorded_at = ?, slot_id = ? where document_id = ?",
                Timestamp.from(clock.instant()), slot.get().slotId(), documentId);
    }
}

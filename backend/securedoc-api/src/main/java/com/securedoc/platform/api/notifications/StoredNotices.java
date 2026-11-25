package com.securedoc.platform.api.notifications;

import com.documentplatform.monitoring.CheckResult;
import com.documentplatform.monitoring.CheckState;
import com.documentplatform.monitoring.DependencyStatus;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.NotificationEvent;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.RecordIdentifier;
import com.securedoc.platform.application.intake.IntakeGate;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Task 0059: the other events of the agreed catalogue, read from what LexaVault already stores (cases, incidents, polling runs,
 * audit records) and from its own health checks. Counts and internal ids only.
 */
@NotificationsOn
@Component
public class StoredNotices {

    /** A failed folder scan counts as still failing this long (the folder is scanned every minute). */
    private static final String SCAN_FRESH = "interval '5 minutes'";

    private final JdbcTemplate jdbc;
    private final NotificationSettings settings;
    private final ObjectProvider<DependencyStatus> health;
    private final ObjectProvider<IntakeGate> space;
    private final TransactionTemplate transaction;
    private final String node;

    public StoredNotices(JdbcTemplate jdbc, NotificationSettings settings, ObjectProvider<DependencyStatus> health, ObjectProvider<IntakeGate> space,
                         PlatformTransactionManager transactions, @Value("${securedoc.logging.node-id:unknown}") String node) {
        this.jdbc = jdbc;
        this.settings = settings;
        this.health = health;
        this.space = space;
        this.transaction = new TransactionTemplate(transactions);
        this.node = node != null && node.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}") ? node : "server";
    }

    /** What is going on in the entity now. */
    public List<NotificationEvent> goingOn(TenantId entity) {
        List<NotificationEvent> out = new ArrayList<>();
        count(out, entity, LexaVaultEvents.REVIEW_WAITING, "/review", "select count(*) from document_packages where tenant_id = ?"
                + " and status in ('PENDING_REVIEW', 'IN_REVIEW')", entity.value());
        // The target Reports measures against (60 minutes unless the Business Admin chose another).
        count(out, entity, LexaVaultEvents.REVIEW_PAST_TARGET, "/review", "select count(*) from document_packages p"
                + " join reporting_package_facts f on f.package_id = p.id left join reporting_tenant_settings s on s.tenant_id = p.tenant_id"
                + " where p.tenant_id = ? and p.status = 'PENDING_REVIEW' and f.first_review_started_at is null"
                + " and f.first_review_created_at < now() - coalesce(s.review_wait_target_minutes, 60) * interval '1 minute'", entity.value());
        count(out, entity, LexaVaultEvents.APPROVALS_WAITING, "/", "select count(*) from document_packages where tenant_id = ?"
                + " and status = 'PENDING_FINAL_APPROVAL'", entity.value());
        count(out, entity, LexaVaultEvents.OVER_TARGET, "/support/incidents", "select count(*) from document_packages p"
                + " join reporting_package_facts f on f.package_id = p.id left join reporting_tenant_settings s on s.tenant_id = p.tenant_id"
                + " where p.tenant_id = ? and p.status in ('SUBMITTED', 'PROCESSING') and f.first_delivered_at is null"
                + " and f.received_at < now() - coalesce(s.delivery_target_minutes, 240) * interval '1 minute'", entity.value());

        long failures = settings.threshold(EntityNotifications.tenant(entity), EntityNotifications.entity(entity),
                LexaVaultEvents.REPEATED_FAILURES, LexaVaultEvents.FAILURES_IN_AN_HOUR);
        long now = number("select count(*) from processing_incidents where tenant_id = ? and stage <> 'DELIVERY'"
                + " and last_observed_at > now() - interval '1 hour'", entity.value());
        if (now >= failures) out.add(event(entity, LexaVaultEvents.REPEATED_FAILURES, "/support/incidents").count("cases", now).build());

        // Polling: the last finished run of an enabled source failed.
        for (UUID source : jdbc.queryForList("select c.id from integration_connections c join lateral (select r.status from"
                + " polling_runs r where r.integration_connection_id = c.id and r.completed_at is not null order by r.started_at desc"
                + " limit 1) last on true where c.tenant_id = ? and c.enabled and last.status = 'FAILED'", UUID.class, entity.value())) {
            out.add(event(entity, LexaVaultEvents.SOURCE_UNREACHABLE, "/admin/integrations")
                    .identifier(RecordIdentifier.of(source.toString())).build());
        }
        if (number("select count(*) from audit_records where tenant_id = ? and action = 'WATCHED_FOLDER_SCAN_TRIGGERED'"
                + " and outcome = 'FAILURE' and occurred_at > now() - " + SCAN_FRESH, entity.value()) > 0) {
            out.add(event(entity, LexaVaultEvents.SOURCE_UNREACHABLE, "/support/status")
                    .identifier(RecordIdentifier.of("watched-folder")).build());
        }

        // This server's own checks: each server reports as itself.
        IntakeGate gate = space.getIfAvailable();
        if (gate != null && gate.full()) {
            out.add(event(entity, LexaVaultEvents.INTAKE_STOPPED, "/support/status").identifier(RecordIdentifier.of(node)).build());
        }
        DependencyStatus checks = health.getIfAvailable();
        for (CheckResult r : checks == null ? List.<CheckResult>of() : checks.results()) {
            if (r.name().equals("model-server") && r.state() == CheckState.DOWN) {
                out.add(event(entity, LexaVaultEvents.AI_MODEL_DOWN, "/support/status").identifier(RecordIdentifier.of(node)).build());
            }
            if (r.code() == MonitoringCode.MON_DISK_ALMOST_FULL || r.code() == MonitoringCode.MON_DISK_FULL) {
                out.add(event(entity, LexaVaultEvents.DISK_ALMOST_FULL, "/support/status").identifier(RecordIdentifier.of(node)).build());
            }
        }
        return out;
    }

    /** Whether a condition of this server's checks is this server's to clear. */
    boolean mine(EventTypeId type, RecordIdentifier identifier) {
        return type != null && !(type.equals(LexaVaultEvents.AI_MODEL_DOWN) || type.equals(LexaVaultEvents.DISK_ALMOST_FULL)
                || type.equals(LexaVaultEvents.INTAKE_STOPPED))
                || identifier != null && identifier.value().equals(node);
    }

    /** What happened since the last round, each told once (by one server). */
    public void publishNew(TenantId entity, Notifier notifier) {
        each(entity, notifier, LexaVaultEvents.FILES_REJECTED, "INTAKE_REJECTED", "/admin/audit");
        each(entity, notifier, LexaVaultEvents.POLICY_CHANGED, "SENSITIVE_DATA_POLICY_CHANGED", "/admin/policy");
    }

    private void each(TenantId entity, Notifier notifier, EventTypeId type, String action, String link) {
        for (UUID record : jdbc.queryForList("select a.id from audit_records a join notification_mail_settings m on m.tenant_id = a.tenant_id"
                + " where a.tenant_id = ? and a.action = ? and a.outcome = 'SUCCESS' and a.occurred_at > now() - interval '1 hour'"
                + " and a.occurred_at > m.updated_at and not exists (select 1 from notification_published n where n.event_type = ?"
                + " and n.item_id = a.id) order by a.occurred_at limit 200", UUID.class, entity.value(), action, type.value())) {
            once(entity, type, record, () -> notifier.publish(event(entity, type, link).build()));
        }
    }

    /** Claims the item and publishes in one transaction: told once, also with several servers. */
    void once(TenantId entity, EventTypeId type, UUID item, Runnable publish) {
        transaction.executeWithoutResult(status -> {
            if (jdbc.update("insert into notification_published (event_type, item_id, tenant_id, published_at) values (?, ?, ?, now())"
                    + " on conflict do nothing", type.value(), item, entity.value()) == 1) publish.run();
        });
    }

    void removeOld() {
        jdbc.update("delete from notification_published where published_at < now() - interval '3 days'");
    }

    private void count(List<NotificationEvent> out, TenantId entity, EventTypeId type, String link, String sql, Object... args) {
        long cases = number(sql, args);
        if (cases > 0) out.add(event(entity, type, link).count("cases", cases).build());
    }

    private long number(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    private static NotificationEvent.Builder event(TenantId entity, EventTypeId type, String link) {
        return NotificationEvent.builder(type, EntityNotifications.tenant(entity)).entity(EntityNotifications.entity(entity))
                .link(AppLink.of(link));
    }
}

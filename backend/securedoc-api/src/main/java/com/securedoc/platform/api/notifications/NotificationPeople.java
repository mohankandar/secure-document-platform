package com.securedoc.platform.api.notifications;

import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.Recipient;
import com.documentplatform.notifications.RecipientResolver;
import com.documentplatform.notifications.RoleId;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Task 0059: who holds a role in an entity, for notifications. Roles come from the login system's groups and are known only
 * when a person signs in, so that is when they are written down: the person's id, the entity, the roles this sign-in gave there
 * and the e-mail address the login system gave. At each sign-in the person's rows are replaced, so entities they no longer
 * belong to are gone. A row not renewed within {@code keep} counts for nothing and is removed. Nobody is ever guessed, and
 * nothing here gives access: every decision is checked against the roles of the request it is made in.
 */
public class NotificationPeople implements RecipientResolver {

    private static final Logger log = LoggerFactory.getLogger(NotificationPeople.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Duration keep;
    private final Clock clock;

    public NotificationPeople(JdbcTemplate jdbc, TransactionTemplate transaction, Duration keep, Clock clock) {
        this.jdbc = Objects.requireNonNull(jdbc);
        this.transaction = Objects.requireNonNull(transaction);
        this.keep = Objects.requireNonNull(keep);
        this.clock = Objects.requireNonNull(clock);
    }

    /** One verified sign-in. Without a usable e-mail address the person is in no list. Never stops a sign-in. */
    public void signedIn(ActorId actor, String verifiedEmail, Map<TenantId, Set<String>> memberships) {
        try {
            transaction.executeWithoutResult(status -> {
                // Two screens opened at once: one after the other.
                jdbc.queryForList("select pg_advisory_xact_lock(hashtext(?))", "notification-person " + actor.value());
                jdbc.update("delete from notification_people where actor_id = ?", actor.value());
                if (!usable(verifiedEmail)) return;
                memberships.forEach((entity, roles) -> {
                    if (roles.isEmpty()) return;
                    jdbc.update("insert into notification_people (actor_id, tenant_id, email, roles, seen_at)"
                            + " select ?, t.id, ?, ?, ? from tenants t where t.id = ?", actor.value(), verifiedEmail,
                            "," + String.join(",", new TreeSet<>(roles)) + ",", Timestamp.from(clock.instant()),
                            entity.value());
                });
            });
        } catch (RuntimeException notRecorded) {
            log.warn("NOTIFICATION_PERSON_NOT_RECORDED type={}", notRecorded.getClass().getSimpleName());
        }
    }

    static boolean usable(String email) {
        if (email == null || email.length() > 320 || email.chars().anyMatch(c -> ",;<>\"".indexOf(c) >= 0)) return false;
        try {
            new Recipient("x", email);
            return true;
        } catch (RuntimeException notAnAddress) {
            return false;
        }
    }

    @Override
    public Set<Recipient> resolve(com.documentplatform.notifications.TenantId tenant, EntityId entity, RoleId role) {
        Optional<UUID> id = EntityNotifications.uuid(tenant);
        // The entity is the tenant itself; anything else is another entity's, or the customer's: nobody.
        if (id.isEmpty() || entity != null && !entity.value().equals(tenant.value())) return Set.of();
        return new LinkedHashSet<>(jdbc.query("select p.actor_id, p.email from notification_people p"
                + " join tenants t on t.id = p.tenant_id and t.status = 'ACTIVE'"
                + " where p.tenant_id = ? and position(? in p.roles) > 0 and p.seen_at > ? order by p.actor_id",
                (rs, i) -> new Recipient(rs.getString(1), rs.getString(2)), id.get(), "," + role.value() + ",", oldest()));
    }

    @Override
    public Optional<Recipient> person(com.documentplatform.notifications.TenantId tenant, String userId) {
        Optional<UUID> id = EntityNotifications.uuid(tenant);
        Optional<UUID> actor = EntityNotifications.uuid(userId);
        if (id.isEmpty() || actor.isEmpty()) return Optional.empty();
        List<Recipient> found = jdbc.query("select p.actor_id, p.email from notification_people p"
                + " join tenants t on t.id = p.tenant_id and t.status = 'ACTIVE'"
                + " where p.tenant_id = ? and p.actor_id = ? and p.seen_at > ?",
                (rs, i) -> new Recipient(rs.getString(1), rs.getString(2)), id.get(), actor.get(), oldest());
        return found.stream().findFirst();
    }

    /** People per role in the entity, for the settings screen: a count only. */
    public int count(TenantId entity, RoleId role) {
        return resolve(EntityNotifications.tenant(entity), null, role).size();
    }

    public int purge() {
        return jdbc.update("delete from notification_people where seen_at <= ?", oldest());
    }

    private Timestamp oldest() {
        return Timestamp.from(clock.instant().minus(keep));
    }
}

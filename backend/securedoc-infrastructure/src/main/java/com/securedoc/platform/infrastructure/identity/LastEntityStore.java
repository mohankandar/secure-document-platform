package com.securedoc.platform.infrastructure.identity;

import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0058: the entity a person worked in last ({@code person_last_entity}). It only says where the next sign-in lands; it
 * never gives access.
 */
@Repository
public class LastEntityStore {

    private final JdbcTemplate jdbc;

    public LastEntityStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    public Optional<TenantId> find(ActorId person) {
        return jdbc.queryForList("select tenant_id from person_last_entity where actor_id = ?", UUID.class, person.value())
                .stream().findFirst().map(TenantId::of);
    }

    /** Stores the entity. @return the entity before, when it was another one or none (then something changed) */
    public Change save(ActorId person, TenantId entity, Instant now) {
        // Locked, so two changes of one person are made one after the other, each with the right entity before it.
        Optional<TenantId> before = jdbc.queryForList("select tenant_id from person_last_entity where actor_id = ? for update",
                UUID.class, person.value()).stream().findFirst().map(TenantId::of);
        if (before.isPresent() && before.get().equals(entity)) return new Change(false, before);
        jdbc.update("insert into person_last_entity (actor_id, tenant_id, chosen_at) values (?, ?, ?) on conflict (actor_id)"
                + " do update set tenant_id = excluded.tenant_id, chosen_at = excluded.chosen_at",
                person.value(), entity.value(), Timestamp.from(now));
        return new Change(true, before);
    }

    public record Change(boolean changed, Optional<TenantId> before) {
    }
}

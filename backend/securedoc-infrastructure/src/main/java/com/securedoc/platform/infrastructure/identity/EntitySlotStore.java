package com.securedoc.platform.infrastructure.identity;

import com.documentplatform.identity.jdbc.PostgresEntityStore;
import com.documentplatform.identity.jdbc.PostgresGroupGrantStore;
import com.documentplatform.identity.store.EntityRecord;
import com.documentplatform.identity.store.EntityStatus;
import com.documentplatform.identity.store.EntityStore;
import com.documentplatform.identity.store.EntityTimeZone;
import com.documentplatform.identity.store.EntityTimeZones;
import com.securedoc.platform.application.port.out.EntityTimeZonePort;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.tenant.Capability;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.net.URI;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Task 0058: the customer's entity slots ({@code idn_entity}) and LexaVault's own entity rows ({@code tenants}, id = the
 * entity UUID of the slot). Every call that changes something uses the connection of the transaction that is running, so a
 * slot's change, the entity row and the audit record are saved or undone together.
 */
@Repository
public class EntitySlotStore implements EntityTimeZonePort {

    /** What a newly activated entity may do: the V1 capabilities. */
    private static final List<Capability> CAPABILITIES = List.of(Capability.DOCUMENT_UPLOAD, Capability.OCR_PROCESSING,
            Capability.SENSITIVE_DATA_DETECTION, Capability.REDACTION, Capability.HUMAN_REVIEW, Capability.VAULT,
            Capability.AUDIT);

    private static final String BUSINESS_ADMIN = "BUSINESS_ADMIN";

    private final JdbcTemplate jdbc;

    public EntitySlotStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Platform-identity's store on the running transaction's connection. */
    public <T> T withStore(Function<EntityStore, T> work) {
        return jdbc.execute((ConnectionCallback<T>) c -> work.apply(PostgresEntityStore.onConnection(c)));
    }

    /** The customer the entity belongs to; empty when the entity is not a slot of this installation. */
    public Optional<String> customerOf(TenantId entity) {
        return withStore(s -> s.entity(entity.value())).map(EntityRecord::tenantId);
    }

    public List<EntityRecord> slots(String customer) {
        return withStore(s -> s.entities(customer));
    }

    /** Locks the customer, so two changes of its entities are made one after the other. */
    public void lockCustomer(String customer) {
        jdbc.queryForList("select 1 from idn_customer where tenant_id = ? for update", customer);
    }

    /**
     * The customer's slots in which one of the groups has the Business Admin role by that entity's own grants, also slots
     * nobody has activated yet (setup loads their grants before). Group names are compared exactly. The caller gives the
     * issuer and the groups of a verified sign-in only.
     */
    public Set<String> slotsAdministeredBy(String customer, URI issuer, Collection<String> verifiedGroups) {
        Set<String> out = new LinkedHashSet<>();
        if (issuer == null || verifiedGroups == null || verifiedGroups.isEmpty()) return out;
        new PostgresGroupGrantStore(Objects.requireNonNull(jdbc.getDataSource()))
                .grantsOfCustomer(customer, LexaVaultProduct.CODE, issuer).forEach((slot, grants) -> {
                    if (grants.stream().anyMatch(g -> BUSINESS_ADMIN.equals(LexaVaultProduct.grantRole(g.role()))
                            && verifiedGroups.contains(g.group()))) {
                        out.add(slot);
                    }
                });
        return out;
    }

    /**
     * Brings LexaVault's own entity row in line with the slot: made on the first activation (with the V1 capabilities),
     * then named and switched as the slot is. A slot that was never activated has no row, and none is made for it.
     *
     * @return whether the row was made now
     */
    public boolean mirror(EntityRecord slot, Instant now) {
        if (slot.activatedAt() == null) return false;
        UUID id = slot.entityUuid();
        Timestamp at = Timestamp.from(now);
        String status = slot.status() == EntityStatus.ACTIVE ? "ACTIVE" : "SUSPENDED";
        int made = jdbc.update("insert into tenants (id, name, slug, status, created_at, updated_at, version)"
                + " values (?, ?, ?, ?, ?, ?, 0) on conflict (id) do nothing",
                id, slot.displayName(), freeSlug(slot.slotId(), id), status, at, at);
        if (made == 0) {
            jdbc.update("update tenants set name = ?, status = ?, updated_at = ?, version = version + 1"
                    + " where id = ? and (name <> ? or status <> ?)",
                    slot.displayName(), status, at, id, slot.displayName(), status);
            return false;
        }
        for (Capability capability : CAPABILITIES) {
            jdbc.update("insert into tenant_capabilities (id, tenant_id, capability, status, created_at, updated_at)"
                    + " values (?, ?, ?, 'ENABLED', ?, ?)", UUID.randomUUID(), id, capability.name(), at, at);
        }
        return true;
    }

    private String freeSlug(String slotId, UUID id) {
        String slug = slotId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]", "-");
        Integer taken = jdbc.queryForObject("select count(*) from tenants where slug = ? and id <> ?", Integer.class, slug, id);
        return taken == null || taken == 0 ? slug : id.toString();
    }

    /** Whether LexaVault's own row of the entity exists (so it has an audit log of its own). */
    public boolean hasEntityRow(UUID entity) {
        Integer n = jdbc.queryForObject("select count(*) from tenants where id = ?", Integer.class, entity);
        return n != null && n > 0;
    }

    /**
     * Whether the entity is active now: LexaVault's own row is ACTIVE, and so is its slot. Read from the database at every
     * question. (An entity of an installation made before the slots has none; its own row decides.)
     */
    public boolean active(TenantId entity) {
        List<Boolean> answer = jdbc.queryForList("select t.status = 'ACTIVE' and coalesce(e.status, 'ACTIVE') = 'ACTIVE'"
                + " from tenants t left join idn_entity e on e.entity_uuid = t.id where t.id = ?", Boolean.class,
                entity.value());
        return answer.size() == 1 && Boolean.TRUE.equals(answer.get(0));
    }

    /** The entity's zones: its own and its customer's. Empty when the entity is not a slot of this installation. */
    public Optional<EntityTimeZone> zones(TenantId entity) {
        return withStore(s -> new EntityTimeZones(s).zone(entity.value()));
    }

    @Override
    public Optional<ZoneId> effective(TenantId entity) {
        return entity == null ? Optional.empty() : zones(entity).flatMap(EntityTimeZone::effective);
    }
}

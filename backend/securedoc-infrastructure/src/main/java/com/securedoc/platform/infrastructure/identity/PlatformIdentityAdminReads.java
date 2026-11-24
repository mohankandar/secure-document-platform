package com.securedoc.platform.infrastructure.identity;

import com.documentplatform.identity.jdbc.PostgresGrantHistory;
import com.documentplatform.identity.jdbc.PostgresLoginConnectionStore;
import com.documentplatform.identity.store.GrantChange;
import com.documentplatform.identity.store.LoginConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The read-only identity views of LexaVault's admin screens (task 0041), over platform-identity's {@code idn_} tables:
 * <ul>
 *   <li>the entity's customer's ACTIVE LexaVault login connection (Admin, Sign-in). They are set
 *       through setup and changed in Admin, Sign-in (task 0058). The secret reference is never read out of here;</li>
 *   <li>the entity's grant history ({@code idn_grant_history}, identity schema V3), LexaVault's product only (Audit).</li>
 * </ul>
 * The entity is LexaVault's tenant id, which is the entity UUID of its slot ({@code EntityUuid.fromSlotId}). An entity
 * with no {@code idn_entity} row (such as the fixed pre-0037 demo tenant) has no connection and no history here.
 */
@Repository
public class PlatformIdentityAdminReads {

    private final JdbcTemplate jdbc;

    public PlatformIdentityAdminReads(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** The customer this entity belongs to; empty when it is not a slot of this installation. */
    public Optional<String> customerOf(UUID entityUuid) {
        List<String> customers = jdbc.queryForList(
                "select tenant_id from idn_entity where entity_uuid = ?", String.class, entityUuid);
        return customers.size() == 1 ? Optional.of(customers.get(0)) : Optional.empty();
    }

    /** The ACTIVE LexaVault login connection of the customer this entity belongs to, if there is one. */
    public Optional<LoginConnection> activeConnection(UUID entityUuid) {
        Objects.requireNonNull(entityUuid, "entityUuid");
        return customerOf(entityUuid).flatMap(customer ->
                new PostgresLoginConnectionStore(jdbc.getDataSource()).active(customer, LexaVaultProduct.CODE));
    }

    /** The entity's LexaVault grant changes in [from, to), oldest first. */
    public List<GrantChange> grantHistory(UUID entityUuid, Instant from, Instant to) {
        return new PostgresGrantHistory(jdbc.getDataSource()).forEntity(entityUuid, from, to).stream()
                .filter(c -> LexaVaultProduct.CODE.equals(c.productCode()))
                .toList();
    }
}

package com.securedoc.install;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.IdentityConnectionStatus;
import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.jdbc.PostgresGroupGrantStore;
import com.documentplatform.identity.jdbc.PostgresLoginConnectionStore;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.LoginConnection;
import com.securedoc.platform.infrastructure.identity.LexaVaultProduct;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

/** Test rows for the idn_ and old identity tables (task 0037). Test values only. */
final class IdnRows {

    private final ThrowawayDatabase db;
    final DataSource ds;

    IdnRows(ThrowawayDatabase db) {
        this.db = db;
        this.ds = new DriverManagerDataSource(db.url, db.user, db.password);
    }

    void customer(String tenantId) throws SQLException {
        exec("insert into idn_customer (tenant_id, name) values (?, ?)", tenantId, "Customer " + tenantId);
    }

    /** A slot; ACTIVE and SUSPENDED slots get a name and an activation, as the table requires. */
    UUID slot(String tenantId, String slotId, String status) throws SQLException {
        UUID entity = EntityUuid.fromSlotId(slotId);
        boolean activated = status.equals("ACTIVE") || status.equals("SUSPENDED");
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement("insert into idn_entity (slot_id, "
                + "entity_uuid, tenant_id, status, display_name, activated_by, activated_at) values (?, ?, ?, ?, ?, ?, "
                + (activated ? "now()" : "null") + ")")) {
            ps.setString(1, slotId);
            ps.setObject(2, entity);
            ps.setString(3, tenantId);
            ps.setString(4, status);
            ps.setString(5, activated ? "Entity " + slotId : null);
            ps.setString(6, activated ? "test" : null);
            ps.executeUpdate();
        }
        return entity;
    }

    /** LexaVault's own entity row. */
    void lexaTenant(UUID id, String status) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement("insert into tenants (id, name, slug, "
                + "status, created_at, updated_at) values (?, ?, ?, ?, now(), now())")) {
            ps.setObject(1, id);
            ps.setString(2, "Entity " + id);
            ps.setString(3, "e-" + id);
            ps.setString(4, status);
            ps.executeUpdate();
        }
    }

    LoginConnection connection(String tenantId, String providerId, URI issuer, String audience) {
        return new PostgresLoginConnectionStore(ds).insert(new LoginConnection(UUID.randomUUID(), tenantId,
                LexaVaultProduct.CODE, providerId, IdentityProviderType.OIDC, issuer, "web-client", audience, null,
                IdentityConnectionStatus.ACTIVE, 1), ChangeSource.SETUP, "test");
    }

    void grants(String slotId, URI issuer, Grant... grants) {
        new PostgresGroupGrantStore(ds).add(slotId, LexaVaultProduct.CODE, issuer, List.of(grants));
    }

    /** A grant row written by hand, as a damaged or old row would be. */
    void grantRow(String slotId, URI issuer, String group, String role) throws SQLException {
        exec("insert into idn_group_grant (slot_id, product_code, issuer_uri, external_group, role) values (?, ?, ?, ?, ?)",
                slotId, LexaVaultProduct.CODE, issuer.toString(), group, role);
    }

    void exec(String sql, Object... args) throws SQLException {
        try (Connection c = db.connect(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            ps.executeUpdate();
        }
    }
}

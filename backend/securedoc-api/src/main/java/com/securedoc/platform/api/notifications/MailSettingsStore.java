package com.securedoc.platform.api.notifications;

import com.documentplatform.notifications.email.MailSecurity;
import com.documentplatform.notifications.email.MicrosoftOAuth;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Task 0059: {@code notification_mail_settings}, one row per entity. */
@Repository
public class MailSettingsStore {

    private final JdbcTemplate jdbc;

    public MailSettingsStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<MailSettings> find(TenantId entity) {
        return jdbc.query("select * from notification_mail_settings where tenant_id = ?", (rs, i) -> new MailSettings(
                TenantId.of(rs.getObject("tenant_id", UUID.class)), rs.getBoolean("enabled"),
                MailSettings.Mode.valueOf(rs.getString("mode")), rs.getString("host"), rs.getObject("port", Integer.class),
                rs.getString("security") == null ? null : MailSecurity.valueOf(rs.getString("security")),
                rs.getString("username"), rs.getString("from_address"), rs.getString("from_name"),
                rs.getString("directory_id"), rs.getString("client_id"),
                rs.getString("credential_type") == null ? null
                        : MicrosoftOAuth.CredentialType.valueOf(rs.getString("credential_type")),
                rs.getString("secret_reference")), entity.value()).stream().findFirst();
    }

    /** E-mail is on and the entity is active. */
    public boolean enabled(TenantId entity) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from notification_mail_settings m"
                + " join tenants t on t.id = m.tenant_id where m.tenant_id = ? and m.enabled and t.status = 'ACTIVE')",
                Boolean.class, entity.value()));
    }

    /** The active entities whose e-mail is on. */
    public List<TenantId> connected() {
        return jdbc.query("select m.tenant_id from notification_mail_settings m join tenants t on t.id = m.tenant_id"
                + " where m.enabled and t.status = 'ACTIVE' order by m.tenant_id",
                (rs, i) -> TenantId.of(rs.getObject(1, UUID.class)));
    }

    public void save(MailSettings s, ActorId by, Instant now) {
        jdbc.update("insert into notification_mail_settings (tenant_id, enabled, mode, host, port, security, username,"
                + " from_address, from_name, directory_id, client_id, credential_type, secret_reference, updated_by, updated_at)"
                + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) on conflict (tenant_id) do update set"
                + " enabled = excluded.enabled, mode = excluded.mode, host = excluded.host, port = excluded.port,"
                + " security = excluded.security, username = excluded.username, from_address = excluded.from_address,"
                + " from_name = excluded.from_name, directory_id = excluded.directory_id, client_id = excluded.client_id,"
                + " credential_type = excluded.credential_type, secret_reference = excluded.secret_reference,"
                + " updated_by = excluded.updated_by, updated_at = excluded.updated_at",
                s.entity().value(), s.enabled(), s.mode().name(), s.host(), s.port(),
                s.security() == null ? null : s.security().name(), s.username(), s.fromAddress(), s.fromName(),
                s.directoryId(), s.clientId(), s.credentialType() == null ? null : s.credentialType().name(),
                s.secretReference(), by.value(), Timestamp.from(now));
    }
}

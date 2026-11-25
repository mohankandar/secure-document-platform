package com.securedoc.platform.infrastructure.storage;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.envelope.EnvelopeInfo;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0060: how each stored file is protected (table {@code stored_files}, migration 065). Written in the same transaction
 * as the file's artifact record where there is one. The record decides whether a file is opened as plain or decrypted; a
 * file without a record is not opened.
 */
@Repository
public class StoredFileRecords {

    /** @param envelope null: the file is plain */
    public record StoredFile(TenantId tenantId, EnvelopeInfo envelope) {
        public boolean encrypted() {
            return envelope != null;
        }
    }

    private final JdbcTemplate jdbc;

    public StoredFileRecords(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredFile> find(String provider, String key) {
        return jdbc.query("""
                select tenant_id, protection, crypto_format, crypto_version, key_service, customer_id, wrapped_key,
                       key_version, encrypted_bytes, ciphertext_sha256
                  from stored_files where storage_provider = ? and storage_key = ?
                """, (rs, n) -> new StoredFile(TenantId.of(rs.getObject("tenant_id", UUID.class)),
                "PLAIN".equals(rs.getString("protection")) ? null : new EnvelopeInfo(rs.getString("crypto_format"),
                        rs.getInt("crypto_version"), rs.getString("key_service"),
                        CustomerId.of(rs.getObject("customer_id", UUID.class)), rs.getString("wrapped_key"),
                        rs.getInt("key_version"), rs.getLong("encrypted_bytes"), rs.getString("ciphertext_sha256"))),
                provider, key).stream().findFirst();
    }

    /** @param envelope null: the file was stored plain (the development-only mode) */
    public void save(String provider, String key, TenantId tenantId, EnvelopeInfo envelope) {
        jdbc.update("""
                insert into stored_files (storage_provider, storage_key, tenant_id, protection, crypto_format, crypto_version,
                        key_service, customer_id, wrapped_key, key_version, encrypted_bytes, ciphertext_sha256, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                on conflict (storage_provider, storage_key) do update set
                    tenant_id = excluded.tenant_id, protection = excluded.protection,
                    crypto_format = excluded.crypto_format, crypto_version = excluded.crypto_version,
                    key_service = excluded.key_service, customer_id = excluded.customer_id,
                    wrapped_key = excluded.wrapped_key, key_version = excluded.key_version,
                    encrypted_bytes = excluded.encrypted_bytes, ciphertext_sha256 = excluded.ciphertext_sha256,
                    created_at = excluded.created_at
                """, provider, key, tenantId.value(), envelope == null ? "PLAIN" : "ENCRYPTED",
                envelope == null ? null : envelope.format(), envelope == null ? null : envelope.formatVersion(),
                envelope == null ? null : envelope.keyService(), envelope == null ? null : envelope.customer().value(),
                envelope == null ? null : envelope.wrappedKey(), envelope == null ? null : envelope.keyVersion(),
                envelope == null ? null : envelope.encryptedBytes(),
                envelope == null ? null : envelope.ciphertextSha256(), Timestamp.from(Instant.now()));
    }

    /** The file is gone: its record, with the wrapped key, goes too. */
    public void remove(String provider, String key) {
        jdbc.update("delete from stored_files where storage_provider = ? and storage_key = ?", provider, key);
    }
}

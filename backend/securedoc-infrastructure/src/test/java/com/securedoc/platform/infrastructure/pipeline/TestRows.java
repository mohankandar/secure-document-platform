package com.securedoc.platform.infrastructure.pipeline;

import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.UUID;

/** Minimal rows the job tables point at (throwaway database only). */
final class TestRows {
    private TestRows() { }

    static void tenant(NamedParameterJdbcTemplate jdbc, TenantId tenant) {
        jdbc.update("""
                INSERT INTO tenants (id, name, slug, status, created_at, updated_at)
                VALUES (:id, 'Test 0027', :slug, 'ACTIVE', now(), now())
                """, new MapSqlParameterSource("id", tenant.value()).addValue("slug", "t-" + tenant.value()));
    }

    static void connection(NamedParameterJdbcTemplate jdbc, TenantId tenant, IntegrationConnectionId id) {
        jdbc.update("""
                INSERT INTO integration_connections
                    (id, tenant_id, name, purpose, direction, provider_type, authentication_type, timeout_seconds,
                     retry_max_attempts, acknowledgement_mode, enabled, configuration_version, health_status,
                     created_at, updated_at)
                VALUES (:id, :tenant, :name, 'SDV_DELIVERY', 'OUTBOUND', 'HTTP_API', 'NONE', 5, 0,
                        'SDV_SUBMISSION_ACK', true, 1, 'UNKNOWN', now(), now())
                """, new MapSqlParameterSource("id", id.value()).addValue("tenant", tenant.value())
                .addValue("name", "Destination " + id.value()));
    }

    /** Task 0028: a split (routing) destination; a tenant may have only one enabled SDV connection. */
    static void splitConnection(NamedParameterJdbcTemplate jdbc, TenantId tenant, IntegrationConnectionId id) {
        jdbc.update("""
                INSERT INTO integration_connections
                    (id, tenant_id, name, purpose, direction, provider_type, authentication_type, timeout_seconds,
                     retry_max_attempts, acknowledgement_mode, enabled, configuration_version, health_status,
                     created_at, updated_at)
                VALUES (:id, :tenant, :name, 'DERIVED_DOCUMENT_DELIVERY', 'OUTBOUND', 'HTTP_API', 'NONE', 5, 0,
                        'SDV_SUBMISSION_ACK', true, 1, 'UNKNOWN', now(), now())
                """, new MapSqlParameterSource("id", id.value()).addValue("tenant", tenant.value())
                .addValue("name", "Split " + id.value()));
    }

    static void packageRow(NamedParameterJdbcTemplate jdbc, TenantId tenant, PackageId pkg) {
        jdbc.update("""
                INSERT INTO document_packages (id, tenant_id, package_type, status, created_by, created_at, updated_at)
                VALUES (:id, :tenant, 'MANUAL_UPLOAD', 'PROCESSING', :actor, now(), now())
                """, new MapSqlParameterSource("id", pkg.value()).addValue("tenant", tenant.value())
                .addValue("actor", UUID.randomUUID()));
    }
}

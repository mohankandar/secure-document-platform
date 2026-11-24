package com.securedoc.install;

/** The connection table migration 062 drops (task 0058), as it was, for tests of databases made before it. */
final class OldConnectionTable {

    static final String CREATE = """
            create table enterprise_identity_connections (
                connection_id uuid primary key, tenant_id uuid not null references tenants(id),
                provider_id varchar(100) not null, provider_type varchar(20) not null, display_name varchar(200) not null,
                issuer_uri varchar(1000) not null, browser_client_id varchar(300), api_audience varchar(300) not null,
                secret_reference varchar(500), status varchar(20) not null, configuration_version bigint not null,
                created_at timestamptz not null, updated_at timestamptz not null)
            """;

    static final String INSERT_ACTIVE = """
            insert into enterprise_identity_connections (connection_id, tenant_id, provider_id, provider_type, display_name,
                issuer_uri, browser_client_id, api_audience, status, configuration_version, created_at, updated_at)
            values (?, ?, 'old-okta', 'OIDC', 'Okta', 'https://login.old.example', 'old-web', 'api://old', 'ACTIVE', 1, now(), now())
            """;

    private OldConnectionTable() {
    }
}

-- PHASE 1 TEST DATA - throwaway database lexavault_phase1 only.
-- Creates two ACTIVE entities (tenants) that share one OIDC issuer and their login-system
-- records. Idempotent: safe to run again. The group-to-role grants are not inserted here: since
-- task 0014, Start-Phase1.ps1 loads db/phase1-role-mapping.csv through the load-role-mappings command.
-- Run by Start-Phase1.ps1 after the backend has applied the schema (001-036 + 037).
-- Contains no passwords or tokens.

\set ON_ERROR_STOP on

-- Refuse to run anywhere except the throwaway database, and only once 037 has removed the
-- cross-tenant ACTIVE issuer index (otherwise the second ACTIVE connection cannot exist).
do $phase1$
begin
  if current_database() <> 'lexavault_phase1' then
    raise exception 'Phase 1 seed refused: database is %, expected lexavault_phase1', current_database();
  end if;
  if to_regclass('public.uq_eic_active_binding') is not null then
    raise exception 'Phase 1 seed refused: uq_eic_active_binding still exists (migration 037 not applied)';
  end if;
  if to_regclass('public.uq_eic_active_per_tenant') is null then
    raise exception 'Phase 1 seed refused: uq_eic_active_per_tenant (034) is missing';
  end if;
end $phase1$;

begin;

-- Entities
insert into tenants (id, name, slug, status, created_at, updated_at, version) values
  ('a1a1a1a1-0000-4000-8000-00000000000a', 'Phase1 Entity A', 'phase1-entity-a', 'ACTIVE', now(), now(), 0),
  ('b2b2b2b2-0000-4000-8000-00000000000b', 'Phase1 Entity B', 'phase1-entity-b', 'ACTIVE', now(), now(), 0)
on conflict (id) do nothing;

-- Same capability set LocalDemoBootstrap gives the demo tenant
insert into tenant_capabilities (id, tenant_id, capability, status, created_at, updated_at)
select gen_random_uuid(), t.id, c.capability, 'ENABLED', now(), now()
from (values ('a1a1a1a1-0000-4000-8000-00000000000a'::uuid),
             ('b2b2b2b2-0000-4000-8000-00000000000b'::uuid)) as t(id)
cross join (values ('DOCUMENT_UPLOAD'), ('OCR_PROCESSING'), ('SENSITIVE_DATA_DETECTION'),
                   ('REDACTION'), ('HUMAN_REVIEW'), ('VAULT'), ('AUDIT')) as c(capability)
on conflict on constraint uq_tenant_capability do nothing;

-- One ACTIVE login-system record per entity, same provider / issuer / audience
insert into enterprise_identity_connections
  (connection_id, tenant_id, provider_id, provider_type, display_name, issuer_uri,
   browser_client_id, api_audience, secret_reference, status, configuration_version,
   created_at, updated_at)
values
  ('a1a1a1a1-0000-4000-8000-0000000c0001', 'a1a1a1a1-0000-4000-8000-00000000000a',
   'phase1-oidc', 'OIDC', 'Phase 1 shared Keycloak (Entity A)',
   'http://localhost:18181/realms/lexavault-phase1', 'lexavault-phase1-web', 'lexavault-phase1-api',
   null, 'ACTIVE', 1, now(), now()),
  ('b2b2b2b2-0000-4000-8000-0000000c0002', 'b2b2b2b2-0000-4000-8000-00000000000b',
   'phase1-oidc', 'OIDC', 'Phase 1 shared Keycloak (Entity B)',
   'http://localhost:18181/realms/lexavault-phase1', 'lexavault-phase1-web', 'lexavault-phase1-api',
   null, 'ACTIVE', 1, now(), now())
on conflict (connection_id) do nothing;

-- History rows, mirroring what the product's own bootstrap writes
insert into enterprise_identity_connection_history
  (history_id, connection_id, tenant_id, provider_id, provider_type, issuer_uri,
   browser_client_id, api_audience, status, configuration_version, change_type, changed_by, changed_at)
values
  ('a1a1a1a1-0000-4000-8000-0000000d0001', 'a1a1a1a1-0000-4000-8000-0000000c0001',
   'a1a1a1a1-0000-4000-8000-00000000000a', 'phase1-oidc', 'OIDC',
   'http://localhost:18181/realms/lexavault-phase1', 'lexavault-phase1-web', 'lexavault-phase1-api',
   'ACTIVE', 1, 'BOOTSTRAP', 'PHASE1-SEED', now()),
  ('b2b2b2b2-0000-4000-8000-0000000d0002', 'b2b2b2b2-0000-4000-8000-0000000c0002',
   'b2b2b2b2-0000-4000-8000-00000000000b', 'phase1-oidc', 'OIDC',
   'http://localhost:18181/realms/lexavault-phase1', 'lexavault-phase1-web', 'lexavault-phase1-api',
   'ACTIVE', 1, 'BOOTSTRAP', 'PHASE1-SEED', now())
on conflict (history_id) do nothing;

commit;

-- Summary for the start script (no secrets)
select 'tenants' as item, count(*) from tenants where id in ('a1a1a1a1-0000-4000-8000-00000000000a','b2b2b2b2-0000-4000-8000-00000000000b')
union all select 'active_connections', count(*) from enterprise_identity_connections where status = 'ACTIVE' and provider_id = 'phase1-oidc'
union all select 'capabilities', count(*) from tenant_capabilities where tenant_id in ('a1a1a1a1-0000-4000-8000-00000000000a','b2b2b2b2-0000-4000-8000-00000000000b');

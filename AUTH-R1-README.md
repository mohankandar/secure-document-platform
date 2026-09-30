# LexaVault Shared Authentication R1 Candidate

This candidate adopts the product-neutral `enterprise-identity` 0.1.0 module for OIDC/JWT identity normalization while preserving LexaVault-owned role mapping and LOCAL mode.

Key points:
- Customer-managed IdP remains authoritative for authentication.
- External groups are normalized by the shared identity module.
- LexaVault continues to map external groups to LexaVault product roles.
- Machine/integration authorization remains product-specific.
- `Build-LexaVault-All.ps1` installs `shared/enterprise-identity` first, then builds LexaVault.

This iteration does not add SAML, LDAP, admin configuration UI, or shared runtime services.


## FIX4 — connection-owned tenant resolution

- Customer OIDC tokens no longer need to carry LexaVault's internal tenant UUID.
- `SECUREDOC_SECURITY_OIDC_TENANT_ID` binds the configured OIDC connection to the internal tenant.
- The configured tenant is parsed once at startup; request-time resolution is an in-memory field read.
- `tenant_id` token claim remains only as a backward-compatible fallback when no configured tenant is supplied.
- The OIDC local launcher defaults the test connection to tenant `00000000-0000-0000-0000-000000000001` and maps the local Keycloak groups to LexaVault roles.

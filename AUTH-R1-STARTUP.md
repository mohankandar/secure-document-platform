# LexaVault Shared Authentication R1 — Startup Modes

## LOCAL regression mode

```powershell
.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR
```

Uses the `local` Spring profile and `securedoc.security.mode=LOCAL`.

## OIDC local-runtime validation

Use a real/test OIDC issuer that exposes standard discovery/JWKS endpoints.

```powershell
.\Start-LexaVault-OIDC.ps1 `
  -IssuerUri "https://<issuer>" `
  -Audience "<lexavault-api-audience>" `
  -SkipScannerBridge `
  -SkipFrontend
```

Or set `SECUREDOC_SECURITY_OIDC_ISSUER_URI` and `SECUREDOC_SECURITY_OIDC_AUDIENCE` first and run:

```powershell
.\Start-LexaVault-OIDC.ps1 -SkipScannerBridge -SkipFrontend
```

The launcher activates `local,oidc-local`: local DB/licensing/AI infrastructure is retained while authentication is switched to OIDC/JWT.

`-SkipScannerBridge` is recommended during the human OIDC test because scanner/integration identity is intentionally a separate machine-to-machine concern.

`-SkipFrontend` is recommended for R1 because LexaVault currently validates the backend resource-server boundary; a browser OIDC authorization-code redirect/login client is not added by this R1 shared-auth module.

Optional claim parameters:
- `-ProviderId` (default `customer-oidc`)
- `-TenantClaim` (default `tenant_id`)
- `-UsernameClaim` (default `preferred_username`)
- `-RolesClaim` (default `groups`)

Existing group-to-product-role mappings remain controlled by the existing `SECUREDOC_SECURITY_ROLE_*` environment variables / defaults.

## SAML / LDAP

No startup scripts are supplied yet because the SAML and LDAP adapters are not implemented in Shared Authentication R1. Add their launchers only when those adapters exist; do not expose a non-functional mode.

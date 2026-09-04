# Production Identity and Tenant Isolation

## Security modes

LexaVault V1 has explicit deployment security modes:

- `LOCAL` — developer/demo only;
- `OIDC` — production/on-prem/enterprise trust boundary and secure default outside explicit local configuration.

OIDC mode must fail closed when required JWT/OIDC configuration cannot be established securely. It does not fall back to LOCAL.

## OIDC/JWT boundary

Production identity comes from cryptographically verified JWT/OIDC authentication at the API edge.

Required trust properties include:

- signature validation;
- issuer validation;
- expiry/not-before validation;
- configured audience validation;
- explicit external group/role mapping;
- no persistence/logging of access or refresh tokens in business data/audit logs.

Provider-specific identity details stay at the edge; core product/domain code remains provider neutral.

## Tenant resolution

A configurable verified identity claim (logical default `tenant_id`) resolves exactly one provisioned tenant for the request.

- missing tenant identity fails closed;
- malformed tenant identity fails closed;
- unprovisioned tenant identity fails closed;
- callers cannot switch tenants with arbitrary headers/query parameters;
- tenant-owned repository/use-case access remains scoped to trusted tenant context.

## Actor identity

Verified issuer + subject provide the stable external identity source. Human-readable username/display claims are informational and do not determine authorization.

Background jobs use explicit system/configured actor identity rather than impersonating an interactive user.

## Production header spoofing

In OIDC mode, caller-supplied `X-Tenant-Id`, `X-Actor-Id`, and `X-Actor-Roles` are not trusted authentication/authorization inputs and cannot override verified JWT identity.

`X-Correlation-Id` may remain request metadata because it is not an identity/authorization source.

## Roles and capabilities

See `../product/AUTHORIZATION.md` for the only active V1 role/capability matrix and the narrow legacy identity transition mapping.

`ADMIN` is never implicit or a production fallback.

## Tenant-isolation verification

The accepted role migration received independent and adversarial Kiro review. The reviewed attack classes found no code-supported privilege escalation or cross-tenant bypass; tenant isolation was confirmed through tenant-scoped access patterns/guards and backend capability enforcement.

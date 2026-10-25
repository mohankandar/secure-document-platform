# Production Identity and Tenant Isolation

## Security modes

LexaVault has explicit deployment security modes:

- `LOCAL` — developer/demo only;
- `OIDC` — production/on-prem/enterprise trust boundary and the secure default outside explicit local configuration.

OIDC mode fails closed when required JWT/OIDC configuration cannot be established securely. It does not fall back to LOCAL.

## Shared Enterprise Identity boundary

Human OIDC normalization is provided by the separately versioned Enterprise Identity library (`enterprise-identity-core` / `enterprise-identity-spring`, currently `0.2.0`). LexaVault does not embed that source and does not delegate product authorization to it.

The shared library normalizes authenticated identity/groups. LexaVault maps normalized external groups to LexaVault roles and capabilities. Machine/integration authentication remains a separate product boundary.

## OIDC/JWT trust properties

Production identity comes from cryptographically verified JWT/OIDC authentication at the API edge, including:

- signature validation;
- issuer validation;
- expiry/not-before validation;
- configured audience validation;
- explicit external group mapping;
- no persistence/logging of access or refresh tokens in business data or audit records.

The browser uses Authorization Code + PKCE. Access/refresh token handling is browser-session scoped; refresh-token rotation is supported and a rejected refresh fails closed.

## Tenant resolution

The preferred production path is **connection-owned tenant resolution**:

1. the JWT is cryptographically verified;
2. LexaVault resolves the persisted ACTIVE Enterprise Identity connection using the trusted provider/issuer/audience binding;
3. that connection supplies the internal LexaVault tenant ID.

Customer IdP tokens therefore do **not** need to contain LexaVault's internal tenant UUID.

Compatibility paths remain intentionally narrow:

- if persistent connection lookup is disabled, a configured OIDC tenant binding may supply the internal tenant;
- only when no trusted persisted/configured tenant binding is available may the configured legacy tenant claim be used as a fallback.

Missing, malformed, unprovisioned or unmatched tenant identity fails closed. Callers cannot switch tenants with arbitrary headers/query parameters.

## Actor identity

Verified issuer + subject are used to derive stable trusted actor identity. Human-readable username/display claims are informational and do not determine authorization.

Background jobs use explicit system/configured actor identity rather than impersonating an interactive user.

## Production header spoofing

In OIDC mode, caller-supplied `X-Tenant-Id`, `X-Actor-Id`, and `X-Actor-Roles` are not trusted authentication/authorization inputs and cannot override verified JWT identity.

`X-Correlation-Id` may remain request metadata because it is not an identity/authorization source.

## Roles and capabilities

See `../product/AUTHORIZATION.md` for the active role/capability matrix.

Important administration boundary:

- `TENANT_IT_ADMIN` receives `TENANT_AUTH_MANAGE` for tenant Enterprise Identity configuration;
- `PLATFORM_OPERATIONS_ADMIN` does not implicitly receive tenant authentication-management capability;
- `ADMIN` is explicit-only and is never a production fallback.

## Enterprise Identity Administration lifecycle

The accepted lifecycle is tenant scoped and fail closed:

- draft configuration;
- explicit Test Connection;
- successful test result has a bounded freshness window;
- activation is exact-version and rejects stale/mismatched validation;
- activation/rollback are transactional and preserve a known-good ACTIVE connection;
- recovery is disabled by default and constrained to explicit startup recovery rules;
- audit/history must not expose credentials or tokens.

## Tenant-isolation verification

Backend authorization and repository/use-case tenant scoping are authoritative. UI visibility is never a security boundary. Enterprise Identity Administration R1 and the broader role/tenant boundary have completed focused independent verification and are treated as frozen unless a concrete regression is demonstrated.

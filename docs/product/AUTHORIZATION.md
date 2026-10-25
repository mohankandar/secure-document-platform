# LexaVault V1 Authorization Model

## Canonical product roles

| Display name | Technical role |
| --- | --- |
| Document Processor | `DOCUMENT_PROCESSOR` |
| Document Supervisor | `DOCUMENT_SUPERVISOR` |
| Business Administrator | `BUSINESS_ADMIN` |
| Report Viewer | `REPORT_VIEWER` |
| IT Administrator | `TENANT_IT_ADMIN` |
| Platform Operations Administrator | `PLATFORM_OPERATIONS_ADMIN` |
| System Administrator | `ADMIN` — internal/framework only |

`ADMIN` is explicit-only and is never a production default.

## Capability matrix

| Capability | Processor | Supervisor | Business Admin | Report Viewer | IT Admin | Platform Ops | ADMIN |
| --- | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| Dashboard | Y | Y | Y | N | Y | Y | Y |
| Document intake/upload | Y | Y | Y | N | N | N | Y |
| Manual review | Y | Y | Y | N | N | N | Y |
| Finding decisions | Y | Y | Y | N | N | N | Y |
| Redaction adjustment | Y | Y | Y | N | N | N | Y |
| Review submission | Y | Y | Y | N | N | N | Y |
| Final delivery approval | Y | Y | Y | N | N | N | Y |
| Reprocess retained original | N | Y | Y | N | N | N | Y |
| Remove from Dashboard | N | Y | Y | N | N | N | Y |
| Executive Reports | N | N | Y | Y | Y | Y | Y |
| Operational Reports | N | N | Y | Y | Y | Y | Y |
| Report CSV export | N | N | Y | N | Y | Y | Y |
| Business policy | N | N | Y | N | N | N | Y |
| Identifier/column configuration | N | N | Y | N | N | N | Y |
| Tenant integrations/watched folder | N | N | N | N | Y | Y | Y |
| Tenant authentication configuration | N | N | N | N | Y | N | Y |
| Technical issue details | N | N | N | N | Y | Y | Y |
| Technical retry | N | N | N | N | Y | Y | Y |
| Technical holds | N | N | N | N | Y | Y | Y |
| Audit records | N | N | N | N | Y | Y | Y |
| Deep platform diagnostics | N | N | N | N | N | Y | Y |

## Capability architecture

Backend authorization is capability-based through `@RequireCapability` and `CapabilityAuthorizationInterceptor`. Product roles resolve to fixed capabilities; tenants do not edit the role-to-capability map in V1.

Important separate capabilities include:

- dashboard access;
- intake/upload;
- Manual Review;
- finding decisions;
- redaction-location adjustment;
- review submission;
- final-delivery approval;
- retained-original reprocess;
- Dashboard removal;
- Executive Reports;
- Operational Reports;
- report CSV export;
- business-policy management;
- identifier/column management;
- integration management;
- tenant authentication management (`TENANT_AUTH_MANAGE`);
- technical issue viewing;
- technical administration view (`TECHNICAL_ADMIN_VIEW`) for safe tenant/platform operational administration surfaces;
- technical retry;
- technical-hold management;
- audit viewing;
- platform diagnostics;
- internal platform-operations capability (`PLATFORM_OPERATIONS`) for Platform Operations / explicit `ADMIN` support surfaces.

Manual Review and final delivery approval remain distinct capabilities even though Processor and Supervisor currently both receive them. That preserves a future policy option to separate duties without rewriting the authorization architecture.

## Identity transition aliases

Retired public workflow roles are:

- `USER`
- `REVIEWER`
- `APPROVER`

They are not active application roles. For migration compatibility only, identity-boundary normalization may map:

- `USER` -> `DOCUMENT_PROCESSOR`
- `REVIEWER` -> `DOCUMENT_PROCESSOR`
- `APPROVER` -> `DOCUMENT_SUPERVISOR`

The canonical role set must be used after normalization and exposed to the UI/current-user response. Legacy aliases must not become additional authorization roles.

Removal path: migrate IdP assignments to canonical groups, confirm no legacy assignments remain, then remove the transition group mappings and aliases.

## Security rules

- Backend authorization is authoritative.
- UI route/menu/button visibility is convenience only.
- Every tenant-owned operation remains tenant scoped.
- Unknown/missing production role mappings fail closed.
- OIDC mode does not trust caller-supplied tenant/actor/role headers.
- `ADMIN` is never inferred from another administrator role or successful authentication.
- Multiple legitimate roles combine only the capabilities of those canonical roles.

## Semantic boundaries

- `Remove from Dashboard` is visibility-only; it retains database metadata and audit/report history.
- No user role manually performs physical original deletion; retention lifecycle owns purge.
- `Reprocess from Retained Original` is a business rerun and is separate from classified transient technical retry.
- `REPORT_VIEWER` is executive/aggregate reporting only. It can view Executive and Usage/Adoption reporting, cannot export, cannot access Operational Details by role alone, and cannot drill into protected package/document/technical payloads.
- `BUSINESS_ADMIN` means approved business functionality, not system/technical administration.

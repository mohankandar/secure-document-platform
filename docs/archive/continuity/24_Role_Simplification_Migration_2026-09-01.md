# LexaVault V1 Role Simplification Migration — 2026-09-01

## Status

Implemented by ChatGPT from the authoritative source package `LexaVault-V1-Batch4-executive-operational-reports-2026-09-01.zip` (baseline SHA-256 `2bdeb4a621c03e5d45805ba5ce6a4f0e46cb557fb43cab3bcbb2b8980d56af28`).

This migration supersedes the product-role matrices in continuity records 16/17 and the authorization subsection of Batch 4 records 22/23. Batch 1–3 behavior and Batch 4 report design/metrics remain frozen.

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

`ADMIN` is never a production default.

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
| Technical issue details | N | N | N | N | Y | Y | Y |
| Technical retry | N | N | N | N | Y | Y | Y |
| Technical holds | N | N | N | N | Y | Y | Y |
| Audit records | N | N | N | N | Y | Y | Y |
| Deep platform diagnostics | N | N | N | N | N | Y | Y |

## Central authorization model

Backend authorization is capability-based through `@RequireCapability` and `CapabilityAuthorizationInterceptor`. The obsolete endpoint-level role annotation path was removed.

New/clarified capabilities introduced by this migration:

- `DASHBOARD_ACCESS`
- `DOCUMENT_INTAKE`
- `MANUAL_REVIEW`
- `FINDING_DECISION`
- `REDACTION_ADJUST`
- `REVIEW_SUBMIT`
- `PACKAGE_APPROVE_DELIVERY`
- `PACKAGE_REPROCESS`
- `PACKAGE_REMOVE_FROM_DASHBOARD`
- `REPORT_EXECUTIVE_VIEW`
- `REPORT_OPERATIONAL_VIEW`
- `REPORT_EXPORT`
- `TENANT_POLICY_MANAGE`
- `TENANT_IDENTIFIER_MANAGE`
- `TENANT_INTEGRATION_MANAGE`
- `TECHNICAL_EXCEPTION_VIEW`
- `TECHNICAL_ADMIN_VIEW`
- `TECHNICAL_RETRY`
- `TECHNICAL_HOLD_MANAGE`
- `AUDIT_VIEW`
- `PLATFORM_DIAGNOSTICS`
- `PLATFORM_OPERATIONS`

Manual review, finding decisions, redaction adjustment, review submission, and final delivery approval remain separate internal permissions even when the same roles currently receive them.

## Identity transition mapping

Legacy workflow-role names are accepted only at identity boundaries and immediately normalized to canonical roles. They are not accepted by `ProductAuthorizationPolicy`, do not appear in the demo role selector, and are not returned as active application roles.

Transition aliases:

- legacy `USER` -> `DOCUMENT_PROCESSOR`
- legacy `REVIEWER` -> `DOCUMENT_PROCESSOR`
- legacy `APPROVER` -> `DOCUMENT_SUPERVISOR`

OIDC keeps explicit transitional external-group mapping entries in `application.yml` so existing IdP assignments can be migrated without broadening access. Removal path: migrate IdP groups/users to `securedoc-document-processor` / `securedoc-document-supervisor`, confirm no legacy group assignments remain, then delete the three `SECUREDOC_SECURITY_LEGACY_ROLE_*` mappings and the three aliases in `ProductRole.fromIdentityName`.

## Backend enforcement changes

- Dashboard aggregate/list/detail/lifecycle and redacted-document access are capability-gated so Reports-only identities cannot call them directly.
- Package creation/upload/submit and intake identifier-definition APIs require `DOCUMENT_INTAKE`.
- Review queue/preview uses `MANUAL_REVIEW`; confirm/dismiss uses `FINDING_DECISION`; bounding-box adjustment uses `REDACTION_ADJUST`; review completion/resume uses `REVIEW_SUBMIT`.
- Final-delivery approval remains `PACKAGE_APPROVE_DELIVERY`.
- Retained-original reprocess and Dashboard hiding remain separate Supervisor-level capabilities.
- Business identifier/dashboard-column changes use `TENANT_IDENTIFIER_MANAGE`; sensitive-data/lifecycle policy uses `TENANT_POLICY_MANAGE`.
- Report read and CSV export stay separate. `REPORT_VIEWER` has Executive + aggregate Operational read access but no export.
- Safe operator-only technical metadata is keyed from `PLATFORM_DIAGNOSTICS`.

## Frontend changes

- Demo role selector contains canonical user-facing names only.
- Dashboard, Upload, Manual Review, Reports, Business Admin, technical admin, and Audit navigation/route surfaces are capability-gated from trusted `/api/v1/me` capability data.
- Report Viewer is Reports-only; CSV is hidden because the role lacks `REPORT_EXPORT`.
- Permission-denied behavior is retained for direct route navigation.
- Existing visual design, page structure, report charts/filters/metrics, Dashboard polling, breadcrumbs, and responsive/touch behavior were not redesigned.

## Semantic boundaries preserved

- Remove from Dashboard remains visibility-only; metadata/audit history stays intact.
- Physical original deletion remains retention-owned; no product role gains a manual physical-delete action.
- Business reprocess remains distinct from transient technical retry.
- Report Viewer Operational Reports remain aggregate/read-only and do not expose technical issue payloads/actions.
- Business Administrator receives all approved business functionality but no IT/technical administration.
- Tenant scope and fail-closed identity behavior remain authoritative backend concerns.

## Automated verification added

Backend focused tests:

- `ProductAuthorizationPolicyTest` — exact role/capability matrix for every canonical role and explicit rejection of legacy names as authorization roles.
- `RequestContextResolverSecurityTest` — local/OIDC canonicalization, transitional aliases, fail-closed identity behavior, and explicit non-default ADMIN behavior.
- `AuthorizationEndpointContractTest` — Dashboard/intake/review/lifecycle/report endpoint capability contracts.
- `CapabilityAuthorizationInterceptorTest` — direct API denial/allow behavior for Processor, Supervisor, Business Admin, Report Viewer, and IT Admin cases.

Frontend focused tests:

- `src/security/uiAuthorization.test.ts` — canonical selector plus top-level authorization surfaces for all seven canonical roles using Node's built-in test runner.

## Verification results in this environment

- Frontend authorization tests: PASS, 8/8.
- Java runtime: Java 21 is present.
- Maven: not installed and no repository Maven wrapper is present. Package installation/download is blocked by this container's unavailable external DNS/package resolution, so the Maven suite cannot be truthfully marked PASS here.
- Frontend dependency installation is blocked by the same external package-resolution limitation; therefore the Vite production build cannot be marked PASS here. The dependency-free authorization tests do not require those packages and did pass.

## Manual acceptance checklist

### Document Processor

- Dashboard visible.
- Upload Documents visible and usable.
- Manual Review visible; confirm/dismiss and redaction adjustment work.
- Review submission and final delivery approval work.
- Reprocess and Remove from Dashboard absent; direct APIs return access denied.
- Reports/admin/technical/audit surfaces absent.

### Document Supervisor

- All Document Processor functions work.
- Reprocess from retained original works only in valid lifecycle state.
- Remove from Dashboard works only in valid state and remains visibility-only.
- No tenant-wide Reports or IT administration by role alone.

### Business Administrator

- All Document Supervisor business workflow functions work.
- Business policy and identifier/column configuration work.
- Executive + Operational Reports visible; CSV export works and remains audited.
- No watched-folder/integration administration, technical issue/retry/hold, audit administration, or platform diagnostics.

### Report Viewer

- Reports opens directly.
- Executive Overview and aggregate Operational Reports are readable.
- CSV export is absent and direct export API is denied.
- Dashboard, Upload, Review, package workflow, Business Admin, technical issue/action, and Audit APIs are denied.

### IT Administrator

- Dashboard and Reports visible; CSV export works.
- Tenant integration/watched-folder administration, technical issue detail, selective retry, technical holds, and Audit work.
- Upload, Manual Review, delivery approval, retained-original reprocess, Dashboard removal, and business-policy/identifier configuration are denied.

### Platform Operations Administrator

- IT Administrator technical functions work.
- Safe deep platform diagnostics are present where supported.
- No secrets, credentials, tokens, raw sensitive values, cross-tenant data, or tenant-facing raw stack traces are exposed.
- Business workflow and business-policy administration remain denied.

### System Administrator

- Requires explicit `ADMIN` assignment.
- Never appears as a production default.
- All capabilities are available only when explicitly assigned.

### Identity/tenant regression

- Canonical OIDC groups map to canonical application roles.
- Transitional legacy IdP groups map only through the documented aliases and `/api/v1/me` returns canonical names.
- Client-supplied identity headers cannot override OIDC identity.
- Missing/malformed/unprovisioned tenant identity remains fail-closed.
- Cross-tenant ID substitution remains denied.

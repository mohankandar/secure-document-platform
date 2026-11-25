# LexaVault V1 Authorization Model

Checked 2026-11-25 (task 0095) against `ProductAuthorizationPolicy.java`, which decides.

## Canonical product roles

| Display name | Technical role |
| --- | --- |
| Document Processor | `DOCUMENT_PROCESSOR` |
| Document Supervisor | `DOCUMENT_SUPERVISOR` |
| Business Administrator | `BUSINESS_ADMIN` |
| Report Viewer | `REPORT_VIEWER` |
| IT Administrator | `TENANT_IT_ADMIN` |
| Platform Operations Administrator | `PLATFORM_OPERATIONS_ADMIN` |

Any other name, `ADMIN` included, has no capability in this policy.

## Capability matrix

| Capability | Processor | Supervisor | Business Admin | Report Viewer | IT Admin | Platform Ops |
| --- | :---: | :---: | :---: | :---: | :---: | :---: |
| Cases (Dashboard) | Y | Y | N | N | N | N |
| Document intake/upload | Y | Y | N | N | N | N |
| Sensitive case field values, on the case | Y | Y | N | N | N | N |
| Manual review, finding decisions, redaction adjustment, review submission; reject in Review; priority of a case | Y | Y | N | N | N | N |
| Send an export again that a destination refused | Y | Y | N | N | N | N |
| Final delivery approval (one, several, by link); reject in Approval | N | Y | N | N | N | N |
| Reprocess retained original | N | Y | N | N | N | N |
| Remove from Dashboard (no button on the Case File screens) | N | Y | N | N | N | N |
| Reports: Overview, Daily | N | N | Y | Y | N | N |
| Reports: Operations | N | N | Y | N | N | N |
| Report CSV export; compliance report, summary | N | N | Y | N | N | N |
| Business policy, entities, licensing, business notifications | N | N | Y | N | N | N |
| Case fields and columns | N | N | Y | N | N | N |
| Integrations, source priority, Bulk sources, mail account and technical notifications | N | N | N | N | Y | N |
| Sign-in connection; diagnostic bundle | N | N | N | N | Y | N |
| Support: problems, exports, status (read) | N | N | N | N | Y | Y |
| Technical retry; technical holds; destination checks | N | N | N | N | Y | N |
| Platform operations overview and queue | N | N | N | N | Y | Y |
| Storage, workers, branding, usage data, health checks | N | N | N | N | N | Y |
| Audit records | N | N | N | N | Y | Y |
| Compliance report with the full audit trail | N | N | N | N | Y | N |

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
- internal platform-operations capability (`PLATFORM_OPERATIONS`) for Platform Operations support surfaces.

Manual Review and final delivery approval are separate capabilities: Document Processor can review and submit, while Document Supervisor can approve delivery. Business Administrator has business policy and reporting permissions, not document operations, unless separately assigned an operational role.

## Identity transition aliases

Retired public workflow roles are:

- `USER`
- `REVIEWER`
- `APPROVER`

They are not active application roles. Setup stores a grant under one of them as the role it stands for:

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
- Multiple legitimate roles combine only the capabilities of those canonical roles.

## Semantic boundaries

- `Remove from Dashboard` is visibility-only; it retains database metadata and audit/report history.
- No user role manually performs physical original deletion; retention lifecycle owns purge.
- `Reprocess from Retained Original` is a business rerun and is separate from classified transient technical retry.
- `REPORT_VIEWER` is executive/aggregate reporting only. It can view Reports › Overview and Daily intake and delivery, cannot export, cannot see Operations by role alone, and cannot drill into protected package/document/technical payloads.
- `BUSINESS_ADMIN` means business configuration and reporting only. Desk, intake, review, approval and technical administration require separately assigned roles.
- The four-eyes rule (nobody approves a case they reviewed) holds for bulk approval; single approval does not check it.
- Multiple product roles combine their authorized capabilities; a Business Administrator who also holds an operational role retains that operational role's permissions.

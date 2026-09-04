# API Surface — V1

The backend REST API is the authoritative security boundary. Frontend route/button hiding never replaces backend capability and tenant checks.

## Main API areas

| Area | Primary controllers |
| --- | --- |
| Current identity/capabilities | `CurrentUserController` |
| Human/manual package intake and documents | `PackageController`, `IntakeConfigurationController` |
| Machine push ingestion | `ExternalIntakeController` — see `EXTERNAL_INGESTION_V1.md` |
| Dashboard/package visibility | `PackageDashboardController` |
| Workflow/final approval | `PackageWorkflowController`, `PackageLifecycleController` |
| Focused Manual Review | `FocusedReviewController`, `FindingReviewController`, `ReviewController` |
| Reports/operations | `OperationsController` |
| Business configuration | `BusinessAdminController` |
| Technical issues/retry | `TechnicalExceptionController` |
| Tenant/platform administration | `PlatformAdminController`, `WatchedFolderIntakeController`, `IntegrationConnectionController` |
| Audit | `AuditController` |
| Artifact access/download | `ArtifactAccessRequestController`, `ArtifactDownloadController` |

Authorization is capability-based through `@RequireCapability` / `CapabilityAuthorizationInterceptor`; tenant-owned resources remain tenant scoped in backend queries and guards.

For the canonical role/capability matrix, see `../product/AUTHORIZATION.md`.

Machine ingestion uses authenticated integration-source binding rather than assigning a fake human product role. Production tenant/client identity remains OIDC/JWT-derived and fail-closed.

## Integration contracts

- `EXTERNAL_INGESTION_V1.md` — manifest-first Push API ingestion.
- `POLLING_INGESTION_V1.md` — scheduled polling/pull ingestion.
- `SDV_DELIVERY_V1.md` — LexaVault→SDV redacted master-delivery contract.

- `DERIVED_ROUTING_V1.md` — Requirement 4 deterministic derived redacted-document routing and delivery contract.

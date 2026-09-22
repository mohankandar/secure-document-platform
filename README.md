# LexaVault V1 — VERIFIED / FROZEN + UI/UX Modernization R1–R5 — VERIFIED / FROZEN

V1 status: **FINAL V1 REGRESSION / RELEASE-READINESS — COMPLETE / VERIFIED / FROZEN**.

Latest completed post-V1 adjustments: **Platform-Managed Processing Storage — VERIFIED / FROZEN**, **Tenant Licensing R1 Iteration 1 Foundation — VERIFIED / FROZEN**, **Tenant Licensing R1 Iteration 2 FIX1 — VERIFIED / FROZEN**, and **UI/UX Modernization R1–R5 — VERIFIED / FROZEN**. Dashboard Retention Visibility FIX2 remains in the active post-freeze validation line, and the current candidate is **Operational Continuity, Technical Attention & Privacy Telemetry R1**. It adds a configurable 3-total-attempt package-processing recovery budget, safe reclamation of legacy/no-lease PROCESSING packages, recovery-exhausted Technical Attention, lifecycle-driven Technical Attention cleanup with durable history preservation, exceptional Tenant IT actions, and opt-in privacy-safe product telemetry with an explicit field allow-list and pseudonymous identifiers. `031-operational-continuity.xml` brings the candidate schema to **73 Liquibase changeSets**. The previously documented manual lifecycle endpoint maximum is corrected to `POST /api/v1/lifecycle/purge-due?limit=500`. **Full local build/runtime validation is required before this candidate is frozen; On-Prem Installer / First-Run Setup remains next afterward.**

Tenant Licensing R1 Iteration 1 provides tenant-bound signed/offline Ed25519 license verification, immutable license history/current assignment, document-processing entitlement enforcement, Platform Operations licensing APIs, and privacy-safe audit events. LOCAL remains unenforced by default for developer/demo use; enforced deployments fail closed. Focused runtime validation on 2026-09-18 covered missing-license denial, tamper rejection, valid-license processing admission, history/token secrecy, wrong-tenant rejection, automatic expiry, missing `DOCUMENT_PROCESSING` entitlement denial, and restoration/revalidation of the original license.

See `TENANT_LICENSING_R1_ITERATION1_VERIFIED_README.md` for the accepted evidence and `TENANT_LICENSING_R1_ITERATION1_CANDIDATE_README.md` for the original candidate scope/test plan.

Iteration 2 adds the Platform Operations `Licensing` tab, tenant status/expiration visibility, signed-license install/update, entitlement/limit details and immutable history UX. FIX1 is now VERIFIED / FROZEN after focused local validation of malformed-token handling, entitlement-blocked state, near-expiry attention, minute-level countdown, automatic expiry transition without manual refresh, history preservation across verification-key changes, restored normal-license state, and continued raw-token secrecy. Active/current authorization remains fail-closed under the currently configured key. Optional connected validation remains unconfigured until a remote licensing-service contract exists. See `TENANT_LICENSING_R1_ITERATION2_FIX1_VERIFIED_README.md`.

This source starts from the accepted/frozen LexaVault V1 + Processing Storage baseline. Final master/derived delivery behavior is intentionally unchanged.

UI/UX Modernization R1 treats `LexaVault` as a development codename/default brand rather than an immutable market name. Effective UI branding now resolves from built-in defaults to deployment overrides to tenant overrides. Customer-facing name/logo/accent changes are presentation-only and do not alter immutable tenant/licensing identity or backend business semantics. See `UI_UX_MODERNIZATION_R1_VERIFIED_README.md`, `UI_UX_MODERNIZATION_R1_CANDIDATE_README.md` and `UI_UX_MODERNIZATION_R1_FIX1_CANDIDATE_README.md`.


## UI/UX Modernization R2–R5 — VERIFIED / FROZEN

The combined R2–R5 release is accepted as one frozen baseline with the original hard checkpoints preserved:

- **Checkpoint A — Core operator workflows:** Dashboard foundation retained; Upload and Scan expose explicit workflow progress; Package Details is a modern intake workspace with contextual actions, responsive summary, documents, delivery/retention and derived-routing tabs.
- **Checkpoint B — Human review:** Manual Review uses the shared responsive page foundation while preserving the proven review/redaction workflow and automatic processing continuation.
- **Checkpoint C — Administration:** Business Administration is section-based and summary-first; Integrations is purpose-grouped and editor-on-demand; frozen Processing Storage, Licensing and Branding behavior remains unchanged.
- **Checkpoint D — Reports, Support & Audit:** Reporting, Support Incidents and Audit use the same modern responsive shell, progressive disclosure and privacy-safe presentation without changing underlying semantics.
- **Checkpoint E — Regression:** desktop/laptop, 1180×820 landscape and 820×1180 portrait layouts, role boundaries, upload/scan/package/review flows, reporting/support/audit and final Integrations group isolation were exercised in user runtime validation.

Validation-driven FIX1–FIX4 are included in the frozen baseline. Business Administration uses compact collapsible configuration rows; Support Incidents uses primary triage controls plus Advanced Filters; routine zero-discovery scheduled watched-folder audit events are hidden by default but remain available; Package Details no longer exposes an undefined document count; Integrations separates Intake Sources, Master Delivery, Derived Delivery and All Connections, locks Purpose in purpose-specific groups, constrains provider choices, and closes/resets open editors when groups change.

User-reported backend and frontend builds passed for the combined release. Final browser validation confirmed the last FIX4 state-isolation defect is resolved. Assistant checks for the final Integrations change included TypeScript/TSX syntax transpile PASS and frontend authorization tests **8/8 PASS**. No backend business logic, API contract, tenant isolation, authorization, licensing, processing-storage, scanner-bridge, retention or delivery semantics were intentionally changed. No new Liquibase migration was added; the frozen post-V1 schema baseline remains **71 changeSets** through `030-ui-branding.xml`. See `UI_UX_MODERNIZATION_R2_R5_VERIFIED_README.md` for the accepted evidence and the candidate/FIX readmes for the implementation history.

## Frozen product baseline

- High Availability / Multi-Node Intake: VERIFIED / FROZEN.
- Scanner Intake software: VERIFIED / FROZEN; real target hardware certification pending.
- Production AI Inference Tier: VERIFIED / FROZEN.
- Business / Operational Reporting: VERIFIED / FROZEN.
- Production Performance R3 FIX4: VERIFIED / FROZEN.
- LOCAL reference AI profile: `qwen3.5:4b`, parallelism 2, page batch 4, 14,000-character cap.
- Frozen V1 Liquibase baseline: 64 changeSets through `027-package-processing-ha.xml`.
- Current post-V1 **frozen** schema baseline: 71 changeSets through `030-ui-branding.xml`.

## Validation / startup checks

Run:

```powershell
.\scripts\Invoke-LexaVault-FinalReleasePreflight.ps1
```

If that passes, start the current verified baseline:

```powershell
.\start-local.cmd -LoggingFormat Json -EnableRollingFileLog -LogLevel INFO
```

Then run:

```powershell
.\scripts\Invoke-LexaVault-FinalReleasePreflight.ps1 -SkipBuild -RequireLocalRuntime
```

See `FINAL_V1_RELEASE_READINESS_README.md` and `docs/testing/FINAL_V1_RELEASE_READINESS.md` for evidence reuse, blockers and the distinction between software release-candidate approval and customer production-deployment readiness.

# LexaVault V1 — VERIFIED / FROZEN + Tenant Licensing R1 Iteration 2 FIX1 — VERIFIED / FROZEN

V1 status: **FINAL V1 REGRESSION / RELEASE-READINESS — COMPLETE / VERIFIED / FROZEN**.

Latest completed post-V1 adjustments: **Platform-Managed Processing Storage — VERIFIED / FROZEN**, **Tenant Licensing R1 Iteration 1 Foundation — VERIFIED / FROZEN**, and **Tenant Licensing R1 Iteration 2 FIX1 — VERIFIED / FROZEN**. Next planned workstream: **UI/UX Modernization with responsive iPad/tablet support**, before the On-Prem Installer / First-Run Setup workstream.

Tenant Licensing R1 Iteration 1 provides tenant-bound signed/offline Ed25519 license verification, immutable license history/current assignment, document-processing entitlement enforcement, Platform Operations licensing APIs, and privacy-safe audit events. LOCAL remains unenforced by default for developer/demo use; enforced deployments fail closed. Focused runtime validation on 2026-09-18 covered missing-license denial, tamper rejection, valid-license processing admission, history/token secrecy, wrong-tenant rejection, automatic expiry, missing `DOCUMENT_PROCESSING` entitlement denial, and restoration/revalidation of the original license.

See `TENANT_LICENSING_R1_ITERATION1_VERIFIED_README.md` for the accepted evidence and `TENANT_LICENSING_R1_ITERATION1_CANDIDATE_README.md` for the original candidate scope/test plan.

Iteration 2 adds the Platform Operations `Licensing` tab, tenant status/expiration visibility, signed-license install/update, entitlement/limit details and immutable history UX. FIX1 is now VERIFIED / FROZEN after focused local validation of malformed-token handling, entitlement-blocked state, near-expiry attention, minute-level countdown, automatic expiry transition without manual refresh, history preservation across verification-key changes, restored normal-license state, and continued raw-token secrecy. Active/current authorization remains fail-closed under the currently configured key. Optional connected validation remains unconfigured until a remote licensing-service contract exists. See `TENANT_LICENSING_R1_ITERATION2_FIX1_VERIFIED_README.md`.

This source starts from the accepted/frozen LexaVault V1 + Processing Storage baseline. Final master/derived delivery behavior is intentionally unchanged.

## Frozen product baseline

- High Availability / Multi-Node Intake: VERIFIED / FROZEN.
- Scanner Intake software: VERIFIED / FROZEN; real target hardware certification pending.
- Production AI Inference Tier: VERIFIED / FROZEN.
- Business / Operational Reporting: VERIFIED / FROZEN.
- Production Performance R3 FIX4: VERIFIED / FROZEN.
- LOCAL reference AI profile: `qwen3.5:4b`, parallelism 2, page batch 4, 14,000-character cap.
- Frozen V1 Liquibase baseline: 64 changeSets through `027-package-processing-ha.xml`.
- Current post-V1 schema baseline: 70 changeSets through `029-tenant-licensing.xml`.

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

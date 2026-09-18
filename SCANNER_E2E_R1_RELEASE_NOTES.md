# LexaVault Scanner Intake E2E — R1 Implementation Candidate

**Release:** Scanner-E2E-R1  
**Date:** 2026-09-10  
**Status:** IMPLEMENTED CANDIDATE / LOCAL BUILD + E2E VALIDATION REQUIRED  
**Not VERIFIED/FROZEN**

## Purpose

R1 is a versioned checkpoint of the Product Owner-authorized enterprise Scanner Intake end-to-end implementation. It preserves the previously validated SB-1/SB-2/SB-3 acquisition foundation and adds the internal SB-4 durability and SB-5 LexaVault submission path needed to demonstrate:

`Scan -> Ready -> Submit -> durable queue -> LexaVault -> common processing -> Manual Review when required -> common export -> Dashboard/Reports`.

This release is intentionally versioned so subsequent corrections can be delivered as R2/R3 without losing or obscuring the last coherent checkpoint.

## Product invariants represented in R1

- One Scanner Intake = one tenant business context + one physical scan job + one consolidated multi-page PDF.
- Business fields come only from LexaVault tenant `BusinessIdentifierDefinition` configuration.
- Explicit **Submit** after the scan reaches READY.
- Submit first commits the package to durable local ownership; the operator may then start the next package.
- `SECUREDOC_SCAN_BRIDGE_MODE=SIMULATOR|PHYSICAL`.
- SIMULATOR is a supported QA/development acquisition mode and is never an automatic fallback for PHYSICAL.
- PHYSICAL is fail-closed until a real scanner adapter is installed/certified.
- Scanner-specific behavior stops at intake/provenance. Accepted packages use the existing Business Record, validation, OCR/AI/redaction, Manual Review, approval, delivery/export and Dashboard lifecycle.
- Scanner provenance is persisted for reporting, including source classification `SCANNER_INTAKE`.
- Local scanned PDF is retained until LexaVault acceptance/reconciliation and then removed; terminal attention state retains the safe local copy.
- Future Document Intelligence auto-population is preserved as future-compatible scope but is not implemented in Scanner Bridge.

## Major R1 implementation areas

### Scanner Bridge

- Durable file-backed scanner session/queue state.
- Recovery of WAITING, READY, QUEUED and in-flight SUBMITTING work after restart.
- Explicit READY -> QUEUED operator handoff.
- Background submission worker so the operator does not wait for downstream processing.
- Idempotent/reconcilable LexaVault submission.
- Bounded-memory/streaming PDF delivery for large scanner packages.
- Exclusive bridge work-root ownership protection.
- Configurable terminal-state cleanup after safe acceptance.
- SIMULATOR and PHYSICAL acquisition adapter boundary.
- Scanner-less QA file selection in SIMULATOR mode.
- Spring constructor wiring is explicit for services that also expose test-only `Clock` constructors.

### LexaVault API/application/domain

- Dedicated scanner intake boundary under `/api/v1/scanner-intakes`.
- Manifest-first scanner intake creation with exactly one PDF.
- Raw `application/pdf` document delivery with request-body spooling and a bounded maximum size.
- Existing external-intake idempotency/checksum/reconciliation reused rather than duplicated.
- Scanner packages classified as `SCANNER_INTAKE`.
- Business identifier validation and Business Record resolution remain existing authoritative LexaVault behavior.
- Scanner provenance is normalized/validated and attached to the accepted package.
- Human package-creation APIs cannot spoof scanner source classification.
- Scanner integration connection constraints are enforced in domain/application logic.

### UI/reporting

- Scanner page supports Start Scan -> READY -> Submit -> safely queued -> Start Next Package.
- Bridge connectivity and restart recovery are shown without leaving contradictory stale state.
- Manual Upload remains separate from Scanner Intake.
- Dashboard source label supports Scanner.
- Operational/Executive report intake-source filter/source mix includes Scanner.

## R1 non-goals / deferred gates

- No physical TWAIN/WIA/vendor scanner implementation yet.
- No physical scanner certification yet.
- No production Windows Service/MSI packaging certification yet.
- No new Document Intelligence extraction/auto-population behavior.
- No Scanner-specific downstream review/redaction/export pipeline.
- No claim of production performance certification; performance remains a later V1 hardening gate.
- Browser-to-local-bridge production trust hardening remains a productionization gate and must be reviewed before physical rollout.

## Verification state

Static source checks were performed during implementation, including explicit Spring constructor wiring and Java source compilation where the local execution environment allowed it. This execution environment does not provide the full Maven toolchain, so R1 MUST be validated on the authoritative development workstation before acceptance.

Required first gate:

```powershell
.\build-all.cmd
```

Then execute `docs/testing/SCANNER_INTAKE_E2E_LOCAL_TEST.md`.

Only after local E2E PASS should Kiro be used for one focused Scanner Intake verification checkpoint. Do not mark R1 VERIFIED/FROZEN based on packaging alone.

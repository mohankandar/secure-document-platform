# Scanner-E2E R5 — Physical Windows WIA Verification

**Status:** VERIFIED / FROZEN (SOFTWARE) — REAL PHYSICAL HARDWARE CERTIFICATION PENDING  
**Date:** 2026-09-11

## Acceptance result

Focused independent verification returned **PASS WITH FINDINGS — SOFTWARE READY / HARDWARE CERTIFICATION PENDING**.

R5 closes the previous software gap: physical acquisition is now genuinely implemented through Windows Image Acquisition (WIA), runs out-of-process through the repository PowerShell helper, and feeds the already-verified durable Scanner-E2E queue/submission path. The development workstation did not have a real scanner attached, so hardware certification remains an explicit deployment gate rather than an unfinished application feature.

## Verified software evidence

- `build-all.cmd` was reported successful on the Windows development workstation.
- `start-local.cmd PHYSICAL` starts successfully.
- `PhysicalScannerAcquisitionAdapter` is a real implementation rather than the previous fail-closed stub.
- `WindowsWiaScannerClient` performs WIA discovery/acquisition through `windows-wia-acquire.ps1`; COM/WIA stays outside the JVM.
- No-scanner probe returned `LEXAVAULT_WIA_NO_SCANNER` / exit 20 and maps to `PHYSICAL_SCANNER_NOT_AVAILABLE` before a scan session is created.
- PHYSICAL mode cannot silently fall back to SIMULATOR; simulator staging is rejected in PHYSICAL mode.
- `PhysicalScanPdfAssembler` deterministically consolidates acquired pages into one PDF using `.part` + force + atomic move and verifies the final PDF.
- Physical output enters the same durable session store, READY state, explicit Submit, idempotent/reconcilable LexaVault scanner intake, provenance and normal downstream processing path as SIMULATOR.
- Crash-window recovery exists for a finalized physical PDF that was published before READY state persistence.
- Focused physical adapter tests passed: **2 tests, 0 failures, 0 errors, 0 skipped**.
- No product source was modified by the independent verifier.

## Non-blocking findings carried forward

1. **Multi-scanner unattended selection:** when multiple WIA devices are installed and no explicit device selector is configured, acquisition can invoke an interactive WIA picker. Production unattended/service deployments should configure a deterministic `device-id` / `device-name`; later installer/service hardening should prefer explicit fail-closed selection over an invisible interactive wait.
2. **Bridge-local `firstName` / `lastName` metadata fields:** legacy bridge metadata configuration remains inconsistent with the authoritative tenant Business Identifier Definition ownership model. This is not a Scanner-E2E integrity blocker and should be removed as bounded cleanup.

## Hardware certification gate

Before production use of physical Scanner Intake on a target customer device, certify representative hardware with at least:

- one flatbed/single-page acquisition;
- one multi-page feeder acquisition;
- duplex acquisition when the target device supports duplex;
- cancel/no-paper/device-disconnect failure behavior;
- final paper -> WIA -> consolidated PDF -> correct LexaVault package/document -> normal processing confirmation.

This hardware gate does **not** block progression of the V1 software roadmap.

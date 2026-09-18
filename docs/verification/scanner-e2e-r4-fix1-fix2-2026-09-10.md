# Scanner E2E R4 FIX1/FIX2 — Local correction checkpoint

**Date:** 2026-09-10  
**Status:** IMPLEMENTED CANDIDATE — unified LOCAL startup reported working; full E2E + focused independent verification pending

## FIX1 — Start Scan browser mutation

Local simulator use exposed a browser integration defect: bridge GET health/status calls could remain green while the `Start Scan` POST failed during browser preflight, causing the UI to report **Bridge unavailable** even though the bridge process was alive.

R4 FIX1 handles trusted CORS preflight at the Scanner Bridge security-filter boundary, including Chromium private/local-network preflight when requested. Existing controls remain in force: exact allowed Origin, loopback-only bind, and the per-process `X-LexaVault-Bridge-Token` for state-changing requests. The UI now distinguishes a browser-blocked mutation from a genuinely unreachable bridge.

Simulator association remains intentionally fail-safe: do not pre-place a PDF in the simulator inbox. The supported sequence is **enter identifiers -> Start Scan -> Select Test PDF -> DOCUMENT RECEIVED -> Submit**.

## FIX2 — Unified LOCAL startup

Scanner Bridge startup/shutdown is folded into the normal local scripts:

```powershell
.\start-local.cmd              # SIMULATOR by default
.\start-local.cmd SIMULATOR
.\start-local.cmd PHYSICAL
.\start-local.cmd PRINTER      # alias for PHYSICAL
.\stop-local.cmd
```

The launcher starts/reuses PostgreSQL and Ollama as appropriate, then starts backend, Scanner Bridge, and frontend. A project-managed bridge running in the other mode is restarted in the requested mode. The normal stop command stops the bridge too.

## Physical-scanner boundary

R4 still uses a fail-closed `PhysicalScannerAcquisitionAdapter`. Selecting `PHYSICAL` / `PRINTER` proves the mode boundary and startup behavior only; real TWAIN/WIA/vendor acquisition is not implemented or certified in this candidate and must never silently fall back to `SIMULATOR`.

## Remaining acceptance gate

The Product Owner reports the unified local startup working. Do not infer full Scanner Intake acceptance from that smoke result. Complete `../testing/SCANNER_INTAKE_E2E_LOCAL_TEST.md`, then run the focused independent/Kiro verification checkpoint before marking Scanner Intake or the carried SB-1/2/3 findings VERIFIED / FROZEN.

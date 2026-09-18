# LexaVault Scanner E2E R4 — Start Scan browser fix

**Date:** 2026-09-10  
**Status:** BUILD CANDIDATE / LOCAL VALIDATION REQUIRED

## Defect addressed

The Scanner Intake page could show **Bridge unavailable** when `Start Scan` was clicked even though bridge GET health/status calls were green. `Start Scan` is a browser mutation that uses the per-process `X-LexaVault-Bridge-Token`, so the browser performs a CORS preflight first. The bridge now answers trusted preflight requests directly at the security-filter boundary, including Chromium private/local-network preflight when requested, while retaining exact Origin allow-listing, loopback-only binding, and the mutation token.

The UI also now distinguishes a browser-blocked mutation from an actually stopped bridge.

## Simulator sequence clarification

Do not put a PDF into `C:\LexaVaultTest\scanner-inbox` before creating the metadata session. That is intentionally rejected as a stale-file/misassociation guard. In simulator mode use:

1. Enter business identifiers.
2. Click **Start Scan**.
3. Click **Select Test PDF** and choose the PDF.
4. Wait for **DOCUMENT RECEIVED**, then click **Submit**.

The Scanner Intake page now displays this sequence before `Start Scan`, and the stale-inbox error is more explicit.

## Changed files

- `backend/securedoc-scan-bridge/.../BridgeRequestSecurityFilter.java`
- `backend/securedoc-scan-bridge/.../BridgeRequestSecurityFilterTest.java`
- `backend/securedoc-scan-bridge/.../ScannerSimulatorService.java`
- `frontend/securedoc-ui/src/api/scanBridgeClient.ts`
- `frontend/securedoc-ui/src/pages/ScanIntakePage.tsx`

## Local gate

Run `build-all.cmd`, restart the local app/UI and Scanner Bridge, then retry Scanner Intake in SIMULATOR mode.

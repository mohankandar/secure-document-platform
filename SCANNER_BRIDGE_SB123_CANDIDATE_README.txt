LexaVault V1 — Scanner Bridge SB-1 + SB-2 + SB-3
STATUS: FINDINGS CORRECTION IMPLEMENTED / LOCAL VALIDATION REQUIRED

Authoritative V1 behavior:
- PDF only.
- Business fields come only from LexaVault tenant Business Identifier Definitions.
- Business Admin owns identifier definitions; the operator enters the transaction values.
- Scanner Bridge must not define a second business-field schema.
- Scanner/bridge owns or generates technical metadata only.
- No OCR, AI, barcode/QR extraction, document classification, or business-data inference in the bridge.
- LexaVault remains authoritative for business validation and downstream processing.

Implemented SB-1/2/3 candidate:
SB-1: standalone loopback Scan Bridge module/config/health foundation.
SB-2: dedicated LexaVault Scan Documents operator page using tenant Business Identifier Definitions only.
SB-3: local scanner simulator with stable-file detection, PDF signature check, safe claim, SHA-256, technical IDs, READY session, invalid-file problem disposition, and stale-inbox misassociation guard.

Kiro focused verification on 2026-09-09: PASS WITH MINOR FINDINGS.
Corrections implemented in this candidate:
1. Removed bridge-local firstName/lastName and generic metadataFields ownership.
2. Added live bridge reachability presentation and safe stale-session reset after bridge restart.
3. When bridge connectivity is lost mid-scan, the waiting panel now shows a disconnected/reconnect state; a missing session uses operator-safe "no longer active" wording.
4. Removed the legacy Scan Documents file-picker from manual Upload Documents; dedicated Scanner Intake is the single scanner workflow.

Still required before VERIFIED/FROZEN:
- local build/runtime findings validation;
- focused Kiro findings-closure verification.

Deferred intentionally:
SB-4 durable queue / restart recovery.
SB-5 authenticated LexaVault API submission / idempotency / reconciliation.
Physical scanner hardware certification.

Local test defaults:
Bridge: http://127.0.0.1:10090
Inbox:  C:\LexaVaultTest\scanner-inbox
Work:   C:\LexaVaultTest\scanner-bridge-work
Problem:C:\LexaVaultTest\scanner-bridge-problem

See:
- docs/scanner-bridge/SCANNER_BRIDGE_V1_SB123.md
- docs/testing/SCANNER_BRIDGE_SB123_LOCAL_TEST.md
- docs/verification/scanner-bridge-sb123-kiro-2026-09-09.md

Local launchers:
- Start-LexaVault-ScannerBridge.ps1 (or start-scanner-bridge.cmd) starts the already-built bridge JAR.
- Stop-LexaVault-ScannerBridge.ps1 (or stop-scanner-bridge.cmd) stops only the bridge and preserves scanner folders.
- Simulate-LexaVault-Scan.ps1 stages a completed synthetic PDF into the simulator inbox.

# Scanner Bridge SB-1 + SB-2 + SB-3 — Kiro Focused Verification — 2026-09-09

**Verdict:** PASS WITH MINOR FINDINGS

Scope was restricted to SB-1, SB-2 and SB-3. SB-4, SB-5, AI HA, reporting, performance and unrelated frozen work were not evaluated. Kiro modified no source, tests, configuration, migrations or documentation.

## Verified PASS evidence

- `mvn -pl securedoc-scan-bridge -am clean install`: BUILD SUCCESS; 12 tests; 0 failures/errors/skips.
- Packaged Scanner Bridge started successfully after the earlier constructor-wiring correction.
- Bound to `127.0.0.1:10090` only.
- `/actuator/health`: `UP`.
- `/api/v1/configuration`: expected bridge/site/scanner/simulator configuration and prohibited-capability list.
- Bridge JVM lifecycle is independent of LexaVault; scanner folders persist across normal bridge stop.
- Scanner Intake loads LexaVault tenant identifier definitions and applies the existing portable validation rules.
- Valid PDF reaches READY with stable submissionId/sourceDocumentId and technical metadata.
- Independently calculated SHA-256 matched bridge SHA-256 exactly.
- Slowly written/changing PDF is not claimed until stable.
- `.txt` is rejected with `UNSUPPORTED_FILE_TYPE`.
- fake `.pdf` without `%PDF-` signature is rejected with `INVALID_PDF`.
- stale pre-session inbox content causes `409 SCANNER_INBOX_NOT_EMPTY` and is not silently associated.
- one waiting session claims exactly one of two PDFs.
- sequential sessions produce unique submissionId/sourceDocumentId values and preserve correct business identifiers.
- no OCR, AI, barcode/QR extraction, document classification or business metadata inference path observed.
- privacy-safe logs contained no operator-entered business values, protected document content, prompts/model responses, credentials or tokens.
- no LexaVault submission occurred, correctly preserving the SB-1/2/3 boundary.

## Minor findings requiring closure before freeze

### 1. Medium — business-field ownership

The bridge defined `firstName` and `lastName` in local `application.yml`, exposed them as `metadataFields`, validated them in `ScanSessionService`, and caused Scanner Intake to render them beside authoritative tenant business identifiers. This violates the V1 rule that Scanner Bridge must not create a second business-field configuration source.

Required correction: remove bridge-owned business-field definitions and render business fields only from LexaVault tenant Business Identifier Definitions.

### 2. Low — stale bridge availability indicator

If the bridge was reachable at page load and then stopped mid-session, the cached configuration could leave the header showing `Bridge ready` while polling simultaneously reported that the bridge was unavailable.

Required correction: track live reachability and clear/reset stale in-memory session UX after bridge restart.

## Acceptance rule

SB-1/2/3 remains **not frozen** until the two findings above are corrected and a focused findings-closure check passes.

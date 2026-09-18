# LexaVault V1 — Scanner Bridge SB-1 + SB-2 + SB-3

Status: **FOUNDATION CARRIED INTO SCANNER-E2E-R4 FIX2 / FINAL FREEZE PENDING**

This unit establishes the V1 scanner boundary without requiring physical scanner hardware.

## Frozen V1 boundary

Scanner Bridge V1 is a **human-metadata-assisted, PDF-only transport component**.

The operator enters authoritative **tenant-configured business identifiers**. The scanner and bridge do not inspect document content to infer business identity.

### Operator-entered business data

The LexaVault Scanner Intake UI loads the tenant's existing `BusinessIdentifierDefinition` configuration from LexaVault and renders those fields. Examples in the current LOCAL/demo tenant include:

- Contract Number
- Member Number
- Service Entity

**Scanner Bridge does not define a second business-field schema.** Until LexaVault explicitly introduces a Business Admin-managed generic metadata schema, Scanner Intake renders only the existing tenant business-identifier definitions.

LexaVault remains authoritative for identifier definition ownership and authoritative validation. The bridge only carries the operator-entered identifier values for the active scan session; it never fills, corrects, or replaces them from document content.

### Configuration ownership

- **Business Admin / LexaVault:** tenant business-identifier definitions, required/primary/display/search/extract flags, validation type, normalization, lengths, allow-lists and custom patterns.
- **Operator:** transaction-specific business-identifier values entered for the scan.
- **Tenant IT / deployment:** bridge technical identity and workstation/site configuration such as tenantId, bridgeId, siteId, scannerId, port, allowed browser origins and local paths.
- **Bridge generated:** scan session ID, submissionId, sourceDocumentId, original filename, received timestamp, file size, SHA-256 and controlled work reference.

### Scanner / technical metadata

Generated automatically by the bridge:

- tenantId
- bridgeId
- siteId
- scannerId
- scan session ID
- submissionId
- sourceDocumentId
- original file name
- received timestamp
- file size
- SHA-256
- controlled work reference

### Explicitly prohibited in Scanner Bridge V1

- OCR
- AI extraction
- barcode extraction
- QR-code extraction
- document classification
- business-metadata inference

LexaVault may OCR the scanned PDF later as part of the already-frozen scanned-PDF, sensitive-data detection, redaction, review, and delivery pipeline.

## Intake UX boundary

LexaVault exposes **Upload Documents** and **Scan Documents** as separate intake entry points for users with `DOCUMENT_INTAKE`.

- **Upload Documents** is manual file intake: drag/drop and `Browse Files` only.
- **Scan Documents** is Scanner Bridge intake: operator enters the same tenant-configured business identifiers, starts a local scan, and waits for the bridge-prepared PDF.
- The old file-picker-style `Scan Documents` button inside **Upload Documents** is retired now that a dedicated Scanner Bridge workflow exists. It must not create two visually different "scan" workflows.
- Both intake paths must ultimately converge on the same LexaVault package/document processing lifecycle; SB-5 will connect the prepared bridge scan to that existing API lifecycle.

The scanner page should present normal operator terminology. Bridge/device IDs, simulator paths, submission/source IDs and SHA-256 are support/diagnostic details, not additional business fields. SB-1/2/3 may expose them for local validation, but they must remain visually separated from business information.

Bridge availability is a **live connectivity state**, not merely the `enabled` flag cached from initial configuration. If the bridge becomes unreachable, the UI must stop showing `Bridge ready`. If a pre-SB-4 in-memory scan session disappears after a bridge restart, the UI must clear the stale waiting state and allow a new scan.

## SB-1 — Foundation and configuration

A new Maven module, `securedoc-scan-bridge`, runs as a loopback-bound Spring Boot process by default.

Default local development endpoint:

- `http://127.0.0.1:10090`

Default simulator paths:

- `C:\LexaVaultTest\scanner-inbox`
- `C:\LexaVaultTest\scanner-bridge-work`
- `C:\LexaVaultTest\scanner-bridge-problem`

### Local workstation launcher

For development and local validation, Scanner Bridge is now part of the normal LexaVault LOCAL lifecycle:

- `start-local.cmd` defaults to `SIMULATOR`; `start-local.cmd SIMULATOR` is explicit; `start-local.cmd PHYSICAL` / `start-local.cmd PRINTER` select the physical boundary.
- `stop-local.cmd` stops the project-managed Scanner Bridge along with the backend/frontend.
- `Start-LexaVault-ScannerBridge.ps1` / `start-scanner-bridge.cmd` and their stop scripts remain available for focused bridge-only troubleshooting.

The bridge launcher runs the already-built `securedoc-scan-bridge` executable JAR in its own PowerShell window, waits for `/actuator/health` to report `UP`, and preserves the scanner work/problem folders across stops. It accepts optional tenant/bridge/site/scanner/port/path overrides while defaulting to the local values.

This launcher is for development/local validation. The eventual enterprise deployment model remains an IT-managed Windows service installed on the end-user workstation or approved site host.

Configuration includes:

- enabled flag
- tenantId
- bridgeId
- siteId
- scannerId
- future LexaVault base URL (delivery is intentionally inactive until SB-5)
- allowed browser origins
- simulator paths and stability timings

It does **not** include business-field definitions.

Actuator exposes health/info only. Logs use structured JSON and must not include document text or operator-entered protected values.

## SB-2 — Operator business-identifier workflow

LexaVault UI adds **Scan Documents** for users with the existing `DOCUMENT_INTAKE` capability.

The page:

1. Loads tenant business-identifier definitions from LexaVault `/api/v1/intake/identifier-definitions`.
2. Loads local bridge technical configuration from the loopback Scan Bridge.
3. Lets the operator enter tenant-configured business identifiers only.
4. Applies portable client-side validation for fast feedback while keeping LexaVault authoritative.
5. Starts exactly one waiting scan session for the configured scanner.
6. Polls the local bridge until the scan becomes READY.
7. Displays prepared-scan technical metadata separately from business information for local validation/support.
8. Tracks bridge availability live and fails safely if the local bridge stops or restarts.

SB-1/2/3 does **not** create a LexaVault package or upload a document. That boundary is intentionally reserved for SB-5.

## SB-3 — Local scanner simulator and stable-file intake

The simulator continuously observes a dedicated inbox.

For a candidate file:

1. Observe size + last-modified time.
2. Require the file to remain unchanged for the configured stability window.
3. Enforce `.pdf` extension.
4. Verify PDF signature (`%PDF-`) without extracting document content.
5. Require an active operator scan session.
6. Atomically move the PDF to a session-specific bridge work directory when supported by the filesystem.
7. Verify the file did not change during claim.
8. Compute SHA-256.
9. Generate a sourceDocumentId.
10. Mark the same session `READY` with the operator-entered business identifiers and technical metadata associated.

### Misassociation guard

A new operator scan session cannot be started while the simulator inbox already contains an unclaimed regular file. This prevents a stale pre-existing PDF from being silently associated with newly entered business identifiers.

### Rejected simulator input

Unsupported or invalid files are moved to the configured problem directory with a safe reason code. The active waiting scan session is not consumed by an invalid file.

## Intentional deferrals

### SB-4 — Durable queue / retry / crash recovery

Not part of this unit. Session state is currently process-memory state. A bridge restart may require the operator to begin a new session; durable reconciliation is the next implementation boundary.

### SB-5 — LexaVault API submission / idempotency / reconciliation

Not part of this unit. No package creation, document upload, submit, or receipt reconciliation occurs from the bridge yet.

### Physical scanner integration

Not required for this unit. The local simulator stands in for scanner hardware. Hardware/driver certification remains a later acceptance gate when a supported scanner is available.

## Candidate acceptance criteria

- New scan-bridge Maven module builds with the backend reactor.
- Local bridge binds to loopback by default.
- Configuration endpoint exposes safe bridge/scanner technical configuration and **no bridge-owned business-field schema**.
- Operator UI loads tenant business identifiers only from LexaVault.
- Dedicated `Scan Documents` navigation remains the scanner workflow; manual `Upload Documents` does not expose a second scan file-picker action.
- Bridge-ready/unavailable UI reflects live reachability, including bridge stop/restart while the page remains open.
- While the bridge is unreachable, a previously waiting local session is shown as disconnected rather than as actively waiting for a PDF.
- A lost pre-SB-4 in-memory session fails safely, uses operator-safe wording, and the operator can start a new scan.
- One waiting session at a time per configured scanner.
- Pre-existing inbox content cannot be silently attached to a new scan session.
- Stable PDF is claimed and becomes READY.
- Partial/changing file is not consumed before stability window.
- Non-PDF and fake-PDF input is rejected to problem disposition.
- SHA-256 is generated over the claimed PDF.
- submissionId and sourceDocumentId are generated and stable on the READY session.
- Operator-entered business identifiers remain associated with the same session.
- No OCR, AI, barcode/QR extraction, classification, or metadata inference dependency/path exists in the bridge.
- Structured logs contain safe operational metadata only.
- Existing frozen LexaVault application behavior is unchanged.

## Independent verification checkpoint — 2026-09-09

Kiro focused verification returned **PASS WITH MINOR FINDINGS** for SB-1/2/3:

- bridge build/startup/loopback health/configuration: PASS;
- process independence/folder persistence: PASS;
- tenant identifier rendering and validation: PASS;
- simulator happy path/stability/PDF-only/fake-PDF/stale-file/one-session-one-document/sequential IDs/SHA-256: PASS;
- no OCR/AI/extraction and privacy-safe logging: PASS;
- source integrity: no Kiro source/config/test/migration/doc changes.

Corrections implemented in the current candidate, pending local validation and focused Kiro closure:

1. **Medium — business-field ownership:** bridge-local `firstName`/`lastName` and the generic bridge-owned metadata-field schema have been removed. Scanner Intake now renders business fields only from LexaVault tenant Business Identifier Definitions.
2. **Low — availability UX:** Scanner Intake now tracks live bridge reachability, changes the status badge when the bridge stops/returns, and clears a stale pre-SB-4 in-memory session safely after bridge restart.
3. **User UX cleanup:** the legacy `Scan Documents` file-picker action was removed from manual **Upload Documents**; the dedicated Scanner Intake route is now the single scanner workflow.

Do not mark SB-1/2/3 VERIFIED/FROZEN until local validation passes and a focused findings-closure verification passes.


## Continuation

SB-1/2/3 is the acquisition foundation. Product Owner-authorized end-to-end continuation (durability, explicit submission, common downstream lifecycle, reporting provenance and simulator/physical acquisition boundary) is defined in `SCANNER_INTAKE_END_TO_END.md`.

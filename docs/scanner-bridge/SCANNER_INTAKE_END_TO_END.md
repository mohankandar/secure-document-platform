# LexaVault V1 — Enterprise Scanner Intake End-to-End Contract

**Status:** Scanner-E2E-R5 VERIFIED / FROZEN (SOFTWARE) — PHYSICAL HARDWARE CERTIFICATION PENDING — 2026-09-11

## Business outcome

Scanner Intake is an enterprise intake channel, not a separate processing product. A high-volume operator receives physical document packages, enters the tenant-configured business identifiers, scans the entire physical package in one feeder job, explicitly submits it, and immediately moves to the next package. After LexaVault accepts the submission, all downstream behavior is the existing common LexaVault lifecycle.

```text
Business identifiers
  -> one physical scan job
  -> one consolidated multi-page PDF
  -> durable local queue
  -> explicit Submit
  -> authenticated/idempotent LexaVault intake
  -> existing Business Record resolution + validation
  -> existing OCR / AI discovery / redaction
  -> existing Manual Review if required
  -> existing approval policy if required
  -> existing delivery/export
  -> Dashboard / Reports
```

The operator does not manually segment a paper package into multiple logical PDFs. One Scanner Intake represents one operator-entered business context plus one consolidated scanner-produced PDF, regardless of whether the package contains 20, 60, 200, or more pages.

## Reuse boundary

Scanner Intake MUST reuse existing LexaVault contracts after secure receipt:

- tenant-configured `BusinessIdentifierDefinition` schema;
- authoritative backend business-identifier validation/normalization;
- Business Record resolution/linking;
- existing PDF intake validation and secure original storage;
- existing package submission/processing dispatcher;
- existing OCR/AI/redaction/review/final-approval lifecycle;
- existing delivery/export and Dashboard;
- existing tenant-scoped reporting infrastructure.

Scanner-specific OCR, AI, review, redaction, Business Record, approval, or export pipelines are prohibited.

## Acquisition modes

Scanner Bridge has an explicit startup-selected acquisition mode:

- `SIMULATOR` — QA/development mode. A tester may select a test PDF from the Scanner Intake page. It enters the same bridge acquisition/durable queue path as a scanner-produced PDF.
- `PHYSICAL` — Windows physical-scanner mode. The current V1 candidate uses a Windows WIA acquisition adapter, acquires one or more paper pages, consolidates them into one durable PDF, then enters the same queue/submission path as `SIMULATOR`. There is no automatic simulator fallback. Real-device/driver compatibility remains a deployment/hardware certification gate until verified on representative target scanners; it does not block the frozen software baseline.

Configuration property/environment variable:

```text
SECUREDOC_SCAN_BRIDGE_MODE=SIMULATOR|PHYSICAL
```

Physical/WIA deployment controls are optional and remain workstation configuration rather than operator-entered values:

```text
SECUREDOC_SCAN_BRIDGE_PHYSICAL_PROVIDER=WIA
SECUREDOC_SCAN_BRIDGE_PHYSICAL_DEVICE_ID=<optional exact WIA device id>
SECUREDOC_SCAN_BRIDGE_PHYSICAL_DEVICE_NAME=<optional exact WIA scanner name>
SECUREDOC_SCAN_BRIDGE_PHYSICAL_SOURCE=AUTO|FEEDER|FLATBED
SECUREDOC_SCAN_BRIDGE_PHYSICAL_DUPLEX=true|false
SECUREDOC_SCAN_BRIDGE_PHYSICAL_DPI=300
SECUREDOC_SCAN_BRIDGE_PHYSICAL_MAX_PAGES=250
SECUREDOC_SCAN_BRIDGE_PHYSICAL_TIMEOUT_SECONDS=900
```

`AUTO` prefers a loaded feeder when WIA reports one ready and otherwise uses flatbed. If multiple WIA scanners are present and no device selector is configured, the Windows WIA device picker is used. Physical acquisition is allowed only when Windows exposes a compatible WIA scanner; unsupported/non-WIA hardware remains unavailable rather than falling back to simulator.

For the normal LOCAL workflow, the unified launcher is preferred:

```powershell
.\start-local.cmd              # defaults to SIMULATOR
.\start-local.cmd SIMULATOR
.\start-local.cmd PHYSICAL
.\start-local.cmd PRINTER      # alias for PHYSICAL
```

`stop-local.cmd` stops the project-managed Scanner Bridge together with the rest of the local application. Standalone bridge launchers remain available for focused troubleshooting.

Simulator mode MUST NOT be an automatic fallback when physical acquisition is unavailable.

## Durable local ownership

After a scan is finalized, Scanner Bridge durably owns the prepared submission until LexaVault has definitively accepted/reconciled it.

Required behavior:

- durable session/queue metadata on local disk;
- atomic state transitions;
- restart recovery for waiting, ready, queued, and in-flight submissions;
- one active acquisition session per configured scanner;
- multiple already-queued packages may coexist while the operator scans the next package;
- streaming/bounded-memory hashing and delivery for large PDFs;
- no deletion of the consolidated local PDF until LexaVault acceptance is confirmed;
- after confirmed acceptance, delete the local PDF and scrub locally persisted business identifiers; retain only safe technical state needed for short-lived operational/reconciliation visibility;
- retryable connectivity/server failures remain queued and retry automatically;
- terminal validation/authentication/state failures become `ATTENTION_REQUIRED` and are not retried forever.

## Submission semantics

The operator explicitly selects **Submit** only after the consolidated PDF reaches `READY`.

Submit means Scanner Bridge first commits the intake to its durable queue. The UI may then let the operator start the next package immediately. LexaVault delivery occurs asynchronously and normally starts immediately.

Scanner Bridge uses a tenant-bound machine/integration identity. It does not rely on browser-supplied tenant identity for authorization. LOCAL/demo may use the existing explicit integration headers; production uses the verified OIDC/JWT service identity boundary.

The LexaVault scanner intake contract is manifest-first with exactly one expected PDF and reuses the existing external-intake idempotency/checksum/reconciliation machinery. Repeated create/upload after response loss must converge to the same LexaVault package and document.

## Scanner reporting provenance

Scanner provenance must be stored on the resulting LexaVault package as immutable intake metadata so reporting never has to infer source from logs, filenames, or UI behavior.

Required V1 provenance keys:

- `intakeChannel=SCANNER`;
- `scannerCaptureMode=SIMULATOR|PHYSICAL`;
- `bridgeId`;
- `siteId`;
- `scannerId`;
- `scanSessionId`;
- `scanStartedAt`;
- `scanCompletedAt`;
- `sourceSubmissionId`;
- `sourceDocumentId`;
- `sourceFileSizeBytes`;
- `sourceSha256`.

Package type/source classification is `SCANNER_INTAKE` so existing Reports/Dashboard source dimensions can distinguish Scanner from Manual Upload, API, and Watched Folder.

The common processing lifecycle remains authoritative for review, completion, export, and page-count outcomes. Scanner-specific reports can later use the stored scanner provenance plus common lifecycle facts.

## Operator UX

The normal business-user screen is intentionally simple:

```text
Scan Documents

Business information
  <tenant configured identifiers>

Scanner: Connected

[ Start Scan ]

... acquiring ...

Document received / Ready to submit
[ Submit ] [ Cancel ]

... after durable queue commit ...

Package safely queued / submitted
[ Start Next Package ] [ View Package when known ]
```

Developer-only paths, SHA values, bridge IDs, and prohibited-capability explanations should not dominate the production operator experience. They may remain available in local diagnostics/testing surfaces.

## Failure semantics

At minimum, deterministic behavior is required for:

- incomplete/changing scanner output;
- bridge restart while waiting;
- bridge restart after PDF receipt;
- bridge restart during delivery;
- workstation/network outage after Submit;
- LexaVault unavailable;
- response lost after LexaVault accepted create/upload;
- checksum mismatch;
- backend business-identifier validation change/failure;
- authentication/tenant-source binding failure;
- disk-full/write failure;
- duplicate/replayed delivery.

For every failure the implementation must preserve: where the document is, whether resend is safe, whether the operator may continue to the next package, and whether support can diagnose the state without document content in logs.

## Future Document Intelligence compatibility

Future smart intake may discover configured business-identifier values from the scanned document and suggest/auto-populate them. That feature is NOT implemented in this unit.

Current architecture must preserve these invariants:

- Business Identifier Definitions remain the authoritative schema;
- intelligence discovers values for configured fields rather than inventing fields;
- LexaVault backend validation remains authoritative;
- confidence/provenance must be preserved when intelligence is later introduced;
- Scanner Bridge itself does not become an OCR/AI extraction engine.


## R2 software-completion hardening

Before local E2E validation, the Scanner Intake software path also requires the following enterprise safeguards. These are part of the software feature and are not deferred to physical scanner certification:

- **Browser-to-bridge request trust:** the loopback bridge must issue a per-process browser request token, enforce the configured UI Origin for browser calls, and require the token on every state-changing local bridge request. A random website must not be able to start, cancel, stage, submit, or retry scanner work merely because the service is bound to localhost.
- **Bridge instance/build provenance:** diagnostics must expose a non-secret process instance ID and application version so operators/support can distinguish a restarted or stale local bridge from the current runtime.
- **Local storage guard:** before beginning a new scan, the bridge must verify that its durable work filesystem has the configured minimum usable space. Simulator ingestion must enforce the bridge maximum scan-PDF size while streaming, and storage health must be visible through safe scanner status.
- **Recoverable technical attention:** delivery failures that become `ATTENTION_REQUIRED` must remain durably visible through a privacy-safe local diagnostics contract. After the underlying problem is corrected, support must be able to requeue the same retained package for idempotent reconciliation/delivery without creating a new scan or deleting the only safe local copy.
- **Operator simplicity remains unchanged:** these safeguards belong behind the operator workflow. Normal users still see Scan -> Ready -> Submit -> Start Next Package; technical IDs, storage metrics, retry controls, and bridge instance details remain diagnostic/support information.

These controls must not create scanner-specific downstream processing, change Business Admin ownership, or implement future Document Intelligence.

The R2 browser request nonce is deliberately a browser-to-loopback anti-CSRF/local-request control only. It does **not** establish trusted per-human operator identity or replace production machine/service authentication. Physical scanner certification, Windows Service/installer and filesystem ACL hardening, production hosted-origin/private-network browser validation, trusted operator delegation/attribution, and final throughput/capacity certification remain explicit later production gates.

## R3 trust-boundary correction

Senior review of the R2 candidate found one software-level trust-boundary defect before local E2E: `server.address` / launcher bind configuration could be overridden to a wildcard or LAN interface even though the browser request nonce is explicitly designed only for a loopback bridge. R3 therefore adds a runtime fail-closed guard plus launcher preflight validation so Scanner Bridge startup is allowed only when every resolved bind address is loopback. `0.0.0.0`, `::`, LAN addresses, unresolvable names, and other non-loopback bindings are rejected.

R3 also removes document SHA-256 fingerprints from Scanner Bridge log messages. SHA-256 remains required in durable scanner state, manifest/checksum reconciliation, and immutable LexaVault scanner provenance; it is simply no longer duplicated into logs where it is unnecessary for operations.

All R2 scope/no-regression boundaries remain unchanged.

## R4 browser mutation + unified startup corrections

R4 FIX1 closes a browser integration defect discovered during local simulator use. Scanner Bridge GET health/status calls could succeed while `Start Scan` failed at the browser preflight boundary and the UI incorrectly collapsed that into **Bridge unavailable**. Trusted preflight is now handled directly by the bridge security filter, including Chromium private/local-network preflight when requested, while preserving exact Origin allow-listing, loopback-only binding, and the per-process mutation token. The UI separately reports a blocked mutation versus a genuinely unreachable bridge.

The simulator safe-association sequence is explicit: enter tenant business identifiers, choose **Start Scan**, then **Select Test PDF**, wait for **DOCUMENT RECEIVED**, and finally **Submit**. A PDF that already exists in the simulator inbox before the session starts remains intentionally rejected as stale/unclaimed input.

R4 FIX2 folds Scanner Bridge lifecycle into the normal LOCAL launcher. `start-local.cmd` defaults to `SIMULATOR`, accepts `SIMULATOR`, `PHYSICAL`, or `PRINTER`, and starts the bridge along with PostgreSQL/Ollama as needed, backend, and frontend. `stop-local.cmd` stops the project-managed bridge too. R5 now supplies the Windows WIA acquisition implementation behind the existing PHYSICAL boundary; PHYSICAL/PRINTER never falls back to SIMULATOR.

The Product Owner reports the unified LOCAL startup working. This is a local smoke checkpoint only; the full E2E checklist and focused independent verification remain required before Scanner Intake is marked VERIFIED / FROZEN.

## R5 physical scanner verification gate

The R5 candidate implements Windows WIA acquisition behind the existing adapter boundary. Full Scanner-E2E acceptance now requires one real target-scanner run proving paper -> WIA acquisition -> one consolidated PDF -> READY -> Submit -> correct LexaVault package/document -> normal processing. If the target device or enterprise driver does not expose usable WIA acquisition, that is a concrete device/driver compatibility finding; a TWAIN/vendor-specific adapter may then be added without changing durable queue, submission, processing, review, export, or reporting semantics.

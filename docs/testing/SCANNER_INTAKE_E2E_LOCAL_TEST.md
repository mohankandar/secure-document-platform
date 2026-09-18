# LexaVault V1 — Enterprise Scanner Intake E2E Local Acceptance

**Candidate:** Scanner-E2E-R5 PHYSICAL-WIA  
**Status:** SOFTWARE VERIFIED / FROZEN — REAL PHYSICAL HARDWARE CERTIFICATION PENDING

This checklist is retained as the complete Scanner Intake acceptance/certification procedure. The R5 software baseline is now verified/frozen: simulator/common behavior and the Windows WIA physical software path have been independently verified without hardware. The remaining real target-scanner steps are a deployment/hardware certification gate and do not block software roadmap progression.

## 1. Build gate

From repository root:

```powershell
.\build-all.cmd
```

PASS requires backend reactor/tests and frontend production build to complete successfully. Run the existing frontend authorization suite if the build script does not include it.

## 2. Start local environment

Start normal LexaVault local services/app/UI using the established local procedure, then start Scanner Bridge in simulator mode:

```powershell
.\start-local.cmd SIMULATOR
```

`start-local.cmd` is now the normal local launcher and starts the backend, Scanner Bridge, and frontend together. `SIMULATOR` is also the default when no scanner mode argument is supplied.

Verify:

```powershell
Invoke-RestMethod http://127.0.0.1:10090/actuator/health
Invoke-RestMethod http://127.0.0.1:10090/api/v1/configuration
Invoke-RestMethod http://127.0.0.1:10090/api/v1/scanner-status
```

PASS requires health UP, capture mode SIMULATOR, expected bridge/site/scanner identity, and loopback-only listener.

## 3. UI/configuration ownership

Open **Scan Documents**.

PASS requires:

- business fields come from the tenant Business Identifier Definitions only;
- no bridge-owned First Name/Last Name fields;
- allowed values such as Service Entity render as configured selection choices;
- Manual Upload remains a separate workflow and contains no duplicate Scanner file-picker action;
- business-user scanner surface is simple, with technical details secondary.

## 4. Scanner-less QA happy path

Use one valid PDF representing an entire physical package.

1. Enter valid Contract Number / Member Number / Service Entity values.
2. Select **Start Scan**.
3. In SIMULATOR mode select **Select Test PDF**.
4. Wait for acquisition to finish.

PASS requires one consolidated PDF reaches READY, with source document ID, submission ID, size and SHA-256 generated.

## 5. Explicit Submit / operator throughput

From READY select **Submit**.

PASS requires:

- READY commits to durable QUEUED/SUBMITTING state;
- UI tells the operator the package is safely owned/queued;
- **Start Next Package** is available without waiting for OCR/redaction/review/export;
- a second scanner intake can be started while the first queued package continues delivery.

## 6. LexaVault receipt / common lifecycle

Verify the first scanner package appears in normal LexaVault Dashboard/package APIs.

PASS requires:

- exactly one LexaVault package for the scanner source submission;
- exactly one source PDF/document for the scan job;
- Business Record resolution uses the entered identifiers and existing rules;
- package source/type is `SCANNER_INTAKE`;
- processing uses the normal LexaVault processing dispatcher;
- no scanner-specific OCR/redaction/review/export pipeline is used.

## 7. No-review golden path

Use a test PDF that can complete without Manual Review.

PASS requires:

`Scanner -> Submit -> LexaVault processing -> redaction -> existing approval/policy behavior -> existing delivery/export`

and the package reaches the normal completed/exported state.

## 8. Review-required golden path

Use a test PDF that intentionally requires Manual Review under the existing policy/detector behavior.

PASS requires:

- scanner package appears in the same existing Manual Review workflow;
- reviewer resolves/approves using the normal workbench;
- processing resumes through the existing lifecycle;
- final export/delivery succeeds through the same existing boundary.

## 9. Durable READY restart recovery

1. Acquire PDF until READY.
2. Stop Scanner Bridge before Submit.
3. Restart the bridge in SIMULATOR mode.
4. Reload/revisit Scan Documents.

PASS requires the same READY package is recovered with the same identifiers/source IDs and can still be submitted. No PDF loss and no duplicate package.

## 10. Durable QUEUED/SUBMITTING restart recovery

1. Submit a READY package.
2. Stop the bridge while it is QUEUED/SUBMITTING.
3. Restart it.

PASS requires durable work resumes. If LexaVault had already accepted the create/upload before the response was lost, reconciliation converges to the existing package rather than creating a second package.

## 11. LexaVault/network outage after Submit

Make the LexaVault scanner intake endpoint temporarily unavailable, then Submit.

PASS requires:

- package remains safely queued locally;
- operator may start the next package;
- retry occurs after connectivity returns;
- no duplicate package is created;
- retryable failure does not become silent data loss.

## 12. Terminal validation/authentication failure

Cause a non-retryable server validation/authentication/source-binding error.

PASS requires Scanner Bridge transitions the item to `ATTENTION_REQUIRED`, retains the only safe local PDF, exposes a safe reason/reference, and does not retry forever.

## 13. Large-PDF / bounded-memory smoke

Use a large multi-page scanned PDF representative of high-speed feeder output.

PASS requires acquisition, hashing, durable queueing and upload complete without reading the complete PDF into an unbounded JVM byte array. Observe process memory for gross/unbounded growth.

Performance certification is NOT part of this unit; this is a correctness/memory-safety smoke test.

## 14. Reporting provenance

Inspect resulting package metadata/reporting.

PASS requires immutable Scanner provenance sufficient to distinguish scanner traffic from other intake channels, including at least:

- `intakeChannel=SCANNER`;
- `scannerCaptureMode=SIMULATOR`;
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

Dashboard/report source dimensions must identify the package as Scanner rather than Manual Upload/API/Watched Folder.

## 15. Privacy and local cleanup

After confirmed LexaVault acceptance/reconciliation:

PASS requires:

- local consolidated PDF is removed according to Scanner Bridge policy;
- persisted business identifier values are scrubbed from terminal local state;
- only privacy-safe technical/reconciliation metadata remains for the configured short retention window;
- logs do not contain document text, OCR text, business identifier values, credentials, prompts/model responses, SSN/PAN/DOB or other protected content;
- `ATTENTION_REQUIRED` items are not automatically purged while they may own the only safe copy.

## 16. Physical WIA end-to-end acquisition

This is now a required hardware verification gate for full Scanner-E2E freeze.

Restart with:

```powershell
.\stop-local.cmd
.\start-local.cmd PHYSICAL
# PRINTER is an accepted alias for PHYSICAL.
```

Load a complete paper package in the Windows scanner, enter valid tenant business identifiers, and select **Start Scan**.

PASS requires:

- Scanner Bridge reports `captureMode=PHYSICAL` and `acquisitionAvailable=true` only when a compatible WIA scanner is visible;
- the operator UI does not expose **Select Test PDF** and physical mode never silently falls back to SIMULATOR;
- the physical device acquires the pages and the bridge consolidates them into exactly one PDF;
- the resulting session reaches `READY` with `scannerCaptureMode=PHYSICAL` and a non-empty source document;
- selecting **Submit** delivers that exact PDF to the correct LexaVault package/document using the same durable/idempotent Scanner Intake path;
- normal OCR/redaction/review/export lifecycle continues after receipt;
- cancellation/device errors/no-paper conditions never create a fake successful document.

Default physical configuration is Windows WIA, 300 DPI, `AUTO` source, simplex. If needed for the target scanner, set workstation configuration before startup:

```powershell
$env:SECUREDOC_SCAN_BRIDGE_PHYSICAL_DEVICE_NAME="<exact Windows WIA scanner name>"   # optional
$env:SECUREDOC_SCAN_BRIDGE_PHYSICAL_SOURCE="FEEDER"                                 # AUTO|FEEDER|FLATBED
$env:SECUREDOC_SCAN_BRIDGE_PHYSICAL_DUPLEX="true"                                   # only when desired/supported
$env:SECUREDOC_SCAN_BRIDGE_PHYSICAL_DPI="300"
.\start-local.cmd PHYSICAL
```

If multiple WIA scanners are installed and neither device ID nor name is configured, Windows may present a scanner-selection dialog. If Windows does not expose the target hardware through WIA, record **BLOCKED — DEVICE/DRIVER DOES NOT EXPOSE WIA**; do not substitute SIMULATOR and do not mark physical E2E PASS.


## 17. Browser-to-bridge trust boundary

The normal UI obtains the ephemeral bridge token automatically and keeps it inside the bridge API client; it must not render or persist the token in component state. For a focused local security check, use the configured UI origin explicitly:

```powershell
$origin = "http://localhost:5173"
$config = Invoke-RestMethod http://127.0.0.1:10090/api/v1/configuration -Headers @{ Origin = $origin }
$token = $config.browserRequestToken
$config.bridgeInstanceId
```

PASS requires:

- allowed LexaVault UI origin can call bridge GET endpoints;
- a disallowed browser Origin is rejected;
- state-changing requests without `X-LexaVault-Bridge-Token` are rejected;
- state-changing requests with the current token pass the bridge trust filter when otherwise valid;
- restarting the bridge rotates the token and changes the bridge instance ID;
- the UI detects the new bridge instance, refreshes the nonce automatically, and does not require a browser restart;
- `/api/v1/configuration` is `no-store`; no bridge token appears in application logs.
- starting the bridge with `SECUREDOC_SCAN_BRIDGE_BIND_ADDRESS=0.0.0.0` (or another non-loopback address) fails closed before the bridge is usable; normal `127.0.0.1`/loopback startup still succeeds.

## 18. Local storage / oversized scan guard

PASS requires:

- scanner status reports safe usable-space/minimum-space information;
- when usable space is below the configured minimum, a new scan fails closed before acquisition starts;
- simulator acquisition rejects a PDF larger than the configured bridge scan limit without publishing it as READY;
- partial simulator `.part` artifacts are removed/quarantined safely and no business metadata is logged.

## 19. Technical-attention recovery

Cause a terminal delivery condition that produces `ATTENTION_REQUIRED`, then correct the underlying condition.

PASS requires:

- the item appears in the privacy-safe bridge delivery diagnostics without business identifier values, original local path, or document content;
- the retained PDF remains present while attention is unresolved;
- requeue/retry transitions the same session back to durable QUEUED state;
- the worker reconciles the existing LexaVault package first when one is known;
- successful recovery converges to the same package and does not create a duplicate.

## 20. Remaining production gates after the R5 WIA candidate

Do not confuse the required real-scanner functional gate in section 16 with broader production packaging/hardening work:

- target-scanner WIA device/driver E2E verification is required before full Scanner-E2E freeze;
- add TWAIN/vendor integration only if the target enterprise scanner cannot satisfy the required acquisition behavior through WIA;
- Windows Service/installer, service-account ACLs and enterprise endpoint deployment remain production packaging/hardening work;
- production browser-to-loopback transport/PNA validation for the actual hosted LexaVault origin remains deployment verification;
- trusted per-human operator attribution/delegation across browser -> bridge -> machine-intake boundary remains a production identity follow-up;
- final production throughput/concurrency/capacity hardening remains a later V1 gate.

The bridge request nonce introduced in R2 and retained through R4 is an anti-CSRF/local-browser control; it is not a replacement for a future trusted human delegation identity.

## Acceptance outcome

Record one of:

- **FULL SCANNER E2E PASS — SIMULATOR + REAL PHYSICAL SCANNER VERIFIED**
- **PARTIAL — SIMULATOR/COMMON FRAMEWORK PASS, PHYSICAL HARDWARE VERIFICATION PENDING**
- **LOCAL E2E FAIL — FIX BEFORE FREEZE**
- **BLOCKED — DEVICE/DRIVER ENVIRONMENT**, only when the R5 physical adapter is present but the target workstation cannot expose usable WIA hardware.

Do not mark the complete Scanner Intake capability VERIFIED/FROZEN until real physical-scanner acquisition plus the already-verified common Scanner-E2E path are accepted.

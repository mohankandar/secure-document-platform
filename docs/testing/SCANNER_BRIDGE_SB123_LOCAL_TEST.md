# Scanner Bridge SB-1 + SB-2 + SB-3 — Local Validation

Status: findings-correction local validation procedure after Kiro PASS WITH MINOR FINDINGS (2026-09-09).

## Preconditions

- R7 VERIFIED/FROZEN LexaVault baseline builds normally.
- Java 21.
- Maven available on the development machine.
- Frontend dependencies installed.
- Physical scanner is **not required**.

## 1. Clean backend build

From `backend`:

```powershell
mvn clean install
```

Expected:

- existing R7 tests remain green;
- new `securedoc-scan-bridge` module is included in the reactor;
- Scan Bridge tests pass;
- no existing module regression.

The new module currently contains tests for:

- one waiting scan session with operator-entered business identifiers;
- second concurrent session rejected;
- READY session preserving business-identifier/submission identity;
- stable-file timing;
- stability reset when a file changes;
- normal stable PDF claim;
- stale inbox misassociation guard;
- exactly one of two candidate PDFs claimed per session;
- unsupported file rejection;
- fake-PDF rejection.

## 2. Frontend build

From `frontend/securedoc-ui`:

```powershell
npm ci
npm run build
npm run test:auth
```

Expected:

- production build PASS;
- existing auth tests remain 8/8 PASS.

## 3. Prepare simulator directories

The bridge creates these automatically, but start with them empty for a deterministic test:

```powershell
Remove-Item 'C:\LexaVaultTest\scanner-inbox' -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item 'C:\LexaVaultTest\scanner-bridge-work' -Recurse -Force -ErrorAction SilentlyContinue
Remove-Item 'C:\LexaVaultTest\scanner-bridge-problem' -Recurse -Force -ErrorAction SilentlyContinue
```

## 4. Start LexaVault API and UI

Start the normal local LexaVault API and frontend using the existing local profile.

The UI must be able to load `/api/v1/intake/identifier-definitions`.

## 5. Start Scan Bridge

From `backend`:

```powershell
mvn -pl securedoc-scan-bridge spring-boot:run
```

Default bridge endpoint:

`http://127.0.0.1:10090`

Check:

```powershell
Invoke-RestMethod http://127.0.0.1:10090/actuator/health
Invoke-RestMethod http://127.0.0.1:10090/api/v1/configuration
```

Expected:

- health `UP`;
- bridge/site/scanner IDs returned;
- simulator inbox is `C:\LexaVaultTest\scanner-inbox`;
- prohibited capabilities include OCR, AI extraction, barcode/QR extraction, classification, and business metadata inference.

## 6. Operator business identifiers + normal simulator scan

In LexaVault UI choose **Scan Documents**.

Enter valid tenant business identifiers. For the current reference tenant use a fresh valid set such as:

- Contract Number: `000900009911`
- Member Number: `00900009911`
- Service Entity: `001`

Confirm that no Scanner Bridge-local First Name / Last Name fields appear unless Business Admin has created equivalent authoritative LexaVault business-identifier definitions.

Select **Start Scan**.

Expected UI state: `Waiting for scanned PDF`.

Now copy a synthetic PDF into the simulator inbox:

```powershell
Copy-Item '.\test-data\golden-path-watched-intake-template\01-clean\01_clean_no_findings.pdf' `
  'C:\LexaVaultTest\scanner-inbox\scan-001.pdf'
```

Wait longer than the configured stability window (default 1.5 seconds plus scan interval).

Expected:

- source disappears from scanner inbox;
- session changes to `READY FOR SUBMISSION`;
- UI displays filename, submissionId, sourceDocumentId, size, and SHA-256;
- PDF exists under a session directory in `scanner-bridge-work`;
- no LexaVault package has been created by this bridge unit yet.

## 7. Misassociation guard

With no active scan session, place a PDF in the inbox first:

```powershell
Copy-Item '.\test-data\golden-path-watched-intake-template\01-clean\01_clean_no_findings.pdf' `
  'C:\LexaVaultTest\scanner-inbox\stale-before-metadata.pdf'
```

Immediately attempt to start a new Scan Documents scan session.

Expected:

- start is rejected with `SCANNER_INBOX_NOT_EMPTY`;
- stale PDF is not associated with newly entered business identifiers.

Clear/disposition the stale test file before continuing.

## 8. Incomplete/changing file stability

Start a fresh scan session.

Create or copy a PDF into the inbox while continuing to modify/append it during the stability window.

Expected:

- bridge does not claim while size/last-modified changes;
- stability timer restarts after each change;
- bridge claims only after the file remains unchanged for the full stable window.

## 9. Unsupported file

Start a waiting session, then place a text file in the inbox.

Expected:

- text file moves to `scanner-bridge-problem`;
- reason sidecar contains `UNSUPPORTED_FILE_TYPE`;
- waiting scan session remains waiting.

## 10. Fake PDF

Start/retain a waiting session and create `fake.pdf` containing plain text rather than PDF content.

Expected:

- file moves to problem;
- reason = `INVALID_PDF`;
- waiting scan session is not consumed.

## 11. Two simultaneous PDFs

Start one scan session. Place two stable valid PDFs into the inbox.

Expected:

- exactly one PDF is associated with the session and becomes READY;
- the other PDF remains unclaimed;
- no single operator scan session consumes two different PDFs.


## 12. Bridge availability and restart UX

With Scanner Intake open and showing `Bridge ready`, stop the Scanner Bridge process.

Expected:

- within the availability polling interval the badge changes to `Bridge unavailable`;
- the page does not simultaneously show `Bridge ready` and a bridge-unavailable error;
- if a scan was already waiting, the panel changes to a disconnected/reconnect state rather than continuing to imply that the bridge is actively waiting for a PDF;
- `Start Scan` is disabled while the bridge is unreachable.

Restart the bridge.

Expected:

- availability recovers to `Bridge ready`;
- if an old in-memory session was lost by the restart, the stale waiting state is cleared with an operator-safe message (not a raw `Scan session was not found` error) and the operator can start a new scan;
- SB-4 durable restart recovery is not required in this unit.

## 13. Upload Documents has no duplicate scanner workflow

Open **Upload Documents**.

Expected:

- manual intake exposes drag/drop and `Browse Files`;
- there is no second `Scan Documents` file-picker button inside the manual upload page;
- the sidebar/dedicated `Scan Documents` route remains the scanner workflow.

## 14. V1 negative architecture check

Search the Scan Bridge module for document intelligence/extraction dependencies.

Expected:

- no OCR engine invocation;
- no AI/model invocation;
- no barcode/QR parser;
- no PDF text extraction;
- no classification engine;
- no metadata inference from document content.

The `%PDF-` signature check and SHA-256 hashing are transport-integrity operations and are allowed.

## 15. Privacy-safe logging

Review bridge logs.

Expected logs may include:

- bridgeId/siteId/scannerId;
- sessionId/submissionId/sourceDocumentId;
- size and SHA-256;
- safe reason codes.

Logs must not contain:

- operator-entered Contract/Member/Service Entity values;
- first/last name;
- OCR/document text;
- prompts/model responses;
- SSN/DOB/PAN/credit score;
- credentials/tokens.

## Candidate PASS condition

SB-1/2/3 local validation is PASS when all tests above succeed and the original R7 baseline remains regression-green.

Do not mark Scanner Bridge VERIFIED/FROZEN yet. After local PASS, use Kiro for an independent, verification-only checkpoint. SB-4 and SB-5 remain separate future implementation units.

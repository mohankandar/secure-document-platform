# Unit 22 Verification Plan — Delivery + Lifecycle

**Purpose:** Kiro verification/minimal correction only. Do not reimplement Unit 22 and do not start Unit 23.

## Build/schema
1. `mvn clean verify` — all backend modules SUCCESS.
2. `npx tsc --noEmit` and `npm run build` — frontend clean.
3. Start local PostgreSQL; Liquibase 010 applies and Hibernate validation succeeds.
4. Verify lifecycle/export tables, indexes, FKs and tenant scope.

## A — default successful export/retention
- Business Admin lifecycle defaults: original 24h, redacted cache 24h, technical hold 72h, final approval off.
- Process one high-confidence digital or scanned package.
- Expect COMPLETED, export cycle 1, export/document records, original/redacted deadlines based on **successful export time**, not intake time.
- Customer filename preserved.

## B — redacted cache purge
Use temporarily shortened policy/test timestamps in a controlled verification setup.
- After redacted deadline, run scheduled/admin purge.
- REDACTED internal artifact becomes deleted/unavailable.
- Customer-delivered output remains untouched.
- ORIGINAL remains until its own deadline.
- Audit records the purge without sensitive content.

## C — original purge
- After original deadline, purge.
- ORIGINAL and remaining internal artifacts are deleted.
- metadata/export/audit records remain.
- lifecycle reports purged state and reprocess becomes unavailable.
- no original-download business flow is available.

## D — reprocess cycle 2
- Before original expiry, request reprocess as APPROVER.
- Same intake/package lineage is reused; no new Business Record or Intake Batch is created.
- Existing original is reused; findings/working outputs are regenerated.
- successful cycle 2 is delivered under the original filename without overwriting cycle 1.
- retention deadlines reset from cycle-2 successful export.

## E — three-cycle limit
- Complete cycle 3 successfully.
- Verify reprocess is then unavailable/rejected.
- technical delivery retries must not increment successful cycles.

## F — delivery failure does not start retention or consume cycle
- Force a retryable local/API delivery failure before first successful export.
- package becomes technical FAILED after bounded retry exhaustion.
- successfulExportCycles remains 0.
- originalDeleteAt/redactedDeleteAt remain unset.
- 72h never-exported technical hold is scheduled.

## G — technical-hold extension
- On never-exported FAILED package, extend hold with ADMIN.
- 7-day/14-day style extension within allowed range succeeds and is audited.
- invalid shorter/non-later/out-of-range extension is rejected.
- after hold expiry purge removes internal artifacts.

## H — final approval off
- Normal high-confidence package remains straight-through and completes/delivers automatically.

## I — final approval on
- Enable final approval in Business Admin.
- High-confidence processing/redaction/verification completes but package stops at `PENDING_FINAL_APPROVAL` with no destination output and no post-export retention clock.
- USER/REVIEWER cannot approve.
- APPROVER `Approve & Deliver` resumes automatically, delivers once, records cycle 1, starts retention, and ends COMPLETED.

## J — no duplicate approval/export
- Repeating approval when package is not waiting for approval is rejected.
- Refresh/retry does not create a duplicate successful export record/cycle.

## K — local filename/cycle isolation
- Cycle 1 and cycle 2 both preserve `application.pdf`.
- They land in separate cycle paths and neither overwrites the other.
- Multi-document package remains package-gated.

## L — API/callback provider
Run a local mock HTTP endpoint if practical.
- `securedoc.delivery.type=api` selects API adapter.
- one package-level request contains all documents plus package/cycle context.
- 2xx -> successful export record.
- 429/5xx -> retryable DELIVERY failure.
- 4xx rejection -> no false success (report current classification behavior).
- original filenames preserved.

## M — S3-compatible provider
If MinIO/local S3 or credentials are available:
- `securedoc.delivery.type=s3` selects S3 adapter.
- files preserve names/paths and `_COMPLETE` is written last.
- staging is cleaned after success.
- failure does not publish a completion marker.
If no S3-compatible service is available locally, verify build/config/wiring and report runtime scenario NOT EXECUTED rather than faking it.

## N — scheduler
- Confirm scheduled purge bean is enabled by default and configurable off.
- Use a short verification interval only in test/local configuration.
- Confirm due artifacts are processed without a manual business action.

## O — legacy original access retirement
- Active UI has no Vault navigation/page.
- original artifact download route no longer exists.
- Manual Review page preview still works and remains single-page/REVIEWER scoped.

## P — regressions
Verify at high level:
- Unit 16 digital AI-first straight-through
- Unit 17 scanned OCR path
- Unit 18 technical/business exception separation
- Unit 19 review confirm/dismiss auto-resume
- Unit 20 Business Record/intake reuse
- Unit 21 Business Admin identifier/redaction policy

## Security/audit
- lifecycle/reprocess/approval/purge APIs are tenant scoped.
- reprocess/final approval require APPROVER; technical hold/purge require ADMIN.
- export/retention audit contains no sensitive values or document body text.
- PDF binaries remain outside PostgreSQL.

## Final report
Return build results, migration result, scenarios A–P, exact source corrections (if any), final builds, known limitation(s), and `UNIT 22 APPROVE` or `DO NOT APPROVE`. Stop before Unit 23.

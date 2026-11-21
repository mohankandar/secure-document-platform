# Report 0024: Scan Bridge retry follow-ups from 0023

Date: 2026-11-20
Branch: `feature/0024-scan-bridge-retries` (from the 0023 tip, `2d8c102`; see step 0)
Outcome: DONE. The step 0 merge was refused by the permission check, so the owner needs to run it (command below).

## What was done

**Step 0**
1. `docs/work/0023-report.md` ends with "Owner's answers". Checked.
2. Committed it and this task file on `feature/0023-leaks-and-silent-failures`: `2d8c102 docs(work): 0023 owner's answers; task 0024`.
3. The merge into `feature/lexavault-r1b-tenant-identity` was **refused by the session's permission check**. As the task says, I did not work around it. Command for the owner:
   ```
   git checkout feature/lexavault-r1b-tenant-identity
   git merge --no-ff --no-edit feature/0023-leaks-and-silent-failures
   ```
4. Created `feature/0024-scan-bridge-retries` from the 0023 tip. Once the merge above is done, 0024 merges cleanly on top of it: 0024 only adds commits after `2d8c102`.

**Change 1: 429 is retried**
- `LexaVaultScannerDeliveryClient`: 429 is now retryable. Every other 4xx is still terminal.
- The `Retry-After` header is read as seconds or as an HTTP date (a date in the past means no wait). It is read only on a 429.
- `ScannerSubmissionWorker`: a 429 waits as long as `Retry-After` asks, up to `retry-after-max-seconds` (default 300 s, a new setting).
- With no usable `Retry-After` (missing, unreadable or zero), the 429 uses the existing spacing.
- A 429 counts toward the existing cap of 3. After the cap, the item goes to `ATTENTION_REQUIRED` with its code, as today.

**Change 2: "acceptance pending" is waiting, not failing**
- If LexaVault has the document but has not accepted it yet, that attempt is taken back off the attempt count (`ScanSessionService.waitForAcceptance`).
- The start of the wait is stored on the item as a new field, `ScanSession.acceptancePendingSince`. It is kept in the bridge's local JSON state, survives a restart, and is not a database change. State files without the field read as "not waiting".
- Re-checks use the existing spacing settings: 2 s at first, then growing with the time already waited, capped at 60 s. The last check falls exactly on the limit.
- After `acceptance-wait-limit-seconds` (default 600 s = 10 minutes, a new setting), the item goes to `ATTENTION_REQUIRED` with `LEXAVAULT_ACCEPTANCE_WAIT_EXPIRED`. The screen shows this as "Reference: …", and the log line `SCAN_BRIDGE_ACCEPTANCE_WAIT_EXPIRED` gives the time waited and the limit.
- A real failure during the wait counts toward the cap, and the wait keeps its original start.
- Acceptance, or a person's retry, clears the wait.

**Change 3: documentation**
- New section "Delivery retries and waiting" in `docs/scanner-bridge/SCANNER_INTAKE_END_TO_END.md`. It covers what happens for each kind of LexaVault answer, the cap, the settings and their environment variables, and the log lines.
- Both new settings are in `application.yml`: `SECUREDOC_SCAN_BRIDGE_RETRY_AFTER_MAX_SECONDS` and `SECUREDOC_SCAN_BRIDGE_ACCEPTANCE_WAIT_LIMIT_SECONDS`.

## Commands run and results

| Command | Result |
|---|---|
| `git commit` (step 0.2) | `2d8c102` |
| `git merge --no-ff --no-edit feature/0023-leaks-and-silent-failures` on R1B | **refused by the permission check**, not run |
| `cd backend; mvn -o test -pl securedoc-scan-bridge` | 96 run, 0 failed |
| `.\Build-LexaVault-All.ps1` | LEXAVAULT FULL BUILD PASSED (backend `mvn clean install`, TypeScript check, UI framework contract, Vite build) |
| `cd frontend/securedoc-ui; npm run test:standalone` | passed (counts below) |
| `.\test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` | PASS: 0 matches in the database, 3 logs and the 2 audit exports; throwaway container `lexavault-0015-pg` removed |

## Tests: counts per module, and any failures with their output

| Module | Before (0023) | After |
|---|---|---|
| domain | 62 | 62 |
| application | 262 | 262 |
| scan bridge | 77 | 96 |
| infrastructure | 28 | 28 |
| API | 394 | 394 |
| **Backend total** | **823** | **842** |

Frontend counts are unchanged: test:auth 10, test:vocabulary 149, test:ui-framework 17, test:oidc-signin 137, test:tablet 85, test:a11y 138. No failures.

**Tests changed (stated, as the rules require):**
- `ScannerSubmissionWorkerTest.acceptancePendingIsAlsoCappedAndThenNeedsAttention` was **removed**. It asserted the old behaviour that the owner changed. The new class covers the replacement.
- In `LexaVaultScannerDeliveryClientRetryClassificationTest`, the "every 4xx is terminal" test **no longer lists 429** and was renamed to `every4xxOtherThan429IsTerminalAndKeepsLexaVaultsCode`.
- Two test constructors got the new `null` field.

**New tests:**
- `LexaVaultScannerDeliveryClientRetryClassificationTest` (+5, against a real local HTTP server):
  - 429 with `Retry-After` in seconds
  - 429 with `Retry-After` as a date
  - 429 with no `Retry-After`
  - parsing: a past date, rubbish, negative, blank, a huge number
  - `Retry-After` ignored on a 503
- `ScannerSubmissionWorkerRetryAfterAndAcceptanceWaitTest` (16, with a movable clock):
  - 429 tests:
    - `Retry-After` in seconds
    - `Retry-After` as a date
    - too large (bounded to 300 s)
    - bound is configurable
    - missing (existing spacing 2 s, then 4 s)
    - zero
    - 429 on the first attempt and 3 retries, then needs a person
    - another 4xx is not retried
  - Acceptance-wait tests:
    - pending for less than the limit, then accepted (0 failures counted)
    - spacing, and the last check falls on the limit
    - pending past the limit (needs a person, `LEXAVAULT_ACCEPTANCE_WAIT_EXPIRED`, at exactly 10 minutes)
    - limit is configurable
    - pending mixed with a real failure (only the failure counts)
    - real failures during the wait still reach the cap
    - a person's retry starts a fresh wait
    - the wait survives a restart

## Files changed

- `backend/securedoc-scan-bridge/src/main/java/.../config/ScanBridgeProperties.java`: 2 settings
- `.../model/ScanSession.java`: `acceptancePendingSince`
- `.../service/LexaVaultScannerDeliveryClient.java`: 429 retryable, `Retry-After` parsing, `DeliveryException.retryAfter()`
- `.../service/ScanSessionService.java`: `waitForAcceptance`; the wait is cleared on acceptance and on a person's retry
- `.../service/ScannerSubmissionWorker.java`: `Retry-After` delay and the acceptance wait
- `backend/securedoc-scan-bridge/src/main/resources/application.yml`: 2 settings
- 3 test classes changed, 1 new (`ScannerSubmissionWorkerRetryAfterAndAcceptanceWaitTest`)
- `docs/scanner-bridge/SCANNER_INTAKE_END_TO_END.md`
- This report

Scan Bridge only. No schema change, no dependency, no file deleted, nothing in LexaVault or the frontend.

## Commits

- `2d8c102 docs(work): 0023 owner's answers; task 0024` (on `feature/0023-leaks-and-silent-failures`)
- `feat(0024): Scan Bridge retries 429 with Retry-After; acceptance pending waits up to a time limit` (on `feature/0024-scan-bridge-retries`; hash in `git log`)

Nothing pushed.

## Not done, and why

- **The step 0 merge.** The permission check refused it. The command for the owner is above.

## Questions for the owner

1. **Spacing while waiting.** The task says to check "at the existing interval". I used the existing retry settings: 2 s at first, growing with the time already waited, up to 60 s. That gives about 15 checks in 10 minutes. A fixed 2 s would be about 300 checks, and each check re-sends the PDF; that upload is idempotent, and it is the existing recovery step. Keep this?
2. **What the person sees.** After the limit, the scanner screen shows the existing "needs attention" panel with "Reference: LEXAVAULT_ACCEPTANCE_WAIT_EXPIRED". A dedicated sentence on the screen would be a frontend change, outside this Scan Bridge-only task. Should it be part of the `LV-` codes work?
3. **The local bridge API now also returns `acceptancePendingSince`**, a timestamp only, in the session it sends to the browser. The UI ignores it. Fine as is?

## Suggested next step

- Owner: run the step 0 merge, review, then merge `feature/0024-scan-bridge-retries` into R1B.

# Report 0015: no redacted value is ever saved

Date: 2026-11-19
Branch: `feature/no-redacted-values` (from `feature/lexavault-r1b-tenant-identity` @ `72ded83`)
Outcome: DONE

## What was done

**A1. Snippet removed from the code.**
- `Finding`: `snippet` field, factory and `reconstitute` parameters, and getter removed.
- `FindingEntity`: `snippet` column mapping removed. `JpaFindingRepositoryAdapter`: its mapping removed (new row, update, read).
- `SensitiveDataDetectionPort.DetectedEntity`: `maskedPreview` removed. Every builder updated: `SensitiveDataDetectionService` (AI located, unlocated, OCR-guard review findings and the evidence merge), `OcrSensitiveFieldSafetyPolicy` (2 places), `ManualFallbackService`, `LocalRuleBasedDetectionAdapter`.
- `LocalRuleBasedDetectionAdapter`: `maskSsn`, `maskEmail`, `maskPhone`, `maskDob`, `maskCreditCard` and the `MaskFunction` interface removed. The raw match is used only for offsets and SSN validation, as before.

**A2. Detection and redaction unchanged.** Types, pages, offsets, boxes, confidence, detector names and decisions are untouched. All existing tests pass. The only edits to existing tests remove the preview argument: 3 calls in `OcrSensitiveFieldSafetyPolicyTest`, 1 each in `ReviewFoundTextReaderTest` and `ReviewPagePreviewServiceTest`. No assertion changed.

**A3. Migration `039-findings-drop-snippet.xml`**, registered in the master changelog after 038:
- `039-01-findings-clear-snippet`: sets every non-null `findings.snippet` to NULL. Its rollback is empty.
- `039-02-findings-drop-snippet`: drops the column. Its rollback adds back an empty `VARCHAR(255)` column. No values are restored.
- Both changeSets have a `columnExists` precondition (`MARK_RAN`).
- It ran only on the throwaway database in A6: 2 changeSets applied, and the `findings.snippet` column is gone. It was never run against `securedoc-postgres`.

**A4. Transient value holders guarded.**
- Records that print only type and page (or page number and length) in `toString()`:
  - `DiscoveredSensitiveContent`
  - `SensitiveDataValidationPort.Candidate`
  - `SensitiveContentAnalysisPort.PageContent`, which also covers `AnalysisRequest`
  - `SensitiveDataDetectionPort.PageInput`
  - `TextExtractionPort.PageText`
  - `OcrPort.OcrPageOutput`
  - `OcrSensitiveFieldSafetyPolicy.OcrToken` (private)
  - `TesseractOcrAdapter.WordRow` (private)
- `ReviewFoundText`, `ReviewItemSummary` and `FoundTextResponse` were already redacted.
- Records still compare by their content; only `toString()` changed.

What A4 found in the AI JSON parsing (a real leak path, now closed):
- Jackson 2.18.9 already has source-in-location off by default. But a parse error message still quotes the token it stopped at, for example `Unrecognized token 'SSN412557531'`.
- The Ollama and OpenAI-compatible adapters kept that Jackson exception as the `cause` of the safe `TechnicalDependencyException`. So the model's text travelled with the exception.
- No current log line or audit detail prints the cause: the audit detail uses `TechnicalFailureClassifier` codes, and the logs use reason codes. But any stack trace would print it, and so would any future `log.warn(..., e)`.
- This is proven on the base commit: `NoRedactedValueSavedTest` fails there with `exception chain holds '412557531'`.
- Fix: a new `SensitiveContentResponseParser.readTreeWithoutEcho` parses the answer and the provider envelope. It also switches source-in-location off on the parser. On failure it throws a fixed message with no cause. The two adapters now pass `null` as the cause for parse failures.
- The parser's own rejections already used fixed messages.

**A5. Review after the original is gone.**
- `ReviewFoundTextReader` already returned nothing for these cases: no artifact, two artifacts, a failed artifact, and a read error. Its reader-level tests already covered them.
- Added `afterTheStoredTextIsGoneTheQueueShowsTypeAndPageOnly`. Through `FocusedReviewService`, a deleted text artifact and a missing file both give a queue item with `foundText` null, type SSN and page 2, and no digits in the item.
- No UI change.

**A6. Security test.** Fake values: SSN `412-55-7531`, card `4000 0566 5566 5556` (Luhn-valid test number), date of birth `03/14/1962`. Searched forms:
- the full value as written, plus the card with dashes;
- the digits only: `412557531`, `4000056655665556`, `03141962`;
- the last 4: `7531`, `5556`, `1962`.

The digit forms count only when they are not part of a longer hex or digit run, so UUIDs and timestamps do not match by chance.

*In the JVM*: `NoRedactedValueSavedTest` (`securedoc-api`, no new dependency).
- Setup:
  - the real `SensitiveDataDetectionService` and the real rule-based detector;
  - the real Ollama adapter, answering from a JDK `HttpServer` stub whose answer holds the 3 values;
  - the real `JpaFindingRepositoryAdapter` over a stub Spring Data repository, which captures the `FindingEntity` rows;
  - a capturing audit port and event publisher;
  - a Logback `ListAppender` on the root logger at level ALL.
- Test 1: all 3 types are found, each by both the AI and the rules, and 3 findings are saved. All fields of the saved `Finding` objects, the `FindingEntity` rows, the audit records and the events are dumped by reflection, and every log line is rendered with its arguments, MDC and stack traces. 0 matches.
- Test 2: a malformed AI answer that holds the SSN digits. Searched the exception chain with stack trace, the audit records, the events and the logs. 0 matches.
- Negative control: the same test run against the base commit `72ded83` fails both cases:
  - `saved Finding holds '7531' ... snippet=***-**-7531`;
  - `exception chain holds '412557531'`.

*End to end, scripted*: `test-tools/no-redacted-values/Invoke-NoRedactedValueCheck.ps1`. A throwaway container `lexavault-0015-pg` on 127.0.0.1:55415, a mock Ollama answering with the values, and the built jar in the LOCAL profile.
- Migration 039: 2 changeSets applied; `findings.snippet` column count is 0.
- Package `41ba7608-…` finished processing with status COMPLETED. Findings saved:
  - `CREDIT_CARD_NUMBER:REDACTED:ollama:qwen3.5:9b+local-rule-based`
  - `DATE_OF_BIRTH:REDACTED:…`
  - `SSN:REDACTED:…`
- Match counts (10 search forms each):

| Searched | Matches |
|---|---|
| Database: `pg_dump` after processing, new since the dump before processing | 0 |
| Log `securedoc-platform.json` (JSON rolling file) | 0 |
| Log `backend.out.log` (console) | 0 |
| Log `backend.err.log` | 0 |
| Audit export JSON (`/api/v1/audit-records/export?format=json&includeRoutineActivity=true`, 15 records) | 0 |
| Audit export CSV (same filter) | 0 |

- Result: PASS. Only `lexavault-0015-pg` was removed. No other container was touched.
- Control: the same search over the stored extracted-text file in the run's storage folder finds the full SSN once and the last 4 once. The search works. That file is the original-side text artifact; see question 1.

## Commands run and results

| Command | Result |
|---|---|
| `cd backend; mvn -o clean install` | BUILD SUCCESS, 459 tests, 0 failures |
| `mvn -o test -pl securedoc-api -am -Dtest=NoRedactedValueSavedTest` in a throwaway worktree of `72ded83` (negative control; worktree removed afterwards) | 2 of 2 failed, as expected |
| `test-tools\no-redacted-values\Invoke-NoRedactedValueCheck.ps1` | PASS, 0 matches, exit 0 |
| `cd frontend/securedoc-ui; npm run test:standalone` | all pass |

## Tests: counts per module, and any failures with their output

| Module | Before | Now | Change |
|---|---|---|---|
| Domain | 31 | 31 | |
| Application | 138 | 142 | +1 review queue test, +3 `TransientValueHoldersToStringTest` |
| Scan bridge | 47 | 47 | |
| Infrastructure | 8 | 8 | |
| API | 229 | 231 | +2 `NoRedactedValueSavedTest` |
| **Backend total** | 453 | **459** | |

Frontend: `test:auth` 10, `test:vocabulary` 90, `test:ui-framework` 17, `test:oidc-signin` 69, `test:tablet` 55 (WebKit). Total 241, 0 failures. No frontend file changed.

## Files changed

- Domain: `Finding.java`
- Application:
  - `SensitiveDataDetectionService.java`, `OcrSensitiveFieldSafetyPolicy.java`, `ManualFallbackService.java`
  - ports: `SensitiveDataDetectionPort.java`, `SensitiveContentAnalysisPort.java`, `SensitiveDataValidationPort.java`, `TextExtractionPort.java`, `OcrPort.java`
- AI: `SensitiveContentResponseParser.java`, `OllamaSensitiveContentAnalysisAdapter.java`, `OpenAiCompatibleSensitiveContentAnalysisAdapter.java`
- Infrastructure:
  - `LocalRuleBasedDetectionAdapter.java`, `TesseractOcrAdapter.java` (toString only)
  - `FindingEntity.java`, `JpaFindingRepositoryAdapter.java`
  - `039-findings-drop-snippet.xml` (new), `db.changelog-master.xml`
- Tests:
  - `NoRedactedValueSavedTest.java` (new), `TransientValueHoldersToStringTest.java` (new)
  - `ReviewFoundTextReaderTest.java`, `OcrSensitiveFieldSafetyPolicyTest.java`, `ReviewPagePreviewServiceTest.java`
- Test tool (new): `test-tools/no-redacted-values/` (`Invoke-NoRedactedValueCheck.ps1`, `Start-FakeValueOllama.ps1`, `MakeFakeValuePdf.java`, `README.md`)
- Docs: `docs/work/0015-task.md`, `docs/work/0015-report.md`, `docs/work/ui-enhancements-design.md`

No dependency added. No UI change.

## Commits (hash and message), or "not committed"

One commit on `feature/no-redacted-values`: `fix(security): 0015 no redacted value is ever saved ...`. The owner gets the hash in the session message. Not pushed, not merged.

## Not done, and why

- `CLAUDE.md` "Known state" still says 453 backend tests. I did not edit it because the task did not ask for it. It should say 459.
- I did not fix the old acceptance harness (see question 2), because it is outside this task.

## Questions for the owner

1. **The stored extracted-text file holds the values.** The `EXTRACTED_TEXT` artifact in file storage holds the page text. Review reads the last 4 from it live, so it holds the values until retention removes it. That is by design and outside this task. Do you want a later task to check that retention removes it, and when?
2. **The old acceptance harness uses a role name that no longer exists.** The harness under `test-tools/lexavault-acceptance` sends `X-Actor-Roles: ADMIN`. The current code rejects that with 403 (the valid names are in `ProductRole`). The new script uses `DOCUMENT_PROCESSOR,TENANT_IT_ADMIN`. Should a small task update the old harness?
3. **Parse failures now carry less detail.** AI parse failures now carry no cause, only the fixed message and failure code. Troubleshooting a bad model answer therefore cannot use the Jackson detail. Is that acceptable?
4. **Small changes in frozen-adjacent code under A4.** `TesseractOcrAdapter.WordRow` (OCR) got a `toString()` only, under A4's "any similar record". Behaviour is unchanged. Please confirm this was all right.

## Suggested next step

Owner review of the diff. Then decide on question 1, and register build plan step 2 (audit detail changes) as the next task.

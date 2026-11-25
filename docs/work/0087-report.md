# Report 0087: delivery to a destination through platform-integration

Date: 2026-11-25
Branch: `feature/0087-integration-delivery`, with the integration branch (0065, 0095, and 0091 after the last build) merged in
Outcome: **DONE, with gaps in the live run (below)**. 5 questions. Built and run by a subagent from a written brief. I read the sending, progress, profile and migration code and the changes to the export and connection services, did the merge and repeated the checks marked "me".

## What was done
1. **Delivery** to an external destination runs on platform-integration 1.1.0, driven by the destination's published profile as data (a file in the installation's profile folder; its name and version are fixed when IT saves the connection). Calls go through the outbound rules, secrets through the app's resolver.
2. **`SdvDeliveryAdapter`, its settings and 0052's condition are removed.** The product-name guard has no exception for it any more.
3. **Workflow kept:** export job, retries, pauses, IT queue, retention, codes. Progress is saved after every call that worked; documents go in a fixed order with the same ids as before.
4. **Case fields** go typed, under their own key or by IT's mapping. A sensitive field only when IT lists it; otherwise nothing is sent.
5. **IT's screen:** profile, sign-in, field names, sensitive fields, case level, test. Guide: `docs/install/DESTINATIONS.md`. New code **LV-DLV-016** "Delivery begun with another set-up".

**Migration 067:** two columns (`destination_settings`, `destination_progress`). Its rollback is refused while a delivery is half done. No `int_` tables, no new schema run. **Dependencies:** `platform-integration`, and its testkit in tests.

## Old guarantees
| Before | Now |
|---|---|
| Streamed, one submission, progress told, safe health check, rate limits, tries per mode | kept, tested |
| Counted only when the destination says the submission is complete | **weaker:** the profile has no status call. Every document must be answered with id, checksum and size, else LV-DLV-007 or 008 |
| The answered source id must match | not in the profile; replaced by checksum and size |
| Case level sent with the submission | **no place in the profile:** refused (LV-DLV-003), or on each document when IT chooses so |
| Idempotency and checksum headers, revision | not in the profile; same ids, the destination's own duplicate rules |
| `env:` and `sys:` secret names | the app's entity secret names only |

## Live run
A receiver built from its released tag `sdv-ingestion-v1.1.0` (no packaged release exists) in its own folder, with its own containers and fresh secrets; two LexaVault servers in LOCAL. "Receiver" is submissions and documents for that case.

| Case | LexaVault | Receiver |
|---|---|---|
| Two documents sent | DONE | 1 / 2 |
| Progress lost, all sent again | DONE, LV-DLV-009 | 1 / 2 |
| Server killed mid-send, answer lost | the other server finished, LV-DLV-009 | 1 / 3 |
| Unknown field key; wrong type | LV-DLV-004 (SDV-INT-008; SDV-INT-009) | 0 / 0 |
| Bad document, then a person's resend | LV-DLV-004 (SDV-INT-015), then DONE | 1 / 0, then 1 / 1 |
| Document changed on the way | LV-DLV-007, not counted | not listed |
| Bad sign-in, then fixed | LV-DLV-005, then DONE | 0 / 0, then 1 / 1 |
| Case level above Standard | LV-DLV-003 | 0 / 0 |
| Receiver's database paused; receiver killed; receiver busy at its health check | paused, then DONE (no attempt used where counted) | 1 / 1; 1 / 2; 1 / 1 |
| Contract kit against the running receiver | 4 of 4 | |
| Nothing twice; no secret or token in database and logs | pass | |

**Gaps:** the busy answer to a *create* call was not seen live (LexaVault timed out instead; that check is FAIL in the logs and unit-tested only). The same file in a second case was stored as new, so "already stored" across cases was not triggered. Too large: not run. Sensitive fields to the receiver: not run (LexaVault had no Vault there).

**Upgrade from a 0094 database** (the real old jar, one case half sent): pass. Nothing goes out until IT chooses a profile (LV-DLV-006); then the waiting case is delivered once; the half-sent one stops at LV-DLV-016.

## Commands run and results
| Command | Commit | Result |
|---|---|---|
| `mvn -B -Pdev-build clean install`, PostgreSQL tests on (me) | `ea0a610e` | SUCCESS: domain 87, application 379, scan bridge 144, infrastructure 194 (2 skipped), API 950 (5 skipped), 0 failures |
| No-redacted-value check (me) | `ea0a610e` | PASS, 0 matches |
| Product-name guard (me) | `ea0a610e` | clean (1963 files) |
| The same build | `b1026b58` | SUCCESS, 0 failures (API 937, 4 skipped; infrastructure 194, 2 skipped) |
| Contract kit, published profile and specification | `88302a00` | PASS |
| `npm run build` | `1887a94e` | built |
| `npm run test:standalone`, 1 run | `b1026b58` | 10, 243, 19, 232, 110 pass; **a11y 376 pass, 4 failed** |
| Export end to end, inline and staged | `b1026b58` | PASS |
| Two servers, plain | `b1026b58` | PASS, 35 checks |
| Compliance report check | `b1026b58` | PASS |
| `Build-LexaVault-All.ps1 -SkipTests` | `b1026b58` | PASS |

**The 4 a11y failures** are two keyboard tests over the whole error guide (`keyboard0089.spec.ts`, light and dark), at the 60 s limit. The one tried on the 0094 code times out on this machine too; with a 300 s limit the guide tests pass. No test changed for it. CI decides.

## Existing tests changed or removed
- Removed: three `SdvDeliveryAdapter*Test` and the 4 cases of it in `ExportDestinationAdapterFollowupsTest`; their checks are now in `ProfileDestinationDeliveryTest` (31 cases), except where the table above says weaker or not in the profile.
- Counts and lists: 6 migration tests, 3 error-code tests; mocks added in the sign-in, a11y and tablet specs.

## Behaviour and default changes
- A document store connection without a profile sends nothing (LV-DLV-006) and can't be switched on. The old screen keeps stored settings but can't set them.
- Internal addresses and plain http are refused unless the outbound settings allow them. `platform.outbound.allow-sign-in-none` now works (off; never hosted).
- Only case fields the case holds are sent.

## Not done or not proven
Profile settings have no screen fields. Client certificates are refused. Barcode (split) destinations still use their own adapter. The settings' storage has no PostgreSQL test of its own. `CLAUDE.md` still names the exception (question 4).

## Questions for the owner (recommendation first; fail closed until answered)
1. Case level the profile can't carry: "not sent" as default, IT may choose "on each document"? Yes.
2. LV-DLV-016 has no way out in LexaVault: a small task for IT's "sent already" / "send again"? Yes.
3. Configuration stays in LexaVault's tables? Yes.
4. Remove the known exception from `CLAUDE.md`? Yes (needs your word).
5. Raise the limit of the two guide keyboard tests? Yes.

## What the published profile lacks (for its owner)
The case level, a status call, the source document id in the answer, a listed code for "level refused".

## PLATFORM-CORE (by reading, nothing patched)
`CallEngine` tells its result only at the end of an attempt; two separate runs of per-document steps share `documentsDone`; `IntegrationProfile.complete` makes every mapped field mandatory; `CallEngine.close()` closes the shared `Outbound`.

## For the owner
1. Let the export queue run empty; stop every server; run `Build-LexaVault-All.ps1`; start.
2. Put the profile file in a folder; set `SECUREDOC_DELIVERY_PROFILE_FOLDER` and the outbound settings.
3. Store the client secret as `LEXAVAULT_SECRET_<entity id>_<NAME>`.
4. Open the connection, choose the profile, test, save; retry the waiting cases.

## Suggested next step
Smoke check on the merged head, then stop (design chat, usage budget).

No other product's name or behaviour in code (the former exception is removed).

# Report 0094: platform-core 1.1.0

Date: 2026-11-25
Branch: `feature/0094-platform-1-1`
Outcome: **DONE for the version. Part 2 (batching) has nothing to change: no list or page opens sensitive values.** 2 questions. Built by a subagent; I checked the diff, the test counts in its build log and where the code opens sensitive values.

## What was done
1. **platform-core 1.1.0** (1.1.1 exists only as `rc.1`): `backend/pom.xml`, the version line of `Build-LexaVault-All`, and four documents. No schema step change, migration, dependency or setting. The LV-22 to LV-25 workarounds stay.
2. **It is the released 1.1.0:** 19 jars downloaded from GitHub Packages into an empty repository (since removed) are byte-identical to those used.
3. **A counting test** (run on 1.0.0 first): 25 cases with sensitive values, 20 list, report and export addresses, 6 roles: the key service is never asked to open anything. A list that starts opening values fails it.

## What each place reads
| Place | Key-service opens, before and after |
|---|---|
| Case list, Dashboard, Review queue, final approval, search | 0 (shown as `•••`; search uses the fingerprint) |
| Reports and their CSV | 0 (sensitive fields left out) |
| Support, waiting exports, exports needing IT, derived deliveries | 0 |
| Approval link, intake repeat check | 0 (by reading) |
| The case itself, Processor or Supervisor | 1 (0 for other roles) |
| Export of one case | 1 per attempt |

A record has one envelope, so a case with several sensitive fields is already one call. `readSensitive(list)` is not used: putting it in lists would widen 0061's rule (sensitive values only on the case itself).

## Commands run and results
| Command | Commit | Result |
|---|---|---|
| `CaseFieldsAppPostgresTest` on platform-core 1.0.0 | content of `af66e17a` | 9 pass |
| The same on 1.1.0, with `CaseFieldServiceTest` and `SchemaStep*Test` | `e5a229f9` | 9, 12, 8 and 6 pass |
| `mvn -B -Pdev-build clean install`, PostgreSQL tests on | `e5a229f9` | SUCCESS: domain 87, application 377, scan bridge 144, infrastructure 158, API 956 (4 skipped), 0 failures |
| No-redacted-value check (also a LOCAL start) | `e5a229f9` | PASS, 0 matches |
| Product-name guard | `e5a229f9` | clean (1935 files) |
| `Build-LexaVault-All.ps1 -SkipTests` | `e5a229f9` | PASS; 17 platform modules in the app, all 1.1.0 |

No frontend run (no screen changed). **Left for M2:** two servers, export end-to-end, sign-in, a real Vault on 1.1.0.

## Tests
`CaseFieldsAppPostgresTest` only: one test added, two moved in the order. It also covers one case with another record's envelope among good ones: its values stay hidden, its delivery is refused, the others open. Two early runs failed because of faults in the new test; fixed.

## Behaviour changes
None.

## Not proven
A batch call with a mixed good and bad page: no page would use it.

## Questions for the owner (recommendation first)
1. Keep the single read for the case and for the export? Yes.
2. Close part 2 as "nothing qualifies"? Yes.

## PLATFORM-CORE
By reading: `readSensitive(List)` batches the key calls, but still reads each record from the database on its own.

## Suggested next step
Task 0087.

No other product's name or behaviour in code (the known exception, `SdvDeliveryAdapter`, is untouched).

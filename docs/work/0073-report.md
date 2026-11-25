# Report 0073: P4, priority Urgent / Normal / Low

Date: 2026-11-24
Branch: `feature/0073-priority`, from `dba7715` (0072 merged); 0077 is merged into it
Outcome: **DONE** (5 questions, the recommended answer taken in each)

## What was done
1. **Set at intake:** push and scanner API field `priority`, polling package, watched folder trigger file, New case (upload and scan; the scan bridge carries it). Anything but the three names is refused (`LV-INT-015`; a trigger file: malformed trigger). Part of the request's fingerprint when sent.
2. **Default per source:** every receiving connection and the watched folder, on the Integrations screen (Tenant IT, audited). Used when the source sends none.
3. **A reviewer changes it** in the case panel (audited, `priority=NORMAL->URGENT`). Waiting jobs move at once; a running job finishes, and the case's next jobs have the new priority. A job being queued at that moment is not left behind (a lock per case; tested with two transactions, also that the change does not wait for a step that is saving).
4. **Order:** every stage queue and the export queue: Urgent, Normal, Low, oldest first, entities still taking turns within a priority. The Review queue and the cases that need you: the same, oldest case first. A case row says Urgent or Low.
5. **One in five:** after four Urgent jobs in a row the next is a Normal one if one waits; after four Normal ones without a Low one, the next Normal turn is Low's. Per server and stage.
6. **Migration 082**, two tables with rollback. Not a column of the case: processing writes that whole row and would undo a reviewer's change.
7. API documents: `EXTERNAL_INGESTION_V1.md`, `POLLING_INGESTION_V1.md`.

## Commands run and results
| What | Result |
|---|---|
| `mvn -B -o -fae -Pdev-build clean package` with a throwaway PostgreSQL | 85 + 319 + 136 + 141 passed; 859 with 2 failed and 1 skipped (a new test's own mistake; an audit detail's order) |
| After fixing both: `clean package -Dtest=CasePriorityTest,CreatePackage*,Audit*,ExternalIntake*,Package*` | 4 + 47 + 43, 0 failed |
| The whole set again, `clean test` with PostgreSQL, after merging 0077 | 85 + 319 + 137 + 141 + 860, 0 failed, 1 skipped |
| `JdbcCasePriorityPostgresTest` (in the run above) | 8, 0 failed |
| `npm run test:standalone`, once | 10 + 217 + 19 + 195 + 95 + 168, 0 failed |
| `load-run.mjs 1000 4g 8192 priority` | 10 of 10 checks |

**Load run** (one server, mock AI, the frontend run beside it): 1,000 Normal cases of one document, then 31 Urgent ones when 852 still waited.
- The first Urgent case was exported at **place 164 of 1,031**. 148 were done when it was sent; the 15 others were already under way.
- The 31 Urgent cases took places 164 to 202, with **8 Normal cases among them**.
- All 1,031 exported, no job twice, memory at most 3,208 MB. The learned AI limit ended at 6 with a mock that takes 5, as in 0067.

## Limits
- With `pipeline-mode: inline` (today's default) cases are still worked on in the order they come; only the export queue (export-mode job) follows priority. The case's planning step in staged mode is also first come, first served.
- Review lists what it always did: the newest 250 cases of the entity, now sorted.
- The guarantee is per server; two servers each give one in five.

## Tests changed
- Six migration roll-back tests (one change set more: 082). `StageJobsMigrationPostgresTest` and `VisionMigrationPostgresTest` queue jobs on a schema rolled back below 082 with today's SQL, so they create the table it reads.
- `sensitivity.spec.ts` (three priority tests added), `adminIntegrations.spec.ts`.

## Questions for the owner
1. **"The oldest Normal job" against turns between entities:** on Normal's turn the entities still take turns, and it is the oldest job of that entity. Recommended: keep (the task asks for fairness within a priority).
2. **A wrong priority is refused**, unlike an unknown sensitivity level (taken as Standard). Recommended: keep.
3. **Who may change it:** whoever may review (manual review). Recommended: keep.
4. **Urgent with only Low waiting:** the design guarantees Normal against Urgent and Low against Normal, so Low waits while only Urgent and Low wait. Recommended: keep.
5. **Compliance appendix:** the details of the two new audit actions print as "—" (the reviewed list is unchanged). Recommended: leave.

No other product's name or behaviour in code.

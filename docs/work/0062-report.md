# Report 0062: R1, redaction catalog, regulation packs, locked items, styles

Date: 2026-11-24
Branch: `feature/0062-r1-redaction-catalog`, from the newest integration branch (`2a2ebe8`)
Outcome: **DONE** (5 questions)

## What was done
- **Catalog** (`RedactionCatalog`): one entry per kind (12): name, detection method, default style, on by default, locked, last-4 allowed. It replaces the fixed lists in `TenantRedactionPolicy`. `FINANCIAL_ACCOUNT` and `GOVERNMENT_ID` are removed; a stored finding of either is still read, as `CUSTOM`.
- **Packs** (`RegulationPack`): GLBA, PCI DSS, HIPAA, FRCP 5.2, each with what it covers and what it doesn't. Turning one on turns its kinds on; single kinds can then be turned off by hand. Turning one off leaves its kinds on.
- **Locked items:** full card number, CVV, PIN. Always on, always removed in full: enforced in the policy, the save, the redaction and by a database check. New pattern detector for CVV and PIN, label needed (`LockedItemDetector`). The AI is not asked for them.
- **Styles:** remove fully (default for every kind), or keep the last 4 for SSN, ITIN, EIN, routing, account, driver's license, passport. The style in force when a document is searched is stored on its findings, so a change reaches new documents only.
- **Keep the last 4:** the whole value is covered; the four digits are read again from the original at redaction time and drawn white on the end of the cover. The final check covers both parts. Removed in full instead when not certain: page read by OCR, not a single line, fewer than 8 characters, not 9 digits for the nine-digit kinds, touching another redaction, marked by hand, or the four digits are a redacted value elsewhere in the document.
- **Migration 070:** `tenant_redaction_catalog`, `tenant_regulation_packs`, `tenant_redaction_policies.locked_items_on`, `findings.redaction_style`. Copies and then drops the four V1 columns and `tenant_redaction_kinds`. Rollback restores them (packs and styles are lost; CVV and PIN findings become `CUSTOM`).
- **Audit and compliance report:** the change record adds `packs=` and `keepLastFour=` only when there are any. The report lists packs and styles in force and their changes.
- **Screen:** Admin, Redaction & retention: packs, locked chips, styles. Proof picture `0062-proof/01-policy-catalog.png` (shortened catalog from the test).

## Existing entities keep today's behaviour
The task says both "locked items always on" and "existing entities keep exactly today's behaviour". Taken: an entity that existed before migration 070 keeps exactly its kinds until its policy is next saved; that save turns the locked items on (the screen says so first). New entities have them at once. Proved: migration test over all 16 combinations of the four switches, with and without opt-in kinds, and with no policy row; detection test (card off, CVV and PIN on the page: same findings, same AI request).

## Commands run and results
| Command | Result |
|---|---|
| `mvn -B -o -Pdev-build clean verify`, PostgreSQL on (throwaway `lexavault-0062-pg`) | domain 83, application 308, scan bridge 136, infrastructure 118: 0 failures. API 734 (1 skipped): **2 failures**, see below |
| the 2 failed classes and the redaction tests again (after the last code change) | pass; jar built |
| `npm run test:standalone` suites, 1 run | auth 10, vocabulary 194, ui-framework 17, oidc-signin 170, a11y 156: pass. tablet: 94 pass, **1 failed**, see below |
| no-redacted-value check | PASS, 0 matches |
| compliance report check (4 new lines: packs, styles and their changes) | 120 checks, 0 failed |
| product-name guard | clean |
| after merging the integration branch (0057): `mvn -B -o -Pdev-build clean verify`, PostgreSQL on | BUILD SUCCESS: domain 84, application 303, scan bridge 136, infrastructure 116, API 767 (1 skipped), 0 failures |
| after that merge: type check, `test:vocabulary` | pass (200) |

- `SchemaStepPostgresTest`: my throwaway database had the same word as user and password, so "the password is never printed" matched the user name. Passes with a different password.
- `OllamaModelPreloaderTest`: failed in the full run, passes alone; taken as a timeout on a loaded machine, not traced.
- The browser suites were started twice: the first time another session held their port and they did not run.
- The tablet failure: the first page load timed out (60 s) before the card showed, on a loaded machine. Repeated 3 times on each of 5 sizes: 15 of 15 pass.

## Existing tests changed
- `PostgresTestDatabase.backTo050()` counts the change sets itself, so later migrations don't break it.
- Rollback counts (one more change set) and tests of migrations 047, 048 and 050, which now look at the storage of 050. The old table's store test is replaced by the catalog's.
- Expected audit and report texts now include CVV, PIN and the card (locked): `OptInKindsPolicySaveTest`, `SensitiveDataPolicyAuditDetailTest`, `ComplianceReportServiceTest`. `OptInPatternKindsDetectionTest` reads the catalog. `words.test.ts` reads `RedactionCatalog.java`.

## Not done, or left for the milestone
- Left for M1: export end-to-end (its set-up now copies the catalog rows), 3 frontend runs, two-server proof.
- **The benchmark text path was not run** (queue C uses the GPU). No V1 detector, AI prompt or default threshold was changed; the detection tests pass.
- No picture of a redacted page: the test reads the drawn digits back by OCR instead.
- A two-server upgrade with mixed versions is not supported by 070 (dropped columns): stop the servers first.

## Questions for the owner (recommended one taken)
1. Existing entities: locked items from the next save (taken), or at the migration?
2. Scanned pages never keep the last 4 (a misread digit would be shown as fact). Accept?
3. HIPAA pack without names, phones, emails and record numbers (not in the catalog); the screen says so. Accept for V1?
4. A 4-digit PIN that also appears elsewhere in the document (for example as a year) stops the case at the final check, as a last-four SSN does today. Accept?
5. Turning a pack off leaves its kinds on. Accept?

## Suggested next step
Task 0063.

No other product's name or behaviour in code.

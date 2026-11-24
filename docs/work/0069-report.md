# Report 0069: pattern types with check digits, digitally signed PDFs

Date: 2026-11-24
Branch: `feature/0069-patterns-signed`, from the integration branch (`a28ba5e`)
Outcome: **DONE** (4 questions). 0066 is stopped; this task doesn't depend on it.

## Already there (0043, 0047, 0050, 0062)
All named kinds, their labels, the ABA and Luhn checks, EIN prefixes and ITIN groups, their catalog entries and packs; a signed PDF is processed and recorded on the case.

## What was done
- **Driver's license:** only the common US state formats, listed in `docs/operations/REDACTION_CATALOG.md` (new: kinds, checks, packs). Before, any plausible mix of letters and digits counted.
- **SSN ranges** (000, 666, 9NN, group 00, serial 0000): not found without an SSN label, as before. With a label they are **still covered**, with confidence 0.75 instead of 0.95, so at the default level a person looks.
- **GLBA pack:** EIN added (the task: tax ID). No entity's switches change; one with the pack on sees "Turned off by hand: EIN".
- **Signed PDFs:** the audit says "Original was digitally signed; the signature no longer applies to the redacted copy" and the document id, no signer. The compliance report counts them (section 4) and the full edition prints the sentence; `AppendixDetailAllowList`, a reviewed list, gets this one entry.
- No migration, no dependency, no screen change. `Redaction-Capabilities.md` is not on this machine: the task text was used.

## Commands run and results
Built in an isolated copy with `mvn verify`, nothing installed, shared repository read only (rule 11).

| Command | Result |
|---|---|
| `mvn -B -o -Pdev-build verify`, PostgreSQL on (throwaway, removed) | BUILD SUCCESS: domain 84, application 314, scan bridge 136, infrastructure 133, API 793 (1 skipped), 0 failures |
| the same after merging 0058 (before the PR was merged; the PR was opened one merge too early) | BUILD SUCCESS: domain 85, application 318, scan bridge 136, infrastructure 130, API 805 (1 skipped), 0 failures |
| no-redacted-value check (before that merge) | PASS, 0 matches |
| compliance report check (before that merge) | 126 checks, 0 failed |
| product-name guard | clean |

Two builds before it failed on expectations this task changes (GLBA pack, report rows, allow-list count); see below.

## Existing tests changed
`RedactionCatalogTest` (GLBA has EIN), `ComplianceReportServiceTest` (9 rows, not 8), `AppendixDetailAllowListTest` (93, not 92), `SearchableRedactionServiceTest` (the new sentence). Detector versions go up (1.7.0, 1.2.0).

## Not done, or left for the milestone
- The signed PDF in the test has a signature field and dictionary, not a valid certificate.
- The compliance check does not contain a signed document; the row is covered by the unit test.
- Benchmark text path: not run (GPU is queue C's).

## Questions for the owner (recommended one taken)
1. A labelled SSN in a range never issued: cover it and ask a person (taken), or don't match it? The task's "must NOT match" would leave a misread SSN visible.
2. This makes V1's labelled test SSNs (987-…) go to review at 80%. Accept?
3. Signer name: never recorded (taken), even when the page shows it. Accept?
4. Driver's licenses outside the listed formats (Washington, other countries) are no longer found. Accept?

## Suggested next step
Answers for 0066.

No other product's name or behaviour in code.

# Queue restart c, 2026-11-23: summary

| Task | Outcome | Branch | Final commit | Report |
|---|---|---|---|---|
| 0043 R1 part 1, new pattern types | DONE | `feature/0043-r1-patterns` | `6d574f5` | `docs/work/0043-report.md` |
| 0044 R2 part 1, searchable redacted pages | DONE | `feature/0044-r2-searchable` (from 0043) | `558be9f` (this summary follows it) | `docs/work/0044-report.md` |

Nothing merged into `feature/lexavault-r1b-tenant-identity`; nothing pushed. Work done in the scratch worktree `C:\dev\lexavault\lv-queue-b`; your app and main checkout were not touched. Throwaway containers (`lexavault-0043-pg` and the check scripts' own) removed; protected containers not touched. The strict product-name guard is clean on both branches.

Proof on each branch: full backend build with the PostgreSQL tests on (0 failures), `npm run test:standalone` (all pass), the no-redacted-value check (0 matches), the compliance report check (116 checks, 0 failed); for 0044 also `Test-LexaVault-ExportE2E.ps1` (8 passed).

## For the owner
1. **0043:** Admin › Policy › Sensitive data lists six new kinds, all off: bank routing number, bank account number, ITIN, EIN, driver's license number, passport number. Migration 047 (`tenant_redaction_kinds`) is in the master changelog, as approved.
2. **0043 benchmark (text path, part A):** the new kinds find routing 14/26, account 15/26, ID numbers 13/22 with 2 false flags; with vision added 23/26, 23/26, 22/22. Check-line routing/account from text alone: 3/10 (OCR garbles the MICR line). The V1 four are unchanged (72/97, as in the 0040 run).
3. **0043 questions:** "SSN or ITIN" gives two findings on one box when both are on (OK?); "license number" alone counts as a driver's license label (keep?).
4. **0044:** "Searchable redacted pages" is on by default. Look at a redacted PDF: same picture; the words around the boxes can be searched and copied. New stop code `LV-RED-005` when a redacted value appears in the output text.
5. **0044 timing (60-page package):** +0.95 s per redacted page; file +0.1%; page stays 150 DPI. Also a second text read of the original for the final check: 13 s on that package (OCR of scanned pages).
6. **0044 questions:** storage of the switch as a row in `tenant_redaction_kinds` (keep, or a column in a later migration?); limit the second text read to pages with findings?; short values (under 4 characters) not compared, only proven covered (OK?); a value printed unredacted on another page now stops the document (OK?).
7. Merge `feature/0043-r1-patterns` and `feature/0044-r2-searchable` when you have reviewed them (0044 contains 0043).

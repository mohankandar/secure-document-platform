# Report 0082: error code guide for customers (page and PDF)

Date: 2026-11-25
Branch: `feature/0082-error-code-guide` (from 620b2ee, PR #31; nothing newer when it was opened)
Outcome: **DONE; the 161 support texts wait for a review.** Built by a subagent from a written brief. I read all 161 texts, the start code, the links, the who-acts table, the build check and some of the adjusted tests, looked at two pictures, and ran every command in the table myself.

## What was done
- **One source**: `docs/api/error-codes.md` with the catalogue. New per code: "Support tells the customer". The page, the PDF and the app's lists are generated from it.
- **Page**: account menu, Help: error codes; `/help/error-codes` and `/help/error-codes/LV-XXX-NNN`. Search by code or words, grouped by area, no API call, every role.
- **Links**: one component, in a new tab (nothing unsaved is lost). Every code on Reports links to its entry (tested).
- **PDF**: `backend/securedoc-infrastructure/target/LexaVault-error-codes.pdf`, 57 pages, written by the build's tests without a new plugin; the same texts give the same file. A test opens it and checks every code and text. `Build-LexaVault-All` fails without it (not made with `-SkipTests`).
- **Who acts**: user, IT Admin, support (and Nobody) from one table; an unknown name fails the build. The catalogue's own words stay beside it.
- No migration, no dependency. **No other product's name or behaviour in code.** Pictures: `docs/work/0082-proof/` (`pdf-page-2.png` was taken before the wording correction below).

## Commands run and results
Isolated copy of the committed tree, nothing installed (rule 11).

| Command | Result |
|---|---|
| `mvn -B -o -Pdev-build verify -fae`, PostgreSQL on (throwaway, removed), run alone | BUILD SUCCESS: domain 87, application 377, scan bridge 144, infrastructure 158, API 941 (3 skipped), 0 failures |
| no-redacted-value check; product-name guard | PASS, 0 matches; clean (1928 files) |
| Frontend (base 15275): auth, vocabulary, ui-framework, oidc-signin, tablet, a11y | 10, 238, 19, 231, 105, 188 passed |
| One text removed (LV-DLV-002's support text, in a copy; run by the subagent) | BUILD FAILURE: "codes without a guide text: [LV-DLV-002: Support tells the customer]"; restored: success |

**Failures on the way:** the subagent's two backend runs each ended with one API test that timed out on a local server, a different one each time (`NoRedactedValueSavedTest`, `OllamaModelPreloaderTest`); both passed alone 3 of 3, and in my full run on a quiet machine. Load is likely, not proven; the tests are unchanged.
**Corrected in my review:** "sending system" is now "source system" (13 texts).

## Existing tests changed
`reportTabs.spec.ts` (no links to cases, and exactly 5 code links), `LvErrorCodeFrontendFileTest` (new fields), `LvErrorCodeGuideTest` (a helper moved, tests added), `words.test.ts`, `screens.spec.ts`, `tablet.spec.ts` (added lines). No check weakened.

## Codes shown without a link
Toasts; rows that are a link themselves (Waiting for you, Support problems: the page they open has it); the Support reason filter; the watched-folder hint on Admin Overview (not checked fully); raw reasons under Technical details; older pages except scanning; API answers, CSV files, logs and the Scan Bridge.

## Not proven
- A production web server must answer `/help/...` with the app's start page. Not tried.
- Links were tried in the browser on Reports, Support status and No access; elsewhere by a source check and types.
- The PDF has real text, language, title, bookmarks and linked contents, but no tagged structure.
- The build scripts: the `.sh` was only syntax-checked, neither was run.

## Questions for the owner
1. Review the 161 texts (owner or design chat).
2. **A code's link opens the guide in a tab without a session, so the guide can be read without signing in** (`main.tsx` shows it alone; the sign-in code is untouched). Its texts are static and in the public web files anyway. Accept? (recommended: yes)
3. Is the grouping right: source system and Business Admin = User, Platform Ops = Support?
4. Where does the PDF go in a release (0088)?

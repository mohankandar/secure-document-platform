# Report 0089: accessibility check of the new screens, and the accessibility statement

Date: 2026-11-25
Branch: `feature/0089-accessibility` (from 1cb33b5, PR #32; nothing newer when it was opened)
Outcome: **DONE; known exceptions below.** Built by a subagent from a written brief. I read the source changes, the shared test helpers, the statement and the keyboard write-up, and ran every command in the first table myself. I only checked the new test files for skipped tests and disabled rules; I did not read them line by line.

## What was done
- **Automated:** axe, WCAG 2.0 and 2.1 A and AA, no rule turned off, on every listed screen and over 60 more states (dialogs, refused, empty, not loaded), light and dark. Also 320 px, 200% zoom and text spacing, in light.
- **Keyboard only:** written up per screen in `docs/work/0089-keyboard.md`, as tests that run with every `test:a11y`.
- **Statement:** `docs/product/ACCESSIBILITY_STATEMENT.md`. The task says U026 wrote one; none was found in the repository (only a table in U028), so this is the first.
- No backend change, no migration, no dependency. `tokens.css`, `index.css`, the `ui-*` components and the contract test are unchanged. **No other product's name or behaviour in code.**

## Found and fixed
| | Was |
|---|---|
| Contrast | Danger notice and eyebrow 4.41:1 (light); failed-action message 2.53:1 (dark); danger button on hover 1.06:1 |
| Names | Admin tabs had no name at 640 px or less; two hints were not tied to their fields |
| Reflow | Sideways page scroll on Entities, Notifications, Case fields, Platform overview, Integrations, own range in Reports |
| Keyboard | Two scrolling tables not reachable; radio groups and tabs without arrow keys; 1 px focus ring on a wrong field |
| Focus lost | After closing sheets; while saving a priority or Migration; on Remove and Clear search |

Audit Type and Support Show had the arrow-key defect too and got the same fix (outside the listed screens).

## Result per screen
Automated and keyboard pass on 0058, 0059, 0061, 0062, 0071, 0073, 0074, 0076, 0078, 0079, 0081 and 0082, with these exceptions: 0074 and Reject opened from Review have no 320 px layout, and drawing a mark needs a pointer (owner accepted both, U021 and U026); after 0076 Approve and 0079 Save times no control has focus.

## Commands run and results
| Command | Result |
|---|---|
| `tsc --noEmit`; `npm run build` | 0 errors; built |
| Frontend (base 15275): auth, vocabulary, ui-framework, oidc-signin, tablet, a11y | 10, 238, 19, 231, 105, 378 passed, 0 failed |
| product-name guard | clean (1934 files) |
| Backend | not run locally: no backend file changed (CI builds it) |

Run by the subagent, not repeated by me: a label and the sheet's close were removed in turn; axe and the keyboard tests failed, then both were restored. Its full a11y run had 2 failures (the dark message), fixed before my run.

## Existing tests changed
`screens.spec.ts` and `keyboard.spec.ts`: helpers moved to `scan.ts` and `keys.ts`; no assertion changed. The ring measurement in `keys.ts` now accepts more: a field's border plus shadow of one colour, the search box's ring, and a ring still fading in (waits 250 ms). **The calendar button inside a date field counts as 2 px without being measured** (the browser draws it; seen in one picture).

## Not fixed (in the statement)
- The error guide PDF and compliance PDF are untagged.
- A refused priority or migration change is told only by a 4-second message.
- Focus on no control after Activate, Save times, Approve selected.
- Older wide tables in Admin and Support were not checked at 320 px.

## Not proven
- No screen reader, voice control or forced colours; nobody with a disability tested it. Edge only, and WebKit for tablets.
- Answers are mocked; not seen in the real app. Loading states: only Reports Daily and the approval link.
- The colour contrast of focus rings was not measured again. `test:a11y` takes about 18 minutes now.

## Questions for the owner
1. The contrast and focus-ring fixes are in `studio.css` and `admin.css`. I took the frozen framework to be the tokens, `index.css` and the `ui-*` components. Agree?
2. Keep refused changes on the screen, not only in the 4-second message? (recommended: yes, small task)
3. Put focus on the result after an action whose button goes away? (recommended: yes, same task)
4. Check older Admin and Support tables at 320 px? (recommended: yes, same task)
5. Tag the two PDFs? (recommended: after the first release, unless a customer asks)
6. The statement names task numbers so it can be traced. Remove them before it goes to customers? (recommended: yes)

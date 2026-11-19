# Report U019: final polish from the design chat's live review

Date: 2026-11-19
Branch: feature/ui-polish
Outcome: DONE. One note for the owner on fix 3 (see "Questions for the owner").

## What was done

| # | Fix | What was done | Left |
|---|---|---|---|
| — | Also include: `.cf-strip*` rules, `CLAUDE.md` counts | Already done in `d1a4cbf` (U018 second follow-up). The counts in `CLAUDE.md` were updated again for this task's new tests (`test:vocabulary` 85, `test:oidc-signin` 69). | — |
| 1 | Page width | `.cf-main` has no `max-width`, only the canvas padding. Cases, Review, Reports, Support, Audit and every Admin and Platform tab now use the full width. The centred columns stay: New case 1052 px, the problem page 1052 px, No access 520 px. The problem page was 1100 px of content; the canvas is a 1100 px main less its 24 px padding each side, so 1052 px, the same as New case. | — |
| 2 | New case drop zone | One centred line, "**Drop PDFs here** or <u>choose files</u>", with the rules line centred under it, as in `NewCase.html`. The order note was removed: the list already numbers the files ("1 · name") and has up and down buttons. | — |
| 3 | Cases filter placeholder | Two field names that end in the same word now say it once: "Filter by contract or member number". The box measures the text with the input's own font, at its current width, and shows "Filter cases" when the text doesn't fit. It measures again on resize and once the web font has loaded. If it can't measure, it shows "Filter cases" (fail closed). | See question 1: the default loan wording doesn't fit either. |
| 4 | Admin form controls | On Admin cards, text inputs, dropdowns and the confidence slider stop at 480 px. Number-and-unit fields are 96 px plus their unit. Mode cards, tables and long text keep the full width. The connection editor sheet keeps the canvas's full-width fields. Checked at 1920 px on Redaction & retention, Case fields, the connection editor, Platform and Branding. | — |
| 5 | Review, empty queue | The "0 decided this session" meter and its bar are hidden when there is nothing to review. | — |
| 6 | Case panel highlight | "Keeping and deleting", "Redacted copy expires" and "in 18 hours" are each kept on one line. The summary wraps only between them, and the yellow highlight keeps its padding on both lines. | — |
| 7 | Integrations row actions | The backend has only a scan endpoint for the installed watched folder (`POST /platform-admin/watched-folder/scan`), with no edit and no switch-off. So that row keeps Check now and adds a short muted line under it: "Set up by the install configuration". When the folder is a real connection, its row already has Switch off and Edit, like every other connection. No backend change. | — |

## Commands run and results

- `npm run build` (in `frontend/securedoc-ui`): passed.
- `npm run test:standalone`: passed, 0 failures:
  - `test:auth` 10;
  - `test:vocabulary` 85 (was 83, plus 2 new);
  - `test:ui-framework` 17;
  - `test:oidc-signin` 69 (was 67, plus 2 new).
- **Proof pairs at 1920 px**, light and dark, in `compare/U019/` (not committed): 54 images.
  - Every U014–U017 screen is there: 23 screens, both themes.
  - Plus four U019 shots:
    - `U019-ReviewEmpty`;
    - `U019-CasePanelSoon` (the panel 18 hours before expiry);
    - `U019-CasesContract` (Contract Number and Member Number fields);
    - `U019-FolderRow` (the installed folder with its note).
  - Personas now take their capabilities from `e2e-oidc/policyCapabilities.ts` (`capabilitiesFor`), so each role has exactly what `ProductAuthorizationPolicy` grants.
  - The script is the U017 one with these changes. It was copied into `e2e-oidc/` only while it ran, then removed. It is kept at `C:\Users\mohan\AppData\Local\Temp\claude\b95672b1ca4b9404\0331a9a3-1c90-4683-9624-3cc6a32d836f\scratchpad\zz-u019-proof.spec.ts`.
- **Width check** (the same script, `WIDTHS=1024,1440,1920,2560`): 12 screens at each width, as Supervisor, Business Administrator, IT Administrator, Platform Operations and no role. **Nothing scrolls sideways at any width.**

  | Screen | 1024 | 1440 | 1920 | 2560 |
  |---|---|---|---|---|
  | Cases, Review, Admin, Support | 992 | 1392 | 1872 | 2512 |
  | New case | 992 | 1052 | 1052 | 1052 |
  | Problem page | 992 | 1052 | 1052 | 1052 |
  | No access | 520 | 520 | 520 | 520 |

  The width check ran in light only. The theme changes colours, not layout. Both themes were checked by eye in the 1920 pairs.
- **Rules:**
  - no raw hex added;
  - no new `@media` breakpoint;
  - no new dependency (`package.json` unchanged);
  - `src/index.css`, `uiFrameworkContract.test.ts` and the legacy pages are unchanged.

## Tests: counts per module, and any failures with their output

- Counts as above. No failures in the final run.
- **Added:**
  - `words.test.ts`: the shared-word rule, and the fit check with its fallback.
  - `review.spec.ts`: an empty queue has no meter.
  - `adminIntegrations.spec.ts`: the installed folder has Check now only, with the note, while a connection row keeps Switch off and Edit. `openIntegrations` takes an optional list of connections.
  - `newCase.spec.ts`: two lines added to the existing test. They check the one drop line and that the order note is gone.
- **During the work:** my first folder assertion went into the existing Receiving test and failed. That test's folder is a real connection, which correctly has Switch off and Edit. I put that test back unchanged and wrote the separate test above. This was a test mistake, not an app fault.

## Files changed

- `frontend/securedoc-ui/src/studio/`:
  - `studio.css`: fixes 1, 2 and 6;
  - `support/support.css`: fix 1;
  - `admin/admin.css`: fixes 4 and 7;
  - `UploadCapture.tsx`: fix 2;
  - `Desk.tsx` and `words.ts`: fix 3;
  - `words.test.ts`;
  - `Review.tsx`: fix 5;
  - `CaseDrawer.tsx`: fix 6;
  - `admin/IntegrationsArea.tsx`: fix 7.
- `frontend/securedoc-ui/e2e-oidc/`: `adminIntegrations.spec.ts`, `review.spec.ts`, `newCase.spec.ts`.
- `CLAUDE.md`: the frontend test counts.
- This report.

## Commits (hash and message), or "not committed"

- `dacaabd` docs(ui): U019 task from the design chat
- "fix(ui): U019 final polish: full-width pages, drop line, fitted filter placeholder, admin control widths, empty Review meter, panel phrase wrap, folder row note". The hash is in the chat summary.

Not pushed, not merged.

## Not done, and why

- Nothing from the task is left.
- The backend test from U018's owner's answer 2 is still for the backend work after the UI series.

## Questions for the owner

1. **The default loan wording doesn't fit the box either.**
   - "Filter by loan number or borrower name" is 249.5 px; the box has 239 px for text (320 px less the icon, padding and border). So it was already cut off before this task.
   - Under the task's rule, Cases now shows "Filter cases" for the standard loan setup at every width. The canvas shows the full wording only because its icon is a text glyph, with no gap after it.
   - "Filter by contract or member number" (231.6 px) fits and is shown.
   - Options:
     - **(a)** keep it as it is;
     - **(b)** before falling back, try the main field alone ("Filter by loan number");
     - **(c)** make the box about 335 px wide (beyond the canvas's 320).
   
   Which one?

## Suggested next step

The design chat reviews the pairs in `compare/U019/`, and the owner answers question 1. Then the owner's click-through and the merge decision for `feature/ui-polish`.

## Owner's answers

1. **Question 1 (filter wording):** combine (c) and (b). The Cases filter box is 360 px wide. It tries the full wording first, then the main field alone ("Filter by loan number"), and only then "Filter cases". Done in U020: at 1024, 1440 and 1920 px the standard loan setup now shows "Filter by loan number or borrower name".

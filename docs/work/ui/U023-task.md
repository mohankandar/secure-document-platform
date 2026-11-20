# Task U023: Safe policy (presets, preview, confirm)

Written: 2026-11-20, by the design chat. Read `docs/work/ui/README.md` and `docs/work/ui/SPEC.md` first. Build plan step 6 in `docs/work/ui-enhancements-design.md`.

- **Canvas** (match it exactly, except the wording change below): https://claude.ai/artifact/4x5YzFLB2Mqxq2thDZnbum, artboards "Sensitive data card with presets and preview" and "Confirm when fewer findings go to a person". The content you need is copied below.
- **Backend:** `GET /api/v1/business-admin/sensitive-data-policy/estimate` (task 0019); the existing policy save; `PolicyPreset` rules (0016).
- **Where:** Admin › Redaction & retention › the "Sensitive data" card (`src/studio/admin/PolicyArea.tsx`, `SensitiveDataCard`). Business Administrator only, as today.
- **Report:** `docs/work/ui/U023-report.md`.

## Step 0. Merge 0019

1. On `feature/policy-estimate`, commit the design chat's change to `docs/work/0019-report.md` (the owner's answers).
2. Merge `feature/policy-estimate` into `feature/lexavault-r1b-tenant-identity` (no fast-forward; standard message). Build and test: 597 backend, 281 frontend. Do not push.
3. Create the new branch `feature/ui-safe-policy` from it.
4. **Repository backlog:** add section C to `docs/product/ENHANCEMENT_BACKLOG.md` exactly as below. It keeps the repository copy in step with the project copy:

```
## C. UI enhancement backlog (from `docs/work/ui-enhancements-design.md`)

| # | Item | Note |
|---|---|---|
| C1 | Bulk approve, with the four-eyes rule | Design kept in `docs/work/ui-enhancements-design.md` #5. Only matters when approval before delivery is on. |
| C2 | Shared foundation with Vero | Fonts, spacing, button and form behaviour, top bar; own colours, no switcher. Done when Vero's UI is next touched. |
| C3 | Strict also checks cases with nothing found (owner, 2026-11-20) | Today, under "Every case" (Strict), a case where nothing is found is delivered without a person looking (confirmed in task 0019). An option for customers who want every case checked. Changes frozen V1 processing, so it needs its own task and the owner's go-ahead. |
```

Put it before "Not yet decided" if the repository file has that heading, otherwise at the end.

## What they see (from the canvas)

The "Sensitive data" card, top to bottom:

1. **Title** "Sensitive data", and under it in grey: "Which values are found and redacted, and when a person has to look."
2. **Presets:** a `fieldset` with the legend "How much should a person check?". There are three radio cards in a grid that wraps (min 240 px each). Each is a real `<label>` with an `<input type="radio">` (20 px), a bold name and a grey line:
   - **Strict:** "A person checks every finding. Cases with nothing found are delivered without a check." **This is the owner's wording (2026-11-20). It replaces the canvas's "Every case goes to a person."**
   - **Balanced:** "A person checks findings below 80% confidence. The rest are redacted automatically."
   - **Light:** "A person checks findings below 70% confidence. The rest are redacted automatically."
   - The chosen card has a 2 px dark border and a light background.
   - The card matching the **saved** policy carries a small grey "In use" pill.
   - When the saved or edited values match no preset, no card is chosen, and the line below shows **Custom** in bold.
   - Under the cards in small grey text: "Changing the numbers below by hand shows **Custom**. Below the threshold, a finding always goes to a person; nothing is skipped."
   - Choosing a preset sets only the review mode and the threshold: Strict = `REVIEW_ALL` with the threshold left as it is; Balanced = `UNCERTAIN_ONLY` at 0.80; Light = `UNCERTAIN_ONLY` at 0.70. It never changes the sensitive types or the last-four switch. Use the same rule as the server's `PolicyPreset`, ported once, with tests against the server's edge cases.
3. **Preview** (`role="status"`), shown only when the edited review mode or threshold differs from the saved policy:
   - a yellow-tinted box (existing tokens, no raw hex);
   - bold line: "If you save <Strict|Balanced|Light|these settings>, based on the last 30 days in <entity> (N cases)";
   - two white tiles in a wrapping grid (min 260 px):
     - "Cases a person would have checked": current in grey mono → proposed in larger mono, then the change in amber mono, "▲ 364 more" or "▼ 52 fewer";
     - "Findings a person would have checked", the same way;
     - no change shows "no change" in grey;
   - small text: "An estimate from past findings (type and confidence only). Nothing changes until you save, and only cases received after saving use it.";
   - **under 20 cases:** the box shows the bold line with "(N cases)" and "Not enough recent cases to estimate." instead of the tiles;
   - **while loading:** keep the box's height and show "Working out the estimate…". Ask again when the values change, with a short delay (about 300 ms) so dragging the slider doesn't flood the server. Only the latest answer counts;
   - **on failure:** "The estimate couldn't be worked out. You can still save." No retry loop.
4. **"Fine-tune by hand":** a `<details>` (closed by default, opened automatically when the saved policy is Custom). It holds today's review-mode choice and threshold slider, unchanged in behaviour. Editing them updates the chosen preset card, or Custom.
5. **"What is redacted · not changed by presets":** today's type chips and the "Treat labeled last-four SSNs as sensitive" checkbox, unchanged. "· not changed by presets" is in small grey text.
6. **Buttons at the bottom right:**
   - **Reset**: back to the saved values;
   - **Save <preset name>**: "Save Strict", "Save Balanced" or "Save Light"; "Save" for Custom or when only types or the switch changed. Disabled when nothing changed.

**Confirm step** (`role="alertdialog"`, `aria-modal`, labelled and described). Shown on save only when the estimate says **fewer findings** would go to a person than under the saved policy (proposed `findingsToPerson` < current). It is not shown when the estimate is missing, not enough data, or not fewer.
- Title: "A person would check fewer findings".
- Text: "With **<new name>**, about **<f>** fewer findings and **<c>** fewer cases in the last 30 days would have had a person check them." Numbers in mono; leave out "and **<c>** fewer cases" when the cases don't drop.
- Grey box: "Those findings are still redacted, automatically, without a person looking first. Findings below <threshold>% confidence still go to a person."
- Small grey line: "This change, and who made it, is recorded in the audit trail and shown in the compliance report as <old name> → <new name>." Names are the preset names, or "Custom".
- Buttons: **"Keep <old name>"** (primary, dark, focused on open), which closes and resets the card to the saved values; **"Save <new name>"** (secondary), which saves. Esc acts as "Keep". Focus returns to the save button.

**After saving:** the "In use" pill moves, the preview hides, and the page's existing success notice confirms it.

**Tablet:** U021 says Admin is desktop-first. The card must not break or scroll sideways on an iPad; no other tablet work.

## Checks

- `npm run build` and `npm run test:standalone` pass; give counts. Add tests:
  - the preset rule matches the server's (edges 0.79 / 0.80 / 0.81 / 0.70, and `REVIEW_ALL` at any threshold);
  - choosing each preset changes only the mode and threshold;
  - Custom shows when hand-edited;
  - the "In use" pill;
  - the preview's words for more, fewer, no change, not enough data, loading and failure;
  - only the latest estimate answer is used;
  - the confirm step shows only when fewer findings would go to a person; "Keep" resets and "Save" saves;
  - the save button's name;
  - focus and Esc in the confirm step.
- No raw hex outside `tokens.css`, no new width breakpoint, no new app dependency.
- **Proof** in `compare/U023/` (not committed):
  - the card at 1440, light and dark, next to the canvas;
  - the preview for more, fewer and not enough data;
  - Custom with Fine-tune open;
  - the confirm step next to the canvas;
  - one real run against the backend: change Balanced → Light, confirm, save, then show the Audit trail record (`preset=LIGHT`, `previous=[… preset=BALANCED]`);
  - the card on an iPad (WebKit), portrait.
- Commit on `feature/ui-safe-policy`. Do not push or merge.

## Report must include

Outcome; the merge commit; the backlog step; test counts; the proof list; the commit hash; questions for the owner.

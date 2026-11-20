# Report U023: Safe policy (presets, preview, confirm)

Done on 2026-11-20, branch `feature/ui-safe-policy`.

## Outcome

Done. The "Sensitive data" card now has the three presets with an "In use" pill, a preview of what changes, "Fine-tune by hand", "What is redacted · not changed by presets", a save button named after the preset, and the confirm step when fewer findings would go to a person. Frontend only. No backend change, no schema change, no new dependency. Build and every standalone suite pass. One gap: the real run could not show the confirm step, because the local demo entity has only 6 cases in 30 days (see "Real run" and question 1).

## Step 0

| # | Item | Result |
|---|---|---|
| 1 | Owner's answers | Committed on `feature/policy-estimate` as `ead2207` (docs(work): 0019 report: owner's answers). |
| 2 | Merge | `feature/policy-estimate` merged into `feature/lexavault-r1b-tenant-identity`, no fast-forward, standard message: **`c8413e3`**. Built and tested on the merge: **597 backend** (domain 51, application 214, scan bridge 47, infrastructure 14, API 271) with `mvn clean install`, and **281 frontend** (10 / 108 / 17 / 81 / 65) with `npm run build` and `npm run test:standalone`. Not pushed. |
| 3 | Branch | `feature/ui-safe-policy` created from `c8413e3`. |
| 4 | Backlog | Section C (C1 to C3) added to `docs/product/ENHANCEMENT_BACKLOG.md` word for word, before "Not yet decided". |

## What was built

- **`src/studio/admin/policyRules.ts`** (new, pure functions):
  - the preset rule, ported once from the server's `PolicyPreset.of`, using the same 1e-9 tolerance;
  - the preset names and lines, with the owner's Strict wording;
  - `applyPreset`, which sets only the mode and threshold (Strict keeps the threshold);
  - the save button's name;
  - the estimate query (threshold with two decimals; none for Strict);
  - the preview headline and tiles (missing counts are never read as zero);
  - `needsConfirm` (only when the proposed findings are fewer than the current, with enough data) and the confirm words;
  - `latestOnly()`, so only the latest estimate answer counts.
- **`PolicyArea.tsx` › `SensitiveDataCard`**, laid out as on the canvas:
  - real `<label>` and radio cards in a grid that wraps (min 240 px);
  - the chosen card has a 2 px dark border and a light background;
  - "In use" sits on the saved preset;
  - the preview (`role="status"`, `aria-busy` while loading) shows only when the mode or threshold differs from the saved policy. It asks 300 ms after the last change, and earlier requests are cancelled and ignored. While loading, the last answer stays in place unseen, so the box keeps its height. Failure gives one message and no retry;
  - Fine-tune is a `<details>`, closed by default and open when the saved policy is Custom. Its contents are today's controls, unchanged;
  - "What is redacted" holds today's chips, definitions and switch, unchanged. The "Sensitive types (4)" legend is kept, so the vocabulary rule and the existing `adminBusiness` test still hold.
- **Confirm step** (`FewerChecksDialog`):
  - `role="alertdialog"`, `aria-modal`, labelled by the title and described by the sentence;
  - drawn at the Case File root, with the same focus handling as U022: Tab stays inside, and Esc means Keep;
  - "Keep <old>" is focused when it opens. It resets the card and returns focus to the save button;
  - "Save <new>" saves, then focus returns to the save button.
- **Save button** uses `aria-disabled` instead of `disabled`, so focus can return to it after Keep, when nothing has changed. It ignores clicks while it has nothing to save.
- **`shared.tsx` › `SettingsCard`**: a new optional `footer` prop (draft, dirty, saving, save, reset). Other cards are unchanged.
- **`client.ts`**: `sensitiveDataPolicyEstimate(query, signal)`. **`Admin.tsx`** passes the entity name to `PolicyArea`.
- **`admin.css`**: `cf-sp-*` styles, all from `tokens.css`:
  - preview box: border `--cf-stage-review`, background a mix of `--cf-stage-review-soft` and the surface;
  - amber change text: `--cf-warn-ink`;
  - one override lets the presets hint and the preview lines run the full card width, as on the canvas (Admin otherwise limits text to 75ch).
- **Checks:** no raw hex added outside `tokens.css`, no new `@media` rule, no new dependency.

## Tests added

- `policyRules.test.ts` (13, in `test:vocabulary`):
  - the server's `PolicyPresetTest` table (0.79 / 0.80 / 0.81 / 0.70 / 0.69 / 0.71 / 0.801 / 0.799, `REVIEW_ALL` at 0.50 to 0.95) and floating-point noise;
  - each preset changes only the mode and threshold;
  - Custom when hand-edited;
  - the save button's name;
  - when the preview shows, and the query it sends;
  - headline, more, fewer, no change, not enough data, loading and failure words;
  - latest-answer-only;
  - the confirm conditions and words (cases left out when they don't drop; Custom on either side).
- `e2e-oidc/adminSafePolicy.spec.ts` (11, in `test:oidc-signin`, real app with mocked API):
  - the card from top to bottom, with the accessible names and the 20 px radio;
  - Strict: more findings, no confirm, then "In use" moves and the preview hides;
  - each preset keeps the types and the switch;
  - Custom, saved and hand-edited;
  - only the types changed: "Save", no preview, no estimate request;
  - no change; under 20 cases; loading keeps the box's height; failure with no retry;
  - one request after the slider stops, and only the latest answer is used;
  - the confirm step: Esc, Keep, Save, focus, Tab, and the cases left out.
- `e2e-tablet/tablet.spec.ts` (+1 test × 5 iPad sizes): card, preview, Fine-tune and the confirm step by tap. Nothing scrolls sideways, and the buttons are at least 44 px.

## Commands run and results

| Command | Result |
|---|---|
| `cd backend; mvn clean install` (on the merge `c8413e3`) | passed, 597 tests |
| `npm run build`, `npm run test:standalone` (on the merge) | passed, 281 (10 / 108 / 17 / 81 / 65) |
| `npm run build` (final code) | passed |
| `npm run test:standalone` (final code) | passed, **310** (auth 10, vocabulary 121, ui-framework 17, oidc-signin 92, tablet 70) |

Each suite was run once; this task does not touch entity switching or sign-in. No existing test was changed.

## Proof (`docs/work/ui/compare/U023/`, not committed)

- `compare-card-1440-light-vs-canvas.png`, `compare-card-1440-dark-vs-canvas.png`: the card (Strict with its preview) next to the canvas.
- `card-saved-*`, `card-strict-preview-more-*`, `card-light-preview-fewer-*`: at 1440 and 1024, light and dark.
- `card-preview-not-enough-1440-light.png`, `card-preview-loading-1440-light.png`, `card-preview-failed-1440-light.png`.
- `card-custom-finetune-1440-light.png` and `-dark.png`: Custom with Fine-tune open.
- `compare-confirm-1440-light-vs-canvas.png`; `confirm-1440/1024-light/dark.png`.
- `ipad-768x1024-portrait-card.png`, `-page.png`, `-confirm.png` (WebKit, iPad settings).
- Real run: `realrun-1-balanced-to-light-preview.png`, `realrun-3-saved-light.png`, `realrun-4-audit-trail.png`, `realrun-audit-record.json`.
- `canvas-card-1440.png` and `canvas-confirm-1440.png` are the canvas artboards rendered as plain pages.
- The scripts are kept outside the repository, in `C:\Users\mohan\AppData\Local\Temp\claude\b95672b1ca4b9404\109aaf78-27fa-4a81-8dde-b06a4906e8ea\scratchpad\u023\`: `zz-u023-proof.spec.ts`, `zz-u023-tablet.spec.ts`, `realrun*.cjs` and `canvas2html.cjs`.

## Real run against the backend

- Started with `.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiModel qwen3.5:9b`, using Edge headless as Business Administrator on the local demo entity. The saved policy was Balanced.
- Light → the real estimate answered `{"windowDays":30,"cases":6,"enoughData":false,…}`. The card showed "If you save Light, based on the last 30 days in Local Demo Tenant (6 cases)" and "Not enough recent cases to estimate." So **the confirm step correctly did not show**, and Save Light saved (PUT 200).
- The audit record (read as IT Administrator from `GET /audit-records`):
  `Sensitive-data policy changed: … threshold=0.70, reviewMode=UNCERTAIN_ONLY, … preset=LIGHT, previous=[… threshold=0.80, reviewMode=UNCERTAIN_ONLY, … preset=BALANCED]`. The audit trail lists it as "Changed the sensitive-data settings · auto-redact level now 70%".
- Afterwards the demo entity was put back on Balanced (Save Balanced), so it is as it was. Together these added two `SENSITIVE_DATA_POLICY_CHANGED` records to the development database.
- Stopped with `.\Stop-LexaVault-Local.ps1`. `securedoc-postgres` is still running, and nothing was recreated or removed.

## Commit

`TBD`, on `feature/ui-safe-policy`. Not pushed or merged.

## Questions for the owner

1. **The confirm step against real data.** The local demo entity has 6 cases, so the real run proves the save and the audit record but not the confirm step; the browser tests cover the confirm step. Shall I prove it once against the 0019 throwaway environment (`test-tools/policy-estimate`, 21 real cases), running the UI against it? That means a larger run, but no change to shared data.
2. **The Custom line.** The task says the line under the cards "shows **Custom** in bold" when no preset matches. The everyday line already has "Custom" in bold, so I made the line change in that state: "**Custom**: these settings match no preset. Below the threshold, a finding always goes to a person; nothing is skipped." Keep this wording, or use other words?
3. **Saving within 300 ms of a change.** If someone saves before the new estimate arrives, there is no estimate yet, so no confirm step (as the task says: "not shown when the estimate is missing"). Fine, or should Save wait for the estimate when one is on its way?
4. **Confirm buttons for Custom.** When the new or old policy is Custom, the buttons read "Keep Custom" / "Save Custom" (the task's "<name>" rule). The card's own button says just "Save" for Custom. Keep this, or use "Save these settings" in the dialog?

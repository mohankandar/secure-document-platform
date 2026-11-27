# 0111 report: small fixes from the owner's tests

Branch `feature/0111-small-fixes`, from `origin/feature/lexavault-r1b-tenant-identity` at `7c568566` (queue B, worktree `lv-queue-c`).

## What changed, per item

| # | Item | State | What |
|---|---|---|---|
| 1 | True counts on the cases dashboard | Done | `GET /packages?dashboard=true` (new parameter; without it the list is as before) lists the dashboard's cases oldest first in a stable order. The Case File screens page through all of them (100 a page, at most 5,000), so every count is true. Each group shows 25 cases, then "Show 25 more (N not shown)". |
| 2 | Cancelled cases | Done | CANCELLED is a known status: "Cancelled" (row: "Cancelled, nothing more to do"; panel heading "Case cancelled"), not "Unrecognized". The dashboard list leaves out every case with `dashboard_hidden_at` set; Reports still count them. |
| 3 | Cases with no documents expire | Done (server setting) | Every hour, a case still CREATED with no documents 7 days after it was opened is cancelled, hidden from the dashboard and recorded in the audit trail (`PACKAGE_CANCELLED`, "Case closed as expired: no documents 7 days after it was opened"). API intakes are skipped: they have their own expiry. The time is `securedoc.cases.empty-case-expiry-days` (`SECUREDOC_EMPTY_CASE_EXPIRY_DAYS`, default 7). The stuck case a61f2900… (CREATED since 2026-11-16) is closed at the first run after the owner updates. **Not done: a Business Admin screen setting per entity.** That needs a new column, which is a schema change, so I need the owner's word first (question 1). |
| 4 | U4 input length | Done | Typing stops at the field's exact length, else its maximum length. When the field strips separators, they don't count ("123-45-6789" fits 9). Deleting always works. |
| 5 | U5 option order | Done | Dropdown choices are shown in code order (000, 001, 002 …; numbers compared as numbers). |
| 6 | U6 "Required" | Not changed: could not reproduce | In the code, a field's "Required" message shows only after that field loses focus, or on every field when Create case / Start scan is pressed. The grey "· required" next to each label is always shown and is not a message. Question 2. |
| 7 | U8 row opens its panel every time | Re-checked, passes | Browser test: the same row opened and closed 3 times, then another row, then back. Each time the panel opens. Item 2 also changes this: a cancelled case is no longer a status the panel cannot read. |
| 8 | U9 why Redact waits | Done | When the finding has no place yet, "Draw the box first." is shown next to the disabled Redact button, linked to it for screen readers. Pressing R gives the same sentence. |
| 9 | R5 box on tilted images | **Not done** | It needs the owner's tilted licence photo and vision-model runs on the GPU (queue C's machine time). Left for a follow-up task. |
| 10 | R6 note icon; form-field box | Half done | A comment note's icon is no longer drawn into the output. This applies on copied pages and on pages drawn as an image (a new test renders both). Form-field box: on the text path, a finding in a field already gets the field's whole box (`FindingLocationMapper` uses the field's rectangle). So the owner's case came through another path (vision, or 0105's placement). Not settled without the owner's file. Question 3. |
| 11 | Benchmark gate | Done | `test-tools/vision-product`: findings.json carries the page check's reason. `score.mjs` prints "pages OCR cannot read, held for a person: N" apart from "unread pages". |
| 12 | Start script | Done | `Start-LexaVault-Local.ps1`: text model `qwen3.5:9b` by default, page batch size 2 unless `SECUREDOC_AI_OLLAMA_PAGE_BATCH_SIZE` is set, and the vision model (`SECUREDOC_AI_VISION_MODEL`, default `qwen3-vl:8b-instruct`) must be pulled. `-PullModelIfMissing` pulls it. The owner's checkout had no local edit left to keep; `Update-And-Start.ps1` passes the same values, so his run is unchanged. |

## What was run

(see the end of this file)

## Questions

1. Item 3: may the expiry time become a per-entity Business Admin setting? That needs one column on the entity's settings (a schema change). Recommended: yes, together with 0108's amber/red waiting-time setting, which needs the same kind of change.
2. Item 6: which screen and which click showed "Required" on every field? (Upload or scan, and was a field changed before?)
3. Item 10b and item 9: which file and page showed the partly covered form field, and the tilted licence? A follow-up task can take both with the owner's files.

No other product's name or behaviour in code. Known exception unchanged: `SdvDeliveryAdapter` (switch-over part 3).

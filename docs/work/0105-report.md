# 0105 report: every finding gets its place automatically

Branch `feature/0105-finding-places`, from `origin/feature/lexavault-r1b-tenant-identity` (merged again before the PR).
Migration 087 (owner-approved task: new columns on `findings`). No new dependency.

## 1. Measured first (owner's local database, read-only, 2026-11-27)

The owner's runs hold 253 findings. Person-placed ones (audit `FINDING_BOUNDING_BOX_UPDATED`), by file:

| File | Page | Kind | Reader (found_by) | Detector | Count |
|---|---|---|---|---|---|
| 03_manual_review_ocr_ambiguous_score | 1 | CREDIT_SCORE | (empty) | ocr-sensitive-field-guard | 1 |
| 05-drivers-license-photo | 1 | DATE_OF_BIRTH | VISION | vision | 3 |
| 06-handwritten-form-scanned | 1 | DATE_OF_BIRTH | TEXT (OCR) | ocr-sensitive-field-guard | 3 |
| 06-handwritten-form-scanned | 1 | DATE_OF_BIRTH | VISION | vision | 3 |
| 06-handwritten-form-scanned | 1 | SSN | VISION | vision | 3 |
| 09-form-fields-and-comments | 1 | SSN | TEXT | text model | 3 |
| 10-mixed-good-blank-unreadable | 3 | DATE_OF_BIRTH | VISION | vision | 2 |
| long-20-pages | 5 | DATE_OF_BIRTH | VISION | vision | 2 |
| long-20-pages | 14 | DATE_OF_BIRTH | TEXT | text model + pattern | 2 |
| long-40-pages | 5, 14, 23 | SSN, DOB, SSN | VISION | vision | 6 |
| long-60-pages | 5 | DATE_OF_BIRTH | VISION | vision | 2 |

Why, per kind (what the code does):
- **The count is inflated.** Review sends the finding's existing box with every confirmation
  (`draft ?? selected.boundingBox`) and the server recorded that as "box updated", moved or not. 21 of the 22 vision
  findings are in the list, but a vision finding always has the model's box (`VisionPageResult.Item` requires one). They
  reached a person because the vision model's confidence is 0.75, below the 0.80 auto-redact threshold (0040 answer 2), not
  because they had no place. Fixed: only a box that differs is a person's placing, on the server and on the screen.
- **The text model's value without a place** (09, form fields): a value is placed only when the model's exact text stands
  on the page exactly once. Checked on the owner's file with no model (PDF reading only, counts only): the SSN-shaped value
  stands **once**, inside the form field's span, so the place exists. The model's answer must therefore have differed from
  the page text (separators or spacing): step 2b now matches it that way too, and also covers a value that stands more
  than once. Not proven on this file until the run in section 5.
- **The OCR field guard** (03, 06): a strong label ("Date of Birth", "Credit score") whose value OCR could not settle gave
  a page-level finding with no place, although the guard knew where the label and, mostly, the value stand. Fixed by steps
  2b and 2c.
- **The 12 findings with no reader** are all `review-page-check`: boxes a person drew on a page checked by hand. Their
  origin was never set. Fixed.

## 2. Placement, in order
- **a. The model's box:** kept on the page (as before); now also checked for a size a value can have (no side under 0.2% of
  the page, no more than a quarter of the page). Otherwise it is still covered, and a person checks it with the reason
  `MODEL_BOX_IMPLAUSIBLE`.
- **b. The page's words:** a value that is not on the page exactly once is placed at **every** place it stands (the same
  value twice is the same value), then, for a value with digits, where it stands as OCR reads it (`O` for 0, `l`/`I`/`|`
  for 1, spaces, dashes, dots and slashes left out), never inside a longer number or word, at least 4 characters.
  **Decision taken (recommended, please confirm):** before this, a repeated value went to a person as "ambiguous"; now
  every occurrence is covered.
- **c. The label's field area:** an unsettled OCR field is covered at its value's words, else on the form line right of its
  label (below it when the label ends at the right edge). It still goes to a person (`OCR_FIELD_UNSURE` /
  `LABEL_WITHOUT_VALUE`), but with the box already placed.
- **d. Last resort:** a finding with no place at all goes to a person with the reason `NO_PLACE_FOUND`. No whole-page cover
  is made automatically (that would remove the page).
- **Form fields and annotations:** their value is placed by the field's own rectangle (0053, unchanged), and is now named as
  such (reader `FORM_FIELD`).

## 3. Recorded on every finding (migration 087)
`reader` (PDF_TEXT, OCR, VISION, FORM_FIELD, SIGNATURE, PAGE_CHECK, PERSON), `method` (PATTERN, AI_MODEL, CHECK, PERSON),
`placement` (READER_WORDS, MODEL_BOX, MATCHED_WORDS, LABEL_FIELD_AREA, PAGE, NONE, PERSON), `person_reason`, `placed_by`,
`placed_at`. A new finding never leaves the search without a reader (a fallback from its origin). Findings saved before keep
what they have; the new columns are empty for them. The model version stays in `ai_models`.

Queries for the owner (read-only; counts only):

```sql
-- automatic % by reader (redacted or confirmed findings)
select reader, round(100.0 * count(*) filter (where placement <> 'PERSON') / count(*)) as automatic_pct, count(*)
  from findings where reader is not null and status in ('CONFIRMED', 'REDACTED') group by reader order by 3 desc;

-- placed by a person, by reader and kind
select reader, entity_type, count(*) from findings
 where placement = 'PERSON' and reader <> 'PERSON' group by 1, 2 order by 3 desc;

-- why a person was needed
select person_reason, reader, count(*) from findings where person_reason is not null group by 1, 2 order by 3 desc;
```

## 4. Reports, Automation
Overview tab, for a Business Admin: a period picker Today / This week / This month (entity's time zone),
"Redacted automatically: NN%", the counts **Automatic**, **Placed by a person**, **Drawn by a person**, and one line with
the share found by the page's text or OCR against the vision model. From the findings (and, for findings from before this
task, their audit records); counts only, per entity. `GET /api/v1/operations/reports/automation?period=`.
On the owner's data today (before this task's columns): demo entity 197 automatic, 29 placed (inflated, see 1), 12 drawn.

## 5. Re-run of run 2: NOT DONE
Run 2 through the product needs the text and vision models on the GPU for the long files, and long GPU runs are queue C's
(QUEUE.md). **For the design chat:** schedule it (queue C, or the owner) on the merged head:
drop `test-pdfs\watched-drops-run2` (new contract numbers), then run the queries above and the leak and over-redaction
check. Until then the "after" table is not measured.

## Tests
- `FindingPlacementSteps0105Test` (7): a repeated value at both places; OCR confusions and separators; nothing inside a
  longer number, no too-short or word values; the field area right of and below a label; an unsure OCR field at its value,
  beside its label, or not placed; the reader (form field, OCR, PDF text) and an unplaced text finding's reason; the model's
  box check.
- `FindingPlacement0105Test` (domain, 4): the same box sent with a confirmation is no placing; a moved box records the
  person and time and keeps the reader; a box for a finding without one; names round-trip, unknown names dropped.
- `PageBatchPlacement0105Test` (1): placement kept in a page batch's file, older files still read.
- `AutomationReportServiceTest` (3): day, week (from Monday) and month in the entity's zone; the share; an unknown period.
- Frontend: `automationRules.test.ts` (4) and `reports.spec.ts` (one new test: headline, counts, line, period picker).
- No-value check: no test value appears in the build log; the report reads counts only; the new columns hold names only.

## What was run
- Backend, on the merged head, PostgreSQL tests on (throwaway container, removed):
  `mvn -o verify -pl securedoc-api -am`: BUILD SUCCESS. domain 91, application 408, infrastructure 216 (2 skipped),
  API 985 (5 skipped), 0 failures. Migration 087 ran on every fresh test database.
- One read-only check on the owner's `09-form-fields-and-comments.pdf` (PDF reading only, a temporary test not committed).
- Frontend: `tsc --noEmit`; one `npm run test:standalone` with `LV_E2E_PORT_BASE=15275`: exit 0 (auth 10, vocabulary 249, ui-framework 19, oidc-signin 237, tablet 110, a11y 380 passed).
- Shared Maven repository read only; nothing installed.
- Left: the re-run of run 2 (section 5), and the full proof set at the milestone.

## Questions for the design chat
1. Step 2b now covers every place of a repeated value (before: a person decided). Confirm.
2. Vision-only findings stay at confidence 0.75, below auto-redact (0040 answer 2), so each still goes to a person to confirm,
   now with its box already placed. Raise them to automatic when the box passes step 2a? This is the largest remaining
   share of person work in the owner's run.
3. Schedule the run 2 re-run on the GPU (section 5).

## No other product's name or behaviour in code
No other product's name or behaviour in code.

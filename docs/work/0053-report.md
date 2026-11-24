# Report 0053: Detection reads form-field values

Date: 2026-11-24
Branch: `feature/0053-form-fields`, from 0050's branch (PR #1, not merged yet)
Outcome: **DONE** (1 question for the owner)

## What was done
1. **Extraction reads form fields** (`PdfFormFieldText`): text fields, combo boxes and lists, on the page where they are shown.
   - Each field is one line after the page's text: its label, then its value. The value's place is the field's box, also on turned pages.
   - The value is the stored one, the words a choice shows, and the drawn text where that differs.
   - The label is the field's description, its name and the page's words beside or above it, **without any word that has a digit**. A label has no place, so a value must never be in it.
   - The page's own text and offsets do not change. Whether a page needs OCR is still decided by its own text only.
   - Check boxes, radio buttons, push buttons and signatures are ignored. Hidden fields and fields outside the page are not read; they do not reach the output. (A field outside the page was still drawn by 0050; it no longer is.)
2. **Detection and redaction are unchanged code**: the value is text of the page with a place, so it is found, covered (page burned flat) and compared by the final check.
3. **XFA:** PDFBox says whether a form has XFA, but does not draw it. A form with XFA and no shown field, or one the viewer must make itself (`NeedsRendering`), stops with the new **`LV-PRC-010` "Form type can't be read"**. A form with XFA and ordinary fields is read by its fields, and its XFA part does not reach the output (test).

No migration, no new dependency.

## Commands run and results
| Command | Result |
|---|---|
| `mvn -B -o clean install`, PostgreSQL tests on | BUILD SUCCESS, 0 failures |
| `npm run test:standalone` | 10, 193, 17, 168, 95, 156 passed, 0 failed |
| `Invoke-NoRedactedValueCheck.ps1`, inline / staged | PASS, 0 matches / same |
| `Invoke-ComplianceReportCheck.ps1`, inline / staged | PASS / PASS |
| `scripts/Test-LexaVault-ExportE2E.ps1`, inline / staged | PASS / PASS |
| Product-name guard | clean (1679 files) |
| 26 public benchmark PDFs | 108 of 108 pages read exactly as before, all outputs verified safe. **None of them has a form field**, so they prove "unchanged", not the new reading. |

## Tests: counts per module, and any failures with their output
Domain 79, application 304, scan bridge 136, infrastructure 115, API 646 (+7; 1 skipped, the timing test). 0 failures in the final run.

New `FormFieldValuesTest`: an SSN only in a text field (four page rotations), a card number only in a combo box, a mixed page (text, field and list), a value only in the drawn appearance, a comb field, pages without fields, page batches, XFA. Each value is found, its box is dark on the output page, and it is absent from the output's bytes and decompressed streams.

Existing tests changed: code counts for the new code (`LvErrorCodeRegistryTest` PRC 9 to 10 and 140 to 141; `lvCodes.test.ts` 140 to 141). The design's totals line also said 139 and RED 4; corrected to 141 and RED 5.

## Files changed
New: `PdfFormFieldText`, `PdfFormUnreadableException`, `FormFieldValuesTest`. Changed: the two extraction adapters, `PdfOutputCleaner`, `LvErrorCode`, the two code documents, the generated code list.

## Commits (hash and message), or "not committed"
- `008adfe` feat(extraction): detection reads form-field values; XFA-only forms stop with LV-PRC-010
- the report commit

## Not done, and why
- Free-text notes and stamps: see the question. The approval names form fields only.

## Questions for the owner
1. **Free-text notes and stamps** are drawn into the output and detection does not read them. It is **the same kind of leak**. The same code can read them. Approve it as a small task?

## For the owner
- A value that is both in a field and on the same page is found twice. The AI can then not tell which place it means, so it may ask for a review. That is the safe direction.

## Suggested next step
The question above, before the self-test.

No other product's name or behaviour in code.

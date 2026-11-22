# Vision-based redaction with page routing (requirement)

**Owner-approved:** 2026-11-21 in the capabilities thread. **V1 headline.** Full context: `Redaction-Capabilities.md` section 8a (claude.ai project doc).
**For:** the LexaVault enhancement thread, to place in the task order and write the tasks.
**App:** LexaVault only (`secure-document-platform`). Not platform-core: this is redaction logic.
**Not built yet** (checked 2026-11-21).

## 1. What and why
A vision model reads the **page image** directly: handwriting, stamps, IDs, checks, poor scans. Tesseract cannot read handwriting, and handwriting is already a V1 requirement, so V1 needs vision. Vision is slower and needs a GPU, so **each page takes the cheapest path that can handle it**. **Only the pages that need vision go to it, never the whole document.**

## 2. Routing rule (per page)

| Page has | Path |
|---|---|
| Only typed/digital text | Fast text path (patterns + text AI), as today |
| Signatures or initials only | Fast path + a small signature detector. Signatures are boxed if the entity's rule says so; nothing to read |
| Handwritten words or numbers | Vision |
| ID document, check or photo (including one pasted inside a digital PDF) | Vision |
| Low OCR confidence (threshold is a setting) | Vision |
| Hidden text layer and image disagree | Vision |

- **Signatures are not handwriting.** Borrowers initial most pages of a mortgage package; treating initials as handwriting would send nearly every page to vision.
- **Expected share:** about 2–3 pages of a 60-page package (to confirm by benchmark).
- **Results apply to the whole case:** a value vision finds on one page is redacted wherever it appears in the case (existing consistency rule).
- **Cheap context:** the vision model may also receive the typed text of neighbouring pages (a label on one page, the value on the next).

## 3. Required conditions

| # | Condition |
|---|---|
| 1 | **Routing only:** vision handles just the hard pages; typed pages stay on the fast path |
| 2 | **Safe fallback:** if vision is switched off or the server has no GPU, pages that would go to vision go to **manual review**. Nothing is ever skipped. V1 works on any hardware |
| 3 | **Benchmark gate** before vision is promised to the first customer (section 5) |
| 4 | **GPU:** the first customers all have GPUs (owner, 2026-11-21) |
| 5 | **Model checks:** licence terms for commercial use, and acceptance of the model's origin by the customer's vendor-risk team. The customer may supply their own model (existing rule) |
| 6 | Existing rules hold: no redacted value is ever saved or logged (the vision model's answer included); every decision explainable in the compliance report (path taken and confidence, never the value) |

## 4. Settings (Business Admin / IT)
- Vision on/off per entity; signatures boxed or not per entity; OCR-confidence threshold; vision model endpoint (IT, like the text model today).

## 5. Can start early: benchmark (no product code)
Run before or in parallel with the pipeline work:
- 20–30 real sample documents from the first customer (inside their environment or with their approval only), including handwritten forms, IDs, checks, faxes.
- Compare: today's text path vs a local vision model, on the routed pages.
- Measure: seconds per page, pages routed per package, items missed, items wrongly flagged.
- Output: the chosen model, the OCR threshold, and whether the 60-second-per-document target holds.

## 6. Dependencies and placement (for the enhancement thread to confirm)
- Product build depends on **staged pipeline P2** (page batches as jobs, `staged-pipeline-design.md`): routing decides per page, so it follows P2.
- Suggested order from the capabilities thread: case fields (0031/0032) → new pattern redaction types → P2 → **this** → P3–P6. The benchmark (section 5) can start now.

## 7. Marketing position
"Vision-based redaction" is the headline; "also runs without a GPU" is the reassurance. The product specification says plainly that vision needs a GPU and that, without one, those pages go to human review.

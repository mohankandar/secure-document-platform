# LexaVault V1 redaction requirements: status and placement

Recorded 2026-11-21 by the LexaVault design chat, from the capabilities thread's "Decisions not yet captured" list (items 1–10, owner-approved; full text in the project doc `Redaction-Capabilities.md`). Each item was checked against the code on `feature/lexavault-r1b-tenant-identity`.
**Task numbers are assigned only by the LexaVault design chat.**

| # | Decision (short) | In the code today | Placement |
|---|---|---|---|
| 1 | **Redaction catalog**: one entry per identifier (name, detection method, default style); **regulation packs** (GLBA/Financial, PCI, HIPAA, FRCP 5.2) per entity with fine-tuning; **locked items** (CVV, PIN, full card number) always on; **style** per item (remove fully / keep last 4); changes audited, new documents only | **Not built.** The four types are fixed in code; no packs, no locked items, no style choice | Task R1 (with item 2) |
| 2 | **New pattern types (V1)**: bank account, routing, driver's licence, passport, tax ID; check digits (Luhn, routing checksum, invalid SSN ranges) and nearby labels | **Not built.** Today: SSN, date of birth, credit card number, credit score | Task R1 |
| 3 | **True redaction hardening**: invisible text removed; scanned PDFs: image and hidden OCR layer both redacted; garbage-font text falls back to OCR; earlier versions not carried over; hidden content cleaned (metadata, comments, form fields, scripts, bookmarks, link addresses, thumbnails, attachments, hidden layers); final search of the output | **Partly built.** The output is a new document (so earlier versions and document metadata don't carry over). Pages with a redaction or an annotation are rendered to an image and boxed, with no text layer (verified). **Untouched pages are copied as they are**, so their invisible text, form fields, links and hidden layers can carry over; no document-level clean-up of scripts, attachments or outline; the final check looks only for a text layer on redacted pages | Task R2 |
| 4 | **Password-protected PDFs**: accepted only through an API with the password in the body over HTTPS, in memory only, never stored or logged; watched folder / file polling: stop with "Password-protected file. Send it through the API." for IT Admin | **Not built.** Intake detects encryption and refuses it (`LV-INT-008`); no way to give a password | Task R3 |
| 5 | **Digitally signed PDFs**: processed; the broken signature is expected and recorded in the audit trail and compliance report; nothing added to the page | **Not built** as a rule: processed (the output is a new document, so the signature is gone) but **nothing is recorded** | Task R2 |
| 6 | **Output**: text stays searchable (Vero searches content); no labels on boxes; no notes or footers; flatten-pages option is V2 | **Conflict on searchability:** no labels and no footers are true today, but **every page with a redaction loses its text** (rendered to an image at 150 DPI). Untouched pages keep their text | Task R2 (design decision below) |
| 7 | **Vision-based redaction with page routing** (V1 headline) | Not built. Requirement file `docs/work/vision-routing-requirement.md` (committed with this file) | After pipeline P2 |
| 8 | **Performance target and benchmark**: under 60 s per document (30 better); benchmark on 20–30 first-customer samples, text vs vision path | Not started | Benchmark B1 now (public samples, owner's GPU), B2 with the customer's samples |
| 9 | **Later AI ideas (V2)**: IT Admin operations assistant (logs/metrics only), AI-suggested tuning from reviewer corrections, AI-generated test documents | Not built | Backlog, V2 |
| 10 | **Unreadable or corrupt pages always go to review**, never passed through; one case can hold several PDFs; LexaVault does not split a PDF into documents | **Partly.** Several PDFs per case: yes. Unreadable pages: today the **case stops** (OCR failure, `LV-PRC-002/003/006`) instead of the page going to review. Not splitting: true for documents in the case; the **split-by-barcode export** to split destinations exists and is a separate, approved feature (taken as not meant by this rule unless the owner says otherwise) | Stopping the case is fine (capabilities thread, 2026-11-21). **Backlog:** let the Processor continue an unreadable-page case manually (today "Continue manually" is offered only for cases stopped at the AI step) |

## Design decision for item 6 (searchable text on redacted pages): **B chosen by the owner, 2026-11-21**

Today a page with a redaction becomes an image with no text, which is the safest output but not searchable. Options:
- **A. Keep the page's own text and remove only the redacted words** from the page's content. Best quality, but hard to do reliably (fonts, overlapping text, garbage fonts) and the riskiest: a mistake leaks a value.
- **B. (recommended) Keep the image page, then add a text layer made by OCR of the redacted image.** The black boxes are already burned in, so the redacted values can't appear in the new text; the final check verifies it. Searchable, safe, slightly lower text quality than the original.

## Order (LexaVault, confirmed by the owner 2026-11-21, revised the same day at the Shared thread's suggestion)

1. 0031 live run against Vero · 2. P1b job clean-up · 3. **Switch-over to platform-core, part 1:** identity, setup, licensing, notifications · 4. **Switch-over, part 2:** case fields (when platform-core's `platform-fields` is built) · 5. **R1** catalog, packs, locked items, styles and new pattern types, built on platform-fields · 6. **R2** true redaction hardening, searchable output (option B), signed PDFs recorded · 7. **R3** password-protected PDFs through the API · 8. Pipeline P2 · 9. Vision-based redaction · 10. Reports build, P2b, P3–P6.
Alongside: benchmark 0033 whenever the coding session is free. V2 backlog: item 9.

## Backlog (not development yet), recorded 2026-11-21

| # | Item | Notes |
|---|---|---|
| BL1 | Continue manually for a case stopped on an unreadable page (item 10) | Extends today's Continue manually (AI-step stops only) |
| BL2 | Security testing: SOC 2 readiness, penetration test, encryption details, data residency for hosting | Planning (capabilities doc §11–12, business readiness) |
| BL3 | Disaster recovery plan: backups and recovery | Planning |
| BL4 | On-prem upgrade and rollback procedure: supported versions, upgrades keep settings and data, tested rollback | Planning; the contract depends on it |
| BL5 | **Per-entity time zone** | **Does not exist today** (checked 2026-11-21): time zones exist only per polling connection (daily schedule) and per compliance report request. The shared part is platform-core's (`docs/design/integration-standards.md`); LexaVault must use it for the Bulk window, Reports' "today" and retention timers. Needed before P5 (Bulk window) |
| BL6 | V2 AI ideas (item 9) | V2 |
| BL7 | AI model governance, LexaVault side (owner, 2026-11-22; shared design `Model-Governance-Design.md`): **pin the AI model by digest**, not by name, and refuse to run on a mismatch; **every finding records the model, version and digest** that produced it (check what is stored today, add the rest in R2); default pass marks from benchmark 0033, approved by the owner; register LexaVault's uses with platform-model-governance when it is built | R2 (digest and per-finding record); with the shared build (register) |
| BL8 | Rejection reason (owner, 2026-11-22): when a reject step is designed (review or approval), it stores a **reason code** (an `LV-` code), never free text; the Cases list already shows it when sent | With the reject step |

## Benchmark
Task 0033 (written 2026-11-21): vision benchmark, no product code.

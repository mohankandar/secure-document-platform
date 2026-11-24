# Report 0070: unreadable pages checked by hand

Date: 2026-11-24
Branch: `feature/0070-unreadable-pages`, from the integration branch (`791b218`)
Outcome: **STOPPED** before any product code: 0064 (merged after 0066's analysis) already decides what happens to these pages, differently

## What was found (read from the merged code, nothing run)
| Code | Task 0070 expects | The code since 0064 |
|---|---|---|
| `LV-PRC-002` no readable text | the case stops; Continue manually | doesn't stop: the page goes to vision; not read there, it becomes a "Page to check" finding in Review |
| `LV-PRC-003` too poor | the same | the same as above |
| `LV-PRC-006` can't be drawn | the same | still stops the case |

- `TextExtractionService` reads with `extractRouted` (lenient: `HybridPdfTextExtractionAdapter`); Find, with `extractReadable`. Every unread page of a document is listed (one finding each), and Text goes on: answer 6 is already true.
- With vision off or no model, the page also goes to a person (0064 point 4): it is never passed unread.
- What 0070 would still add: Continue manually's **any number of boxes** per page. Review has one box per "Page to check" (0064, "Not done").
- Built as written, 0070 would stop cases 0064 lets through, and skip vision for those pages.

## Questions for the owner
1. Which rule holds? Recommended: 0064's. Then 0070 becomes: **several boxes on a "Page to check"** in Review, with the words "Unreadable: check by hand" and an "I have reviewed this page" confirmation, audited by page number.
2. `LV-PRC-006` (about a page that can't be drawn): treat it like the other two, a "Page to check"? Nobody could see that page in Review either, as it can't be drawn. Recommended: keep the stop.

## Suggested next step
0071 (it doesn't depend on this), then 0070 rewritten.

No other product's name or behaviour in code (none written).

# Task U004: Review: the words found, zoom, and "Redact all like this" (findings 6, 7)

Written: 2026-11-17, by the design chat. Spec: `docs/work/ui/SPEC.md` (locked). Read `docs/work/ui/README.md` first.

Design: `designs/Review.html`.

## Do

1. **Show the text found** in the decision panel ("Text found: 412-55-0193", plus a line of surrounding text), and a short version in each queue item.
   - **Backend needed, and a security decision:** `FocusedReviewItem` has no text today. Propose (in the report, before building it) a field returned only to people with `MANUAL_REVIEW`, never written to logs or the audit detail, and how it is read from the stored findings. Ask the owner before adding it. Until then, build the panel so the text row is hidden when the field is absent.
2. **Zoom** above the page: −, percentage, +, Fit. The page scrolls so the finding is in view.
3. **Why a person is asked**, one sentence naming the policy level: "LexaVault is 62% sure this is sensitive. Your policy redacts automatically from 85%, so a person decides."
4. **Redact all like this:** when other open findings of the same type exist in the same case, show "<n> more <type> waiting in this case" and a button "Redact all <n+1> like this" (shortcut Shift+R). It confirms each finding through the existing per-finding call, so each one is audited as its own decision. Findings whose place is not found are skipped and stay in the queue; say how many were skipped. There is no "Keep visible all".
5. Type names come from the U001 vocabulary module.

## Tests

- Bulk action only counts same type and same case; skips findings with no place.

## Checks (all tasks)

- `npm run build` and `npm run test:standalone` pass; give counts.
- Every changed screen checked in light and dark, at 1440 px and 1024 px, with the local app (`.\Start-LexaVault-Local.ps1`). Say which local test roles you checked.
- No raw hex outside `tokens.css`; only documented breakpoints; no new dependency without asking.
- Commit on `feature/ui-polish`. Do not push or merge.

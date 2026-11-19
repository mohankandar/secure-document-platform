# Task U020: last polish from the design chat's review of the U019 pairs

Written: 2026-11-19, by the design chat, after reviewing all 54 U019 pairs at 1920 px. The owner asked the design chat to suggest whatever makes it a professional product and pre-approved these. Read `docs/work/ui/README.md` first. Same rules as U014–U019. Small task; keep it tight.

## Owner's answer to U019 question 1 (record it under "Owner's answers" in U019-report.md)

Combine **(c) and (b)**:
- make the Cases filter box 360 px wide;
- try the full wording first, then the main field alone ("Filter by loan number"), and only then "Filter cases".

## Fixes

1. **Readable line length for prose:** at 1920 px, the explanatory paragraphs on full-width cards run about 1,300 px. Examples are the Sensitive data detection note and the long hints in Admin and Platform. Cap descriptive text (notes, hints, card subtitles, paragraphs) at about 75ch. Tables, rows and lists keep the full width.
2. **Key–value rows on wide cards:** on the Sign-in card, "Login system" sits at the left and its value about 1,300 px away at the right. On full-width cards, put the value next to its label: a fixed label column (about 200 px), with the value left-aligned after it. The same applies to any other read-only key–value list on a full-width card. The narrow side panels (case panel, Review decision panel) keep label left and value right.
3. **Account menu name repeated:** the menu shows the name twice ("Dana Reyes / Dana Reyes / Document Supervisor") when the display name and the username are the same. Show the second line only when it differs from the first.
4. **Review page size on wide screens:** at 1920 px, the page opens at 76% with wide empty space on both sides. Open at "Fit" (fit to the width of the viewer area, up to a sensible maximum such as 150%) when the queue loads. The zoom buttons work as today.
5. **Connection editor summary (step 3):** the address breaks in the middle of the URL inside a narrow column. Give the address its own full-width row in the summary, so it shows on one line or breaks only at "/".

## Not to change (known and decided)

- Licensing content (Phase 5).
- The Branding accent field (removal is in the backend work after the UI series).
- "Person 00000001" in the Audit trail (actor names are in the backend work after the UI series).

## Proof

Retake the affected pairs at 1920 px, light and dark, into `compare/U020/`: Cases, Admin › Redaction & retention, Sign-in, Platform Storage and Branding, Main (account menu open), Review and Connection editor step 3. Do not commit them.

## Checks

- `npm run build` and `npm run test:standalone` pass; give counts.
- 1024, 1440 and 1920 px; nothing scrolls sideways.
- No raw hex outside `tokens.css`; only documented breakpoints; no new dependency.
- Commit on `feature/ui-polish`. Do not push or merge.

# LexaVault accessibility statement

Date: 2026-11-25. This is the first version kept in the repository. The earlier check (U026 to U028) planned a statement but left only a conformance table, in `docs/work/ui/U028-report.md` (A5).

## What we aim for

LexaVault's screens are designed and checked against the **Web Content Accessibility Guidelines (WCAG) 2.1, levels A and AA**. Some parts do not meet it yet; they are listed under "Known exceptions". This statement says what was checked and how. It is not a certificate, and no outside body has audited the product.

## How it was checked

- **Automated:** axe with every WCAG 2.0 and 2.1 A and AA rule, no rule turned off, on each screen and state below, in light and dark, in Microsoft Edge. Automated checks find only part of all problems.
- **Keyboard only:** the main tasks and each screen, with real key presses: everything can be reached in reading order, the focus ring is at least 2 px, nothing traps focus, Esc closes dialogs and menus and focus goes back, arrow keys work in tabs, radio groups and menus. Written up in `docs/work/0089-keyboard.md`.
- **Layout:** no sideways page scroll at 320 px and at 200 % zoom, and nothing cut off with wider text spacing, on the screens marked * below; for the others, checked on Cases, Reports, Approve and the Compliance report dialog.
- **Tablets:** a WebKit suite with iPad settings checks layout and the size of touch targets (44 px) on the screens it opens.
- These checks run with every frontend change (`npm run test:a11y`, `npm run test:tablet`).

**Not done:** no testing with a screen reader, with voice control or in forced-colours mode, and no testing by people with disabilities. Firefox and Safari on a computer were not checked.

## Screens covered

Checked 2026-11-20 (U026 to U028) and again with every run since: sign-in, entity chooser, no access, Cases, case panel, Approve, account menu, find a case or action, New case, Review, Continue manually, Reports, Compliance report dialog, problem card, Admin (redaction and retention, case fields, integrations, sign-in, audit trail), Platform operations, Support and the problem page; later Licensing, Support status and the export screens.

Checked 2026-11-25 (task 0089), with their dialogs, messages, empty and failed states:

- Entities, and the entity switcher (0058) *
- Notifications, and the page an approval link opens (0059) *
- Case fields of every kind, on New case and in Admin (0061) *
- Redaction catalog, packs, locked items and styles (0062) *
- Reject a case, from the case panel * and from Review (0071)
- Priority of a case and of a source (0073) *
- A page to check in Review (0074)
- Bulk approval (0076) *
- The queue and time left, in Admin and in Reports (0078) *
- Migration (Bulk) sources (0079) *
- Reports: Overview, Daily intake and delivery, Operations (0081) *
- The error code guide, in the app and on its own without a session (0082) *

## Known exceptions

| What | WCAG | State |
|---|---|---|
| Marking a place on a page by hand (a finding without a place, a page to check, Continue manually) needs a pointer. A page that needs no mark can be confirmed and finished by keyboard; a case that needs marks can be finished by a colleague with a mouse. | 2.1.1 (A) | Accepted by the owner (U026); backlog C4 |
| Review and Continue manually are desktop and tablet screens; they are not laid out for 320 px. | 1.4.10 (AA) | Accepted by the owner (U021) |
| The error code guide PDF and the compliance report PDF have real text but no tagged structure (no reading order or headings for a screen reader). The same error code guide is a page in the app. The compliance report has no such page. | 1.3.1 (A), for the PDFs | Open |
| Redacted documents keep the accessibility of what the customer sent; LexaVault does not add tags. They were not checked. | not assessed | Open |
| After some actions are done, the button that was pressed is no longer there or is switched off (for example Activate, Save times, Approve selected). Focus is then on no control; Tab goes on from the same place. | 2.4.3 (A) | Open, not assessed as a failure |
| Wide tables in Admin and Support screens from before 2026-11-25 (audit trail, storage, capacity, sign-in history) were not checked at 320 px. | 1.4.10, 2.1.1 | Not checked |
| Short messages at the bottom of the screen go after about 4 seconds. What they confirm stays visible on the screen; a failed priority or migration change is told only there. | 2.2.1 (A) | Open |

## Tell us

Report an accessibility problem through your usual LexaVault support contact, with the screen and what you were doing.

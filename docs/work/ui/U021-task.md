# Task U021: tablet-friendly for looking, deciding and approving (iPad)

Written: 2026-11-19, by the design chat. Read `docs/work/ui/README.md` first. Run after `feature/ui-polish` is merged. Work on a new branch, `feature/ui-ipad`, taken from the branch it was merged into.

## Owner's decision

- **Tablet-friendly:** for looking, deciding and approving.
- **Desktop-only:** for configuring and for manual work.

| Screen | On a tablet |
|---|---|
| Cases, the case panel, Approve | Must work well by touch |
| Review (Redact / Keep visible, Redact all) | Must work well by touch |
| Reports (Business Administrator and Report Viewer) | Must read well |
| Continue manually (drawing boxes) | Show a short note at the top: "Marking values by hand works best on a computer." Don't make finger-drawing a goal. Nothing may break. |
| Admin, Support, Platform, Audit trail | Must not break or scroll sideways. No redesign. |

## Do

1. **Test in WebKit (Safari's engine)**, using Playwright's `webkit` project with iPad device settings (touch, iPad user agent), at:
   - 768 × 1024 (portrait);
   - 820 × 1180 (iPad Air, portrait);
   - 1024 × 768 and 1180 × 820 (landscape);
   - 1366 × 1024 (iPad Pro, landscape).
   
   Playwright WebKit is a dev dependency of Playwright, not a new app dependency. If installing its browser needs approval, ask the owner.
2. **Touch targets:** buttons, chips, tabs and row actions on the tablet-friendly screens must be at least 44 × 44 px tappable (padding or hit area; the look may stay the same) when the device has a coarse pointer (`@media (pointer: coarse)`). That is a media feature, not a width breakpoint.
3. **Nothing depends on hover or keys alone:** anything shown on hover must also show without it. The J/K and R/V shortcuts stay, but every action has a visible button.
4. **Layout at 768–834 px:**
   - Cases rows may drop secondary columns (as the existing breakpoints do), but the identifier, next step, stage and action stay visible.
   - In Review, the queue can collapse into a picker, and the decision panel can go under the page, so all three never squeeze side by side.
   - Use only the documented breakpoints (1200, 1024, 768).
5. **Safari specifics:** on the tablet-friendly screens, check that dropdowns, the file picker ("choose files" on New case), the IBM Plex font and the account menu work in WebKit.
6. **Proof:** WebKit screenshots of each tablet-friendly screen, portrait and landscape, light and dark, in `compare/U021/` (not committed). The design chat reviews them.

## Checks

- `npm run build` and `npm run test:standalone` pass; give counts. Add a WebKit tablet test for Cases → Approve and Review → Redact by tap, and say how it runs.
- Desktop at 1440 and 1920 must look exactly as after U020: retake the Cases and Review desktop pairs to show nothing moved.
- No raw hex outside `tokens.css`; no new width breakpoint; no new app dependency.
- Commit on `feature/ui-ipad`. Do not push or merge.

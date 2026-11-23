# Report 0046: CI red on the tablet suite (43 px case-panel controls on Linux)

Date: 2026-11-23
Branch: `feature/0046-tablet-44px` (from `feature/lexavault-r1b-tenant-identity`, `cedec5a`)
Outcome: DONE

## What was done
- Reproduced on Linux first: `mcr.microsoft.com/playwright:v1.56.1-noble` (matches `@playwright/test` 1.56.1), tablet suite: **5 failed / 90 passed**, exactly the CI failure (`tablet.spec.ts:229`, case-panel `summary` rows 43 px high, all 5 iPad sizes).
- Cause: under a coarse pointer, `.cf-drawer .cf-disclosure > summary` got its height only from `padding: 13px 0` plus the font's own line height (18 px on Windows, 17 px on Linux).
- Fix (product, `src/studio/studio.css`, coarse-pointer block only): the summary row now has `box-sizing: border-box; min-height: 44px; padding: 13px 0; line-height: 18px;`. The fixed line height keeps today's look on Windows (13 + 18 + 13 = 44, text centred) and the minimum guarantees 44 px whatever the font. Desktop (fine pointer) is unchanged. The UI framework is not touched. No test changed.
- Checked the other tablet tests' controls the same way: a temporary probe (not committed) in `expectTappable` and `smallControls` listed every measured control under 50 px high whose CSS `min-height` is below 44 px. Windows and Linux: only the case-panel summaries (now fixed), and `cf-sp-action` / `cf-cr-action`, which have a fixed `height: 44px` and so don't depend on font metrics. Nothing else reaches 44 px through line height alone.

## Commands run and results
| Command | Result |
|---|---|
| Linux, before the fix: `docker run --rm --ipc=host mcr.microsoft.com/playwright:v1.56.1-noble` → `npm ci`, `CI=1 npx playwright test --config playwright.tablet.config.ts` (source copied in read-only, own node_modules) | 5 failed, 90 passed (the CI failure) |
| Same, after the fix | **95 passed** |
| Windows, `npm run test:standalone` | **PASS**: test:auth 10, test:vocabulary 191, test:ui-framework 17, test:oidc-signin 166, test:tablet 95, test:a11y 156 |
| `bash scripts/ci/check-product-names.sh` | clean (1628 files) |

Throwaway container `lv-0046-tablet` ran with `--rm` (removed). Work done in a scratch worktree (`C:\dev\lexavault\lv-queue-b`) because the owner's app runs from the main checkout. Protected containers not touched.

## Tests: counts per module, and any failures with their output
Frontend only (see the table). Backend not changed, not rebuilt.

## Files changed
- `frontend/securedoc-ui/src/studio/studio.css` (one rule, coarse pointer only)
- `docs/work/0046-report.md`

## Commits (hash and message), or "not committed"
- `fix(ui): task 0046, case-panel section rows 44 px on any font` (hash in the queue summary)

## Not done, and why
- Nothing. Not pushed (no CI run of this branch; the Linux container run stands in for it).

No other product's name or behaviour in code.

## Questions for the owner
None.

## Suggested next step
Push when you merge, so the GitHub run confirms the tablet job is green.

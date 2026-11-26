# Report 0101: Review keeps showing the page (owner test U1)

Date: 2026-11-26
Branch: `feature/0101-review-page` (from 999a97a1; 0097, PR #45, is not in it: no overlap)
Outcome: **DONE.** No backend change. **No other product's name or behaviour in code.**

## Cause (found red first)
The page image was turned into a browser address in `useMemo` and the address was revoked in an effect's clean-up
(`Review.tsx`, `PageStage`). The dev server runs `React.StrictMode`, which mounts every component twice. The first time a
finding is opened its image is not cached yet, so the address is made after the mount and survives: **the page shows**.
When the page is already in SWR's cache (back to a finding, another document then back, leaving the case and coming back),
the address is made during the mount and revoked by StrictMode's first unmount, and the image still points at it: **only the
alt text shows**. The new test failed exactly there (the second visit to finding f1: image width 0).

The same pattern was in Continue manually (`ManualCase.tsx`, page viewer and thumbnails); fixed the same way.

A second fault was found while fixing: SWR compares answers by content, and a `Blob` shows none, so a new image counted as the
old one and "Try again" kept a failed image. Compared by identity now.

The endless spinner is not reproduced in the mocked browser. Its likely cause is the same remount; in addition, a page preview
that has not answered after 60 seconds now counts as failed (with "Try again") instead of spinning on.

## What changed
- `src/studio/pageImage.tsx` (new): `useObjectUrl` makes and revokes the address in one effect; `sameImage` for SWR's compare;
  `PageFailed`: "The page could not be loaded." with a **Try again** button.
- `Review.tsx` and `support/ManualCase.tsx`: use them. A failed request or an image the browser can't show gives the message
  and Try again, never the alt text. The image that failed is tied to its address, so a new answer starts clean.
- `api/client.ts`: the two page-preview requests stop after 60 seconds (`PAGE_PREVIEW_TIMEOUT_MS`).
- `e2e-oidc/review.spec.ts`: 3 new tests (below); the mock can answer a preview with a failure or a broken picture.

## Commands run and results
| Command | Result |
|---|---|
| `review.spec.ts -g 0101` before the fix (base 15275) | **3 failed** (red): the 10-round test at the second visit to f1 (image width 0); no message in the two failure tests |
| `review.spec.ts` after the fix | 21 passed |
| the 10-round test `--repeat-each=10 --workers=1` | **10 passed in a row** |
| `tsc --noEmit`; `npm run build` | 0 errors; built |
| `npm run test:standalone` (base 15275), the one frontend run | **all passed**: auth 10, vocabulary 243, ui-framework 19, oidc-signin 236, tablet 110 (WebKit), a11y 380 |
| product-name guard | clean |
| Backend | not run: no backend change |

CI runs the same tests (`test:oidc-signin` is part of `test:standalone`).

## Tests added
1. **The page shows every time**: 10 rounds of next finding, back, the other case's document, back, leave Review for Cases and
   come back; each time the image has loaded at its full width (1224 px) and no failure message shows.
2. **A page that fails to load** (HTTP 500): "The page could not be loaded." and Try again, no image and no alt text; Try again shows the page.
3. **An image the browser can't show**: the same.

## Pictures
The page viewer only (no found value in a picture), from the tests with `LV_SHOTS_DIR`:
- `docs/work/ui/0101-page-after-10-rounds.png`: the page after 10 rounds.
- `docs/work/ui/0101-page-failed.png`: a page that failed, with Try again.

## Not checked
The real app with the owner's cases (000642500006, 779001126001); a production build in the browser (StrictMode is off there,
so the fault showed on the dev server, which is what Start-LexaVault-Local runs).

## Questions for the owner
None.

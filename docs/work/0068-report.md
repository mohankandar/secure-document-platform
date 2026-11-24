# Report 0068: Browser-test ports per queue

Date: 2026-11-24
Branch: `feature/0068-browser-test-ports`, from the integration branch; 0063 merged in afterwards
Outcome: **DONE**

## What was done
- `LV_E2E_PORT_BASE` (default 15175) moves every port a Playwright suite starts, by the same amount. One file: `frontend/securedoc-ui/e2ePorts.ts`. A value that is not a whole number from 10000 to 40000 is refused.
- Defaults unchanged: sign-in 15175, tablet 15176, a11y 15177, export screen 15235, export backend 18035, stand-in AI 11535. `Test-LexaVault-ExportE2E.ps1` also moves its database port (55435) and then names its container after the base; ports given on the command line win.
- `reuseExistingServer` was already false in all four suites; a test now keeps it so.
- `test:e2e` (5173) is unchanged: it uses the app already running and starts no server.
- One line in `CLAUDE.md`.

## Line for the queue files (the design chat copies it)
- QUEUE.md (A): "Browser tests: set nothing (base 15175)."
- QUEUE-B.md: "Browser tests: set `LV_E2E_PORT_BASE=15275` first."
- QUEUE-C.md: "Browser tests: set `LV_E2E_PORT_BASE=15375` first."

## Commands run and results
| Command | Result |
|---|---|
| `npm run test:oidc-signin` at the same time from two worktrees, bases 15275 and 15475 | 170 and 170 pass (13:46 to 14:04, both); another session's suite was on 15176 meanwhile |
| `npm run test:ui-framework` (2 new tests) | 19 pass |
| type check; the four configs list their tests | pass |
| product-name guard | clean |

## Not done, or left for the milestone
- Tablet, a11y and the export end-to-end were not run on moved ports (their tests were listed); the export end-to-end is a milestone run.
- Existing test changed: `entitySwitch.spec.ts` reads its port from `e2ePorts.ts` instead of 15175.

## Suggested next step
Task 0066.

No other product's name or behaviour in code.

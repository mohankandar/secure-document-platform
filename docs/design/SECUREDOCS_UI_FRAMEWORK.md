# SecureDocs UI Framework — LexaVault Foundation

Status: **PASS / ACCEPTED IMPLEMENTATION FOUNDATION** after independent Kiro re-audit. This document defines the reusable LexaVault UI implementation foundation; SDV may reuse compatible framework ideas without copying product workflows.

## Canonical page anatomy

Normal application pages follow this order:

1. Breadcrumb
2. Page title and page-level actions
3. Primary product/administration navigation, when present
4. Feature-level tabs, when present
5. Filters / summaries / workflow controls
6. Primary content/results

`PageScaffold` owns steps 1–3 so feature pages cannot accidentally render primary tabs above the title.

## Foundation layers

- Typography tokens from Design System R1
- Spacing, surface, radius, elevation and semantic-color tokens from R2
- Shared buttons, form controls, status treatment and focus states from R2
- Page-pattern primitive: `PageScaffold`
- Data-pattern primitives: `FilterPanel`, `DataRegion`
- Existing shared primitives: `HorizontalTabs`, `PageHeader`, `StatusBadge`, `WorkflowStepper`, `InfoGrid`, `Icon`

## Responsive and accessibility contract

- 100% desktop zoom remains the reference density.
- Horizontal navigation scrolls instead of clipping when space is constrained.
- A keyboard-visible “Skip to main content” link bypasses the sidebar.
- Shared focus-visible treatment remains enabled for interactive controls.
- Reduced-motion preferences are honored.
- Mobile/tablet layouts keep primary Administration navigation in normal page flow.

## Regression guard

`npm run test:ui-framework` checks 17 framework contracts covering page anatomy, Administration-tab placement, token/raw-color fencing, retired primitive families, canonical primitive availability, the fixed breakpoint set, card truth, Upload non-overlap, and packaging of the OIDC session-continuity acceptance script. `Build-LexaVault-All.ps1` runs this contract automatically after TypeScript checking.

## Scope boundaries

The framework work does not change Enterprise Identity trust rules, product authorization, tenant isolation, audit semantics, licensing behavior, document processing, scanner behavior, or reporting calculations.

## R3-R5 consolidation contract

The R3-R5 consolidation completes LexaVault adoption of the framework rather than introducing a new visual design.

- `src/tokens.css` is the single raw-color source. Feature/application CSS consumes semantic tokens; raw hex colors outside `tokens.css` are rejected by the framework contract test.
- All top-level LexaVault routes use `PageScaffold`. Dashboard intentionally supplies an empty breadcrumb list so the home page does not render a redundant self-breadcrumb.
- Legacy feature surfaces/buttons/fields remain behaviorally compatible but are normalized through the shared surface, control, focus, state and responsive contracts in `index.css`.
- Reporting keeps its accepted information architecture and calculations while moving its page header/navigation and color vocabulary onto the shared framework.
- Responsive behavior favors scrolling/reflow over shrinking business text below the shared typography scale.
- The UI framework contract test also prevents the two OIDC session-continuity script copies from drifting.

## Primitive consolidation FIX1

The framework no longer relies on visually matching legacy primitive families. The canonical primitive set is now explicit:

- `Button` / `.ui-button` (`primary`, `secondary`, `tertiary`, `danger`, `small`, `icon`)
- `Card` / `.ui-card` with feature classes restricted to layout modifiers
- `StatusBadge` for inline semantic state
- `Alert` / `.ui-alert` for error/success/warning/info messages
- `EmptyState` and `Skeleton` / `.ui-loading-state`
- `Field` / `.ui-field`
- `HorizontalTabs`

Retired button, card, status, alert and tab class families are removed from TSX markup and CSS. The previous cross-family `!important` card border/shadow rule is removed; border/radius/shadow now come from `.ui-card` itself.

Responsive CSS uses a documented fixed breakpoint set: **640, 768, 900, 1024, 1200, 1280 and 1400px**. CSS variables cannot currently be used directly inside media-query conditions, so `test:ui-framework` enforces the fixed values.

Dynamic geometry remains an explicit exception: document/redaction bounding boxes and data-visualization widths/heights may use runtime inline styles because those values represent data/coordinates, not design-system escape hatches.


## Primitive consolidation FIX2

FIX2 closes the final post-consolidation re-audit items without changing the visual direction:

- all standard primary/secondary/tertiary actions are fenced onto `.ui-button`; legacy `button.primary` / `button.secondary` selectors are prohibited,
- Dashboard stages use `StatusBadge`, including an explicit danger tone for `TECHNICAL_ATTENTION`,
- actionable Dashboard attention uses the sanctioned `AttentionLink` primitive rather than pretending a navigable action is a passive status badge,
- retired primitive selectors are rejected in CSS as well as TSX,
- the fixed responsive breakpoint contract is enforced automatically.

The framework contract intentionally allows feature-specific layout modifiers and runtime data/document geometry where they do not redefine shared visual primitives.

## FIX3 durability contract

Framework consistency is enforced by `frontend/securedoc-ui/src/security/uiFrameworkContract.test.ts` and the machine-readable policy in `uiFrameworkPolicy.ts`. The normal repository build invokes this contract before the Vite production build. The approved responsive ladder is 640 / 768 / 900 / 1024 / 1200 / 1280 / 1400px; CSS outside that set fails the contract. Raw CSS hex values outside `tokens.css`, retired primitive selectors/classes, bare button variants, page-scaffold drift, card override regressions, and the Upload sticky-overlap pattern are also rejected.

For a focused check, run `./Test-LexaVault-UiFramework.ps1`. This intentionally uses the repository's zero-dependency Node contract rather than introducing a second stylelint/Husky toolchain solely for the same rules.

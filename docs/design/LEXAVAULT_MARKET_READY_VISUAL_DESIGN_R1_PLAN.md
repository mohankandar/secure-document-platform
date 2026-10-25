# LexaVault Market-Ready Visual Design R1

**Status:** APPROVED PLAN / IMPLEMENTATION NOT STARTED  
**Design direction:** Enterprise Precision  
**Foundation:** SecureDocs UI Framework — PASS / frozen implementation foundation

## 1. Goal

Move LexaVault from a consistent, usable enterprise application to a **commercially credible, demo-grade enterprise product** without reopening the accepted framework architecture or changing frozen business/security behavior.

The acceptance question is:

> Would we confidently put this screen on a projector in front of a bank, mortgage company, insurer, or enterprise buyer and present LexaVault as a finished product?

Consistency alone is not sufficient. The product must communicate maturity, authority, information hierarchy and product identity.

## 2. Design point of view — Enterprise Precision

LexaVault should feel restrained, exact and operationally serious.

Primary hierarchy tools, in order:

1. typography;
2. alignment;
3. spacing;
4. information density and grouping;
5. subtle separators/surfaces;
6. brand accent;
7. semantic status color.

Avoid solving hierarchy by placing every section in a rounded card.

### Desired characteristics

- premium but conservative enterprise presentation;
- useful information visible at 100% browser zoom;
- compact, deliberate controls rather than oversized toy-like controls;
- fewer unnecessary boxes and borders;
- stronger primary/secondary action hierarchy;
- high-quality tables and operational lists;
- restrained status badges;
- professional empty/loading/error states;
- consistent icon weight/style;
- polished desktop and iPad behavior;
- subtle, purposeful motion only.

### Explicitly avoid

- glassmorphism;
- decorative gradients everywhere;
- rainbow KPI cards;
- giant marketing/landing-page headings inside the application;
- excessive card hover lifting;
- excessive animation/page transitions;
- colored pills for non-semantic decoration;
- tiny typography used only to increase density;
- copying another product screen-for-screen.

Tesla is a cleanliness reference. Stripe/Linear/Datadog/Microsoft-class products are maturity references. LexaVault must retain its own document-processing identity.

## 3. Non-negotiable engineering guardrails

All visual work must preserve the accepted SecureDocs framework contract:

- raw hex only in `tokens.css`;
- approved breakpoints only: `640, 768, 900, 1024, 1200, 1280, 1400`;
- top-level routes continue to use `PageScaffold`;
- buttons remain canonical `ui-button`/Button;
- surfaces remain `ui-card`/Card when a card is actually appropriate;
- statuses use `StatusBadge`;
- actionable attention uses `AttentionLink`;
- tabs use `HorizontalTabs`;
- no retired primitive family may return;
- no card-family `!important` unification workaround;
- no auth, tenant-isolation, audit, retention, processing, delivery, licensing, scanner or Enterprise Identity semantic changes as part of visual design.

Run `npm run test:ui-framework` after every implementation checkpoint.

## 4. R1A — Visual concept gate

R1A deliberately covers three different UI archetypes plus the global shell. **Do not propagate the design to the rest of the product until the user approves these screens.**

### 4.1 Global shell

Refine:

- LexaVault brand lockup and logo treatment;
- navigation density and active-state affordance;
- sidebar/rail proportions;
- signed-in identity/sign-out area;
- page content width/gutters;
- page title/action alignment;
- mobile/tablet drawer consistency.

Target: quieter chrome that makes application content feel more valuable.

### 4.2 Dashboard — operational/data archetype

Current strength: rich operational functionality. Current weakness: commodity/admin-template presentation.

Design work:

- replace simplistic KPI-card feel with restrained operational summary treatment;
- stronger number/label hierarchy without giant display typography;
- use tonal/state color sparingly and only where it improves scanning;
- make search the primary toolbar control; group columns/sort/refresh as secondary utilities;
- establish a premium table rhythm: row height, cell padding, header weight, numeric alignment, hover/selection;
- integrate status/attention naturally instead of filling the table with decorative pills;
- refine selected filters/stage chips as controls, not status badges;
- improve table skeleton and empty-state presentation;
- keep useful business information visible at 1440px/100% without shrinking typography.

Do not invent trend arrows unless a real prior-period comparison exists in product data.

### 4.3 Upload Documents — workflow/form archetype

Design work:

- make the intake flow feel like a professional controlled workflow rather than a large form inside boxes;
- clarify step progression and current step;
- make the drop zone obvious but restrained;
- reduce instructional prose that can be conveyed by labels/help affordances;
- align identifier fields and source/context information cleanly;
- make Submit Documents unmistakably primary without a giant sticky footer;
- preserve the accepted non-overlap behavior: content must never sit beneath submission actions;
- provide polished selected-file, validation, error and uploading states.

### 4.4 Package Details — information/action archetype

Design work:

- establish a strong package identity/status header without a giant hero banner;
- prioritize primary business identifiers and current lifecycle state;
- reduce nested-card appearance;
- use sections/dividers/InfoGrid where possible instead of cards inside cards;
- make contextual actions obvious but restrained;
- refine Documents, Delivery & Retention, Derived Routing and related tabs as enterprise operational content;
- improve document rows, metadata hierarchy and action placement;
- make attention/error states reassuring and actionable rather than visually alarming.

### R1A acceptance gate

Review at minimum:

- 1440px desktop, 100% browser zoom;
- 1024px constrained desktop/tablet landscape where relevant;
- framework contract PASS;
- no horizontal clipping/overlap;
- no loss of role/capability behavior.

**Stop after R1A for user visual approval.** If the user does not consider the screens marketable, adjust the design direction before propagation.

## 5. R1B — Hero workflows

Begin only after R1A visual approval.

### 5.1 Manual Review workbench

This should become LexaVault's strongest product-demo screen.

- document canvas is the visual hero;
- queue and decision panels support the document rather than compete with it;
- crisp document matting and precise bounding-box treatment;
- clear resize/selection affordances;
- confidence becomes a compact, meaningful visualization;
- reason/context labels are legible but secondary;
- Confirm/Dismiss paths have unmistakable hierarchy;
- resolved/all-caught-up state is polished and calm;
- preserve dynamic inline bounding-box geometry;
- preserve keyboard/accessibility behavior.

The previously identified `review-pui-loading-state` artifact has been corrected in the clean baseline before this phase.

### 5.2 Reporting

Reporting should look like an executive/operations analytics product, not a collection of metric cards.

- retain the accepted information density at 100%;
- create real, restrained visualizations for received vs delivered, source/channel mix, backlog/aging and relevant SLA/turnaround trends where data exists;
- use the existing chart token family and a very limited data palette;
- establish consistent panel anatomy;
- keep Executive and Operational views role-appropriate;
- avoid decorative charts with no decision value;
- route repeated data bars through a reusable component while keeping values dynamic.

### R1B acceptance gate

Manual Review and Reporting must both be screenshot/demo quality before proceeding.

## 6. R1C — Product-wide propagation

After R1A/R1B approval, propagate the accepted language through shared tokens/primitives and focused page adjustments to:

- Scanner Intake;
- Manual Processing;
- Business Administration;
- Authentication Administration;
- Platform/Tenant Administration;
- Processing Storage/Licensing/Branding panels;
- Integrations;
- Support Incidents/details;
- Audit;
- Technical Issue details;
- sign-in/auth shell and remaining application states.

Do not redesign workflows. The goal is visual maturity and consistency with the approved hero screens.

## 7. R1D — Commercial finishing

### Responsive / iPad

- desktop 1440 and 1280;
- 1024 landscape;
- representative iPad portrait/landscape;
- narrow fallback where supported;
- no page-level horizontal overflow except intentional data regions.

### Accessibility

- WCAG AA contrast check after final palette decisions;
- visible branded `:focus-visible` states;
- keyboard path through hero workflows;
- screen-reader sanity pass for Manual Review and major forms;
- preserve reduced-motion behavior.

### Product states

Polish loading, empty, success, warning, error, disabled, permission-denied and no-data states. Prefer the existing `Skeleton`, `EmptyState`, `Alert`, `StatusBadge` and related primitives.

### Motion

Use only when it communicates state:

- fast drawer/menu transitions;
- restrained toast/state transitions;
- skeleton loading treatment;
- optional small resolve confirmation in Manual Review.

No decorative page fade/lift requirement.

### Demo readiness

- seed realistic but non-sensitive demo data;
- make Dashboard/Review/Reports/Package Detail visually credible with populated states;
- capture final 1440px screenshots for website/deck/demo documentation.

## 8. Acceptance and freeze

R1 is complete only when:

1. framework contract remains green;
2. local frontend/backend build passes in the user's environment;
3. R1A and R1B visual gates are explicitly accepted by the user;
4. representative R1C screens are spot-checked;
5. R1D responsive/accessibility checks pass;
6. no security/tenant/business workflow regression is found;
7. final Kiro read-only verification finds no blocking visual/framework regression.

After acceptance, record **LexaVault Market-Ready Visual Design R1 — VERIFIED / FROZEN** in `docs/continuity/CURRENT_STATE.md`.

## 9. Execution order

```text
R1A  Shell + Dashboard + Upload + Package Details
  -> USER VISUAL GATE
R1B  Manual Review + Reporting
  -> USER HERO-SCREEN GATE
R1C  Product-wide propagation
R1D  Responsive + accessibility + product states + demo captures
  -> KIRO FINAL CHECK
  -> VERIFIED / FROZEN
```

The most important rule is the first gate: **do not make the whole product uniformly polished in a visual direction the user has not yet approved.**

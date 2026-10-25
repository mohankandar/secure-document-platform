# Case File frontend integration — functional gap register

Baseline: production `main` at `1b69432`; approved prototype `984604a`.
Integration branch: `ui/lexavault-casefile-integration-review`.

Status legend: OPEN = functionality not demonstrated in prototype; REVIEW = owner decision needed; VERIFIED = parity proven in real runtime. Do not close entries on visual evidence alone. Never silently delete production functionality. No changes to `main` without explicit approval.

| ID | Severity | Production workflow | Prototype observation | Decision needed | Status |
| --- | --- | --- | --- | --- | --- |
| LV-FG-001 | High | Support Incidents list and incident detail (`/support/incidents`, `/support/incidents/:id`) | Compatibility routes and Support navigation added in `8136641`; legacy pages reused pending Case File shell runtime acceptance | Preserve as accessible Case File support surface or obtain explicit disposition before old UI removal | OPEN |
| LV-FG-002 | High | Technical issue detail and recovery (`/technical-issues/:id` or existing actual production route) | Existing `/technical-exceptions/:packageId` route included in Case File shell in `8136641`; runtime and deep links unverified | Map production route/actions and provide a working replacement | OPEN |
| LV-FG-003 | High | Manual fallback processing at `/packages/:packageId/manual-processing` | Existing manual fallback route now preserved in Case File shell in `8136641`; runtime and navigation still unverified | Integrate the workflow and preserve deep links | OPEN |
| LV-FG-004 | Medium | Deployment and tenant branding propagates to production shell via `BrandingProvider` | Prototype shell hardcodes LexaVault mark and does not use BrandingProvider | Wire effective branding into Case File shell without altering security semantics | OPEN |
| LV-FG-005 | Medium | Operational role with `TECHNICAL_EXCEPTION_VIEW` can access support incidents | Case File shell includes `TECHNICAL_EXCEPTION_VIEW` for support-only access in `8136641`; requires role-specific runtime test | Preserve support-only role entry and capability boundaries | OPEN |
| LV-FG-006 | Medium | Production integration environment and auth lifecycle | Case File prototype is mounted with demo data and simulated server | Replace demo transport with actual authenticated API; remove demo controls from production | OPEN |
| LV-FG-007 | Medium | Browser validation across Admin checkpoints A–E | Only partial screen recording; several controls never exercised in browser | Run real API positive/negative path and permission tests | OPEN |
| LV-FG-008 | Medium | UI framework packaging contract | Three known prototype packaging tests fail baseline | Reconcile after real frontend placement, without weakening guards | OPEN |

## Integration checkpoints

1. Preserve backend and `frontend/securedoc-ui` dependencies, security, existing routes and branding until replacement is functionally mapped.
2. Adapt Case File shell to production `CurrentUser`, real capabilities, real API client, OIDC session lifecycle, and branding.
3. Migrate Desk/Intake/Review, including fallback and technical/support deep links.
4. Migrate Reports and Admin A–E.
5. Verify capability denial, tenant isolation, operation safety, responsive layouts, and real API behavior. Remove old UI only once replacements have been verified.
6. Record each gap's resolution commit and runtime evidence before marking VERIFIED.

This is a ledger, not approval to omit or defer high-severity workflows from final cutover.

## Integration checkpoint — compatibility route staging (`8136641`)

The Case File shell now retains the existing Support Incidents list/detail, technical-exception detail, and manual-fallback React routes using existing production pages. Navigation for support-only capability was added, and legacy intake redirects are capability gated. This does **not** imply that the Case File shell is mounted in `App.tsx`, that these pages are styled or tested within the new layout, or that functional parity is VERIFIED. Keep LV-FG-001, 002, 003, and 005 OPEN pending build, runtime, negative-permission tests, and deep-link validation. Do not remove corresponding legacy routes.

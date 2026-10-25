# LexaVault Clean Source Export — 2026-10-05

This clean handoff was produced from the accepted R3–R5 UI Framework FIX3 candidate before starting Market-Ready Visual Design R1.

## Removed

- 195 historical/superseded files;
- root-level candidate/FIX readmes and changed-file manifests;
- archived unit-by-unit implementation notes (`docs/archive`);
- old verification-candidate snapshots (`docs/verification`);
- superseded timeline/master-matrix artifacts;
- prior cleanup/export notes and the oversized stale continuity changelog;
- editor metadata (`.vscode`).

Generated build/runtime artifacts were already absent and remain excluded by `.gitignore`.

## Intentionally retained

- all backend/frontend production source;
- all backend/frontend automated tests;
- Liquibase migrations and Maven/npm manifests/lock files;
- scanner bridge, AI, redaction, worker, infrastructure and API modules;
- Docker/local runtime configuration;
- reusable scripts, performance tooling, acceptance tooling and test fixtures;
- current product/architecture/security/API/operations/testing/training documentation;
- `.kiro` steering/specs/rule details and `aidlc-docs` because the repository's Kiro workflow explicitly depends on them;
- duplicate root + `scripts/testing` copies of the OIDC continuity/UI framework scripts because the framework contract intentionally drift-checks those pairs.

## Source cleanup correction

The undefined `review-pui-loading-state` class used by Manual Review and Manual Processing was corrected to the existing `review-preview-loading` class. No workflow/business/security behavior changed.

## Canonical handoff documents

- `docs/continuity/CURRENT_STATE.md`
- `docs/continuity/DEVELOPMENT_WORKING_AGREEMENT.md`
- `docs/design/SECUREDOCS_UI_FRAMEWORK.md`
- `docs/design/LEXAVAULT_MARKET_READY_VISUAL_DESIGN_R1_PLAN.md`

Historical candidate documents removed from this package should not be reintroduced as active specifications.

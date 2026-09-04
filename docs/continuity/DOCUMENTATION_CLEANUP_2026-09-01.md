# Documentation Cleanup — 2026-09-01

## Scope

Documentation-only consolidation after acceptance of the V1 role-simplification baseline. No Java, TypeScript/TSX, Liquibase, runtime configuration, database schema, or processing behavior was intentionally changed.

## Classification applied

### Keep/current

- architecture and ADR documents that still describe current architecture;
- active `.kiro` project principles/core requirements;
- current build/run/AI/OCR/logging operations guides;
- API surface index.

### Consolidate

Created current canonical documents for:

- current state;
- ChatGPT/Kiro working agreement;
- V1 scope;
- authorization;
- workflow/lifecycle;
- reporting;
- production identity/tenancy;
- acceptance/regression baseline.

### Archive

Moved historical unit timelines, Kiro prompts, self-reviews, runtime correction reports, superseded role models, old requirement freezes, and obsolete local-run instructions under `docs/archive/`.

### Delete

Removed only redundant/obsolete copies:

- duplicate `02_Final_V1_Pre_Acceptance_Code_Review.md` (identical historical copy retained);
- duplicate `03_Final_V1_Kiro_Acceptance_Prompt.md` (identical copy retained in archive);
- duplicate root `README-build-all.md` and `README-local-scripts-v3.md` (current copies live under `docs/operations/`).

The old API placeholder was replaced by a current API-area/security-boundary index rather than retained as a false statement that the API was still planned.

## Result

Future sessions should no longer need to parse the ~2,500-line historical master continuity file or dozens of unit records to determine current product behavior. Those records remain available in the archive when historical rationale is needed.

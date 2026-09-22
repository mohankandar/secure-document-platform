# LexaVault Clean Source Export — 2026-09-22

This source snapshot was cleaned from the developer workspace for final technical review.

## Removed from the clean snapshot

- Git repository metadata (`.git`)
- IDE/editor metadata (`.idea`, `.vscode`)
- local runtime process metadata (`.run`, `.runtime`)
- frontend dependencies/build/test output (`node_modules`, `dist`, `test-results`, `playwright-report`, `coverage`)
- runtime logs
- local processing storage/output/watched-intake data
- generated performance/release-readiness result directories
- generated Operational Continuity acceptance evidence (the reusable acceptance scripts and Playwright specs remain)
- compiled helper `.class` files and temporary/log files
- local smoke-test PDF
- one-off developer SQL scratch files (`CommonQuery.sql`, `Query.sql`, `Export_retention.sql`)
- obsolete `REPLACE_README_FIRST.txt` candidate replacement marker

## Intentionally retained

- complete backend and frontend source
- Maven/npm manifests and lock files
- Liquibase migrations
- local/demo configuration required to understand the development trust boundary
- scanner bridge, worker, infrastructure, redaction, licensing, reporting, support, and platform operations source
- test fixtures under `test-data`
- reusable Operational Continuity acceptance Tests 1–7
- Playwright E2E source
- canonical continuity documentation
- historical implementation/verification notes and candidate READMEs for final review
- `.kiro` project specifications/steering and AIDLC documentation

No product behavior was intentionally changed by this cleanup. The only source-file edit is `.gitignore`, expanded to prevent regenerated local artifacts from re-entering future source snapshots.

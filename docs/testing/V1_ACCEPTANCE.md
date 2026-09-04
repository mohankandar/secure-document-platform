# LexaVault V1 Acceptance and Regression Baseline

## Current acceptance evidence

The current accepted development baseline includes the Common Integration Connection foundation and Requirements 1–5: Push API, scheduled polling/pull ingestion, SDV master delivery, derived redacted-document routing, and the Qwen3.5:4b LOCAL/demo AI performance profile. The current workstream is final V1 hardening/regression only.

### Role migration

- User manual acceptance: PASS.
- Independent Kiro role/capability verification: PASS; workspace safe to accept as new V1 baseline.
- Kiro adversarial authorization review: PASS.
- No code-supported privilege escalation found.
- No cross-tenant bypass found.
- Identity normalization confirmed narrow/fail-closed.
- `ADMIN` confirmed never implicit.
- Platform diagnostics confirmed safe and tenant scoped.
- No files were modified by Kiro during those reviews.

## Regression expectations for future changes

When a change touches authorization, identity, tenancy, workflow, or reports, verify both positive and negative access through direct backend APIs—not only UI visibility.

At minimum preserve:

- exact canonical role/capability matrix;
- tenant isolation and fail-closed behavior;
- no implicit `ADMIN`;
- legacy aliases limited to identity-boundary normalization;
- Report Viewer read-only reporting/no export;
- Processor cannot reprocess or remove from Dashboard;
- Supervisor adds exactly those lifecycle actions;
- Business Admin has approved business functions but not IT/platform operations;
- Tenant IT has technical/admin/reporting functions but not document workflow/business policy;
- Platform Operations diagnostics do not expose secrets/raw sensitive values/cross-tenant data;
- Dashboard removal remains visibility-only;
- technical retry remains distinct from business reprocess.

## Full environment verification

For release/high-value phase checkpoints, prefer the repository build/run scripts and real local dependencies:

- Java 21 / Maven;
- PostgreSQL + Liquibase;
- frontend TypeScript/Vite build;
- configured Ollama/local AI model;
- Tesseract where OCR coverage is relevant.

Do not claim a test/build passed when required tooling or dependencies were unavailable.

Detailed historical per-unit verification evidence is retained under `../archive/`.


## Integration and AI acceptance status

- Requirement 1 — Push API / machine-to-machine ingestion: ACCEPTED after full Kiro Maven/Liquibase/PostgreSQL/Spring/API verification.
- Requirement 2 — Scheduled polling / pull ingestion: ACCEPTED after full Kiro runtime verification; the verified Spring constructor-selection fix is part of the baseline.
- Requirement 3 — Redacted master delivery to SDV: ACCEPTED after live local-sdv delivery, partial/failure/retry, retention-clock, authorization and no-reprocessing verification.
- Requirement 4 — Derived document split/routing: ACCEPTED after deterministic marker/routing, authorization, persistence, HTTP receipt, FILE_SHARE durability and master-SDV-independence verification. One full runtime marker → derived PDF → HTTP/FILE_SHARE path remains a required final-regression exercise before V1 freeze.
- Requirement 5 — High-performance local AI: ACCEPTED. `qwen3.5:4b` is the V1 LOCAL/demo reference model after successful speed + quality benchmark on the current demo laptop; `gemma3:12b` remains optional for larger hardware only.

## Final V1 hardening gate

Before the V1 freeze, verify the focused hardening changes and run the final end-to-end regression, including:

- malformed JSON maps to a safe 400 response instead of generic 500;
- typed technical dependency failures map to safe classified 502/503 responses without provider internals;
- polling run counters retain successful document retrievals when a later document in the same package fails;
- polling source ACK still occurs only after final durable intake acceptance;
- SDV submission completion remains the only master-export/retention-success boundary;
- deterministic marker detection, redacted-only PDF splitting, HTTP receipt and FILE_SHARE durability tests pass;
- full runtime marker → derived PDF → HTTP and/or FILE_SHARE delivery succeeds from a real processed package;
- Requirements 1–5 and canonical authorization/tenant isolation do not regress.

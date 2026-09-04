# LexaVault Development Working Agreement

This agreement applies to future LexaVault implementation and verification work.

## Ownership

### ChatGPT

ChatGPT owns:

- requirements continuity;
- architecture and design continuity;
- implementation planning;
- code changes and focused refactoring required by approved scope;
- unit/batch scope control;
- self-review;
- documentation updates;
- packaging/source handoff.

Before changing code, ChatGPT must inspect the supplied/current repository and relevant current documentation. It must not recreate the application from memory or silently use an older source package.

### Kiro

Kiro is primarily an **independent, credit-conscious local reviewer/tester** because it can inspect and execute against the user's local machine/environment.

Use Kiro when local access materially improves verification, for example:

- full Maven/Java build verification;
- PostgreSQL/Liquibase checks;
- real runtime behavior;
- Ollama/Tesseract/provider checks;
- environment-specific failures;
- security/tenant-isolation challenge testing;
- phase-end regression verification.

Kiro should not implement, refactor, redesign, or broadly generate tests unless the user explicitly instructs it to do so after reviewing a concrete finding.

## Credit-efficiency rule

Do not run Kiro after every small change. Batch related checks and use Kiro at high-value verification checkpoints.

Prefer ChatGPT for:

- design;
- code review;
- troubleshooting from supplied evidence;
- implementation;
- test planning;
- documentation.

Prefer Kiro when machine/environment access is the reason the check is valuable.

## Standard workflow

1. Establish the authoritative source baseline.
2. Read `CURRENT_STATE.md` and relevant current product/architecture docs.
3. Inspect existing implementation before editing.
4. Restate scope and no-regression boundaries for material changes.
5. Implement the smallest safe end-to-end change.
6. Run all verification available in the current environment.
7. Never claim a build/test passed when the required tooling was unavailable.
8. Update current documentation; archive superseded implementation notes when appropriate.
9. Package a clean source handoff when requested.
10. Use Kiro once at a high-value verification checkpoint when local access materially helps.

## Scope discipline

- Do not start unrelated future work while verifying the current change.
- Preserve accepted behavior unless the approved scope explicitly changes it.
- Backend authorization and tenant isolation are authoritative; UI hiding is never a security boundary.
- Do not add architecture complexity merely because it may be useful in a future version.
- If historical documentation conflicts with current documentation/source, current documentation/source wins.

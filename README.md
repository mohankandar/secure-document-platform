# Secure Document Platform

Generic codebase for the LexaVault project codename. Market-facing naming is intentionally not hardcoded into reusable code.

## Current V1 Direction

An AI-first automatic secure document-redaction platform:

```text
PDF -> native extraction/OCR -> AI semantic discovery + deterministic evidence
    -> policy/confidence -> permanent redaction -> verification -> delivery
```

AI independently analyzes every normalized document. Deterministic recognizers/validators strengthen structured findings but do not gate AI discovery. High-confidence straight-through processing requires no human approval by default; only specific uncertain findings are routed to focused review.

## Repository Guidance

Before development, read:

- `.kiro/steering/requirements-index.md`
- `.kiro/specs/secure-document-platform-v1/unit-of-work.md`
- `docs/architecture/06-core-automatic-redaction-pipeline.md`

Current construction status: **Units 16–25 are approved. There is no Unit 26 in the frozen V1 plan.** The project is in **Final V1 Security / Integration + Acceptance** with focused pre-acceptance runtime corrections only when real customer-package testing exposes a concrete defect. The current baseline includes the Ollama page-level retry correction, the 2026-08-31 DOB/SSN precision and Manual Review preview-diagnostic correction, and the focused credit-card precision correction documented under `docs/continuity/`. Provider-neutral/tenant-prompt refactoring remains intentionally deferred until the current credit-card precision rerun passes.

## Current Continuity / Final Acceptance

Before coding or verification, read `docs/continuity/README.md` and `docs/continuity/00_LexaVault_V1_Master_Current.md`.

LOCAL convenience scripts are available at the repository root:

- `Start-LexaVault-Local.ps1` / `start-local.cmd`
- `Stop-LexaVault-Local.ps1` / `stop-local.cmd`

The current customer masking policy is tenant-configured for SSN, DOB, Credit Score and Credit Card Number, with a separate `maskSsnLastFour` option for contextual SSN-last-four exposure.

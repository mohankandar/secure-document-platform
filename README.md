# Secure Document Platform

Generic codebase for the LexaVault project codename. Market-facing naming is intentionally not hardcoded into reusable code.

## Current V1 direction

LexaVault V1 is an AI-first secure document-redaction and processing platform:

```text
PDF -> native extraction/OCR -> AI semantic discovery + deterministic evidence
    -> tenant policy/confidence -> permanent redaction -> verification
    -> focused review only when required -> delivery
```

AI analyzes every normalized document. Deterministic recognizers/validators provide supporting evidence and never gate AI discovery.

## Read before development

1. `docs/continuity/CURRENT_STATE.md`
2. `docs/continuity/DEVELOPMENT_WORKING_AGREEMENT.md`
3. `docs/product/V1_SCOPE.md`
4. `docs/product/AUTHORIZATION.md`
5. the architecture/product document relevant to the requested change

Full documentation index: `docs/README.md`.

Historical unit plans, prompts, self-reviews, and superseded role models are under `docs/archive/` and must not override current documentation/source.

## Build and LOCAL run

Recommended Windows workflow:

```powershell
.\build-all.cmd
.\start-local.cmd
```

See:

- `docs/operations/BUILD.md`
- `docs/operations/LOCAL_RUN.md`
- `docs/operations/LOCAL_AI_SETUP.md`
- `docs/operations/OCR_CONFIGURATION.md`
- `docs/operations/AI_PROVIDER_CONFIGURATION.md`

LOCAL convenience scripts:

- `Build-LexaVault-All.ps1` / `build-all.cmd`
- `Start-LexaVault-Local.ps1` / `start-local.cmd`
- `Stop-LexaVault-Local.ps1` / `stop-local.cmd`

## Current authorization

Canonical roles are `DOCUMENT_PROCESSOR`, `DOCUMENT_SUPERVISOR`, `BUSINESS_ADMIN`, `REPORT_VIEWER`, `TENANT_IT_ADMIN`, `PLATFORM_OPERATIONS_ADMIN`, and explicit internal `ADMIN`.

See `docs/product/AUTHORIZATION.md`. Retired `USER`, `REVIEWER`, and `APPROVER` names exist only as narrow identity-transition aliases where compatibility is still required.

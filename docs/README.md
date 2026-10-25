# LexaVault Documentation

This directory contains the **current** documentation needed to understand, build, operate, test and continue LexaVault. Superseded candidate/FIX readmes and archived unit-by-unit notes are intentionally excluded from the clean source handoff.

## Read first

1. `continuity/CURRENT_STATE.md` — authoritative current baseline and next action.
2. `continuity/DEVELOPMENT_WORKING_AGREEMENT.md` — ChatGPT/Kiro development and verification rules.
3. `design/LEXAVAULT_MARKET_READY_VISUAL_DESIGN_R1_PLAN.md` — active visual-product workstream.
4. `design/SECUREDOCS_UI_FRAMEWORK.md` — accepted implementation-level UI framework.
5. `product/V1_SCOPE.md` — product boundary.
6. `product/AUTHORIZATION.md` — roles/capabilities.
7. `product/WORKFLOW_AND_LIFECYCLE.md` — processing lifecycle.
8. `security/IDENTITY_AND_TENANCY.md` — current identity/tenant trust boundary.

## Areas

- `adr/` — architectural decisions.
- `api/` — machine intake/delivery/API contracts.
- `architecture/` — target architecture and processing design.
- `continuity/` — authoritative handoff and working agreement.
- `design/` — SecureDocs framework and market-ready visual-design plan.
- `intelligence/` — Document Intelligence foundation.
- `operations/` — build/local run/AI/OCR/logging guidance.
- `product/` — product behavior and authorization.
- `scanner-bridge/` — scanner boundary and end-to-end contract.
- `security/` — identity and tenancy.
- `testing/` — reusable acceptance/regression procedures and accepted verification references.
- `training/` — business/support/operator training material.

Precedence for active work:

1. current source code and database migrations;
2. `continuity/CURRENT_STATE.md` and current canonical docs;
3. active `.kiro/steering` requirements;
4. test/verification evidence for regression context.

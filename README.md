# LexaVault

LexaVault is the mortgage/real-estate secure-document processing application built on the `secure-document-platform` codebase. The repository contains the Java 21 backend, React/TypeScript frontend, scanner bridge, local/demo infrastructure, reusable test tooling, and the current canonical product/architecture documentation.

## Current baseline

- LexaVault V1 product behavior: **VERIFIED / FROZEN**.
- Enterprise Identity Administration R1: **VERIFIED / FROZEN**.
- Browser OIDC session continuity: **VERIFIED / FROZEN**.
- SecureDocs UI Framework implementation/consolidation: **VERIFIED / FROZEN** with framework contract **17/17 PASS**.
- Market-Ready Visual Design R1 (R1A-R1D): **VERIFIED / FROZEN** after desktop/laptop/iPad acceptance and independent review.
- Dependency Security Hardening R1: **VERIFIED / FROZEN WITH 2 ACCEPTED MODERATE FRONTEND FINDINGS**. Jackson is aligned at `2.18.9`; frontend Vite is `6.4.3`; no critical findings are known from the completed checks. See `SECURITY-DEPS.md` for scope and limitations.
- Enterprise Identity is an external Maven dependency (`com.documentplatform.identity`, version `0.2.0`) maintained in the separate `enterprise-identity-parent` project.
- Current Liquibase master includes migrations through `034-enterprise-identity-activation.xml` (**76 changeSets** in this source snapshot).

## Start here

Read these before making changes:

1. `docs/continuity/CURRENT_STATE.md`
2. `docs/continuity/DEVELOPMENT_WORKING_AGREEMENT.md`
3. `docs/design/LEXAVAULT_MARKET_READY_VISUAL_DESIGN_R1_PLAN.md` for the accepted visual-design contract
4. `docs/design/SECUREDOCS_UI_FRAMEWORK.md` for the frozen UI framework contract
5. the relevant current file under `docs/product`, `docs/architecture`, `docs/security`, `docs/operations`, or `docs/api`

Historical visual candidate notes and superseded implementation handoffs are intentionally excluded from the clean source tree. Current source and canonical documentation are authoritative.

## Build

Prerequisites: Java 21, Maven, Node.js/npm, and Enterprise Identity `0.2.0` available to Maven.

```powershell
.\Build-LexaVault-All.ps1
```

The normal build includes the frontend SecureDocs UI Framework contract.

Standalone UI framework verification:

```powershell
.\Test-LexaVault-UiFramework.ps1
```

## Local runtime

LOCAL/demo mode:

```powershell
.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR
```

OIDC/demo mode:

```powershell
.\Start-LexaVault-OIDC.ps1 `
  -IssuerUri "http://localhost:8181/realms/document-platform-local" `
  -Audience "lexavault-api"
```

See `docs/operations/LOCAL_RUN.md`, `docs/security/IDENTITY_AND_TENANCY.md`, and `scripts/README.md` for operational details.

## Repository layout

- `backend/` — Maven multi-module backend, scanner bridge, AI/redaction/application/infrastructure/API/test-support modules.
- `frontend/securedoc-ui/` — React/TypeScript application and framework contract tests.
- `docker/` — local supporting infrastructure.
- `scripts/` — reusable build/performance/testing helpers.
- `test-data/` and `test-tools/` — reusable acceptance fixtures and test tooling.
- `docs/` — current product, architecture, security, operations, testing, training, continuity, and design documentation.
- `.kiro/` + `aidlc-docs/` — retained intentionally because the repository's Kiro/AIDLC workflow depends on them.

## Clean-source policy

Generated outputs and local state must not be committed or packaged: `.git`, `.idea`, `.vscode`, `.run`, `target`, `node_modules`, `dist`, `.runtime`, logs, coverage, Playwright output, local processing storage, watched-intake data, performance/release evidence directories, generated helper `.class` files, and non-example `.env*` files are excluded by `.gitignore`.

# LexaVault: working instructions for Claude Code

These instructions apply to every session in this repository. The owner (Kr) makes the business decisions and approves every merge. Claude writes the code.

## What this repository is

LexaVault (`secure-document-platform`) takes in PDF documents, finds sensitive information, redacts it permanently, verifies the result and delivers it. It is a document processor, not a document store. Java 21, Spring Boot, a multi-module Maven backend under `backend/`, and a React, TypeScript and Vite frontend under `frontend/securedoc-ui/`.

Related repositories on this machine:

| Repository | Location | Branch |
|---|---|---|
| LexaVault (this one) | `C:\dev\lexavault\secure-document-platform` | `feature/lexavault-r1b-tenant-identity` |
| Secure Document Vault (SDV) | `C:\dev\sdv\secure-document-vault` | `release/vero-r7` |
| Enterprise Identity (shared library, 0.3.0) | `C:\dev\shared\enterprise-identity-parent` | `main` |

## Read these before changing anything

1. `docs/design/LOGIN_CUSTOMER_ENTITY_DESIGN.md`: the agreed design for login, customers and entities, and the order of work (section 12). This is the current plan.
2. `docs/continuity/CURRENT_STATE.md`: what is verified and frozen.
3. `docs/architecture/LEXAVAULT_R1B_TENANT_IDENTITY.md`: the R1B work on this branch.

`docs/continuity/DEVELOPMENT_WORKING_AGREEMENT.md` names a different tool as the code owner. That part is out of date: Claude now writes the code. Its other rules still apply.

## Rules

**Proof before commit**
- Never commit code that has not been built and tested. With every change, report the exact commands run and what passed or failed.
- If something fails, show the failure and stop. Do not change tests to make them pass without saying so.
- Add or adjust tests with each change. Anything unclear must be refused, not guessed (fail closed).

**Branches**
- One branch per piece of work, taken from `feature/lexavault-r1b-tenant-identity`.
- Do not commit to `main`. Do not push, merge or rewrite history unless the owner says so in the session.

**Frozen areas.** Change these only for a demonstrated regression, and ask first:
- V1 document processing behaviour.
- Enterprise Identity R1, and OIDC session continuity.
- The UI framework.

**R1B guardrails**
- The shared-issuer setting stays off by default.
- Migration 037 stays out of the master changelog until the owner approves registering it.

**Secrets**
- No secrets in the repository, in logs or in the chat.
- Never open, print or copy anything under `C:\dev\sdv\secrets`.
- Never print database passwords or tokens. Refer to where they are stored.

**Local services.** These Docker containers hold real development data: `securedoc-postgres`, `sdv-postgres`, `sdv-vault`, `lexa-keycloak`, `lexa-keycloak-db`.
- Do not remove or recreate them, and do not delete their volumes.
- Do not run `docker compose down` against them.
- Never initialize, reset or reseal the Vault.
- For a test that needs a database, create a separate throwaway database or container and remove only that.

**Files**
- Do not delete untracked runtime folders such as `.run` or `backend/securedoc-storage`.
- `frontend/securedoc-ui/.env.local` is a local, ignored file. Leave it as it is.

**Ask before**
- Any database schema change.
- Adding or upgrading a dependency.
- Deleting files.
- Anything in a frozen area.

**How to report.** The owner prefers short, plain answers: what changed, what was run, what passed, what is next. No long background.

## Work routine

Tasks and reports live in `docs/work/`. When the owner says "do the next task", follow `docs/work/README.md`: find the highest-numbered task with no report, do it, and write the matching report. Work that did not come from a task file still gets a report if it changed code.

## Environment (Windows)

- Java 21 (Temurin), Maven 3.9.16, Node 22, Git, Tesseract 5.4, Ollama with the `qwen3.5:9b` model.
- PostgreSQL runs in Docker on port 5432.
- Enterprise Identity 0.3.0 is installed in the local Maven repository. If it is missing, run `mvn clean install` in `C:\dev\shared\enterprise-identity-parent`.

| Task | Command (from the repository root) |
|---|---|
| Full build with tests | `.\Build-LexaVault-All.ps1` |
| Backend only | `cd backend; mvn clean install` |
| Start locally | `.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiModel qwen3.5:9b` |
| Stop locally | `.\Stop-LexaVault-Local.ps1` (stops the apps and leaves the database running) |
| Restart the frontend only | `.\restart-ui.cmd` |

The app is at http://localhost:5173 and the backend at http://localhost:8080.

## Known state on 2026-11-17

- The full build passes on this machine, with 453 backend tests (domain 31, application 138, scan bridge 47, infrastructure 8, API 229; counted 2026-11-19, U018 follow-up).
- Frontend: `cd frontend/securedoc-ui; npm run test:standalone` runs every frontend suite that needs no running app (`test:auth` 10, `test:vocabulary` 85, `test:ui-framework` 17, `test:oidc-signin` 69; counted 2026-11-19, U019). The standard build does not run it; run it with every frontend change. `test:e2e` still needs the LOCAL app running on 5173.
- Surefire 3.5.2 is pinned in backend/pom.xml for every module. Before that, the 5 infrastructure test classes ran only where Maven's own default Surefire supported JUnit 5 (Maven 3.9 on Windows did, Maven 3.8.7 on Ubuntu did not).
- Enterprise Identity is at **0.3.0** (tag `v0.3.0`, entity admission). LexaVault uses 0.3.0; SDV still uses 0.2.0.
- **Phase 1b is done** (reports 0011 and 0012): the login and entity rules (entity admission, entity choice, group-to-role rules, machine admission, actor ids) now live in the Enterprise Identity library, package `com.documentplatform.identity.admission`. **New login and entity rules are written in the library, not in LexaVault.** LexaVault keeps only its seam: `OidcTenantMembershipResolver`, `TrustedActorResolver`, `ProductEntityRoleCatalog`, `IdentityAdmissionMapping`, its own tables and SQL, roles and capabilities. The library owns no tables and no setting names (`SCHEMA_CONTRACT.md` in the library lists what a product must provide).
- **Phase 1 is accepted** (reports 0004 to 0009): one login system, two entities, in the isolated environment under `test-tools/phase1-shared-issuer/` (60 scripted checks and the browser script pass; the owner's screen checks pass). Migration 037 was applied there through Liquibase by a test-only changelog; it is still not in the master changelog, and shared-issuer selection is still off by default.
- The newest (Case File) design has the entity switch at the top right (task 0009). The single-entity sign-in race on the dev server is fixed (task 0008).
- The Phase 1 containers are kept as the regression environment; start the rest again with `test-tools/phase1-shared-issuer/Start-Phase1.ps1 -JarBuiltFromCommit <sha>`, then `Invoke-Phase1Checks.ps1` (60 checks) and `Invoke-Phase1BrowserSignin.mjs`. Tasks and reports are in `docs/work/`.
- There is no automatic build on GitHub.

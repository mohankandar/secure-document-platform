# Overnight queue 2026-11-23: summary

| Task | Outcome | Branch | Final commit | Report |
|---|---|---|---|---|
| 0041 identity switch-over on platform-core 0.2.0 | DONE | `feature/0037-switchover-1a` | `6a33005` | `docs/work/0041-report.md` |
| 0042 CI | STOPPED: the product-name guard fails on 69 existing files; fixing needs product code changes or your decision (report 0042, question 1). Rest built and proven locally; not pushed | `feature/0042-ci` | `97d5202` (this summary follows it) | `docs/work/0042-report.md` |
| 0043 R1 new pattern types | NOT STARTED (queue stop rule 3) | | | |
| 0044 R2 searchable | NOT STARTED (queue stop rule 3) | | | |

Nothing merged into `feature/lexavault-r1b-tenant-identity`; nothing pushed. Throwaway containers (`lexavault-0041-pg`, `lexavault-0041-keycloak`, `lexavault-0042-pg`) removed; protected containers not touched.

## For the owner in the morning
1. **0041:** stop the running app, rebuild (`.\Build-LexaVault-All.ps1`), then `.\Start-LexaVault-OIDC.ps1 -IssuerUri http://localhost:8181/realms/document-platform-local -Audience lexavault-api -ScannerMode SIMULATOR -AiModel qwen3.5:9b` and `.\Test-LexaVault-OidcSessionContinuity.ps1 -Username businessuser -PromptForPassword`. Expected: identity "1 migration(s) run, at version 3"; setup "8 created … 5 unchanged"; demo step unchanged.
2. **0041:** fast-forwarding `feature/0037-switchover-1a` in this checkout changed frontend sources under your running Vite server (it reloads); the running backend was not touched.
3. **0041 questions:** setup on every OIDC start with `--replace-connection` (OK?); deleting `OidcDiscoveryTester` and the old connection writers later.
4. **0042 question 1:** the product-name guard: ratchet baseline now (recommended), renames first, or strict and red.
5. **0042 question 2:** check or exclude `case-file-next/` and `integration-reference/` copies.
6. **0042:** add the secret: `$env:GITHUB_PACKAGES_TOKEN | gh secret set PLATFORM_CORE_READ_TOKEN -R mohankandar/secure-document-platform`; push and PR commands in report 0042 if you want CI to run; branch protection with `ci-passed` after question 1.
7. Then restart the queue at 0042's guard, followed by 0043 and 0044.

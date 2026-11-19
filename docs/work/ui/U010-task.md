# Task U010: Admin › Platform operations (findings 16, 26, 27, 28)

Written: 2026-11-17, by the design chat. Spec: `docs/work/ui/SPEC.md` (locked). Read `docs/work/ui/README.md` first.

Designs: `designs/AdminPlatform.html`, `designs/PlatformStorage.html`, `designs/PlatformLicensing.html`, `designs/PlatformBranding.html`.

## Overview (IT Administrator and Platform Operations)

1. Service list in plain words ("Text recognition", "Delivery to Secure Document Vault"), status Working / Slow / Down.
2. Watched folder: one plain summary line; node, lease, fencing, takeovers and retry settings under Technical details, visible to Platform Operations only.

## Sub-sections (Platform Operations only, as today)

3. **Processing storage** (was Temporary processing infrastructure): one table (storage, used by, status, actions); steps Add → Test → Make default; "Retire" → "Stop using…" with a confirmation; "Tenant overrides" → "Give one customer its own storage"; names in normal case.
4. **Licensing:** one row per customer and entity, status, pages this month of limit, entities used of licensed, expiry; an attention banner for licences expiring within 30 days, saying what happens on expiry; "Install renewal" in the row; install form "Check and install". Licence id, signature mode, raw limits and history under Technical details.
   - **Backend needed:** pages used this month per entity, if not available. Propose first.
5. **Branding:** one form with an "Apply to" picker (Everyone, or one customer) instead of two full forms; the preview shows today's top bar, not the old sidebar.
6. **Usage data** (was Product telemetry): plain words on what is sent and what is never sent; mode Off / Basic / Detailed.

## Checks (all tasks)

- `npm run build` and `npm run test:standalone` pass; give counts.
- Every changed screen checked in light and dark, at 1440 px and 1024 px, with the local app (`.\Start-LexaVault-Local.ps1`). Say which local test roles you checked.
- No raw hex outside `tokens.css`; only documented breakpoints; no new dependency without asking.
- Commit on `feature/ui-polish`. Do not push or merge.

## Owner's decisions (2026-11-18)

- **Item 4, Licensing:** do not build it in this task. Leave the current Licensing sub-section exactly as it is. The owner decided licensing waits for Phase 5, and the approved install approach (`docs/work/install-onboarding-approach.md`) turns it into a read-only status page (a license file is dropped in and the app restarted, so there is no "Install renewal" or "Check and install" form). No backend work for pages used per entity now.
- **Item 1, status words:** use only what the server can truly tell. If it can't tell slow from failing, show "Working / Having problems / Down", as in U008. No "Slow".
- **Items 2, 3, 5 and 6:** as written.

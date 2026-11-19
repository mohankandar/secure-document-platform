# Report U010: Admin › Platform operations (findings 16, 26, 27, 28)

Date: 2026-11-18
Branch: feature/ui-polish
Outcome: DONE (Licensing not built, as the owner decided)

## What was done

- **Overview (IT Administrator and Platform Operations):**
  - Services are listed in plain words: Database, AI model, Text recognition, Processing storage, and "Delivery to Secure Document Vault" (or "Delivery to a local folder").
  - Status words are limited to what the server reports (owner's decision):
    - HEALTHY: Working
    - DEGRADED: Having problems
    - UNHEALTHY or UNAVAILABLE: Down
    - not probed: "Set up · not checked yet"
    - NOT_CONFIGURED: Not set up
    - DISABLED: Switched off
    - anything else: "Status not recognised", never Working
  - No "Slow". The title counts what needs attention: "1 service needs attention", "All services working" or "No problems found · 4 not checked yet".
  - Fixed on the way: the old screen compared every status to `UP`, which the server never sends, so every service showed as needing attention.
  - The watched folder has one line, using U008's wording: path · checked every N, then "Working · checked 1 min ago · 2 accepted, 1 waiting for its READY file". It also says where Scan now lives.
  - The old "Resilience and integrations" card is gone. Node, lease, fencing, takeovers, retries, AI failover, delivery type, identity mode and the raw service codes are under two closed **Technical details** sections, shown to Platform Operations only.
- **Processing storage** (was "Temporary processing infrastructure"):
  - One table: storage (name and path), used by, status, actions.
  - "Used by" reads, for example, "Default for every customer", "Default for everyone else", "Northwind Commercial only", "Not used yet".
  - Status reads, for example, "Working", "Not working · the folder doesn't exist", "Not working from 1 of 2 servers · can't write here", "Not tested yet". The latest check per server wins.
  - The readiness tag reads "Ready · 1 server", with a plain notice when storage is not ready.
  - Actions: Test, Make default (turned off until the storage works from every server, as the server requires), and "Stop using…". "Stop using…" asks for confirmation and says what happens.
  - The add form opens from "Add storage". The kinds are "Shared network folder" and "Folder on this server".
  - "Give one customer its own storage" picks a customer and a storage, and lists the customers that have their own, with "Use the default".
  - IDs, provider types, versions and node checks are under Technical details.
- **Branding:**
  - One form with an "Apply to" picker: Everyone (default), or one customer (marked "own look" when it has one).
  - Fields up front: Product name, Organization name, Accent colour, Logo.
  - Short name, Browser tab title, Second colour, Small logo and Browser icon are under "More options".
  - For a customer, blank fields use everyone's look, and "Use everyone's look" clears its own look.
  - Reset and Save are off until something changes.
  - The preview now shows today's top bar (logo or mark, name, Cases and Review, account), not the old sidebar.
- **Usage data** (was "Product telemetry"):
  - The mode shows as Off / Basic / Detailed (ENHANCED shows as Detailed), read-only, with "Set at installation".
  - "What is sent" is a sentence from the allowed events ("Nothing is being sent." when off).
  - "Never sent" is a plain list.
  - Event codes and the privacy contract are under Technical details.
- The section labels and the Platform operations hint use the vocabulary ("Processing storage", "Usage data").

### Where the screen differs from the design (on purpose)

- **No "Sign-in · Okta" row:** the server reports no sign-in health.
- **No model speed ("answers in 1.8 s"):** the server reports no timing. The AI row shows only "Ollama".
- **Branding preview:**
  - There is no accent-coloured "Primary" button, because the Case File screens don't use the accent colour (see Owner's answers). The field's hint says where it is used.
  - "Organization name" is shown in the account menu, not under the person's name, so the hint and the preview say that.
- **The Technical incidents card on Overview is unchanged:** the task does not cover it, and U012 handles problem pages.

## Commands run and results

- `npm run build` (frontend): passed.
- `npm run test:standalone`: passed:
  - `test:auth` 8;
  - `test:vocabulary` 58 (10 new);
  - `test:ui-framework` 17;
  - `test:oidc-signin` 56 (4 new).
- **Local app:**
  - Started with `.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiModel qwen3.5:9b`. The backend is unchanged, so the existing jar was used. "Frontend did not become ready" appeared again, as in U007 and U008; both apps answered (200).
  - Checked in Edge (Playwright):
    - **IT Administrator** (local test role): Overview.
    - **Platform Operations Administrator**: Overview, Processing storage, Licensing, Branding and Usage data.
    - Each in light and dark, at 1440 and 1024 px, with every Technical details section opened.
  - No page errors, no error cards and no sideways scroll. The IT Administrator sees no sub-sections and no Technical details.
  - Nothing was saved, tested, run or retired on the dev data.
  - Stopped with `.\Stop-LexaVault-Local.ps1`.
- **Rules:**
  - no raw hex added outside `tokens.css`;
  - no new breakpoint and no new dependency;
  - `uiFrameworkContract.test.ts`, `src/index.css`, the legacy pages and `LicensingSection.tsx` are unchanged.

## Tests: counts per module, and any failures with their output

- **New `src/studio/admin/platformRules.test.ts` (node, 10):**
  - section names;
  - service names and products;
  - status words: no "Slow", and unknown statuses are never shown as Working;
  - the services title;
  - interval words;
  - storage: the latest check per server wins, status words, who uses it, the readiness line with no codes;
  - usage data: modes, events, never-sent words and the summary.
- **New `e2e-oidc/adminPlatform.spec.ts` (Playwright, mocked API, 4).** It also fails on any page error.
  - **IT Administrator:**
    - plain services and statuses;
    - the watched-folder line;
    - no sub-sections, no Run health checks, no Technical details;
    - none of the old words or codes.
  - **Platform Operations, Overview:**
    - the five sections;
    - both Technical details sections are closed and hold the codes;
    - Run health checks calls the endpoint.
  - **Processing storage:**
    - the rows' Used by and Status;
    - Make default is off when the storage isn't working;
    - no Stop using on a storage in use;
    - Stop using asks first, then calls retire;
    - the customer with its own storage is listed;
    - none of the old words.
  - **Branding and Usage data:**
    - one Save;
    - the preview follows the product name;
    - Save sends it to everyone's branding;
    - a customer's form says that blank fields use everyone's look;
    - the Usage data mode, the sends sentence and the never-sent list, with no codes.
- **Failure during the work (in my own test):** the IT Administrator lands on Integrations first, and my mock answered `{}` there (`conns.data is not iterable`, caught by that area's error card). The mock now answers empty lists. The screens under test were right.

## Files changed

- `frontend/securedoc-ui/src/studio/admin/platformRules.ts` (new), `platformRules.test.ts` (new)
- `frontend/securedoc-ui/src/studio/admin/HealthArea.tsx`, `StorageSection.tsx`, `BrandingSection.tsx`, `TelemetrySection.tsx`, `Admin.tsx` (hint), `admin.css`
- `frontend/securedoc-ui/e2e-oidc/adminPlatform.spec.ts` (new)
- `frontend/securedoc-ui/package.json` (the new test file was added to `test:vocabulary`; no dependency change)
- `docs/work/ui/U010-task.md` (owner's decisions, separate commit), `docs/work/ui/U010-report.md`

## Commits (hash and message), or "not committed"

- `8bbd095` docs(ui): owner's decisions for U010 (licensing deferred, status words)
- `bc7b7fa` feat(ui): Platform operations in plain words, one storage table, one branding form, usage data (UI task U010)

## Not done, and why

- Licensing (item 4): owner's decision. It waits for Phase 5 and becomes a read-only status page.

## Questions for the owner

1. The accent colour is stored and used by a few older screens, but the Case File screens never use it. Should the Case File screens use it (for example for the primary button), or should the field be removed from Branding later? I left it with an honest hint.

## Owner's answers (2026-11-18)

1. **Accent colour:** the Case File screens do not use it. Keep the honest hint for now. Removing the field from Branding goes in the backend work after the UI series.

## Suggested next step

U011 (Audit trail).

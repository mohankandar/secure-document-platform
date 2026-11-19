# Report U009: Admin › Sign-in (finding 15)

Date: 2026-11-18
Branch: `feature/ui-polish`
Outcome: DONE, built as the owner's decision **B**. The "decide, then stop" step was skipped, as the owner asked.

## What was done

1. **The Sign-in tab is a read-only card** (`src/studio/admin/IdentityArea.tsx`): "Sign-in", then "Your sign-in is managed through the login hub. To change it, contact LexaVault support."
2. **Read-only details,** only those the server already returns (`GET /admin/enterprise-identity`, no backend change):
   - "Login system": the active connection's name;
   - "Address": its address, in small mono text.
   - Each detail is left out when it isn't there. With no active login system, the card stands alone.
   - **"Last used successfully" is not shown.** The server has no such time. It only returns the last test of a staged draft, which is a different thing.
3. **Removed from this tab:** the staged-draft form, Save draft, Test connection, Activate, Known-good rollback and the confirm dialog. The tab has no inputs and no buttons.
4. **The tab hint** reads "How people sign in. It is managed through the login hub." (It was "Enterprise identity provider: draft, test, activate and roll back.")
5. **Who sees it:** unchanged. The tab needs `TENANT_AUTH_MANAGE`, which only the IT Administrator has.
6. **Left as they were:**
   - role mappings, where Phase 2 put them;
   - the legacy `AuthenticationAdminPage` and its API calls;
   - the backend endpoints;
   - `uiFrameworkContract.test.ts` and `src/index.css`.

## Commands run and results

- **Frontend:**
  - `npm run build`: passed.
  - `npm run test:standalone`: passed:
    - `test:auth` 8;
    - `test:vocabulary` 48;
    - `test:ui-framework` 17;
    - `test:oidc-signin` 52 (3 new).
- **Backend:** not changed by this task. The full `mvn clean install` (453 tests) passed on the same tree before the U008 commit.
- **Local app** (same run as the U008 follow-up: `.\Start-LexaVault-Local.ps1 -ScannerMode SIMULATOR -AiModel qwen3.5:9b`, then `.\Stop-LexaVault-Local.ps1`):
  - **IT Administrator** (local test role): Sign-in in light and dark, at 1440 and 1024 px (Edge, Playwright).
    - The card shows the words.
    - The local app has no active login system, so no details show; the details are covered by the mocked test below.
    - 0 inputs and 0 buttons.
  - **Business Administrator** (local test role): no Sign-in tab.
  - No page errors and no sideways scroll. The tab made no write call.
- **Rules:**
  - no raw hex;
  - no CSS change, no new breakpoint, no new dependency.

## Tests: counts per module, and any failures with their output

- **New `e2e-oidc/adminSignIn.spec.ts` (Playwright, mocked API, 3).** It fails on any page error from the tab.
  - **IT Administrator,** with an active, a staged and a rollback connection in the answer:
    - the words;
    - the name and address;
    - the new tab hint;
    - no input, select or textarea;
    - no Save, Test, Activate, Switch on, Roll back or Edit button;
    - none of the old words (Staged, draft, Issuer, API audience, Browser client, Known-good rollback, the staged address, status codes);
    - no write call.
  - **IT Administrator with no active login system:** the card alone, with no details and no button.
  - **Business Administrator:** no Sign-in tab, and `/admin/identity` doesn't show the card.
- **Proof the test catches the old tab:** run against the old `IdentityArea.tsx`, the two IT Administrator tests failed and the role test passed. The new file was then restored.
- **Failures during the work (all in my own new test; the screen was right):**
  - **"strict mode violation: region 'Sign-in' resolved to 2 elements":** the Admin area and the card had the same name. The card is now labelled "Login hub".
  - **"conns.data is not iterable" and "returned an answer this screen does not understand":** the mock was missing the lists for the Admin areas that open first. It now answers them, and only errors from the Sign-in tab count.
- Failures: none in the final runs.

## Questions for the owner

None.

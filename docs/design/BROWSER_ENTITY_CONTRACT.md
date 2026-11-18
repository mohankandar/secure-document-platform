# Browser entity contract

Status: agreed 2026-11-17 (task 0011). Applies to every product UI that signs people in to an entity through the shared login: LexaVault now, SDV from Phase 6. The browser code itself stays in each product (decision for Phase 1b). This page is what both must match.

## The two server endpoints

| | |
|---|---|
| `GET /api/v1/me/tenants` | The entities this signed-in person may choose, from the server: `[{ tenantId, name, slug, roles[] }]`. Reachable before a choice is made. Sent with `Cache-Control: no-store`. An empty list means the person has no entity: the UI refuses sign-in. |
| `GET /api/v1/me` | Who the person is in the chosen entity: name, roles and capabilities for that entity only. |

## The header

- `X-Selected-Tenant-Id: <entity id>` goes on every API request once an entity is chosen.
- The browser keeps the choice for the browser tab only (session storage), never longer.
- With one entity, the UI chooses it without asking.
- With two or more, the person chooses on a chooser screen, and can switch later from inside the app.

## The three rules

1. **The choice is a request, never proof.**
   - The server checks the header on every request, against the verified token, the ACTIVE login-system record and the person's group grants.
   - A forged, stale or malformed choice is refused.
   - The UI never decides access from the choice.
2. **After a switch, nothing from the previous entity is shown.**
   - Responses to requests started before the switch are discarded, not shown.
   - Everything the UI holds for the previous entity is dropped: data, cached responses, roles and capabilities.
   - Roles and capabilities are then read again for the new entity.
   - If the server refuses the new entity, the person stays where they were.
3. **Sign-out clears the choice.** Ending the session removes the chosen entity together with the tokens, so the next person in that browser starts with no choice.

## Where LexaVault implements it

| Rule | LexaVault code |
|---|---|
| The selection and its revision | `frontend/securedoc-ui/src/api/tenantContext.ts` |
| The race guard (rule 2) | `frontend/securedoc-ui/src/api/client.ts` |
| Chooser and switch, one implementation (`enterTenant`, `listSwitchableTenants`) | `frontend/securedoc-ui/src/App.tsx` |
| Switch control, newest design | `frontend/securedoc-ui/src/studio/EntitySwitch.tsx` |
| Clearing on switch (rule 2) | the workspace is keyed by entity, with a private data cache per mount (task 0009 report) |
| Server side (rule 1) | `RequestContextResolver` and the Enterprise Identity admission rules |

Browser tests: `npm run test:oidc-signin` (sign-in race, switch, refusal, in-flight discard).

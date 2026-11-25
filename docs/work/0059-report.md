# Report 0059: platform-core v1.0.0, part 3: notifications and approvals

Date: 2026-11-24
Branch: `feature/0059-notifications-approvals`, with the integration branch merged in (up to 0072)
Outcome: **DONE** (9 questions). Built by a subagent from a written brief. I read the approval, recipient, mail and secret code, fixed what I found, and did the merges. Commands marked (subagent) were not repeated by me.

## What was done
1. **Notifications per entity.** Each entity is its own notification tenant. Recipients are known from verified sign-ins (actor id, roles, e-mail; 14 days); the list gives no access. Licensing notices are turned into one per entity.
2. **Mail** (Admin > Notifications, IT Admin): Microsoft 365 OAuth, or own Exchange/relay. Secrets are named, never stored, and an entity can only name its own. Addresses and ports are checked at save and at each send. The test message goes only to the sender.
3. **Messages:** review waiting and past target, approvals waiting, source unreachable, files rejected, repeated failures, over target, destination paused / needs IT / unreachable / busy over 30 minutes, cases need IT, AI model down, disk almost full, storage not usable, intake safety stop (0067), policy changed, licence events. Times and thresholds are set per entity.
4. **Approve delivery by link.** Opening decides nothing. Only the addressed person, signed in to that entity and still holding the role; never their own request. One transaction locks the case at that version, stores the decision (first wins) and runs the normal approval. Declining needs a fixed reason. All audited.
5. **Outbound proxy sign-in** is allowed (0054 decision 1).
6. **Phase 1 environment retired** (0058 answer 5): `test-tools/phase1-shared-issuer` removed, `CLAUDE.md` "Known state" updated. Its containers and volumes are untouched.

**Migrations:** 063, 064. No registered changeset edited. **Dependencies:** platform-notifications-spring 1.0.0, which brings angus-mail 2.0.5 and jakarta.mail-api 2.1.5 (left out until now).

## Found in my review and the merges (fixed, each with a test)
- The asking round took the 50 oldest waiting cases of all entities: one entity with nobody to ask would have held up every other. Now counted per entity and submitter.
- Since 0076, refused bulk approvals are recorded under the same audit action. The link would have told the requester "approved". Now only successes count.
- Also tested: a case rejected meanwhile (0071) cannot be approved by its link.

## Commands run and results
On the merged code up to 0076:

| Command | Result |
|---|---|
| `mvn -B -Pdev-build clean install`, PostgreSQL tests on | SUCCESS: domain 85, application 319, scan bridge 136, infrastructure 134, API 876 (2 skipped: timing test, TLS mail), 0 failures |
| `npm run build`; `npm run test:standalone`, 1 run | built; 10, 223, 19, 201, 95, 174, 0 failures |
| No-redacted-value check | PASS, 0 matches |
| Compliance report check | PASS, 126 checks |
| Product-name guard | clean (1823 files) |
| Start paths, throwaway Keycloak and PostgreSQL (subagent, before the merges) | ALL PASS: fresh LOCAL; a 0058 database; fresh OIDC with mail, link and approval |

After merging 0072: the same backend build: SUCCESS (85, 319, 136, 134, 880; 0 failures), guard clean. The frontend suites were not repeated here; CI runs them.

The two checks ran from copies of the tools, changed only in container name and path. **Left for M1:** two-server proof, export end-to-end, repeats, start paths on the merged code. The Build scripts (jar allow-list changed) were not run.

## Failures met
- `PlatformLicensingConfig.activatedEntitySlots` had lost its `@Bean` in 0058; restored.
- `SchemaStepPostgresTest`: the runtime user's 8 new `notif_` rights were not in its list; added.
- Desktop antivirus answers local TLS mail with its own certificate, so the TLS mail test is skipped on this machine.
- `exports.spec.ts:134` timed out once while a build ran; 20 repeats and the later full runs passed. Cause not found.
- My first run of the two checks did not start (a quoting mistake); my new test did not compile at first.

## Existing tests changed (none removed)
`SignInConnectionTestTest` (proxy sign-in allowed, bad references refused), `PlatformLicenceTest` (catalogue moved), `SchemaStepPostgresTest` (rights), 5 migration tests (rollback counts +2); cases added to `OidcTenantDiscoveryControllerTest`, the a11y harness and `screens.spec`.

## Behaviour changes
- **The app does not start until the schema step has run.** The local start scripts run it.
- Nothing is sent until `securedoc.notifications.app-base-url` is set and an entity switches e-mail on. Links also need `platform.notifications.approvals.link-key-secret`.
- Person ids, roles and e-mail addresses are stored for 14 days.

## Where the design is not met
- The message holds the internal case id, not the main ID (question 1). The page's "why" is a fixed text.
- Teams and Slack: not built (question 3).
- Licence warning days (60/30/7/1) are fixed in platform-core.
- Not built, no detector: database down, OCR down, key service (0060), backlog growing, emergency admin, model drift.

## Not proven
- Real Microsoft 365 or Exchange; TLS mail on this machine; two real servers.
- The mail library looks the host up again after the address check (DNS rebinding).
- A licence notice made while e-mail is off is lost. Alerts of a server removed while failing stay open.
- The token is in the address of the first page load, so a web server's log can hold it; it is useless without that person's sign-in.
- The safety-stop message was tested as raised by a server, not as a sent e-mail.

## Questions for the owner (recommended answer taken)
1. Internal case id in messages, because the main ID may be a redacted kind? Yes.
2. Recipients from sign-ins, kept 14 days? Yes.
3. Teams and Slack as a later task? Yes.
4. Mail settings per entity? Yes.
5. Decline leaves the case waiting. Since 0071 a case can be rejected; should a decline reject it? No: rejecting can't be undone.
6. The submitter can't approve by link but still can on the case. Make both refuse? And should a reviewer be refused by link too, as in bulk approval (0076 question 1)? Yes to both, as one task; unchanged now.
7. Links live 24 hours, one per case version? Yes.
8. OCR and database-down messages wait for a standing check? Yes.
9. The 5 new audit actions stay off the compliance appendix until reviewed? Yes.

## PLATFORM-CORE (nothing patched; by reading)
- One mail server per tenant needs its own Notifier: the channel classes are package-private (no wake-up after publish, no health per destination).
- Licensing publishes only as the customer and cannot be mapped to entities without wrapping the stores.
- Microsoft sign-in and SMTP do not go through platform-outbound.
- Licence warning days are not adjustable, though 7.4 says so.
- An app cannot close a request that was decided outside the links.
- Two recipients of a plain notice see each other's address.

## For the owner
After the merge: `Build-LexaVault-All.ps1`, then start as usual; 063 and 064 run once. The containers `lexavault-phase1-*` and their volumes are yours to remove.

## Suggested next step
Task 0060.

No other product's name or behaviour in code (the known exception, `SdvDeliveryAdapter`, is untouched).

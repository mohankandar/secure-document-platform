# Install and onboarding approach (LexaVault and SDV/Vero)

Agreed by the owner (Kr) on 2026-11-18. Applies to both products, on-premises and hosted. Status: **approved approach, not yet built.** It goes on the backlog and is evaluated together with Phase 5 (licensing) when the backend work resumes after the UI series.

## Principle

The install does only the minimum to bring the app up safely. Everything else is set up afterwards **in the app**, by the role that owns it, using the admin screens that already exist.

- **No install screens, no setup wizard, no new screens.**
- **No temporary or default login** in a customer environment.
- **Dev mode is for local development only.** Production refuses dev and demo mode (the existing demo-mode safety check stays).

## The three pieces

### 1. Signed license file: the app does not start without one

- **Issued and signed by Kr's side** with a private key. The app checks it with the matching public key, which ships with the app, so a license cannot be edited or forged.
- **What it holds:** the customer, the tenant and entity IDs, the number of entities allowed, the products covered (LexaVault, SDV) and the expiry date.
- **Tenant IDs come from the license.** They are never typed in separately, so they cannot be mistyped or swapped. Tenant IDs are unique across all customers.
- **Missing, invalid or tampered license:** the app does not start. The log says why, in plain words.
- **Install license:** short, e.g. 30–60 days. When the install is signed off, Kr's side issues the production license. Replacing the file and restarting is enough.
- **Expired license:**
  - new intake stops, and admins see a clear warning beforehand (e.g. from 30 days out);
  - people can still view and export their existing documents;
  - a customer is never locked out of their own documents overnight.
- **In the app:** the Licensing screen is read-only status ("Licensed for 2 entities, expires …"). Licenses are not managed in the UI.

### 2. Setup command: creates the customer from the license

- **One command, run once per customer**, by Kr's platform operations team.
  - It reads the license and creates the customer, its tenants and entities.
  - It loads the sign-in configuration (below).
  - It is safe to run again: it never duplicates or deletes.
- **No database inserts at server startup.** The schema step is separate from startup (decided earlier).
- **Database secrets stay with whoever owns the database.** On-premises, Kr's team gets only the database URL; the password comes from the customer's own secret store.

### 3. Sign-in config file: sign-in is set by script, not screens

- **Filled in by the customer's IT team:**
  - their login system's address (Okta / OIDC issuer);
  - the client ID;
  - which of their groups map to which product roles (IT Administrator, Business Administrator, Document Supervisor, Document Processor, Report Viewer).
- **The client secret is not in this file.** It goes into the customer's secret store, or Kr's on hosted, and the file only says where to read it.
- **The setup command loads it into the login hub.** From the first start, the app accepts only real logins through the login hub.

## First login: real people, existing screens

1. **The customer's IT Administrator** signs in through their own login system and finishes the technical settings: integrations, sign-in details, role mappings.
2. **The Business Administrator** signs in and sets policies, retention, review targets and entities (within the licensed count).
3. **Sign-off** follows the first-install success criteria.

## Emergency access, when the customer's login system is down

- **On-premises:** one local emergency admin, **off by default**.
  - It can be turned on only by a command on the server, by someone with server access.
  - It is time-limited and turns itself off.
  - Every use is recorded in the audit log.
- **Hosted:** Kr's operations staff, in their separate login-hub space, are recorded in the audit log the same way. There is no per-customer local account.

## On-premises and hosted: same routine, different owner

| Step | On-premises | Hosted |
|---|---|---|
| Database and schema | Customer's database administrator runs the provided scripts | Kr's deployment pipeline |
| Login hub | Installed in the customer's environment | Kr's one shared hub; email-first sign-in routes each person to their company's login system |
| License file | Kr's side issues it; installed on site | Kr's side issues and installs it at onboarding |
| Setup command | Kr's team, on site | Kr's platform operations team, as an onboarding step |
| Sign-in config file | Customer IT fills it in | Customer IT fills it in |
| Secrets | Customer's secret store | Kr's secret store |
| License renewal | New file sent; customer drops it in and restarts | Kr's side replaces it |
| Emergency access | Local emergency admin, enabled by server command | Kr's operations staff in their hub space |
| Updates | Sent to the customer | Deployed by Kr's side |

**Moving from on-premises to hosted later:** the same license, customer and tenant IDs carry over, because tenant IDs are unique across all customers.

## Runbook (to write when this is built)

One runbook with an on-premises column and a hosted column, so the team learns one routine. Each step names its owner (customer database administrator, customer IT, Kr's team, customer IT Administrator, customer Business Administrator) and what is handed over (URL, client ID, group names). **Never passwords or secrets.**

## To build (backend, after the UI series)

Evaluate this together with Phase 5 (licensing) and the paused backend work, then plan:

1. license file format, signing tool (Kr's side) and start-up check (app side);
2. expiry behaviour: warning, then intake stops, viewing stays;
3. the setup command (idempotent), reading the license and the sign-in config file;
4. loading the sign-in config into the login hub;
5. the emergency admin (on-premises), off by default, server command, time-limited, audited;
6. the read-only Licensing status screen (replaces the licensing management part of U010);
7. shared parts go in the Enterprise Identity library, so LexaVault and SDV/Vero use the same code.

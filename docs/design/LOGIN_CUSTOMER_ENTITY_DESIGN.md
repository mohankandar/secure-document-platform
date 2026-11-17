# Login, customer and entity design for LexaVault and SDV

**Status:** Requirements and design agreed in discussion on 2026-11-16. Nothing here is implemented yet. The login hub is unproven until its proof of concept (section 13) passes.
**Based on:** `secure-document-platform` (branch `feature/lexavault-r1b-tenant-identity`), `secure-document-vault` (branch `release/vero-r7`), `enterprise-identity-parent` 0.2.0, Keycloak 26.8.
**See also:** `R1B-Gap-Analysis.md` for what the current code already delivers.

## 1. Goal

One product that can be installed on a customer's own servers or hosted by us, with the same code and the same updates.

- About half of customers are expected to run it on their own servers, because the documents are sensitive. The first implementation will be on-premises, for a customer who uses Okta.
- Customers sign in through their own login system (Okta, Entra, and so on). Customers without one get accounts from us.
- A customer can hold more than one **entity**, such as a company it acquired, and each entity's documents stay legally separate.
- A person can only ever reach the entities they are assigned to. Nobody gets in by naming a customer or an entity.

## 2. One product, two ways to run it

**An on-premises install is a hosted install with exactly one customer.** Same code, same screens, same entities, same redaction, same SDV. Only settings differ.

| Setting | On-premises | Hosted by us |
|---|---|---|
| Customers in the install | One | Many |
| Login system the product trusts | The customer's own; or the hub if they have none | The hub |
| Who installs and configures it | Our platform operations team, working with the customer | Our staff |
| Who operates it afterwards | The customer's IT staff | Our staff |
| AI | Local, on their servers | Local, on ours |

Rules that keep it one product:

1. **One codebase, one release.** A version is the same build wherever it runs. No hosted edition.
2. **Differences live only in settings and the licence.** Licences are verified offline, which suits customers with no internet access.
3. **Shipped as containers.** The same images run in our hosting and in a customer's data centre. An update is "run the schema step, pull the new version and restart" (section 6). Not built yet: today the product is built and started with scripts.
4. **The hub is an optional part of the same package.** Installed when a customer has no modern login system or wants password accounts. Our hosted service always runs it.
5. **We go first.** Every release runs on our hosted service before it goes to on-premises customers.
6. **Both setups are tested on every release:** one customer with direct login, and many customers through the hub.
7. **The demo laptop runs the on-premises package**, so a demo shows what a customer would install.

## 3. Customers and entities

| Level | What it is | Example |
|---|---|---|
| **Customer** | The company that buys the product | Customer 2 |
| **Entity** | A business inside the customer whose documents are kept apart | The title business and the contracts business |

**How it is built:** each entity is its own tenant ID in both products. A customer with two entities has two tenant IDs. The products already keep tenants fully apart, including separate encryption keys in SDV, so the legal separation uses isolation that exists and has been tested. The only new thing is a "customer" record that groups tenants.

```
Customer 2  (one login, one contract)
├── Entity A = tenant ID 1 -> own rules, own documents, own keys
└── Entity B = tenant ID 2 -> own rules, own documents, own keys
```

The customer level is built in from the first release, with one customer in it, so hosting later means adding customers, not restructuring.

"Source" in SDV keeps its current meaning: it records which system fed a document into an entity. It is not the legal wall.

Decisions:

| Topic | Decision |
|---|---|
| Viewing | One entity at a time. A person signs in, chooses an entity, and sees only that entity. No mixed view |
| Rules | Each entity has its own redaction rules, identifiers, retention and delivery destinations |
| Moving documents between entities | Never. The software offers no transfer. A customer who wants a document in both uploads it to both |
| Licensing | Per entity. Any multi-entity discount is a pricing matter outside the software |
| Adding an entity | Self-service by the customer administrator, up to the number licensed. More needs a new licence |
| Removing an entity | Always through the platform administrator, because it touches legal retention and encryption keys |
| Customer administrator | Creates entities and assigns access. Cannot see documents. Seeing documents takes a role inside that entity |
| Combined reports | Allowed, as numbers only. Run by a Report Viewer, covering only the entities where that person holds Report Viewer |
| On-premises | Entities are available there too |

## 4. How a login works

**On-premises, customer has a modern login system** (the first customer: Okta, connected directly, no hub)
1. The user opens LexaVault and is sent to the customer's own login system.
2. LexaVault checks that system's signature on the token.
3. LexaVault works out which entities the person's groups allow, and with which roles.
4. One entity: straight in. More than one: the person chooses, and can switch later. Switching clears everything from the previous entity.

**Through the hub (hosted, or on-premises without a modern login system)**
1. The user opens LexaVault at its single address and is sent to the hub.
2. The hub asks for their email to find their organization. People arriving from an Okta tile skip this.
3. The hub sends them to their customer's login system, or shows a password page if the hub holds their account.
4. The hub issues a signed token naming the organization and the person's groups.
5. LexaVault checks the hub's signature and finds the customer for that organization.
6. Then steps 3 and 4 above.

Either way the product trusts exactly one login system and speaks only the standard protocol (OIDC) to it.

## 5. Security rules

| Rule | Why |
|---|---|
| The product trusts one login system's signature | One thing to verify, verified well |
| The customer comes from the install's setting (on-premises) or the signed organization in the token (hub) | Never from a header, an address, an email or anything the browser sends |
| Each hub organization maps to exactly one customer, in a registry the product owns | The product does not take the hub's word for it |
| An entity is reachable only if it belongs to that customer and the person's groups allow it | Both conditions, every request |
| With more than one customer, a group name alone never grants access | Two customers may both have a group called "Supervisors" |
| Roles are set per entity | A supervisor in one entity is nothing in another |
| The chosen entity is a request, not proof | The backend rechecks it every time |
| No document moves between entities | The legal wall is absolute |
| A combined report covers only entities where the person holds Report Viewer | The report cannot expose another entity's numbers |
| A new entity starts empty and locked down | No users and default rules until configured |
| Platform access comes from an install-level setting, never from an entity's own mapping | On-premises that is the customer's IT staff; hosted it is our staff, signing in through their own space with mandatory multi-factor |
| Our installers' access to an on-premises install ends at handover | A customer should not carry standing vendor access |
| We never know a customer's password | Accounts we create get a one-time link to set their own. A temporary password with forced change is used only when a user has no email |
| We never need the customer's database address or passwords | The database belongs to the customer. Their administrator enters those settings; our scripts and documents never contain them |
| In a real install, the running product never creates tables and never inserts starting records | The schema is a separate, reviewed step. Customers and entities are created by an explicit setup command, not as a side effect of starting the server |
| Demo mode refuses to start against a real database | Demo records can never land in a customer's data |
| Every environment is set up by the same steps | What we practise every day is what a customer gets; a step that only exists for customers is a step nobody has tested |
| Customer IDs, entity IDs and licences are issued by us, and no tenant ID is ever used twice | IDs stay unique across all customers, so an install can move to hosting later |
| Local mode, which skips sign-in, is for development only and cannot be switched on in a packaged install | It trusts whatever role the browser claims, which would be dangerous at a customer |
| Anything unclear is refused | Fail closed, as the code already does |

## 6. On-premises installation

**Model: assisted installation.** Our platform operations team installs and configures the product together with the customer's IT and login-system administrators. The customer does not install it alone.

What we bring:
- **Install runbook:** servers, containers, database, AI model, licence, checks to run at the end.
- **Login-system setup guide:** what the customer's administrator creates on their side. For Okta: the application, the groups, and the settings that put a person's groups and the right audience into the token.
- **Group list:** the groups we need, by entity and role. Agreed with the customer before install day.
- **Role mapping file:** the group-to-role mapping for each entity, written down, reviewed with the customer, and loaded at install. Repeatable, and a record of what was agreed.
- **Handover checklist:** the customer's IT staff hold platform access, our installers' accounts are removed, and backups and updates are explained.

**The database is the customer's.**
- The schema is delivered as a separate step, in two forms from the same source: a command their database administrator runs, or a plain SQL script they can review first and run themselves.
- Two database accounts: one that may change the schema, used only for that step, and one with everyday rights, used by the running product.
- The running product checks at start that the schema is the version it expects, and refuses to start if not. It does not create or change tables.
- The customer's administrator enters the database address and passwords. We do not need to know them.
- Customers and entities are created by an explicit setup command run during the install. Nothing is inserted as a side effect of starting the server.

**Customer IDs, entity IDs and licences come from us.**
- We issue the customer ID, one tenant ID per entity, and a licence for each entity. A licence is already tied to a tenant ID in the code.
- The setup command takes those as its input and creates exactly the customer and entities they name. It invents no IDs of its own.
- Because the IDs are ours and unique across all our customers, an on-premises customer can later move to the hosted service without their IDs clashing with anyone else's.
- A customer administrator adding an entity later uses an entity ID and licence we have already issued to them.

**The same steps everywhere, from day one.** A developer's laptop, the demo laptop, an on-premises install and the hosted service are all set up the same way: the schema step, then the setup command with IDs and licences, then start. For speed, one script may run those same steps in order, but it runs the real steps, not different ones.

**What is fixed and what may be relaxed in development.**

| Fixed everywhere, no exceptions | May be relaxed on a developer's machine |
|---|---|
| The schema is a separate step | Sign-in: a local mode that skips the login system and the hub |
| Customers and entities come only from the setup command | |
| IDs and licences are issued by us; no tenant ID is ever shared between two entities, anywhere | |
| No starting records inserted by the server | |
| Entity separation and per-entity roles | |

Local mode exists so daily development does not need a login system running. Because developers skip sign-in day to day, the real sign-in path is run by the automated tests on every release, and local mode cannot be switched on in a packaged install.

**The install asks its questions once, in an answers file.** One file per install records the choices: database address, where the database and Vault credentials are read from, the login system's address and audience, the AI model, where documents are stored, and the customer and entity IDs. Passwords and tokens are referenced, never written in the file. The same file format is used on a developer's machine.

**The AI model is a choice made at install.** The customer can use the model the product offers by default, or supply their own. Only models we have tested are listed as supported; a customer's own model is put through the acceptance test before go-live, because accuracy depends on the model.

**Where the parts run is the customer's choice.** The UI, the backend, the AI and the database can each sit on separate servers, in their data centre or on their cloud account.

To confirm with each customer before install day: that their login system can include a person's groups in the token for the right audience. Depending on how their Okta is set up and licensed, this can need a feature they must enable.

**First customer:** on-premises, Okta, connected directly. No hub.

## 7. The login hub

Used for the hosted service, and on-premises only where a customer has no modern login system or needs password accounts. One hub serves both products.

**It provides** (Keycloak 26.8, already running locally as `lexa-keycloak`)
- Organizations, each linked to its own login system, including OIDC, SAML and LDAP or Active Directory.
- Email-first sign-in that routes a person to their organization.
- Organization details carried in the token.
- Accounts, set-your-own-password links, resets and multi-factor.
- A separate space for our own staff.

**Hub decisions**

| Decision | Choice |
|---|---|
| Hub on-premises | Optional. Not installed for a customer whose own login system the product can use directly |
| Customer addresses | One address; the hub asks for an email first |
| Our operations staff | A separate space in the hub, apart from customers, with mandatory multi-factor |
| Role mappings | We set them up at onboarding; the customer's IT administrator maintains them |
| Number of hubs | One hub for both products |
| Hub hosting | Run Keycloak ourselves for now, replaceable later because the products speak only the standard protocol |

Still to confirm in the hub proof of concept: the exact shape of the organization information in the token, and how groups appear in it.

## 8. Changes to LexaVault and SDV

**Where the code lives.** Login and entities are written once, in the Enterprise Identity library (`enterprise-identity-parent`), and used by both products. The library exists today with 11 small classes for token checking and login-system records, and both products already depend on it. Everything R1B added currently sits inside LexaVault only, so it is moved, not written twice.

| Goes into the shared library | Stays in each product |
|---|---|
| Checking the sign-in token | Its own roles and what each role may do |
| Customer and entity records, and the rule that ties them together | Its own screens |
| Working out which entities a person may enter | Its own data |
| Group-to-role mapping and the mapping file loader | |
| The entity chooser rules | |
| The core of the setup command, and the licence check | |

R1B is proven at runtime in LexaVault first, then moved into the library with its tests as the safety net, and new pieces are built in the library from then on.

**Needed for the first on-premises release**
1. **Customer record** above tenants, with one customer in it.
2. **Entity chooser.** Already built in R1B as the organization picker; relabelled.
3. **Role mapping load from a file.** Our installers load the reviewed mapping file. Today mappings can only be entered with SQL. A screen for the customer's IT administrator to maintain mappings follows.
4. **Platform access from an install-level setting.** With R1B as written, nobody can reach platform screens once the shared mode is on.
5. **Customer administrator role.** New; today every role sits inside a single entity.
6. **Entity creation within the licence.** A customer-level licence carrying a number of entities. Today a licence is tied to one tenant, which already gives per-entity licensing.
7. **Machine integrations tied to one entity.** Today the match is on a client name that is only unique inside one entity.
8. **Caching** on the shared-login path, which runs two database queries on every request.
9. **Container packaging**, with the install runbook and upgrade instructions.
10. **The same in SDV**, with shared pieces moved into the Enterprise Identity library.

Also needed for the first on-premises release, found when reviewing what the server does at start (2026-11-16):

- **A. A setup command that creates the customer and its entities** from the customer ID, entity IDs and licences we issue. Outside demo mode, nothing in the product creates a tenant today; the only code that does is the demo setup. Without this a real install needs hand-written SQL.
- **A2. The demo setup becomes input to the same command.** Today's built-in demo tenant, sample identifiers and scanner connection are replaced by a demo input file run through the setup command, with a development licence. Licensing is currently switched off in the local profile; it is switched on.
- **B. Schema as a separate step.** A switch to stop the server changing the schema at start, the run-it-yourself command, the reviewable SQL script, and the version check at start.
- **C. Login-system record created by the setup command.** Today the server inserts it at start from its settings when none exists.
- **D. Demo-mode safety check.** Demo mode refuses to start unless the database is marked as a demo database.
- **D2. Local mode locked out of packaged installs.** The packaged product refuses to start in the mode that skips sign-in.
- **D3. A register of issued IDs.** Our own record of every customer ID and tenant ID we have issued, so none is ever issued twice.
- **E. Reference records reviewed.** The schema scripts insert a small number of fixed reference records (for example the baseline AI profile). These stay in the reviewed script and are listed for the customer; none is customer data.

**Needed before hosting**
11. **Organization-to-customer registry**, and reading the organization from the token.
12. **Admission must require the customer first.** R1B admits on group name alone, which is safe with one customer and unsafe with several.
13. **Staff sign-in space** in the hub and the platform path for it.
14. **Authentication admin screen locked in hub mode**, since login systems are then configured in the hub.
15. **Role mapping screen** for customers to maintain their own mappings, if not already delivered.
16. **Combined reports.**
17. **Hub hardening and high availability.**

## 9. What carries over from R1B

| R1B piece | In this design |
|---|---|
| Several tenants behind one login system | Used. This is the on-premises multi-entity case exactly |
| Per-tenant group-to-role mapping (migration 036) | Used, as the per-entity mapping |
| Organization picker and tenant switching | Used, as the entity chooser |
| Migration 037 | Used. Tested on PostgreSQL 16 on 2026-11-16 |
| Machine integration binding | Used, tightened to one entity |
| Fail-closed checks and their tests | Used |
| Admission by group name alone | Fine on-premises; must change before hosting |

## 10. Risks and how to limit them

| Risk | How to limit it |
|---|---|
| Two ways to run the product drift apart | Both are tested on every release; differences only in settings |
| On-premises customers fall behind on updates | Support only recent versions, and say so in the contract |
| We cannot see into an on-premises install when something breaks | The existing support incident screens and logs; add an export a customer can send us |
| The customer's login system cannot send what the product needs | Confirm with their administrator before install day (section 6) |
| Installs depend on what one installer remembers | The runbook, the mapping file and the handover checklist |
| If the hub is down, nobody on it logs in | Two copies behind a load balancer, monitoring, tested backups |
| If the hub is compromised, every customer on it is exposed | Locked-down admin console, multi-factor for hub admins, patching, logging of admin changes |
| A setup mistake puts a user in the wrong customer or entity | Onboarding checklist, plus an automated test per customer and entity that a login from elsewhere is refused |
| A person working in two entities must switch | Accepted: this is the required behaviour |

## 11. Proof for the first on-premises release

On the local machine, with a stand-in for Okta running in Docker:

| Test customer | Login system | Entities |
|---|---|---|
| One customer | One stand-in login system | Two, kept separate |

It must show:
1. A person assigned to one entity cannot open the other.
2. A person assigned to both chooses one, sees only that one, and can switch.
3. The two entities run different redaction rules on the same kind of document.
4. A service account for one entity cannot push into the other.
5. The customer's IT staff reach platform screens; nobody else does.
6. The customer administrator can create an entity up to the licensed number, and cannot open documents.
7. The role mapping file loads, and loading it twice changes nothing.

A stand-in proves the connection type works. It will not show quirks specific to real Okta, so one run against a real Okta test account is worth doing before the first install.

## 12. Suggested order

**First: on-premises release**

| Phase | Work | Proof |
|---|---|---|
| 0 | Fix the test plugin so the 5 skipped LexaVault tests run | All tests execute and pass |
| 1 | Run R1B for real: one login system, two entities, isolated database | Points 1 to 3 of section 11 |
| 1b | Move the R1B login and entity code into the Enterprise Identity library (version 0.3.0); LexaVault uses it from there | The same tests and the same runtime proof pass unchanged |
| 2 | Role mapping file and its loader | Point 7 |
| 3 | Platform access from an install-level setting | Point 5 |
| 4 | Machine integrations tied to one entity; caching | Point 4 |
| 5 | Customer record, customer administrator, entity creation within the licence, and the setup command that creates the customer and entities | Point 6; a fresh database set up with no hand-written SQL |
| 5b | Schema as a separate step, the two database accounts, the version check, and the demo-mode safety check | The server starts with an everyday-rights account and refuses a wrong schema version; demo mode refuses a real database |
| 5c | Development and demo set up by the same steps: schema step, setup command with a demo input file and a development licence | A fresh developer machine reaches a working dashboard using only the standard steps |
| 6 | The same in SDV through the shared library | SDV passes the same proofs |
| 7 | Container packaging, install runbook, login-system setup guide, handover checklist | A clean install and an upgrade on a fresh machine, following only the runbook |

**Second: hosting**

| Phase | Work | Proof |
|---|---|---|
| 8 | Hub proof of concept (section 13) | Its eight points |
| 9 | Organization registry and the admission change | Same group name in two customers does not cross over |
| 10 | Staff sign-in space; role mapping screen | Platform screens reachable only that way |
| 11 | Combined reports | Numbers add up; only entities the viewer may see |
| 12 | Hub hardening and high availability | Failover and restore tests |

## 13. Hub proof of concept

Three test customers on the local Keycloak:

| Customer | Login system | Entities |
|---|---|---|
| Customer 1 | A stand-in for Okta (OIDC) | One |
| Customer 2 | A stand-in using SAML | Two, kept separate |
| Customer 3 | Accounts held in the hub | One |

It must show:
1. Each customer's people land only in their own entities.
2. A Customer 1 person is refused in Customer 2, including when both have a group with the same name.
3. In Customer 2, a person assigned to one entity cannot open the other.
4. A person assigned to both entities chooses one and can switch.
5. The two entities run different redaction rules.
6. A service account for one entity cannot push into another.
7. Email-first sign-in sends each person to the right login system.
8. A staff sign-in reaches platform screens, and no customer sign-in can.

If Keycloak's organization feature cannot do any of these cleanly, we revisit before building more.

## 14. Alternatives considered

- **Products trust many login systems directly**, as Salesforce does. Not chosen: far more security code in both products, and an identity service is still needed for customers without a login system.
- **Entities as a filter inside one tenant.** Not chosen: the separation is a legal requirement, and a filter depends on every query applying it.
- **Separate hosted and on-premises editions.** Not chosen: two products to build, test and update.
- **The hub in every on-premises install.** Not chosen: one more component for the customer to run and approve, with no benefit when their own login system can be used directly.
- **Customers install the product themselves.** Not chosen for now: our team installs with them. Self-install can follow once the runbook has been proven on several customers.
- **We set up every entity; full self-service; request and approve.** Not chosen in favour of self-service within the licensed number.
- **Customer-specific addresses** such as `customer.lexavault.com`. Deferred; can be added later without redesign.
- **A hosted identity service instead of Keycloak.** Deferred; to be revisited before the first paying hosted customer.
- **We set initial passwords.** Not chosen: with set-your-own links we never know a customer's password.

No decisions are open.

## 14a. How the work is done

Decided 2026-11-17: Claude writes the code.

- **One route.** Code changes are made with Claude Code on the development machine, where the code can be built, tested and run against the local database and services. Design, review and these documents stay in the Lexavault project chat.
- **One branch per piece of work**, taken from the current working branch, and merged only after review.
- **Nothing is committed that has not been built and tested.** Each change reports what was run and what passed.
- **The owner reviews and approves** each merge and makes the business decisions.
- **Each repository carries its own short instructions file**, pointing to this design, so every working session starts from the same rules.
- The working agreement inside the LexaVault repository still names a different tool as the code owner and is to be updated.

## 15. Backlog

Agreed on 2026-11-16: work for the first Okta customer now, and standardize installation afterwards. Notes and scripts written during the first install are kept, so the standard starts from what was done, not from a blank page.

**Installation standard** (so every installer follows the same steps, whatever the customer's login system)

| Item | What it is |
|---|---|
| Standard install runbook | One runbook for every customer, with the login system as a chapter, not the whole document |
| Setup guide per login system | Okta first; then Entra, SAML, and Active Directory or LDAP through the hub |
| Install scripts | Pre-install check of the customer's servers, install, post-install verification, upgrade, backup and restore |
| Naming standard | How groups, entities and environments are named, the same at every customer |
| Role mapping file template | A blank, documented template with one worked example |
| Handover checklist and sign-off | Including removal of our installers' access, signed by the customer |
| Installer checklist | What our team member must have and know before going on site |
| Support export | A package a customer can send us when something breaks |
| Version of the standard | The standard carries a version, and each install records which one it followed |

**Redaction coverage, accuracy and models** (decided 2026-11-17)

| Item | Decision |
|---|---|
| What gets redacted | The product is meant for use in any country. Version 1 covers the four kinds it has today (Social Security number, date of birth, credit card number, credit score) plus whatever is simple to add; more are added over time. The list of kinds must be expandable by region without redesign |
| Accuracy measurement | Belongs to the testing phase: a set of documents with known answers, and a count of misses. Manual review only sees what the AI flags, so a complete miss is caught only by measurement or by a "review every document" setting |
| Different AI models | Tested in the testing phase; results feed the supported-models list |
| Screen design | The latest (Case File) design ships. The older one is kept for comparison for now and removed in a separate iteration |
| Product name | Kept for now; made a setting so it can change without code changes |

**Deployment options a customer may ask for**

| Item | Today |
|---|---|
| UI on its own server | Works. The UI expects the backend at the same address, so a reverse proxy in front is the standard layout; document it |
| AI on separate servers | Works. The AI address is a setting, with a pool and a fallback |
| Database on its own server or a managed cloud database | Works. It is a setting |
| More than one backend server | Partly. The servers coordinate, but documents in progress are on local disk, so a shared disk is needed |
| Cloud object storage (such as S3) for documents in progress | Not built. Only local disk exists |
| SDV keys in a cloud key service | Not built. Only HashiCorp Vault exists |

Ask each customer which of these they need before promising them.

**Moving a customer from on-premises to hosting**

Not built, and not needed for the first customer. It is a different thing from moving documents between entities, which is never allowed: here a whole entity moves, unchanged, from one install to another.

| Item | Note |
|---|---|
| Export and import of one entity | Its database records, its stored documents and its audit history, moved as a whole |
| SDV encryption keys | The documents are encrypted with keys held in the customer's Vault. A move needs those keys carried across or the documents re-protected under new keys. Decide how before the first customer who might move goes live, because it affects how their keys are created |
| Login | The customer's login system is connected to the hub in place of the direct connection |
| Proof | The moved entity opens its old documents, and nothing from another customer is reachable |

**Parked from the development setup work on 2026-11-16**

| Item | Why it matters |
|---|---|
| Backups of both databases and a Vault snapshot, with one restore test | Two environments depend on one Vault, and no backup of it has been verified |
| Commit the SDV Windows script changes | `Start-SDV-Local.ps1` and `Stop-SDV-Local.ps1` are changed only in the working folder |
| Windows start and stop for everything, and a Vault unseal helper | After a reboot Vault is sealed and SDV will not start |
| LexaVault Windows script: rolling logs and storage outside the repository | The Ubuntu script has these; the Windows one does not |
| Restrict both Postgres containers to `127.0.0.1` | They currently accept connections from the network |
| Separate demo setup | Planned to become the on-premises container package |

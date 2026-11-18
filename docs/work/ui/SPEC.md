# UI polish spec (locked 2026-11-17)

Approved by the owner (Kr) on 2026-11-17 and locked: one review, one design. Changes to this spec need the owner's approval.

Decision: keep the Case File design (`frontend/securedoc-ui/src/studio/`) and polish it. Do not redesign. Goals: modern, user friendly, no learning curve, easy to manage.

## Vocabulary (one name everywhere)

| Today | Use |
|---|---|
| Desk (nav, page title) | Cases |
| Intake (nav) | New case |
| In motion (Desk group) | In progress |
| Intake (stage) | New |
| contract, package, pkg-… shown to people | case, and the case's primary identifier (for example the loan number) |
| "vault" on New case | (remove; it is confused with SDV) |
| SSN / Ssn / Us SSN | Social Security Number |
| Date Of Birth | Date of birth |
| Credit Card Number | Credit card number |
| Credit Score | Credit score |
| unplaced | place not found |
| Reprocess retained original | Process again |
| Retention (case panel section) | Keeping and deleting |
| Identity and source | Case details |
| Technical attention / Needs attention (stage) | Stopped |
| Median, p95, straight-through | Typical, slowest 5%, no review needed |
| Master delivery / Derived delivery / Intake sources | Sending to SDV / Sending by barcode / Receiving |
| Temporary processing infrastructure | Processing storage |
| Product telemetry | Usage data |
| Technical retry | Retry |
| Last known good | Fallback if something goes wrong |
| Disabled / Observe / Enabled (AI mode) | Off / Observe / On |

Field labels always come from the configured case fields (for example "Loan number", "Borrower name"), never hard-coded.

Codes, IDs and internal values (reason codes, correlation IDs, tenant IDs, versions, node names) appear only under a collapsed **Technical details** section.

## Stage colours (add to `tokens.css`, light and dark)

| Stage | Light | Dark |
|---|---|---|
| New | #8a919b | #8a919b |
| Redacting | #3b6fd4 | #6b9bf2 |
| Review | #e0b400 | #f4d03f |
| Approval | #7a52c7 | #a888e6 |
| Delivered | #1d7a4e | #6fcf97 |
| Stopped | #c43d30 | #f0786b |

Soft backgrounds for chips: light #eef0f2, #e7eefb, #fbf3cc, #efe9f8, #e3f1ea, #f8e5e3; dark #23262b, #1a2640, #3a3214, #2a2140, #16301f, #3a1d1a. The yellow highlight (`--cf-mark`) stays reserved for "needs you".

## Who sees what (from ProductAuthorizationPolicy.java)

| What | Processor | Supervisor | Business Admin | Report Viewer | IT Admin | Platform Ops |
|---|---|---|---|---|---|---|
| Cases, New case, Review | ● | ● | | | | |
| Continue a stopped case manually | ● | ● | | | | |
| Approve delivery, Process again | | ● | | | | |
| Retry processing | | | | | ● | |
| Reports: headline, trend, service levels, by entity | | | ● | ● | | |
| Reports: pipeline, backlog, retention, timing, intake, breakdowns | | | ● | | | |
| Reports: Export CSV | | | ● | | | |
| Admin › Redaction & retention, Case fields | | | ● | | | |
| Admin › Integrations, Sign-in | | | | | ● | |
| Admin › Platform operations: health overview | | | | | ● | ● |
| Admin › Platform operations: run checks, storage, licensing, branding, usage data, technical details | | | | | | ● |
| Admin › Audit trail | | | | | ● | ● |
| Support | | | | | ● | ● |

## Findings and fixes

| # | Screen | Fix | Task |
|---|---|---|---|
| 1 | Top bar | One account menu: person, organization, entity switch, theme, local test role (local mode only), sign out | U002 |
| 2 | Cases rows | Plain next-step words instead of the small bars | U003 |
| 3 | Cases rows | One action button per row (Review, Approve, Continue manually / Retry by role) | U003 |
| 4 | Everywhere | Vocabulary table above | U001 and each task |
| 5 | Stages | One colour per stage, table above | U001, U003 |
| 6 | Review | Show the text found with a line of context; zoom (−, %, +, Fit) | U004 |
| 7 | Review | "Redact all like this": same type, same case, each finding audited separately; no "Keep visible" equivalent | U004 |
| 8, 22 | Every screen | Unexpected data never blanks the app: the failing card shows a message, Try again and a reference; top bar and other screens keep working | U001 |
| 9, 10, 30 | Continue manually, Support list, problem detail | Case File look, plain reasons, readable in dark; one problem page replaces Incident Details and Technical Issue Details | U012 |
| 11, 12 | Admin › Document intelligence | Radio left of text; version in plain words; codes under Technical details | U007 |
| 13, 23 | Admin › Report targets, Reports | New review-pickup target % (default 80%); durations in days; "Keep stopped cases for" | U006, U007 |
| 14 | Reports | Entity filter (only with more than one entity) and "By entity" table | U006 |
| 15 | Admin › Sign-in | Development decides if the tab stays with the login hub; if it stays: plain labels, IT Administrator only | U009 |
| 16, 24, 25 | Admin › Integrations, connection editor | Plain tab names and status lines, folder status once, loan numbers not package IDs, Advanced section, Test before Save | U008 |
| 17 | Admin › Case fields | Normal-case names, aligned headings, rules spelled out | U007 |
| 18 | Review, Policy, search | One name per sensitive type; configured field labels | U001 |
| 19 | Case panel | Plain next steps for stopped, delivered, rejected; "Process again" with runs left; rejection reason; "Start a new case for this loan" | U003 |
| 20 | New case | File rules up front, per-file problems, friendly scanner name, "Create case" | U005 |
| 21 | No-access page | Plain message, Sign out, Try again | U001 |
| 26, 27, 28 | Platform operations | Processing storage, Licensing per entity, Branding with one form, Usage data | U010 |
| 29 | Audit trail | Sentence rows with person and loan number; everyday filters up front | U011 |
| 31 | 1024 px screens | Fixed by the account menu; search becomes an icon below 1100 px (use the nearest documented breakpoint) | U002 |
| 32 | Reports chart | Left axis and values on hover | U006 |

Product note (not UI): detection supports four sensitive types today (SSN, date of birth, card number, credit score).

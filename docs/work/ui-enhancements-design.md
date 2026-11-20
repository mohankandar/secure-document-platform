# UI enhancements: design (all eight)

Owner: Kr. Written 2026-11-19 in the UI design chat. **Status: design complete and approved by the owner (2026-11-19).** Construction starts in a new chat from the build plan at the end of this doc.

> Repository copy of the Lexavault project doc `claude/UI-Enhancements-Design.md`, copied 2026-11-19. The project doc is the source; if they differ, the project doc wins.

## Final status

| # | Item | Decision | Canvas |
|---|---|---|---|
| 6 | Compliance report | **Build** | https://claude.ai/artifact/WX5CG7jfDhUaFqYgvLLcfj |
| 1 | Safe policy: presets and preview | **Build** | https://claude.ai/artifact/4x5YzFLB2Mqxq2thDZnbum |
| 2 | Notifications | **Build** | https://claude.ai/artifact/EoKhNoGHhFQfspEKDa8dwV |
| 3 | What LexaVault did for you (time saved) | **Build** | https://claude.ai/artifact/1KWPuxuff66xbVM7uGgZVs |
| 8 | Procurement checklist | **Build** (mostly writing) | none |
| 5 | Bulk approve | **Backlog** (design kept below) | none |
| 7 | Suite look | **Changed, backlog, low priority:** shared foundation only, own colours, no switcher | none |
| 4 | Before/after | **Not doing**: no page shows unredacted content | none |

Source list: `claude/UI-Enhancement-Ideas.md`. Owner decisions in this chat:
- All eight items were kept at first; #5 later moved to the backlog (below).
- Selling points: the compliance report leads; notifications and time saved are core for operations managers.
- **Approach A:** this written overview first. Then a detailed canvas board per item, in the order below. Only after that, `UNNN` task files.
- Backend parts are planned with Phase 5 and the install work, not before (as the ideas doc says).
- **Notifications:** email, Teams and Slack are all supported, and the customer chooses which to switch on (details in #2).
- **Redacted values are never saved by the application** (rule below).
- **Product goal (owner, 2026-11-19):** everything automated, with manual review kept small, so someone doing another job can monitor LexaVault without hesitation. Every item is judged against this.
- **#5 Bulk approve: dropped from this round, moved to the backlog (owner, 2026-11-19).** Approval before delivery is off by default and the goal is no manual steps. Kept design for later: a customer setting, off by default; only cases a different person already reviewed can be ticked; 25 per batch; each case audited separately and marked as a group approval. The four-eyes rule (approver ≠ reviewer) also goes to the backlog, since it only matters when approval is on.

## Rule: no redacted value is ever saved by the application (owner, 2026-11-19)

- **While the original exists**, the people allowed to see it may see it:
  - the reviewer in Review (the full found text, read live from the document; this exists today);
  - the customer's own IT Administrator, under the signed agreement, when fixing a problem. This changes no screen: the app's U012 rule that IT and Platform roles don't see case content stays.
- **LexaVault never saves the value on its own.** Not one digit, not one letter, in the database, logs, audit records, reports, PDFs, messages or browser storage.
  - **Change needed:** the `findings.snippet` column today saves a masked value, for example `***-**-6789`. It stops saving anything. Anything that used it, such as the Review queue's last 4, reads live from the original while the original exists.
- **Once the original is deleted**, nothing in LexaVault knows what was under a redaction. SDV/Vero only ever receives the redacted copy.
- **Hosted service:** if a customer asks Kr's team for a redacted value, the answer is "we don't have it"; they go to their own original document.
- Confirmed in the code: the extracted-text and OCR files are deleted together with the original.
- An automated test proves it: no full or partial redacted value in the database, logs or audit records.

## Why

Make LexaVault easy to buy and easy to live with.
- **Buyers** (legal, compliance, operations, procurement) see proof: an audit-ready report, time saved, exact redactions.
- **Daily users** are told what needs them and can act fast.

## Goals, non-goals, anti-goals

- **Goals:** each item demos in under a minute; each one is useful every day, not just in demos.
- **Non-goals:** new roles; changing the locked UI spec of existing screens beyond what each item adds; new AI features (those are on the AI enhancement list).
- **Anti-goals (a failure even if it works):**
  - Sensitive values, borrower names or file names leak into a message, a PDF or a log.
  - A role sees what it can't see today. The original document is the main risk.
  - A preset quietly weakens redaction.
  - A "time saved" number that a buyer can't check, or that overclaims.

## Constraints (from the code on `feature/lexavault-r1b-tenant-identity` @ `72ded83`)

- Every finding is stored with type, confidence, page and date. Previews, reports and messages use only those, never the found text.
- Business Administrator policy changes are already written to the audit trail. The audit trail already has an export endpoint (`/api/v1/audit-records/export`).
- PDFBox is already a dependency, so a PDF can be made on the server without a new library.
- **No email, Teams or Slack sending exists yet.** Notifications need new backend work and config entries.
- Approval is one case at a time (`ApproveFinalDeliveryUseCase`).
- **Originals are deleted after "Keep originals for" (hours).** Before/after can only show while the original still exists.
- Entities stay separate: every new screen, message and report is per entity. A combined view only adds up numbers, as in Reports.
- Same look as the locked spec: IBM Plex, Case File styles, tablet rules from U021.

## Order

The order to build is in the **Build plan** at the end of this doc.

## The demo story

Set policy safely (#1) → LexaVault handles most cases with no person, and tells the monitor only what needs them (#2) → the buyer sees the value: handled without a person, time saved (#3) → the auditor gets one PDF (#6). The procurement pack (#8) answers the buyer's security and accessibility questions.

---

## 1 · #6 Compliance report

**Detailed design: approved by the owner 2026-11-19.** Canvas: https://claude.ai/artifact/WX5CG7jfDhUaFqYgvLLcfj
- **Business Administrator:** creates it from Reports. Summary edition: policy and changes, keeping and deleting with proof, cases and review, audit activity as counts. No full audit list.
- **IT Administrator:** creates it from Admin › Audit trail and may tick "Include full audit trail" (an appendix, cases by case ID only).
- One entity per PDF. Periods: last month, last quarter, last year, custom.
- A fingerprint (SHA-256) on the cover and every page footer, also saved in the audit record that "compliance report created" writes.
- Confirmed in code: the original, extracted text and OCR text are deleted together at delivery + "Keep originals for".
- Backend gaps: policy changes must save the sensitive type names (today only the count); a new audit action for report creation; check that audit detail text holds no sensitive data before it goes in the appendix.

The overview sketch below is kept for history; the canvas supersedes it.

**What they see.** A new **"Compliance report"** button in Reports, plus a short form:

```
Compliance report — Residential lending
Period   [ Last quarter ▾ ]   Oct 1 – Dec 31, 2026
Include  ☑ Redaction policy and its changes
         ☑ Keeping and deleting settings
         ☑ Audit trail summary (who did what, counts)
         ☐ Full audit trail (every record)
                                   [ Create PDF ]
```

**The PDF:**
- a cover page: customer, entity, period, created by, created at;
- the policy as it was on each day, with every change: who, when, old → new;
- keeping-and-deleting settings, and proof that deletions ran (counts only);
- a statement that LexaVault keeps no redacted values (the rule above);
- case counts by outcome;
- audit summary, with the full audit trail optional;
- a page number and a "created by LexaVault" line on every page.

**Who.** Business Administrator. My call; whether an auditor or IT gets it is decided in the detailed design.

**Why it sells.** Legal and compliance buyers can hand it straight to an auditor.

**Backend.**
- Build the PDF on the server with PDFBox.
- History of policy settings, from the existing audit records.
- Counts of deletions that ran.
- Audit actor display names (already on the backend list).

**Never.** Borrower names, file names or found text.

## 2 · #1 Safe policy: presets and preview

**Detailed design: approved by the owner 2026-11-19.** Canvas: https://claude.ai/artifact/4x5YzFLB2Mqxq2thDZnbum
- **Presets set only the review mode and the threshold.** Sensitive types and the labeled-last-four-SSN switch stay manual, so no preset can weaken what is redacted.
- **Strict:** every case goes to a person. **Balanced:** only uncertain findings, auto-redact at 80% or higher (today's default; existing customers start here). **Light:** only uncertain findings, 70% or higher. A change by hand shows **Custom**.
- Below the threshold a finding always goes to a person; nothing is skipped.
- **Preview:** before/after counts of cases and findings a person would have checked, over the last 30 days for the entity. Under 20 cases: "Not enough recent cases to estimate".
- **Confirm step** when fewer findings would go to a person ("Keep Balanced" is the main button).
- The preset name goes in the policy-change audit record, so the compliance report shows "Balanced → Light".
- **Where confidence comes from (code):** pattern rules give fixed scores (SSN and card 95%, date of birth 90%, credit score 85–90%); the AI model gives its own; +5% when both agree; lowered when the position on the page is unsure.
- **Confirmed:** finding records (type, confidence, page, date, never the value) are kept after the original is deleted, so 30 days of history exist.
- **Security test addition:** the AI's answer holds the found value; it must never be logged or saved.

The overview sketch below is kept for history; the canvas supersedes it.

**What they see.** On Admin › Redaction & retention › Sensitive data:

```
How careful should LexaVault be?
( ) Strict     Every case goes to a person
(•) Balanced   Auto-redact at 85% confidence or higher
( ) Light      Auto-redact at 70% or higher
    Custom     appears when the numbers are changed by hand

If you save this — based on the last 30 days in Residential lending:
  ▲ about 120 more findings would have gone to review (from 340 to 460)
  ▲ about 18 more cases would have needed a person
                              [ Reset ]  [ Save ]
```

**What a preset sets.** The review mode, the confidence threshold and the labeled-last-four-SSN switch. Keeping-and-deleting times and report targets stay manual, because the customer's legal team often fixes them. *My call; confirmed in the detailed design.*

**Who.** Business Administrator.

**Why it sells.** It turns a raw slider into a decision you can explain. Weakening redaction always shows its effect first.

**Backend.** One read-only estimate endpoint. It counts findings and cases in the last 30 days, for the entity, below and above a given threshold. It returns counts only.

**Never.** Found text in the estimate. No preset may turn off a sensitive type.

## 3 · #2 Notifications: "3 cases need you"

**Detailed design: approved by the owner 2026-11-19.** Canvas: https://claude.ai/artifact/EoKhNoGHhFQfspEKDa8dwV
- **Never in a message:** loan numbers, borrower names, file names, any document content or redacted value. Counts and a link only.
- **Who is told:** Processor (findings to review, stopped cases); Supervisor (cases to approve, plus the Processor items); IT Administrator (problems waiting in Support); Business Administrator and Report Viewer nothing for now.
- **Off until each person turns it on.** The Business Administrator sets the entity default in a new Admin › Notifications tab; each person adjusts their own under account menu › My notifications.
- **Choices:** as it happens (grouped, at most every 15 minutes), every hour, every 4 hours (suggested default), twice a day, once a day.
- **Switches:** work hours only (default 8 AM–6 PM, each person's time zone) and weekdays only, both on by default; urgent nudge, on by default for Supervisors at 4 hours (2/4/8/24); pause for 1 day, 1 week, or until turned back on.
- **Limits that stop flooding:** one message lists everything waiting; nothing waiting means no message; at most one message every 15 minutes.
- **Top bar:** a "3 need you" count that links to Cases.
- **Teams and Slack:** one post per entity channel with the same schedule choices; webhook addresses and the mail server live in the config file, set by IT (they work like passwords). Admin shows them read-only.
- Email addresses come from the sign-in system (the /me email item).

The overview sketch below is kept for history; the canvas supersedes it.

**What they see.**
- **In the app:** a small count in the top bar ("3 need you").
- **By email, Teams or Slack:** a digest.

```
LexaVault — Residential lending
3 cases need you: 2 to review, 1 to approve.
Oldest has waited 5 h.        [ Open LexaVault ]
```

- **In the account menu:** "Notifications": on/off, how often (as it happens, hourly, daily) and the channel, from the ones the customer switched on.

**Channels (owner approved).** All three are supported; the customer chooses which to switch on.

| Channel | How it works | Order |
|---|---|---|
| Email | Each person gets their own digest through the customer's mail server | First |
| Teams | A post with counts to each entity's team channel (webhook) | Next |
| Slack | The same, to a Slack channel (webhook) | Next |
| Personal Teams/Slack messages | A bot messages each person; the customer's IT registers an app | Later |

**Who.** Processors and Supervisors, per entity. Each person opts in.

**Why it sells.** People don't watch the Cases page. This is the biggest gain in daily ease of use.

**Backend.**
- Sending email (SMTP), Teams and Slack (webhooks). None exists yet.
- The customer's mail, Teams and Slack settings go in the config file. My call: no setup screen, matching the install approach.
- A saved preference per person.
- A scheduled digest job.

**Never.** Borrower names or found text in a message: only counts and a link. Channel posts are safe for the same reason. *Whether the loan number may appear is decided in the detailed design.*

## 4 · #5 Bulk approve — moved to the backlog (see the decisions at the top)

**What they see.** On Cases, in the Approval stage: checkboxes on each row, then one confirmation.

```
Approve 4 cases for delivery?
  LN204811 · LN204822 · LN204830 · LN204857
Each is delivered and recorded separately.
                         [ Cancel ]  [ Approve 4 ]
```

After approving, a result line names any case that failed and why, in plain words.

**Who.** Supervisor. Tablet-friendly, matching U021.

**Why it sells.** It saves supervisors time.

**Backend.** Approval stays one case at a time underneath: each case gets its own audit record, and one failure doesn't block the others. My call: a cap per batch (for example 25), settled in the detailed design.

**Never.** A case that isn't in the Approval stage can't be selected.

## 5 · #3 "What LexaVault did for you" (Reports)

**Detailed design: approved by the owner 2026-11-19.** Canvas: https://claude.ai/artifact/1KWPuxuff66xbVM7uGgZVs
- **Four tiles** in a "What LexaVault did for you" section at the top of Reports: **Handled without a person** (first, it proves the product goal), Pages checked, Sensitive items redacted, Review time saved (estimate).
- **Formula:** pages in cases that needed no person × minutes per page, rounded down. Pages a person reviewed count as zero.
- **Default 1 minute per page**, deliberately low. The Business Administrator changes it in the Report targets card ("A person takes … minutes to check one page by hand", 0.5–30); the change is audited.
- **"How is this worked out?"** shows the calculation with the real numbers and says it is an estimate.
- Seen by Business Administrator and Report Viewer, like the existing headline. Also printed in the compliance report.

The overview sketch below is kept for history; the canvas supersedes it.

**What they see.** A tile row at the top of Reports:

```
Pages checked   Sensitive items protected   Review time saved (est.)
14,220          3,912                       ≈ 96 h   How is this worked out?
```

"How is this worked out?" opens the plain formula. For example: pages that needed no person × the minutes a person would take per page (a conservative default, which the Business Administrator can set).

**Who.** Business Administrator and Report Viewer, like the existing headline.

**Why it sells.** Buyers sign off on time and risk saved.

**Backend.** Counts that Reports mostly has already, plus one setting for minutes per page. It is also printed in the compliance report (#6).

**Never.** A number without its formula. Always round down.

## 6 · #4 Before/after view — NOT DOING (owner, 2026-11-19)

The owner does not want unredacted content shown on any page. No before/after view and no spot-check page. Review keeps showing the reviewer the found text, as today; that is not a new page. The sketch below is kept only as a record of what was rejected.

**What they see.** In Review and the case panel, a "Compare" switch. The original and the redacted page are shown side by side, with the same zoom and scroll on both.

**Who.** Only roles allowed to see the original today (Processor and Supervisor). Never Business Administrator, IT or Platform roles in the app (the U012 rule stands).

**Why it sells.** It builds trust in seconds.

**Backend.** Serve the original page image to those roles only.
- Once "Keep originals for" has passed, the switch reads "Original deleted on <date>", and only the redacted copy shows.
- Opening the original is written to the audit trail (my call).

**Never.** Caching the original in browser storage.

## 7 · #8 Procurement checklist

**Approved by the owner 2026-11-19, as below:**
- A WCAG 2.1 AA check of the main screens, fixes, then a short accessibility statement.
- "?" help text on Admin settings screens only.
- "Security at a glance": a 2-page PDF kept with each release (a document, not a screen). It includes the rule that no redacted value is kept, so it is written after that backend change lands.

**What they get.**
- **An accessibility statement (WCAG 2.1 AA)**, after an accessibility review of the main screens.
- **Short "?" help text** on settings screens.
- **A printable "Security at a glance"** (PDF): where data lives, who sees what (the role table), what is deleted and when, that no redacted value is kept, sign-in, encryption.

**Who.** It goes to the buyer's purchasing and security team. The help text is for every role.

**Backend.** Almost none. Mostly writing, plus an accessibility check.

## 8 · #7 One suite look (LexaVault + Vero) — CHANGED, backlog, low priority (owner, 2026-11-19)

**Decision:** LexaVault and Vero are separate products for different people (a 1–2 person back-office redaction team vs a 10,000+ call-center team) with separate access. So: **no product switcher, no shared app, no shared colours.** LexaVault stays yellow and black, Vero green and white. They share only the foundation (IBM Plex, spacing, button and form behaviour, top-bar and account-menu layout) so one fix helps both. Engineering work, done when Vero's UI is next touched. The sketch below is the original idea, now superseded.

**What they see.** The same top bar, colours, fonts and account menu in both products. A product switcher appears when the person has both.

**Who.** Everyone who uses both products.

**Backend.** The shared sign-in hub already supports it. The switcher needs to know which products the person is licensed for (Phase 5 licensing).

**Coordinate** with the SDV/Vero UI work. Vero already follows the LexaVault approach, so this is mostly a shared top bar and shared design tokens.

---

## Settled later, in each item's detailed design

- #6: settled (see its section).
- #1: settled (see its section).
- #2: settled (see its section).
- #5: moved to the backlog.
- #3: settled (see its section).
- #4: not doing.
- #7, #8: settled (see their sections).

## Build plan (for the construction chat)

Rules: the work order and lean loop in `claude/Work-Order.md` apply. Backend tasks are `NNNN` (development chat), UI tasks `UNNN` (UI chat), one per run. **Where these sit relative to the Phase 2 merge, Phase 5 licensing and the install work is the development chat's call**, as the ideas doc says; the order below is the order among the enhancements. Every UI task matches its canvas exactly.

| Step | Task | What | Needs |
|---|---|---|---|
| 1 | Backend: **no redacted values saved** | `findings.snippet` stops saving anything; a migration clears existing values; the Review queue's last 4 reads live from the original while it exists; the security test covers database, logs, audit records and the AI's answer, for full and partial values | none; do first, it is a safety fix and the compliance report states it |
| 2 | Backend: **audit detail** | Actor display name (already listed); policy changes save sensitive type names and the preset name; new audit action "compliance report created" with the fingerprint | step 1 |
| 3 | Backend: **compliance report PDF** | PDFBox; per entity; periods; summary edition for Business Administrator, full-audit-trail appendix for IT Administrator (case IDs only); fingerprint; deletion proof from purge records; check audit detail text holds no sensitive data | step 2 |
| 4 | UI **U022 Compliance report** | Dialog in Reports (Business Administrator) and Admin › Audit trail (IT Administrator) | step 3 |
| 5 | Backend: **policy estimate** | Read-only 30-day estimate per entity, counts only; "not enough data" under 20 cases | step 2 |
| 6 | UI **U023 Safe policy** | Presets, preview, Fine-tune, confirm when fewer findings go to a person | step 5 |
| 7 | Backend: **time saved** | Pages in cases that needed no person, per entity and period; minutes-per-page setting (default 1, 0.5–30, audited) | none |
| 8 | UI **U024 What LexaVault did for you** | Four tiles, "How is this worked out?", Report targets field; numbers also in the compliance report | step 7 |
| 9 | Backend: **notifications** | Email on /me (from the sign-in system); person preferences and entity default; SMTP, Teams and Slack senders; mail server and webhooks in the config file; scheduled digest with the anti-flood limits; "need you" count | none (larger task, may split) |
| 10 | UI **U025 Notifications** | Top-bar count, My notifications, Admin › Notifications tab | step 9 |
| 11 | UI **U026 Procurement** | WCAG 2.1 AA check and fixes on main screens; "?" help text on Admin settings | none |
| 12 | Docs | Accessibility statement; "Security at a glance" 2-page PDF | steps 1 and 11 |

Backlog: #5 Bulk approve (with the four-eyes rule); #7 shared foundation with Vero.

## Next step

Design complete. Open a new chat for construction: "Read `claude/Work-Order.md` and `claude/UI-Enhancements-Design.md`, then plan the first task from the build plan."

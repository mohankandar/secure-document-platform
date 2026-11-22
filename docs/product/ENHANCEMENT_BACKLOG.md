# LexaVault enhancement backlog

Started: 2026-11-17. Owner: Kr. Kept in two places that must stay in step: this file in the repository (`docs/product/ENHANCEMENT_BACKLOG.md`) and the copy in the claude.ai project.

This list holds what comes after, or alongside, the on-premises release plan in `docs/design/LOGIN_CUSTOMER_ENTITY_DESIGN.md` (section 12). Nothing here is started until the owner says so.

## A. Owner's work items (agreed 2026-11-17)

| # | Item | Placed | Note |
|---|---|---|---|
| A1 | UI review and improvement of the newest (Case File) design | Straight after the entity switch | Review every screen first, then decide: polish, or redesign in Figma before any code |
| A2 | Wider redaction coverage (Europe and other regions) | After Phase 4 | Needs sample documents per region |
| A3 | LexaVault to SDV integration: redacted output stored in SDV | After Phase 6 | Both products must share the entity model first |
| A4 | Full testing of every intake path in both products (watched folder, upload, scanner, API and the rest) | Before Phase 7 | Each product tested alone, then together |

## B. AI enhancements (owner approved the whole list 2026-11-17; these are the selling points)

Order is the suggested value order. The final order is set after accuracy has been measured on real sample documents.

| # | Enhancement | What it does | Why it matters |
|---|---|---|---|
| B1 | Recognize the document type | Tells a loan application from a deed from a W-2 and applies the right rules to each | Better accuracy, less setup per customer |
| B2 | Confidence-based review | Sure findings pass straight through; only doubtful ones go to a person | Cuts manual review, the customer's largest cost |
| B3 | Independent checking pass | After redaction, a second pass re-reads the finished file and looks for anything that slipped through | Catches leaks before a document leaves |
| B4 | Learn from corrections | When a reviewer fixes a miss, the system proposes a rule change for that entity; an admin approves it | The product improves with use |
| B5 | Plain-language rules | An admin describes a rule in words; the system drafts it for approval | Setup without the vendor's help |
| B6 | Handwriting, signatures and ID photos | A model that understands images, not only text | Mortgage files are full of these; the text reader is weak on them |

## Rules for everything in section B

- The AI model stays local. Customer documents never leave the customer's environment.
- No AI action changes or releases a document without a rule, a person, or both behind it, and every action is in the audit trail.
- Entities stay separate: nothing learned from one entity's documents is applied to another.
- Accuracy is measured before and after each enhancement, on the same sample set.

## Before any of section B

Measure today's accuracy on real sample documents (the testing phase). The misses decide which enhancement is built first.

## C. UI enhancement backlog (from `docs/work/ui-enhancements-design.md`)

| # | Item | Note |
|---|---|---|
| C1 | Bulk approve, with the four-eyes rule | Design kept in `docs/work/ui-enhancements-design.md` #5. Only matters when approval before delivery is on. |
| C2 | Shared foundation with Vero | Fonts, spacing, button and form behaviour, top bar; own colours, no switcher. Done when Vero's UI is next touched. |
| C3 | Strict also checks cases with nothing found (owner, 2026-11-20) | Today, under "Every case" (Strict), a case where nothing is found is delivered without a person looking (confirmed in task 0019). An option for customers who want every case checked. Changes frozen V1 processing, so it needs its own task and the owner's go-ahead. |
| C4 | Draw a redaction box by keyboard, for WCAG 2.1.1 in Review and Continue manually (owner, 2026-11-20, U027) | Arrow keys move and size the box. Until then, marking a region by hand (a finding whose place was not found, and Continue manually) needs a pointer: the documented exception in the accessibility conformance notes (`docs/work/ui/U027-report.md`). |

## D. Clean-up after the platform switch-over

| # | Item | Note |
|---|---|---|
| D1 | Drop the old identity tables `enterprise_identity_connections` (+ `_history`) and `tenant_oidc_group_grants` | Task 0037 moved every sign-in read to platform-identity's `idn_` tables and added the one-off `identity-migration` command. The old tables stay for one release so an existing install can still be migrated; drop them in a later release with a new Liquibase changeset, after the R1 connection admin screen has moved to `idn_` (report 0037, questions). Needs the owner's go-ahead (schema change). |

## Not yet decided

- Whether to survey competing redaction products before committing to the order.
- Which enhancements go into the first on-premises release and which into the release after it.

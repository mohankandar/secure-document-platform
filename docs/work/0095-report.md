# Report 0095: user and administrator guides checked against the shipped screens

Date: 2026-11-25
Branch: `feature/0095-guides-checked` (from 466906b1, PR #36; 05d7eb1e, PR #37, merged in)
Outcome: **DONE, with 4 local test timeouts** (below; they also fail without this change). Built by a subagent from a written brief. I compared the role table with the policy code, read the business guide, What's new and the install changes, confirmed three of the bugs and the redaction defaults in the code, looked at all 21 pictures and ran the first table's commands. I did not re-check the other guides line by line.

## What was done
Guides corrected role by role, the new features added, one "What's new" page, 21 pictures from 466906b1 (mocked answers, blank pages, no found value, no person's name). Six in-app help texts corrected (`helpTexts.ts`); no other product code. Nothing deleted. **No other product's name or behaviour in code.**

**Against what:** rights, defaults and install facts against the code; pictures and "?" help against mocked browser screens. **The real app with a real sign-in was not used.**

| Guide | Checked against | Changes |
|---|---|---|
| business guide | `ProductAuthorizationPolicy`, Desk, CaseDrawer, Intake, Review, RejectCase, BulkApprove, ApprovalLink, EntityMenu, ManualCase, Reports, Admin areas, RedactionCatalog, RegulationPack | Rewritten; roles were wrong; 0058, 0059, 0061, 0062, 0071 to 0074, 0076, 0078, 0081 to 0083 added |
| tenant-it guide | the policy, IntegrationsArea, SourceBulk, IdentityArea, NotificationsArea, QueueSection, Support, AuditArea, application.yml | Rewritten; Bulk, priority, queue, sign-in drafts, what IT can't do |
| platform-operations guide | the policy, HealthArea, StorageSection, CapacitySection, SupportIncidentController, logback-json.xml | Rewritten; reads problems, no actions |
| training README, CHANGELOG, GOVERNANCE | links, App.tsx | README rewritten; changelog entry; picture rule |
| `WHATS_NEW.md` | all of the above | new |
| in-app help | RedactionCatalog, PackageLifecycle, TenantLifecyclePolicy, PolicyArea | 6 texts corrected; the others kept |
| error code guide | ErrorCodes.tsx, guideRules.ts | correct as it was |
| install: OFFBOARDING, MONITORING, SETUP, CASE_FIELDS, NOTIFICATIONS, LICENSING, backup-and-restore | 0093, SCHEMA_STEP, the Admin screens | exact names; LV-SYS-014 after the keys are destroyed; database user |
| install: ERASE_LOG, KEY_SERVICE, SCHEMA_STEP, IDENTITY_MIGRATION, sizing-guide; BUILD and ci (screen words only) | statusRules, IdentityArea, QueueSection | correct as they were |
| product: AUTHORIZATION, REPORTING, WORKFLOW_AND_LIFECYCLE, V1_SCOPE, INTEGRATIONS | the policy, Reports, RedactionCatalog, controllers | matrix replaced; corrected; INTEGRATIONS two lines only |

## Commands run and results
| Command | Result |
|---|---|
| `tsc --noEmit`; `npm run build` | 0 errors; built |
| Frontend (base 15275): auth, vocabulary, ui-framework, oidc-signin, tablet | 10, 238, 19, 231, 105 passed |
| a11y | 374 passed, **4 FAILED**: `keyboard0089.spec.ts` lines 464 and 491, light and dark, all "Test timeout of 60000ms exceeded" while tabbing through the error guide |
| Those tests alone, this branch | 4 failed, 2 passed, the same timeouts |
| The same, light, on the unchanged 466906b1 | **2 failed, 1 passed, the same timeouts** |
| product-name guard | clean |
| Backend | not run locally: no backend change (CI runs) |

**The four failures are not from this change**: in 0089 the same tests took 8 to 11 s here; the page has not changed, and the unchanged base now fails too. The laptop's browser is about 3 times slower today. **Cause not proven**: the NVIDIA GPU reported 0 MiB and 0 MHz, so it may be off. The tests are unchanged. They are near no limit on a normal machine, but they tab through 161 entries with a fixed 60 s; CI decides here.

Run by the subagent: the picture spec (20 passed after 2 locator faults of its own), helpTip 26, tablet "?" 10, 30 links and images with none broken.

## Bugs found (not fixed)
1. **The release frontend shows the old screens unless it is built with `VITE_CASE_FILE_UI_ENABLED=true`** (`App.tsx:553`); no tracked build or start script sets it. Confirmed.
2. Continue manually always says "approval" (`ManualCase.tsx:115,156,217`), though approval is off by default. Confirmed.
3. "Continue manually" is offered on every stopped case; the server takes AI analysis stops only (`ManualFallbackService:143`). Server rule confirmed.
4. "Processed again at most 3 times" (`PolicyArea.tsx:496`): the first export is one of the 3.
5. "Keep stopped cases for" takes 1 hour on screen (`PolicyArea.tsx:492`); the server needs 72 hours. Server limit confirmed.
6. Case fields: `FieldsArea.tsx:179` looks for words the message no longer has, so switching Main ID off is refused at once (by reading).
7. Wording: `Reports.tsx:457`, `HealthArea.tsx:132` ("Scan now"), `AuditArea.tsx:311`, `IntegrationsArea.tsx:665` ("sending system"), `Review.tsx:246` ("1 finding need"), no tab title for Entities and Licensing (`words.ts:31`).
8. Review: the focus ring of the first item covers the case line. Error-guide support texts name `docs/install` files. Remove from Dashboard has no button. Single Approve has no four-eyes check (0076).

## Not checked
Real app, mail, links, scanner, dark pictures; the 161 error texts; docs/api; the rest of the product requirement pages.

## Questions for the owner
1. Build the release with the new screens, or make them the default, before you test? (recommended: yes, first)
2. Fix bugs 2 to 7 in one small UI task? (recommended: yes)
3. "IT Administrator" or "IT Admin"? Both are on screens.
4. Keep `guides0095.spec.ts` to retake pictures? It runs on request only. (recommended: yes)
5. INTEGRATIONS.md (frozen requirements) still names another product and an old model; ENHANCEMENT_BACKLOG lists built items. Update? (recommended: yes)

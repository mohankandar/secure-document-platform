
## ChatGPT ↔ Kiro Development Workflow — Credit-Saving Rule

This workflow is a project operating rule and should be preserved across future ChatGPT threads.

### Division of responsibility

**ChatGPT is the implementation owner.**
- ChatGPT designs and writes the code for each approved unit.
- ChatGPT performs the first self-review.
- ChatGPT fixes defects it finds before handoff.
- ChatGPT updates the relevant steering/timeline/master documentation.
- ChatGPT creates the clean source ZIP for verification.

**Kiro is the verification owner.**
- Kiro must primarily BUILD, RUN, TEST, and VERIFY the code ChatGPT already implemented.
- Kiro must not reimplement the unit from scratch.
- Kiro must not redesign approved architecture.
- Kiro must not start the next unit.
- Kiro may make a source correction only when it finds a genuine compile, runtime, security, integration, or UI defect that prevents the approved unit from satisfying its frozen requirements.
- Any Kiro correction must be the smallest possible fix and must be reported file-by-file with the defect, correction, and verification rerun.

### Kiro credit-efficiency rule

Do **not** burn Kiro credits unnecessarily.

Kiro prompts should be narrow, authoritative, and verification-focused. They should:
1. State the exact approved unit and that the task is **verification only**.
2. State that the current workspace is authoritative.
3. Tell Kiro to read the unit steering/timeline documents first.
4. List concrete PASS/FAIL verification items.
5. Prefer runtime verification of important behavior over speculative code rewriting.
6. Reuse the real local stack already used by the project where applicable:
   - Java 21
   - PostgreSQL
   - Ollama with the approved/acceptance model configuration
   - Tesseract
7. Require the smallest correction only when a genuine defect is found.
8. Require Kiro to list every changed source file and explain every correction.
9. Require final backend/frontend builds after corrections.
10. Require cleanup of test artifacts before handoff.
11. Explicitly stop Kiro before the next unit or later V1 phase.
12. Avoid broad generic test generation, broad refactoring, speculative improvements, architecture rewrites, or documentation churn unless the frozen scope explicitly requires them.

### Standard unit workflow

Use this sequence for future units/phases:

1. Freeze scope/decisions in ChatGPT.
2. ChatGPT implements the unit.
3. ChatGPT self-reviews the implementation.
4. ChatGPT creates a clean Windows-safe ZIP.
5. Kiro performs structured build/runtime/security/UI verification only.
6. Kiro makes only minimal genuine defect corrections, if required.
7. User returns the Kiro report and any changed code to ChatGPT.
8. ChatGPT reviews the Kiro evidence skeptically.
9. ChatGPT formally approves the unit only if evidence is sufficient.
10. Only after approval does ChatGPT begin the next unit/phase.

### Kiro prompt structure

Future Kiro prompts should follow the established Unit 23–25 style:
- authoritative workspace/status header;
- exact task boundary;
- explicit DO NOT rules;
- frozen-purpose section;
- numbered verification sections;
- PASS/FAIL requirement for every material item;
- regression checks for previously approved units;
- cleanup section;
- final build section;
- exact source-change reporting;
- fixed final report format;
- explicit instruction to stop and not start the next unit.

The goal is to spend Kiro credits on **high-value independent verification**, not on duplicating ChatGPT implementation work.


# LexaVault V1 Master Documentation Packet

**Project Codename:** LexaVault  
**Category:** AI-Powered Secure Document Redaction and Processing Platform  
**Decision Freeze:** 2026-08-29 (product/architecture)  
**Current Construction Update:** 2026-08-31  
**Revision:** AI-first decisions + Units 16–25 approved + customer-policy/observability delta + Ollama page-retry correction + detection-precision/manual-review correction + focused credit-card precision correction implemented + Final V1 acceptance pending

> This packet is generated from the current authoritative Markdown documents. Historical review PDFs and early unit notes may contain superseded assumptions.


# CURRENT PROJECT CONTINUITY UPDATE — 2026-08-31

> **New-thread entry point.** Read this section first. It reconciles the frozen 2026-08-29 product/architecture decisions with later source reviews and Kiro verification results. Historical sections remain useful for intent, but historical construction-status wording is superseded by this section and the updated tracker/roadmap later in this packet.




## Focused Credit-Card Precision Correction — 2026-08-31

**Status: ChatGPT implemented; user rebuild/runtime rerun required before acceptance. Provider-neutral/tenant-prompt refactor is explicitly deferred until this precision test passes.**

The post-DOB/SSN precision rerun of the real 61-page customer PDF completed successfully with real Ollama `gemma3:12b` in ~386 seconds and produced only three final findings: one correct `CREDIT_SCORE` on page 59 and two incorrect `CREDIT_CARD_NUMBER` findings on pages 24 and 31. `DATE_OF_BIRTH=0` and `SSN=0`, proving the prior precision correction worked. Tenant filtering also correctly excluded deterministic email/phone findings because those categories are disabled for this customer.

Inspection of pages 24 and 31 shows repeated 13-digit barcode/control markers (`7777777777777`) and no exposed payment-card PAN. The AI adapter previously accepted any model output labeled `CREDIT_CARD_NUMBER` if the exact text existed on the page, and it also normalized the broader model type `FINANCIAL_ACCOUNT` into `CREDIT_CARD_NUMBER`. Both behaviors were too permissive for this customer's credit-card-only policy.

### Implemented narrow correction

1. The existing Ollama prompt now defines `CREDIT_CARD_NUMBER` as an actual exposed payment-card PAN only and explicitly excludes contract/member/account/loan/routing/phone numbers, monetary values, APRs, payment counts, dates, addresses, barcode/control markers, blank card fields and category words alone.
2. AI card findings must have a plausible exposed PAN shape: 13–19 digits, with only normal spaces or hyphens between digit groups. Currency punctuation, labels, dates and shorter identifiers are rejected before they enter the common finding pipeline.
3. Repeated single-digit barcode/control values such as `7777777777777` are explicitly rejected as impossible card PAN candidates.
4. `FINANCIAL_ACCOUNT` is no longer aliased to `CREDIT_CARD_NUMBER`; this tenant requested credit-card numbers, not generic financial-account identifiers.
5. Luhn is **not** made a hard AI gate. Existing deterministic Luhn validation remains supporting/challenging evidence, preserving the frozen AI-first rule. A semantically discovered plausible PAN can still enter the common pipeline even when deterministic evidence does not support it.
6. Rejected AI card candidates emit only safe metadata: `AI_FINDING_REJECTED ... type=CREDIT_CARD_NUMBER page=<n> reason=INVALID_PAN_SHAPE digitCount=<n>`. No candidate value or document text is logged.
7. Added focused `CreditCardPrecisionTest` covering the exact failure classes plus a valid synthetic PAN control.

### Required rerun evidence

Rebuild and process the same 61-page PDF again. Expected result for the inspected sample is:

- `CREDIT_SCORE=1` on page 59;
- `CREDIT_CARD_NUMBER=0`;
- `DATE_OF_BIRTH=0`;
- `SSN=0`;
- the page-24/page-31 barcode/control markers do not enter Manual Review;
- if the model still proposes them, safe `AI_FINDING_REJECTED` diagnostics should show the rejected page/digit count without the value.

Do **not** begin the provider-neutral/tenant-prompt refactor until this focused credit-card precision rerun is evaluated.

## Pre-Acceptance Detection Precision + Manual Review Preview Correction — 2026-08-31

**Status: ChatGPT implemented; user rebuild/runtime rerun required before acceptance.**

A successful 61-page LOCAL run with Ollama `gemma3:12b` proved the page-retry correction: AI completed all 61 pages with `parallelism=2`, `timeoutSeconds=180`, `pageRetries=0`, and the package safely entered `PENDING_REVIEW`. That run also exposed two separate pre-acceptance defects:

1. **DOB precision defect.** The deterministic recognizer treated any `MM/DD/YYYY` or `MM-DD-YYYY` value as `DATE_OF_BIRTH`, creating false DOB findings for transaction, signature, payment and other ordinary dates. The AI prompt also lacked sufficiently explicit DOB negative examples.
2. **Manual Review preview 400.** The reviewer workbench could display only generic `HTTP 400: Bad Request` when the finding-scoped page-preview endpoint rejected a request, leaving the center preview blank and hiding the safe application validation reason.

### Implemented precision correction

- Deterministic DOB discovery now requires explicit birth context (`Date of Birth`, `DOB`, `Birth Date`, `born ...`) and returns only the date value span, not the label.
- A valid date shape alone is no longer deterministic DOB evidence.
- Ollama instructions now explicitly reject sale/contract/signature/report/payment/due/effective/expiration/servicing/form-revision dates as DOB unless the document meaning ties the value to a person's birth.
- Ollama instructions now explicitly reject conceptual SSN references such as privacy-policy text saying `Social Security number` when no exposed numeric SSN value exists.
- Application semantic sanitation now discards SSN model outputs that contain no exposed SSN digits. Full 9-digit SSN remains valid; last-four-only SSN remains valid only with explicit SSN context and the tenant `maskSsnLastFour` policy enabled.
- Added `FINDING_EVIDENCE_SUMMARY` safe logging with semantic/deterministic/merged counts and category counts, without raw values.
- Added focused deterministic DOB regression tests proving ordinary transaction dates are not DOB and explicitly labeled DOB returns only the value span.

### Manual Review preview correction / diagnostics

- Review preview validation failures now emit safe high-level backend logs such as `REVIEW_PREVIEW_REJECTED ... reason=ITEM_NOT_PENDING`, `PACKAGE_NOT_IN_REVIEW`, or `ORIGINAL_UNAVAILABLE`.
- Successful page rendering logs only finding/package/document IDs, page number and rendered dimensions; no document text or sensitive value is logged.
- Global validation errors now log path, stable error code, safe message and correlation ID.
- The frontend preview request accepts both PNG success and JSON error responses and now parses/display the application error message when available instead of collapsing every failure to generic `HTTP 400: Bad Request`.
- Preview errors are shown in the preview pane as well as the page-level error banner.

The exact runtime cause of the previously observed 400 was not available in the captured server log. This correction makes the next occurrence self-identifying rather than guessing at the wrong validation branch. Do not weaken review security checks or render unavailable originals merely to suppress a 400.

### Required rerun evidence

Rerun the same 61-page package and confirm:

- AI completes without whole-document replay;
- `FINDING_EVIDENCE_SUMMARY` materially reduces deterministic DOB false positives;
- the actual credit score on page 59 is still discovered;
- privacy-policy wording that merely mentions Social Security number is not an SSN finding;
- ordinary dates are not emitted as deterministic DOB;
- if Manual Review preview succeeds, `REVIEW_PREVIEW_RENDERED` appears;
- if it fails, the log/UI exposes one safe reason instead of only generic HTTP 400.

## Pre-Acceptance Ollama Large-PDF Reliability Correction — 2026-08-31

**Status: ChatGPT implemented; user build/runtime rerun required before acceptance.**

A real 61-page native-text customer package was processed in LOCAL mode with Ollama `gemma3:12b`. PDF extraction completed successfully in ~2 seconds (`61 native pages`, `0 OCR pages`, ~168K extracted characters), but AI analysis failed twice after roughly four minutes per document-level attempt. The package then failed after ~8 minutes.

The failure exposed a narrow resilience/performance defect in the Ollama adapter rather than a PDF/OCR defect:

- page concurrency was hardcoded to `4`;
- per-request timeout was `60s`;
- the generic resilience decorator retried the **entire 61-page AI analysis** after any transient page failure;
- successful page work was therefore replayed instead of retrying only the failed page;
- the safe failure log did not expose the stable technical reason code or page attempt.

### Implemented correction

1. Ollama page concurrency is deployment-configurable via `SECUREDOC_AI_PARALLELISM` (default `2`).
2. Ollama page retry budget is configurable via `SECUREDOC_AI_PAGE_MAX_ATTEMPTS` (default `2`).
3. Page retry backoff is configurable via `SECUREDOC_AI_PAGE_RETRY_BACKOFF_MS` (default `500`).
4. LOCAL profile uses `SECUREDOC_AI_TIMEOUT_SECONDS` default `180` seconds for `gemma3:12b`; base/non-local default remains `60` seconds and is deployment-configurable.
5. Transient AI failures now retry **only the affected page** inside the Ollama adapter.
6. After page retry exhaustion, the propagated failure is marked non-retryable so the outer generic provider retry does not replay the whole document. Optional configured secondary-provider failover remains available.
7. Generic retry attempt accounting now preserves attempts already consumed by a lower-level targeted retry strategy.
8. Safe diagnostics now include page number, stable failure code, attempt number, retry decision, configured timeout/parallelism, page-retry count and durations. They do not log raw page text, prompts, model output, sensitive values or document content.
9. Added focused regression tests proving:
   - one transiently failed page is retried while a successful page is not replayed;
   - exhausted page retries do not trigger the outer whole-document retry.

### Expected rerun evidence

For the same 61-page customer PDF, the next LOCAL run should begin approximately as:

```text
AI_ANALYSIS_STARTED provider=ollama model=gemma3:12b pages=61 parallelism=2 timeoutSeconds=180 pageMaxAttempts=2
```

If an individual page times out/transiently fails, expected safe evidence is:

```text
AI_PAGE_ATTEMPT_FAILED ... page=<n> attempt=1 reason=AI_TIMEOUT retrying=true ...
AI_PAGE_RECOVERED ... page=<n> attempts=2 ...
```

A successful full analysis should finish with `AI_ANALYSIS_COMPLETED ... pageRetries=<n>` rather than starting a second 61-page document analysis.

This correction does **not** change the frozen AI-first requirement: every nonblank normalized page still enters semantic AI analysis, and deterministic detectors do not gate AI discovery.

## Unit 25 Final Approval — 2026-08-30

**Status: APPROVED**

Kiro completed the structured Unit 25 build/runtime/security/UI verification against the real local stack:
- Java 21
- PostgreSQL
- Ollama `gemma3:12b`
- Tesseract
- controlled local RS256 OIDC mock issuer with discovery + JWKS

### Verification result

The Unit 25 verification completed successfully with:
- backend `mvn clean verify` — BUILD SUCCESS, all 10 modules;
- frontend `tsc --noEmit` and `vite build` — PASS;
- Liquibase clean with 34 existing changesets and no Unit 25 migration;
- LOCAL mode preserved without default ADMIN escalation;
- OIDC mode using verified JWT identity only;
- signature, issuer, audience, expiry, `nbf`, and JWKS fail-closed behavior verified;
- identity-header spoofing blocked in OIDC mode;
- trusted tenant/actor/role mapping verified;
- `BUSINESS_ADMIN` and `PLATFORM_ADMIN` separated, with explicit `ADMIN` super-role behavior preserved;
- central `TenantBoundaryGuard` verified;
- cross-tenant read/write and same-ID mutation protection verified;
- safe `/api/v1/me` verified;
- frontend Authorization Code + PKCE and session-only token storage verified;
- legacy original/vault production routes hardened;
- callback HTTPS + bearer authentication verified;
- security headers, CORS, stateless bearer API model, error safety, and secret/PII leakage checks passed;
- Units 16–24 regression and representative LOCAL/OIDC flows passed;
- focused Unit 25 security-boundary suite passed: 4 classes / 8 tests.

### Kiro source corrections during verification

Kiro changed **test files only**:

1. `JwtBoundaryTest.java`
   - Fixed an invalid test fixture where hardcoded `issuedAt` made the expired JWT fixture fail construction before decoder validation.
   - Correction: use `.issuedAt(nbf)`.

2. `RequestContextResolverSecurityTest.java`
   - Fixed a positive-path test fixture that created `JwtAuthenticationToken` without authorities, leaving it unauthenticated.
   - Correction: use an authenticated helper with `SimpleGrantedAuthority`.

**No production source code was changed by Kiro.**

### Focused follow-up verification

A small follow-up was requested instead of rerunning the entire Unit 25 suite, to conserve Kiro credits.

Both checks passed:

1. **Production credential defaults — PASS**
   - base production/OIDC config contains no datasource credentials;
   - local DB credential is isolated to `application-local.yml`;
   - OIDC issuer/audience default empty and fail closed;
   - callback bearer token defaults empty and authenticated API delivery fails closed without it;
   - no embedded S3 access/secret keys;
   - no embedded AI API key.

2. **Actual JWKS outage — PASS**
   - after stopping the mock issuer/JWKS endpoint, unknown-key and malformed tokens returned 401;
   - no fallback to identity headers, LOCAL mode, or ADMIN;
   - no secret, stack trace, or JWKS payload leak.

### Known Unit 25 limitations carried forward

- New Spring Security dependencies require a reachable Maven repository; Kiro used a temporary Maven Central mirror because the corporate mirror was unavailable. This is an environment issue, not a code defect.
- Unmapped `/api/v1/**` paths may still return a safe generic 500 rather than 404 due to pre-existing interceptor behavior. No sensitive information leak was observed.
- OIDC was verified against a controlled local RS256 issuer, not a real enterprise Okta tenant. Real enterprise IdP configuration belongs in the Final V1 Security/Integration + Acceptance phase.
- HSTS is emitted over HTTPS as expected; local HTTP verification does not exercise a real TLS deployment.

### Next phase

**Do not start a Unit 26.**

The next project phase is the separately documented:

**Final V1 Security / Integration Tests + Acceptance**

That phase must validate the complete V1 product story under the hardened Unit 25 trust boundary and prepare the V1 release candidate.



## Hands-On User Validation — 2026-08-30

The user started and exercised the current approved application in LOCAL mode after Unit 25 approval and reported that testing was **so far successful**.

This is useful product-confidence evidence, but it is **not a substitute for the formal Final V1 Security / Integration + Acceptance phase**. Formal release-candidate acceptance still requires the frozen end-to-end security/integration checks and release documentation below.



## Final V1 Pre-Acceptance Code Review — 2026-08-30

**Historical result on 2026-08-30: READY FOR INDEPENDENT FINAL ACCEPTANCE. Superseded on 2026-08-31 by the user-approved customer-policy/observability delta below.**

ChatGPT reviewed the approved Unit 25 production source against the frozen Final V1 Security / Integration + Acceptance plan.

Key conclusions:
- current code already supports the required digital/scanned/mixed PDF normalization, AI-first discovery, evidence merge, permanent burn-flat redaction verification, focused Manual Review, technical exception separation, package-atomic export, tenant/security boundaries, lifecycle, Dashboard/reporting, and admin personas;
- Final Acceptance must use **real Ollama `gemma3:12b`**. The explicit `heuristic` adapter remains a development-only fallback and does not count as V1 acceptance evidence;
- S3/object delivery is implemented but should be claimed as runtime-accepted only if Final Acceptance can exercise it against MinIO/S3-compatible infrastructure;
- controlled RS256 OIDC/JWT verification from Unit 25 remains valid standards-based security evidence; a real Okta tenant is a deployment-validation exercise if one is readily available;
- no speculative production code should be changed before Kiro acceptance; the later 2026-08-31 customer-policy/observability delta is an explicitly approved requirement, not speculative refactoring;
- the **current Kiro workspace is authoritative** because it contains the two approved Unit 25 test-fixture corrections. Do not overwrite it with an older ChatGPT Unit 25 ZIP.

The next successful project outcome is **V1 Release Candidate**. There is no Unit 26 in the frozen plan.



## Customer-Specific Sensitive-Category / Masking Policy Clarification — 2026-08-31

For the current target customer, sensitive-content masking is tenant/customer-configured.

### Enabled categories for this customer

Only these four categories are enabled for masking/redaction:

1. **SSN**
2. **DOB**
3. **Credit Score**
4. **Credit Card Number**

The following must **not** be masked for this customer merely because they appear in a document:

- customer/person name
- email address
- phone number
- postal/street address
- Contract Number
- Member Number
- routing number / bank-account metadata
- ordinary monetary values, points, dates, identifiers, or other fields

Future customers may enable different categories by tenant policy, but this customer's policy must remain limited to the four categories above.

### SSN last-four behavior

SSN masking includes partial SSN representations.

Required behavior:

- If a **full SSN** is present, redact the full exposed SSN according to the SSN policy.
- If the document contains an SSN that is already partially masked and **only the final four digits remain visible**, such as an explicitly SSN-labeled representation equivalent to `XXX-XX-1234`, redact the remaining visible final four digits.
- If only four digits appear with no SSN context, do **not** redact merely because they are four digits.
- AI/context may identify a last-four-only SSN representation even when a deterministic full-SSN validator cannot validate a 9-digit SSN. Deterministic detection must not gate the AI finding.

This is a **customer-specific masking configuration / policy behavior**, not a fifth sensitive category.

### Acceptance implications

Final V1 acceptance for this customer should include:

- full SSN case;
- SSN-last-four-only case in clear SSN context;
- four-digit negative-control case with no SSN context;
- DOB;
- contextual Credit Score;
- Credit Card Number;
- explicit negative controls proving that name and email remain unmasked.




## Pre-Acceptance Customer Policy + Safe Observability Implementation — 2026-08-31

**Status: IMPLEMENTED IN SOURCE; REQUIRES FINAL ACCEPTANCE VERIFICATION.**

After Unit 25 approval and during hands-on manual testing, the customer-specific policy was clarified and ChatGPT implemented a deliberately small pre-acceptance delta.

### Customer policy implementation

- The tenant remains configurable for exactly the four V1 categories: **SSN, DOB, Credit Score, Credit Card Number**.
- Name, email, phone, address, Contract Number, Member Number, bank/routing metadata and other fields remain outside this customer's enabled policy unless explicitly added/enabled in a future scope.
- Added tenant policy field **`maskSsnLastFour`**.
- Business Admin can enable/disable the SSN-last-four behavior without disabling full SSN redaction.
- Existing/local tenant policies migrate with the flag enabled by default for the current customer requirement.
- Added Liquibase migration **`012-ssn-last-four-policy.xml`**. This is a post-Unit-25 pre-acceptance migration; it does not invalidate the historical Unit 25 statement that Unit 25 itself added no schema migration.
- Ollama semantic instructions now explicitly recognize last-four-only SSN only in clear SSN/Social Security context and request only the exposed four digits as the redaction span.
- Application logic narrows masked forms such as `XXX-XX-1234` to the exposed digits and filters partial-SSN findings when the tenant toggle is disabled.
- Deterministic validation may support a four-digit SSN candidate only when explicit SSN context is present. It still does not discover/gate the AI candidate.
- Added a narrow regression test for contextual last-four support, unrelated-four-digit rejection, and default tenant policy.

### Safe high-level processing logs

Added concise INFO-level operational events for manual/load acceptance:

- `PACKAGE_PROCESSING_STARTED`
- `AI_ANALYSIS_STARTED`
- `AI_ANALYSIS_COMPLETED`
- `DOCUMENT_EXTRACTION_COMPLETED`
- `DOCUMENT_DETECTION_COMPLETED`
- `DOCUMENT_REDACTION_COMPLETED`
- `PACKAGE_DELIVERY_COMPLETED`
- `PACKAGE_PROCESSING_FINISHED`

These events contain IDs, counts, categories, page numbers, decisions, provider/model metadata and elapsed times only. They must not contain raw finding values, OCR/document text, names, emails, credentials, tokens, full paths or raw exception payloads.

For a representative contract package, a useful safe detection line can show a category on a page such as `pagesByType={CREDIT_SCORE=[59]}` without printing the score itself.

### Verified-baseline merge

This source baseline also includes the two Kiro-approved Unit 25 test-fixture corrections:

- `JwtBoundaryTest.java`: use the fixture `nbf` for `issuedAt` so expired-token construction reaches decoder validation.
- `RequestContextResolverSecurityTest.java`: construct an authenticated JWT test token with a harmless granted authority.

No Kiro Unit 25 production correction was required.

### Local operations

Reusable `Start-LexaVault-Local.ps1`, `Stop-LexaVault-Local.ps1`, `start-local.cmd`, and `stop-local.cmd` are now included with the codebase for repeatable LOCAL startup/shutdown.

### Next gate

Manual testing may continue. Formal Final V1 Security / Integration + Acceptance must include this delta before a V1 Release Candidate is approved.



## Pre-Acceptance Delta Self-Review / Build Evidence — 2026-08-31

ChatGPT completed a focused source/self-review of the customer-policy + observability delta.

Passed locally in the available environment:
- Java 21 domain module `javac` compile;
- Java 21 application module source compile using temporary SLF4J API stubs;
- changed AI source compile using temporary Jackson/SLF4J API stubs;
- changed deterministic adapter and tenant-policy persistence source compile using temporary Spring/JPA API stubs;
- changed API DTO compile;
- focused executable SSN-last-four contextual/negative/default-policy check;
- focused new JUnit source compile using temporary JUnit stubs;
- TypeScript changed-file syntax/transpile checks;
- all XML/POM/Liquibase files parsed successfully;
- Liquibase now contains **35 changeSets** including expected post-Unit-25 migration `012-ssn-last-four-policy.xml`;
- new logging statements contain safe metadata only and do not log raw finding/document values.

A full Maven/Vite build is **not claimed** from ChatGPT's environment because Maven/dependency infrastructure is unavailable there. The user/Kiro environment must run the normal final backend/frontend builds before Release Candidate approval.



## Current Pre-Acceptance Source Handoff — 2026-08-31

The current ChatGPT implementation handoff is packaged as:

`LexaVault-V1-pre-acceptance-updated-code-2026-08-31.zip`

It is built from the approved Unit 25 source baseline, merges Kiro's two approved Unit 25 test-fixture corrections, and adds only the explicitly approved pre-acceptance customer-policy/safe-observability delta plus continuity documentation and LOCAL utility scripts.

This handoff is **not yet the V1 Release Candidate**. Manual/runtime validation and the formal Final V1 Security / Integration + Acceptance gate still apply.


## Source-of-Truth Precedence

When information conflicts, use this order:

1. **Frozen V1 product/architecture decisions** remain authoritative for product intent.
2. **Later approved Unit documentation and Kiro verification reports** are authoritative for what was implemented, corrected, runtime-tested and accepted after the freeze.
3. **Current source code** is authoritative for mechanics that the next unit must harden/preserve.
4. Historical early-unit notes, review PDFs and old construction-status wording are context only when they conflict with later approved decisions.

## Current Project State

| Unit / Phase | Status on 2026-08-30 | Current Truth |
| --- | --- | --- |
| Units 1–15 | COMPLETE / previously reviewed | Historical foundation, security and audit groundwork. |
| Unit 16 — Core AI-First Automatic Redaction | **APPROVED** | AI contextual analysis is mandatory primary discovery; real local Ollama path accepted. |
| Unit 17 — OCR / Image Redaction | **APPROVED** | Real Tesseract OCR path accepted; native/OCR pages converge into one AI-first pipeline. |
| Unit 18 — Confidence + Exception Handling | **APPROVED** | Selective retry/backoff; no heuristic substitution for required AI; technical exceptions separate from business review. |
| Unit 19 — Minimal Manual Review | **APPROVED** | Focused page/finding review; no assignment/developer-console workflow. |
| Unit 20 — Business Record + Identifiers + Unified Intake | **APPROVED** | BusinessRecord/IntakeBatch split, multiple identifiers, watched-folder trigger + fingerprint. |
| Unit 21 — Business Admin | **APPROVED** | Business identifiers/policy, confidence/review, sensitive categories and retention/hold settings. |
| Unit 22 — Delivery + Lifecycle | **APPROVED** | Local/API delivery, S3 adapter, export-cycle/retention/reprocessing/purge lifecycle. |
| Unit 23 — Shared Dashboard + Reporting | **APPROVED** | Final merged dashboard/reporting baseline verified; no later Kiro source change required. |
| Unit 24 — IT / Platform Admin | **APPROVED** | Provider/runtime health, watched-folder operations/scheduler and Technical Exceptions verified with no Kiro source changes. |
| Unit 25 — Production Identity / Platform Hardening | **APPROVED** | Production OIDC/JWT trust boundary, tenant guard, role separation, callback/security hardening and focused security tests verified by Kiro; no production-code correction required. |
| Final Security/Integration Tests + V1 Acceptance | **PREPARATION / MANUAL TESTING** | Small pre-acceptance policy/observability delta implemented 2026-08-31; formal Kiro acceptance not yet run. |

## Non-Negotiable AI-First Product Rule

- Every processed document enters AI contextual analysis after native-PDF/OCR normalization.
- Deterministic recognizers/validators are supporting/parallel evidence and never a prerequisite for AI discovery.
- AI identifies semantic sensitivity; native PDF extraction/OCR normally supplies authoritative geometry.
- Application policy decides REDACT vs REVIEW.
- A controlled deterministic redaction engine performs irreversible modification and independent verification.
- If required AI is unavailable, processing becomes a technical condition; the product must not silently claim equivalent deterministic-only success.

## Approved Implementation Decisions Added After the Original Freeze

### Units 16–18 — AI/OCR/Confidence

- Repeated Kiro local acceptance uses **Ollama `gemma3:12b`** as the reference model while the architecture remains provider/model configurable and local/on-prem capable.
- Tesseract is the accepted local/on-prem OCR implementation used in runtime verification.
- Native digital-PDF text/layout is preferred; OCR is page-level fallback for scanned/unusable pages.
- AI-only and deterministic-only candidates both flow into the same Finding/evidence/policy model.
- Required AI failure does not silently fall back to heuristics/deterministic-only success.
- Technical retry/failover is classified and bounded; Manual Review remains a content/business-exception workflow.
- Accepted successful publication behavior is package-oriented/atomic; unresolved content must not be silently partially published as a successful package.

### Units 19–21 — Review, Business Identity, Business Admin

- Manual Review is a separate focused workbench showing only actionable finding/page context.
- No assignment-to-user workflow is required in V1.
- One long-lived Business Record can receive many Intake Batches.
- Multiple identifiers are supported; exactly one is Primary while Required/Searchable/Extractable/Displayable remain independent.
- Watched-folder intake is trigger/manifest gated and fingerprint/idempotency protected.
- Business Admin policy is runtime-authoritative for identifiers, sensitive categories, confidence/review behavior and retention/hold rules.

### Unit 22 — Delivery / Lifecycle

- PDF binaries remain outside PostgreSQL.
- Local filesystem and API/callback delivery paths were runtime verified; S3/object delivery is implemented and should be runtime-tested in final integration if advertised as production-ready.
- Preserve original customer filenames and file boundaries.
- Maximum three successful export cycles per original Intake Batch lineage by default.
- Technical delivery retries do not consume the successful business export-cycle count.
- Original post-export retention defaults to 24h; successful re-export resets the clock.
- Never-exported technical hold defaults to 72h and can be explicitly extended under Business Admin policy.
- Original retrieval is not a target V1 product feature.

### Unit 23 — Final Merged Dashboard / Reporting Baseline

The authoritative Unit 23 is the final merged version, not either earlier ChatGPT copy.

- Kiro found one real startup defect: Unit 23 referenced a Liquibase 4.28 XSD while the project runtime is Liquibase 4.27. The changelog was corrected to the project-compatible **4.27 XSD**.
- A later merge preserved that Kiro fix and added the stronger frozen Business Admin column governance.
- Business Admin controls **Allowed / Default / Order** for identifier and operational Dashboard columns.
- Primary business identifier cannot be disabled as the canonical business identity.
- Disallowed identifier columns and their **values** are suppressed from normal Dashboard payloads.
- User column preferences are pruned against the current permitted set.
- SQL search treats `%`, `_` and `!` literally through escaping.
- Successful export cycles are counted from actual persisted successful export records.
- APPROVED/VAULTED compatibility states map consistently into Processing.
- Final Kiro merge verification required **zero source changes** and Unit 23 was approved.

### Unit 24 — IT / Platform Admin

Unit 24 is **APPROVED** after comprehensive Kiro verification with **zero source changes**.

Verified behavior includes:

- Safe real health/status for PostgreSQL, Ollama, Tesseract, internal storage and final delivery.
- Provider credentials/tokens are never returned to browser/audit; deployment/environment config remains technical source of truth.
- Retry/backoff and optional secondary/failover state expose the existing resilience configuration rather than creating another engine.
- Watched-folder `Scan now` and automatic scheduler reuse Unit 20 intake semantics.
- Scheduler is disabled by default and requires explicit tenant + actor/service identity; no tenant/demo guessing.
- Accepted/waiting/rejected/duplicate watched-folder outcomes and idempotency were runtime verified.
- Technical Exceptions are separate from Manual Review and expose only safe technical context.
- Health/scan privileged actions are auditable.
- Production OIDC/Okta/real identity remained explicitly deferred to Unit 25.

## Unit 25 Frozen Scope and Current Source Status

### Purpose

Unit 25 replaces the pre-production/demo **trust boundary**. It is not merely “add SSO.”

Production trust must be:

`verified OIDC/JWT -> trusted tenant -> trusted actor -> trusted product roles -> tenant boundary validation -> existing tenant-scoped use cases/repositories`

### Frozen Decisions

- Security modes: `LOCAL` and `OIDC`.
- LOCAL remains explicit developer/demo mode; production must not silently fall back to LOCAL.
- In OIDC mode, `X-Tenant-Id`, `X-Actor-Id`, and `X-Actor-Roles` cannot determine/override trusted identity.
- OIDC/JWT stays provider-neutral and Okta-compatible; no Okta/IDP SDK types enter domain/application/AI/redaction product logic.
- Trusted tenant comes from a configurable verified JWT claim (`tenant_id` default).
- Actor identity is derived from verified issuer + subject; display username may use `preferred_username`.
- Roles: `USER`, `REVIEWER`, `APPROVER`, `BUSINESS_ADMIN`, `PLATFORM_ADMIN`, `ADMIN`.
- `ADMIN` remains the centralized explicit super-admin; missing/unmapped claims never infer ADMIN.
- BUSINESS_ADMIN owns Business Admin endpoints; PLATFORM_ADMIN owns Platform Admin/technical operations/audit according to frozen role mapping.
- A small central `TenantBoundaryGuard` validates the trusted tenant before tenant-owned API use cases. **No PostgreSQL RLS, Hibernate tenant framework, or tenant architecture rewrite.**
- Identified same-ID cross-tenant JPA mutation/save paths are hardened.
- Standard Spring Security JWKS discovery/caching/refresh is used; no custom JWKS service. JWKS/IdP failure must fail closed.
- Short-lived access tokens expire normally; no custom token-revocation/blacklist database in V1.
- SPA production auth uses Authorization Code + PKCE with session-scoped token storage and no client secret in the bundle.
- Safe `/api/v1/me` exposes only safe current user/tenant/role/mode context.
- Production API/callback delivery policy is HTTPS + deployment-managed bearer token; token must not reach UI/log/audit.
- Production CORS is allowlist-based with explicit security headers.
- Legacy original/vault/access-request REST surfaces are disabled in OIDC production mode; redacted artifact access remains the supported path.
- Production credentials have no usable development defaults; LOCAL developer convenience stays separate.
- On-prem/local-first operation remains first-class; external cloud identity is not mandatory for LOCAL mode.
- A **small focused executable security-boundary test suite** is in Unit 25 scope; broad generic test generation remains out of scope.

### Current Unit 25 Candidate

- Artifact: `LV25-production-identity-platform-hardening-implemented-awaiting-verification.zip`
- SHA-256: `5e9505cc6df244a4ae1aef54763196066f59c89d4a36649b0853160c620727e0`
- Status: **implemented in source, awaiting Kiro verification; NOT approved yet.**
- No Unit 25 Liquibase migration is intended.
- No Unit 16–24 processing-pipeline redesign is intended.
- Kiro has a strict **63-item verify-only prompt** covering builds, LOCAL/OIDC runtime, JWT signature/issuer/audience/time/JWKS behavior, header spoof prevention, tenant/actor/role mapping, role matrix, cross-tenant read/write isolation, same-ID mutation protection, `/me`, PKCE/session token handling, CORS/security headers, callback auth, focused security tests and Units 16–24 regressions.
- If Kiro changes source, the changed source becomes authoritative only after the correction and report are reviewed/approved.

## Remaining V1 Gaps / Final Phase

| Area | Current State | Required Next Step |
| --- | --- | --- |
| Unit 25 identity/security | **Approved** | Preserve as frozen production-security baseline during Final V1 acceptance. |
| S3/object delivery | Adapter implemented; prior verification did not fully runtime-exercise S3 | Test during final integration if V1 release claims production-ready S3. |
| Callback auth | HTTPS + bearer policy implemented and verified in Unit 25 | Reconfirm only as part of representative final delivery acceptance; do not redesign. |
| Production tenant/role trust | Implemented and Unit 25 verified | Preserve and include representative attack/regression checks in Final V1 acceptance. |
| Legacy vault/original-access surface | Production hardening implemented and Unit 25 verified | Preserve; include a light regression check in Final V1 acceptance. |
| Official acceptance model wording | Kiro reference is Ollama `gemma3:12b`; architecture stays configurable | Release docs should clearly separate reference acceptance model from deploy-time configurability. |
| In-process scheduler limitations | Working V1 implementation | Accept/document unless final operational testing proves a release blocker; do not introduce distributed scheduling by default. |
| Final Security/Integration/V1 Acceptance | **READY TO START** | Freeze acceptance scope, perform only required pre-acceptance fixes, then run one structured Kiro acceptance verification. |

## Final V1 Security / Integration / Acceptance — Required After Unit 25

Unit 25 approval does **not** finish V1. Final acceptance must consolidate the entire product story under the hardened security model, including:

1. Digital PDF with SSN, DOB, credit score and credit-card number.
2. AI analysis on every processed document; deterministic miss must not block AI discovery.
3. Deterministic evidence can independently strengthen/challenge AI findings.
4. Native-PDF geometry + scanned/OCR geometry, including representative mixed content.
5. Real Tesseract scanned PDF path.
6. Permanent redaction independently verified unrecoverable/search/copy/extract safe.
7. Original filename/file-boundary preservation.
8. Straight-through high-confidence export.
9. Focused low-confidence Manual Review and automatic resume.
10. Technical-provider failure remains Technical Exception/Attention, never business review or deterministic-only success.
11. Watched-folder trigger/fingerprint/waiting/rejected/duplicate/disposition behavior.
12. Business Record reuse + separate Intake Batches.
13. Business Admin policy and Dashboard-column governance.
14. Platform Admin provider health/watched-folder/Technical Exception behavior.
15. Local/API delivery and S3 runtime if S3 is a release claim.
16. Export cycles, retention, technical holds, purge and reprocessing.
17. Shared Dashboard + five reports + tenant isolation.
18. Production OIDC/JWT, role matrix, spoof prevention, cross-tenant isolation, safe auth errors and secret handling.
19. Audit/correlation with real authenticated/system actors and safe metadata.
20. Release packaging, startup/deployment instructions, admin guides, provider/data-handling notes, known limitations and rollback guidance.

## Handoff Rule for Any New Thread

- Read this continuity section first.
- Preserve Units 16–24 as approved baselines.
- Do not restart/rewrite approved units because an older historical section says “planned” or “in progress.”
- Unit 25 is **APPROVED**. Preserve it as the production-security baseline. The next phase is Final V1 Security / Integration + Acceptance; do not invent Unit 26.
- Preserve AI-first mandatory discovery, package-oriented successful output, focused Manual Review, separate Technical Exceptions, BusinessRecord/IntakeBatch identity, bounded retention, shared Dashboard, and Business Admin vs Platform Admin ownership.

---


V1 Decision Freeze - 2026-08-29
Current product and architecture decisions
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# Core Product

LexaVault V1 is an AI-first automatic secure document-redaction platform. The platform should carry the implementation complexity so customers can provide documents and configured business identifiers, receive permanently redacted output, and intervene only when something genuinely needs attention.

# Locked V1 Decisions

## AI-First Sensitive-Information Discovery

- Every processed document enters the AI/contextual-analysis stage after its content has been normalized from native PDF text extraction or OCR.
- AI is the primary sensitive-information discovery layer for all supported V1 categories. It is not merely a fallback that runs only after a deterministic detector succeeds or becomes uncertain.
- Deterministic recognizers and validators remain valuable secondary evidence for structured data such as SSNs and credit-card numbers, but they must not gate AI discovery.
- If deterministic logic misses a sensitive item, AI must still be able to identify it from document context. If AI misses a structured item that deterministic logic finds, that deterministic finding remains a candidate and is merged into the same finding/policy pipeline. Agreement strengthens confidence; disagreement contributes evidence for confidence/review decisions.
- The architecture must not require every sensitive category to have a deterministic validator. This is important even in V1 because the same finding model must later support semantic confidential content such as company secrets, proprietary sections, internal strategy, pricing, or other customer-defined sensitive concepts.
- The AI layer determines what content means and whether it is a sensitive candidate. Native PDF extraction/OCR/location components determine where the relevant text or region appears on the page. AI is not required to guess X/Y coordinates when authoritative layout coordinates already exist.
- AI does not directly modify the PDF. The policy layer decides REDACT versus REVIEW, and a deterministic redaction engine performs the permanent document modification and verification.
- V1 architecture is provider-neutral, with local/on-prem AI as the preferred first deployment direction. A successful AI analysis is part of the intended V1 straight-through acceptance path; a provider outage is a technical condition, not a reason to silently redefine the product as deterministic-only processing.

## Digital Text, OCR, and Mixed PDFs

- Do not OCR every PDF by default.
- For a page with usable native PDF text, extract the native text together with layout/character coordinates because it is usually faster and more precise than re-OCRing the page.
- For a scanned/image-only page, or a page whose embedded text is unusable, render/OCR that page and retain OCR text, confidence, and bounding boxes.
- Mixed PDFs are handled page-by-page. The customer sees one consistent workflow regardless of whether a page used native extraction or OCR.
- Both native extraction and OCR normalize into the same document/page content model consumed by AI, deterministic evidence, policy, and redaction.

## Automatic Redaction

- V1 baseline sensitive information: SSN, date of birth, credit score, and credit card number.
- The same customer flow applies to digital text PDFs and scanned/image PDFs.
- The platform must locate sensitive content automatically. Customers do not enter page coordinates or X/Y values.
- Redaction is permanent and irreversible; visual black boxes are acceptable only when underlying sensitive content cannot be recovered, searched, copied, or extracted.
- Preserve original filenames and file boundaries. No `-redacted` suffix and no document merging.

## Policy and Confidence

- Business Admin configures which sensitive categories apply and the confidence/review policy.
- Recommended happy path: if configured confidence is satisfied, redact and export automatically with no human approval.
- Customers may temporarily require review-all or final approval while building trust, then switch to straight-through processing.
- If only one finding is below threshold, send only that finding/relevant page to Manual Review. Other acceptable findings continue automatically.
- V1 uses explicit policy profiles/categories. Future versions may provide richer law/regulation profiles such as GDPR or local regulatory packs; V1 must not claim autonomous legal interpretation.

## Technical Failure Handling

- Do not retry every failure three times.
- Deterministic/non-recoverable issues such as corrupt/unsupported files, missing required identifiers, or genuine unreadable OCR content are not blindly retried.
- Transient failures such as AI/OCR provider timeout, rate limit, temporary 429/503, storage/network interruption may use delayed retry/backoff.
- Optional secondary-provider failover is allowed when configured by IT/Platform Admin.
- Low-confidence content exceptions go to Business Manual Review; unresolved technical problems go to a separate Technical Exception view.

## Business Identity and Intake

- A long-lived Business Record is identified by customer-configured identifiers such as Contract Number, Loan Number, Member Number, Patient Number, or similar.
- A Business Record supports multiple configured business identifiers. Exactly one identifier is Primary for compact/canonical display. Additional identifiers may independently be required, searchable, extractable, displayable, and ordered. Required does not imply Primary.
- One Business Record can receive many Intake Batches over time.
- Each manual upload, scan submission, API request, or watched-folder trigger creates a separate Intake Batch.
- A received file is a Document. Multiple physical documents scanned into one PDF are treated as one received document unless later classification intelligently recognizes sections.
- Manual upload/scan requires all tenant-configured mandatory identifiers before submission.
- API intake supplies mandatory identifiers with documents/document references.
- Watched-folder intake processes a batch only after a trigger/ready/manifest file appears. The trigger marks the folder complete and supplies required identifiers/metadata.
- Watched-folder batches use a deterministic internal fingerprint for duplicate/idempotency protection. Corrected metadata/content produces a new fingerprint.
- Post-ingest watched-folder behavior is customer/environment specific: move to Archive, move to Problem, copy/acknowledge, or leave source files in place based on permissions and IT configuration.
- Missing trigger, malformed trigger, missing required identifiers, unsupported files, duplicate intake, and similar intake outcomes must be structured and reportable.

## Storage and Delivery

- Do not store PDF binaries in PostgreSQL.
- PostgreSQL stores metadata, identifiers/attributes, storage references/manifests, findings, processing attempts, reason codes, export records, retention deadlines, and audit data.
- Storage is pluggable: secure local/on-prem filesystem first, S3/object storage, and future providers.
- Final delivery supports local/on-prem filesystem, S3/object storage, and API/callback. IT/Platform Admin configures the destination.
- Local testing uses a configured local output folder.
- Preserve customer filenames at delivery.

## Retention and Reprocessing

- LexaVault is not a long-term original-document retrieval product.
- Original documents are temporary recovery/reprocessing artifacts.
- Normal original-retention clock begins only after a successful export, not at ingestion.
- Default original post-export retention: 24 hours; Business Admin may configure a longer customer policy such as 48 hours or one week.
- A successful reprocess/re-export resets the retention clock from that latest successful export.
- Default V1 limit: maximum three successful export cycles for the same original Intake Batch lineage. After the third successful export, no further reprocessing is allowed; after retention expiry the original is deleted. Further processing requires new ingestion and a new Intake Batch/package ID.
- Technical delivery retries do not consume the three business reprocess/export cycles.
- If a package never achieves a first successful export, default technical-exception hold is 72 hours. Business Admin may explicitly extend it (for example 7 or 14 days) to match customer support/IT SLA. Extensions must be visible and auditable; no indefinite silent hold.
- LexaVault's temporary internal redacted copy is retained 24 hours by default after confirmed export, configurable up to a hard maximum of 72 hours, then deleted. Exported redacted output at the customer destination is the durable artifact.
- Original-access/download workflow is not part of the target V1 product direction and existing implementation should be simplified/retired as relevant units are revised.

## Reporting

- V1 built-in reporting may remain small, but the data model must support end-to-end operational visibility and external reporting.
- Use structured statuses, timestamps, counts, and reason codes. Do not require parsing audit text or application logs.
- Business users receive simple operational visibility; IT/reporting users can access deeper detail and query/report externally.
- Watched-folder intakes, including folders waiting for a trigger, must become visible/reportable after a configured observation threshold rather than silently sitting in a filesystem.

## UX and Administration

- UI is modern, brand-new, minimalist, and role-driven. The application carries implementation complexity; business users see only business context, processing stage, and actions they can meaningfully take.
- Use one shared Dashboard layout across business roles. Roles control record visibility, permissions, actions, and access to Business Admin / IT Admin areas; do not create separate duplicate dashboards per role.
- Normal users do not see package UUIDs, review task IDs, finding IDs, assignment mechanics, or numeric bounding boxes.
- Dashboard column labels use the configured business labels directly, never a generic `Reference` label. Business Admin controls which approved fields are available/default-visible and their default order; users may show/hide and reorder the approved columns for their own view. Internal UUIDs are not eligible business columns.
- Recommended default operational columns are: Primary Business Identifier, Received, Stage, Documents, and Attention. Additional configured business identifiers or approved fields may be added as adjacent columns.
- Dashboard search operates across tenant-configured searchable business fields. Stage is a first-class quick filter with one-click options such as All, Processing, Needs Review, Exported, Technical Attention, and Rejected; less-common filters remain under a compact More Filters control.
- Overview/KPI cards at the top of the Dashboard are collapsible. Cards are actionable filters. The collapsed/expanded state and user column/filter preferences should be remembered.
- Dashboard filtering, sorting, and pagination should be server-side when data volume warrants it; the simple UI must not require loading the entire tenant dataset into the browser.
- Manual Review remains a separate focused workbench because it is a distinct exception-resolution task. It shows only actionable exception items and the relevant page/finding.
- V1 does not require assignment-to-user workflow; appropriate roles can open and resolve review items.
- Business Admin owns business terminology, identifier definitions (Primary/required/searchable/extractable/display/default order), confidence/review policy, sensitive-data policy, retention settings, and business operational rules.
- IT/Platform Admin owns storage/delivery providers, watched-folder connections, OCR/AI providers, retry/failover technical policy, integrations, identity/SSO, runtime, and technical health.

# Current Construction Direction — Updated 2026-08-30

The original transition note below is resolved: Units 16–24 are now approved. Unit 25 Production Identity / Platform Hardening is implemented in source and awaiting Kiro verification. Do not start the separate Final Security/Integration/V1 Acceptance phase until Unit 25 is reviewed and approved. The AI-first rule defined above remains non-negotiable and was explicitly verified in the approved Unit 16–24 baseline.


---

Product Overview
What LexaVault is about
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# Product Definition

LexaVault is an enterprise AI-first secure document-processing and permanent-redaction platform. Its core job is to accept document batches from multiple intake channels, validate required business identifiers, understand document content using AI, automatically identify and locate configured sensitive information, permanently redact it, and deliver the redacted documents to the customer's configured destination.

The platform carries the complexity. Customers should not need to understand OCR, coordinates, review-task IDs, storage manifests, detector implementation, or internal workflow mechanics.

# AI-First Product Principle

Every processed document goes through AI contextual analysis after content normalization. Native PDF pages use embedded text/layout where usable; scanned/image pages use OCR; mixed PDFs may use both page-by-page. AI is the primary finder of sensitive information. Deterministic recognizers/validators remain supporting evidence for structured categories such as SSN and credit-card numbers, but AI must still be capable of finding sensitive content when a deterministic detector misses it.

This keeps the product centered on document understanding rather than a collection of regular expressions. It also prevents the architecture from depending on deterministic validators for future semantic categories such as confidential pricing, internal strategy, proprietary sections, or other customer-defined sensitive concepts.

# Primary Capabilities

- Manual upload and scan intake with mandatory customer-configured identifiers
- API intake with required metadata and documents/document references
- Watched-folder intake with trigger/manifest completion signal and duplicate fingerprinting
- Page-aware digital text extraction and scanned/image OCR
- AI-first document/context understanding and sensitive-information discovery on every processed document
- Deterministic validation/evidence for structured data where useful
- Automatic location of sensitive content in text and image pages
- Permanent, irreversible redaction by a deterministic redaction engine
- Confidence/policy-driven straight-through processing
- Exception-focused manual review only when needed
- Pluggable export to local/on-prem filesystem, S3/object storage, or API/callback
- Temporary original/reprocessing lifecycle and automatic purge
- Structured operational reporting, audit, tenant isolation, and security
- Business Admin and IT/Platform Admin configuration

# V1 Sensitive-Data Baseline

V1 acceptance focuses on SSN, date of birth, credit score, and credit card number. All four are part of the AI analysis path. Deterministic evidence may strengthen structured findings but is not the exclusive discovery mechanism. Customer policy can expand in later versions without changing the core finding/policy/redaction pipeline.

# Product Positioning

The product should be positioned as an AI-powered automatic redaction platform, not as a workflow-heavy document vault or a regex redaction utility. The recommended customer operating model is straight-through processing when configured confidence is satisfied. Human review is a customer policy choice or an exception path, not the normal happy path.

The long-term vision includes broader semantic confidential-content discovery and richer policy/regulatory intelligence, but V1 uses explicit customer-configured policy categories and thresholds rather than claiming autonomous legal interpretation.


---

Problem Statement and Target Customers
Why this product exists
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-28

# Problem Statement

Organizations receive large volumes of PDFs containing sensitive personal and financial information. Manual identification and redaction are slow, inconsistent, difficult to audit, and expensive to scale. Existing workflows often force users to inspect every document even when most content can be handled automatically.

Customers need a platform that accepts documents from the channels they already use, identifies configured sensitive information in text or scanned pages, permanently removes it, and sends safe output to the destination they already operate. Humans should only be asked to resolve content that falls below confidence or requires explicit policy review.

# Target Customers

- Mortgage, real-estate, title, lending, and loan-processing operations
- Financial and back-office teams handling regulated customer document packages
- Insurance and compliance operations
- Legal/document-processing organizations
- Enterprises requiring on-prem or controlled-environment redaction with strong reporting and auditability

# Customer Trust Factors

- Straight-through processing with customer-controlled review policy
- Permanent and verifiable redaction
- Simple business terminology and minimalist UI
- Clear exception visibility rather than hidden failures
- Configurable business identifiers such as Contract Number, Loan Number, Member Number, or Patient Number
- Structured reporting and audit evidence
- Short-lived original-document handling rather than unnecessary long-term retention
- Tenant isolation, safe logging, and controlled technical administration


---

MVP V1 Scope and Capability Model
First product slice
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# V1 Scope

V1 should prove the AI-first automatic-redaction product end to end: intake, identifier validation, page-aware extraction/OCR, AI contextual discovery, supporting deterministic evidence, automatic location, permanent redaction, confidence/policy routing, exception-focused review, export, retention cleanup, reporting, security, and audit.

# V1 Capability Model

| Capability | V1 Intent |
| --- | --- |
| Intake | Manual/scan, API, and watched-folder patterns normalize into one pipeline. |
| Business Identity | Customer-configured mandatory/searchable identifiers link many intake batches to a long-lived business record. |
| Extraction/OCR | Use native PDF text/layout when usable; OCR scanned/unusable pages; normalize both into the same page/content model. |
| AI Contextual Discovery | Every processed document enters AI analysis. AI is the primary sensitive-information finder for all supported V1 categories. |
| Deterministic Evidence | Structured recognizers/validators may independently find or validate items and strengthen confidence; they do not gate AI and are not mandatory for every category. |
| Finding Merge | Normalize AI and deterministic evidence into one Finding model so agreement, disagreement, and independent discoveries can flow through one policy path. |
| Location | Determine where sensitive content appears from native layout/OCR coordinates without customer-entered coordinates. |
| Policy | Apply explicit customer-configured categories, confidence thresholds, and review mode. |
| Redaction | A deterministic redaction engine permanently and irreversibly removes accepted sensitive content while preserving filename and document boundaries. |
| Straight-Through Processing | Automatically export high-confidence output unless customer policy requires review/approval. |
| Manual Review | Show only problematic finding/page(s) when confidence/policy requires human action. |
| Technical Exceptions | Classify failures, selectively retry transient failures, optionally fail over providers, and expose unresolved issues to IT. |
| Delivery | Export through local/on-prem filesystem, S3/object storage, or API/callback. |
| Retention | Temporarily retain originals/redacted working copies for bounded recovery/reprocessing, then purge. |
| Reporting | Provide minimal V1 operational reporting on top of a reporting-ready relational data model. |
| Administration | Separate Business Admin and IT/Platform Admin responsibilities. |
| Security/Audit | Tenant isolation, authorization, correlation, safe logging, and structured audit records. |

# Explicit V1 Non-Goals

- Generic low-code workflow/form builder
- Full RAG/Q&A over documents
- Fine-tuned customer model training platform
- Autonomous legal interpretation of every jurisdiction
- Mandatory Kafka/external broker from day one
- Microservice decomposition
- Long-term original-document vault/retrieval product
- Visual-mask-only redaction
- Document merging/renaming as part of normal redaction
- Requiring deterministic validators for every sensitive category
- Asking AI to directly rewrite/modify the PDF instead of using the controlled redaction engine


---

V1 Requirements Summary
Functional requirements overview
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# Functional Requirements

| Area | Requirement |
| --- | --- |
| Business Record | Support a long-lived tenant-owned business record with one or more configured business identifiers such as Contract Number/Loan Number/Member Number/Patient Number. Exactly one identifier is Primary; additional identifiers may also be required/searchable/extractable/displayed. |
| Intake Batch | Each manual upload, scan, API submission, or watched-folder trigger creates a separate intake batch linked to the business record. |
| Mandatory Identifiers | Business Admin configures required fields. Intake must be rejected before processing if required identifiers are missing. |
| Manual/Scan Intake | User enters required identifiers and uploads/scans one or more PDF files. A multi-document scan received as one PDF is treated as one document. |
| API Intake | API supplies required identifiers and documents/document references in the submission contract. |
| Watched Folder | Process a batch only when the trigger/ready/manifest file appears. Validate trigger metadata, create fingerprint, prevent duplicate pickup, and support configurable Archive/Problem/leave handling. |
| Document Storage | Do not store PDF binaries in PostgreSQL. Use secure filesystem/object storage behind a provider abstraction. |
| Page-Aware Extraction/OCR | For each page, use usable native PDF text/layout when available; otherwise OCR the page. Mixed PDFs may use both paths. Normalize text, page references, and coordinates into one downstream content model. |
| AI Analysis | Every processed document must enter the AI/contextual-analysis stage after content normalization. AI is the primary sensitive-information discovery layer for all supported V1 categories, not merely a fallback behind deterministic detection. |
| Sensitive Detection | V1 acceptance must identify SSN, DOB, credit score, and credit card number with confidence/evidence. AI must be able to identify these from context even when deterministic logic does not produce the candidate first. |
| Deterministic Evidence | Structured detectors/validators may independently identify or validate findings (for example SSN structure or credit-card Luhn). They are secondary evidence, must not gate AI execution, and are not required for every category. |
| Finding Merge | AI findings and deterministic findings/evidence must normalize into one Finding/policy model. Agreement may strengthen confidence; disagreement or uncertainty may lower confidence or route the specific item to review. |
| Location | Locate detected sensitive information automatically using native PDF/OCR layout data. Normal users must not enter X/Y coordinates. AI may reference semantic spans/regions, but the platform should use authoritative extraction/OCR coordinates when available rather than asking AI to guess coordinates. |
| Policy | Business Admin configures categories, confidence thresholds, and whether high-confidence processing is automatic or review-required. |
| Redaction | Apply permanent irreversible redaction through a controlled redaction engine; underlying sensitive content must not remain recoverable. AI does not directly edit the PDF. |
| Low Confidence | Route only below-threshold finding(s)/relevant page(s) to Manual Review. |
| Technical Failure | Classify deterministic vs transient technical failures; selectively retry/backoff transient issues and optionally fail over providers. AI/OCR provider unavailability is a technical condition, not permission to silently mark an AI-required analysis as complete. |
| Manual Review | Reviewer sees only the actionable exception, corrects/confirms redaction visually, submits, and the automated pipeline resumes. No assignment workflow required for V1. |
| Export | Preserve original filenames and file boundaries. Export to local/on-prem folder, S3/object storage, or API/callback. |
| Original Retention | Start retention after successful export; default 24h, Business Admin configurable. Limit the same original batch lineage to three successful export cycles by default. |
| Never-Exported Hold | Default 72h technical-exception hold, explicitly extendable by Business Admin for customer SLA. |
| Temporary Redacted Copy | Default 24h after export, configurable up to a hard 72h maximum, then delete platform copy. |
| Reporting | Persist structured intake, processing, AI/provider, exception, export, retention, and disposition facts so built-in and external reporting can answer operational questions without parsing logs. Do not persist raw sensitive values in audit/reporting metadata. |
| Audit | Audit important intake, policy, processing, review, export, retention, purge, and security actions using safe metadata. |
| Admin | Separate Business Admin business-policy configuration from IT/Platform Admin technical configuration. |
| Dashboard | Provide one shared minimalist operational Dashboard across roles. Roles control visibility/actions, not separate dashboard layouts. Overview cards are collapsible/clickable; stage quick filters and configured-field search drive the working list. |
| Dashboard Columns | Default columns are Primary Business Identifier, Received, Stage, Documents, and Attention. Business Admin controls approved/default fields and order; users may show/hide/reorder approved fields, including additional business identifiers. |

# Boundary Requirements

- Domain remains pure Java/framework-free.
- Application owns use cases, orchestration, provider-neutral AI/detection contracts, evidence merge, customer policy evaluation, and lifecycle rules.
- Infrastructure owns provider SDKs, JPA, OCR/AI adapters, storage, watched-folder adapters, export adapters, and integration details.
- Workers own asynchronous processing and technical retry/failover execution without bypassing application rules.
- AI analysis is a first-class V1 detection stage; deterministic recognizers/validators are supporting components and cannot be the only path that decides what AI is allowed to see.
- AI/provider output may drive automatic redaction only within explicit configured policy and confidence thresholds.
- The finding model must support categories that have no deterministic validator so future semantic confidential-content redaction does not require a new pipeline.
- AI does not own tenant security, retention, deletion, delivery truth, audit truth, or the physical PDF redaction implementation.
- V1 does not claim autonomous interpretation of laws/regulations; policy profiles remain explicit and auditable.


---

Non-Functional Requirements
Quality attributes and operational expectations
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# Key Non-Functional Requirements

| Concern | Requirement |
| --- | --- |
| Simplicity | Prefer the smallest design that supports the AI-first automatic redaction pipeline; avoid exposing implementation mechanics to customers. |
| Maintainability | Clean module boundaries, generic domain naming, provider isolation through ports/adapters, and one normalized finding/evidence model across AI and deterministic sources. |
| Performance | Processing runs asynchronously; no long-running extraction/OCR/AI/redaction work on request threads. Native PDF extraction is preferred over unnecessary OCR. AI analysis may be chunked/page/section-aware rather than sending very large documents as one request. |
| AI Availability | AI contextual analysis is a first-class V1 processing stage. Transient provider failure is classified/retried/fails over when configured; unresolved AI unavailability becomes a visible technical exception rather than silent deterministic-only success. |
| Concurrency | Bounded worker concurrency; a document/intake batch cannot be processed twice concurrently. |
| Security | Tenant isolation, least privilege, encrypted storage, opaque internal object keys, no raw sensitive values in logs/events/audit, and provider requests constrained to approved deployment/data-handling policy. |
| Redaction Safety | Redaction must be irreversible and verifiable for both text and image-based content. AI must not directly own final PDF mutation. |
| Reliability | Idempotent stages where practical, selective retry only for transient failures, explicit technical exception states, recoverability, and provider failover when configured. |
| Detection Quality | AI and deterministic evidence must be observable enough to evaluate confidence and disagreement without persisting raw sensitive values. The architecture must allow AI-only semantic findings where no deterministic validator exists. |
| Storage | Document binaries live outside PostgreSQL. Temporary platform copies have bounded retention and automatic cleanup. |
| Reporting | Operational state and reason codes are structured in PostgreSQL for built-in/external reporting; no dependence on free-text log parsing. |
| Extensibility | Native extraction, OCR, AI, storage, delivery, watched folder, eventing, and identity remain behind stable interfaces. |
| Customer Experience | Modern minimalist UI, business terminology, straight-through happy path, and exception-only manual review. Customers never select native-vs-OCR paths or enter redaction coordinates. |
| Auditability | Important transitions/actions are append-oriented, correlation-aware, and safe for compliance evidence. AI provider/model/version and safe evidence metadata may be recorded where appropriate without raw PII/PCI. |


---

Target Architecture - High Level Design
Senior architecture direction
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# Architecture Choice

Start with a modular monolith using clean/hexagonal boundaries and asynchronous worker-ready processing. The main architectural goal is not service decomposition; it is a reliable AI-first automatic-redaction pipeline with pluggable intake, OCR/AI, storage, delivery, reporting, and security edges.

# Core End-to-End Flow

```text
Manual/Scan   API   Watched Folder
      \        |        /
       \       |       /
        Normalized Intake
              |
   Validate Required Identifiers
              |
      Business Record + Intake Batch
              |
         Processing Queue
              |
       Page Content Normalization
       /                    \
usable native text      scanned/unusable
+ PDF coordinates        page -> OCR
       \                    /
        Normalized text/layout
              |
      AI Contextual Analysis
       (runs for every document)
              |
  Deterministic Recognizers/Validators
   as independent supporting evidence
              |
     Merge Findings + Evidence
              |
      Resolve Exact Locations
              |
      Confidence + Policy Gate
        /                 \
 high confidence      low confidence
      |                   |
Permanent Redaction   Manual Review of
(controlled engine)   only problem item/page
      |                   |
      +--------- resume ---+
              |
      Verify Redaction
              |
      Export Destination
 local / S3 / API callback
              |
   Retention + Cleanup + Audit
```

# Responsibility Split

| Concern | Primary Responsibility |
| --- | --- |
| What text/content exists? | Native PDF extraction or OCR. |
| Where is the content? | Native PDF layout coordinates or OCR bounding boxes; vision-AI locations may be normalized later when useful. |
| What does the content mean? | AI contextual analysis. |
| Can structured evidence strengthen/contradict the classification? | Deterministic recognizers/validators. |
| Should the finding be redacted or reviewed? | Application policy + confidence/evidence rules. |
| Who modifies the PDF? | Deterministic permanent-redaction engine. |
| Who verifies irreversibility? | Redaction-verification component/application workflow. |

# Main Layers

| Layer | Responsibility |
| --- | --- |
| UI | Minimal dashboard, intake, exception review, Business Admin, IT Admin, operational reporting. |
| API | Thin request boundary, auth context, identifier validation, API contracts. |
| Application | Use cases, orchestration, normalized finding/evidence merge, customer policy, lifecycle, retry classification decisions, tenant enforcement. |
| Domain | Business record, intake batch, document/findings, states, policy/value objects, retention and lifecycle invariants. |
| Infrastructure | PostgreSQL adapters, storage/delivery adapters, watched-folder adapter, native extraction/OCR/AI/redaction providers, audit/outbox. |
| Worker | Async extraction/OCR/AI analysis/evidence merge/location/redaction/export execution, bounded concurrency, leases, transient retry/failover. |

# Storage Boundary

PostgreSQL is the metadata and operational system of record. Original/redacted PDFs are not stored as relational BLOBs. Secure filesystem/object storage holds temporary working artifacts; successful exported redacted documents live at the customer-configured destination.

# Human Boundary

Humans do not drive the normal workflow. Business users intervene only when customer policy requires review or specific content is below confidence. Technical exceptions are routed separately to IT/operations.

# AI Boundary

AI is a core discovery component, but it does not own physical PDF mutation, tenant policy truth, security decisions, or retention/audit truth. The architecture must remain provider-neutral and support local/on-prem inference as the preferred first deployment direction.


---

Core Automatic Redaction Pipeline
The V1 product engine
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# Objective

Prove one simple customer promise: give the platform a valid document batch and required identifiers; the platform understands the document using AI, automatically identifies and locates configured sensitive content, and produces permanently redacted documents at the configured destination unless a specific exception genuinely needs human/IT attention.

# V1 Detection Principle

AI is the primary sensitive-information finder for every processed document. Deterministic detectors/validators are parallel supporting evidence, not a prerequisite for AI analysis.

The intended merge behavior is:

```text
AI finding only             -> valid candidate
Deterministic finding only  -> valid candidate/evidence
AI + deterministic agree    -> stronger confidence/evidence
AI + deterministic disagree -> confidence/policy handles uncertainty
```

A deterministic miss must not prevent an AI finding. A deterministic validator is optional for categories where no reliable structural rule exists.

# Page Content Normalization

Do not OCR every PDF. Process each page according to the best available source:

```text
PDF page
  |
  +-- usable embedded text/layout? -- yes --> native text + coordinates
  |
  +-- no / scanned / unusable ---------> OCR text + bounding boxes + OCR confidence

Both paths -> normalized page content -> AI + supporting evidence
```

A mixed PDF can therefore use native extraction on some pages and OCR on others without changing the customer workflow.

# Happy Path

1. Accept intake and validate mandatory business identifiers.
2. Link/create the BusinessRecord and create a new IntakeBatch.
3. Persist file metadata and secure working-storage references.
4. Normalize page content: use native PDF text/layout where usable; OCR scanned/unusable pages.
5. Run AI contextual analysis on every processed document to identify configured sensitive information and semantic context.
6. Run deterministic recognizers/validators independently where useful for structured categories and merge their evidence with AI results.
7. Resolve each accepted candidate to exact page geometry using native extraction/OCR coordinates. AI is not asked to guess X/Y when authoritative coordinates exist.
8. Evaluate merged confidence/evidence and Business Admin policy.
9. Permanently redact all accepted findings through the controlled redaction engine.
10. Verify the sensitive content is not recoverable from the produced PDF.
11. Export each document under its original filename to the configured destination.
12. Record delivery checksum/status/timestamp and start the post-export retention clock.
13. Audit important transitions using safe metadata without storing raw sensitive values.

# Responsibility Boundary

```text
AI                    -> WHAT is sensitive / what the content means
Native PDF / OCR      -> WHERE the text/region is on the page
Deterministic rules   -> structured validation/supporting evidence
Policy                -> REDACT or REVIEW
Redaction engine      -> permanently modifies the PDF
Verification          -> proves the result is not recoverable
```

Future vision models may return useful regions directly, but those regions still normalize into the same location model and the controlled redaction engine remains responsible for physical document modification.

# Content Exception Path

If one finding falls below the configured threshold, only that finding/relevant page enters Manual Review. Already acceptable findings are not re-reviewed. Reviewer adjusts/confirms the visual redaction and submits; the pipeline resumes automatically.

# Technical Exception Path

Deterministic failure does not burn resources on repeated identical attempts. Transient AI/OCR/storage/delivery failures may back off/retry or fail over to a configured secondary provider. If the required AI analysis cannot complete, the system must not silently treat deterministic-only processing as equivalent to the intended AI-first V1 happy path. Unresolved technical exceptions remain visible to IT/operations and reporting with an age/purge deadline.

# File Integrity

The platform preserves the customer's file boundaries and filenames. It does not merge files or add `-redacted` suffixes.

# Future Semantic Expansion

The same Finding/policy/redaction path should later accept AI-discovered semantic categories such as confidential pricing, company secrets, internal strategy, proprietary sections, or tenant-defined sensitive concepts. Those categories may have no deterministic validator, which is why validators cannot be mandatory gates in the V1 architecture.


---

Low-Level Design
Implementation-level design boundaries
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# Backend Modules

Current repository modules remain the starting point:

- securedoc-domain
- securedoc-application
- securedoc-api
- securedoc-infrastructure
- securedoc-worker
- securedoc-ai
- securedoc-agent
- securedoc-redaction
- securedoc-test-support

The project codename may change; durable code should keep generic platform naming.

# Core Domain Concepts Going Forward

- BusinessRecord - long-lived customer business identity
- BusinessIdentifierDefinition - tenant-configured identifier metadata
- BusinessIdentifierValue / BusinessAttribute - values attached to a BusinessRecord or IntakeBatch as appropriate
- IntakeBatch - one arrival/submission event
- IntakeFile/Document - customer file within a batch
- Finding - detected sensitive item with category/confidence/location/status and safe evidence metadata
- ProcessingAttempt - structured stage/outcome/reason/attempt metadata
- DeliveryRecord - export destination/outcome/checksum/timestamp
- RetentionState - original/redacted temporary lifecycle deadlines
- AuditRecord - safe append-oriented activity metadata

Do not multiply tables/classes for every minor status. Prefer structured enums/reason codes and simple relations.

# Content-Normalization Contract

The detection side needs one provider-neutral logical input representing document/page content regardless of source. Exact class names are implementation choices, but the contract must be able to carry:

- page number/order
- normalized text/tokens or spans
- native PDF coordinates or OCR bounding boxes
- extraction source (`PDF_TEXT`, `OCR`, later vision if applicable)
- OCR confidence when applicable
- enough references to map a semantic finding back to exact redaction geometry

Digital and OCR paths must converge before AI/policy logic so downstream detection does not fork into two separate products.

# AI-First Detection Contract

- The AI/contextual analyzer runs for every processed document after content normalization.
- It returns provider-neutral candidate findings/evidence: category, confidence, semantic span/reference, optional reasoning code/label, and location reference where available.
- AI must not depend on a deterministic detector having already created the candidate.
- Deterministic recognizers/validators run independently where useful and may discover structured candidates or add supporting/contradictory evidence.
- The application merges AI and deterministic evidence into the same Finding model before customer policy decides automatic redaction versus review.
- No application rule may require every Finding type to possess a deterministic validator.

Exact class/port names are intentionally not frozen until Unit 16 code review. The architecture intent is more important than prematurely forcing a type name into the current codebase.

# Redaction Contract

The AI layer does not directly modify PDFs. The application passes approved findings and authoritative geometry to the redaction port/engine. The redaction implementation must permanently remove/burn the sensitive content, and a verification step must confirm the output does not expose the source text/content.

# Dependency Direction

- Domain depends on no framework, JPA, provider SDK, filesystem, or network API.
- Application depends on domain plus provider-neutral extraction/OCR/AI/redaction/storage/delivery contracts.
- Infrastructure implements native extraction, OCR/AI, storage, delivery, watched-folder, persistence, and audit adapters.
- API translates external input into validated application commands.
- Worker executes application use cases and never mutates persistence directly.

# Provider Isolation

External OCR engines, AI models, storage providers, redaction engines, watched-folder implementations, delivery destinations, identity systems, and future event brokers must remain behind ports/adapters. Provider SDK objects/prompts/responses must not become domain models.

# Data Persistence Rule

Document binaries do not belong in PostgreSQL. Store only metadata, safe searchable business attributes, file/object references, page/order manifests if required, structured processing/provider facts, finding metadata that does not expose raw sensitive values, reporting facts, and audit data.


---

Module Boundaries
What belongs where
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# Boundary Matrix

| Module | Owns | Must Not Own |
| --- | --- | --- |
| domain | BusinessRecord/IntakeBatch/Document entities, findings, evidence/value objects as needed, lifecycle/state invariants, policy primitives, domain events. | Spring, JPA, SDKs, repositories, threads, provider types. |
| application | Use cases, commands/results, provider-neutral ports, AI/deterministic finding merge, tenant policy, confidence routing, retention/delivery decisions. | JPA, controllers, direct SDKs, filesystem details, provider-specific prompt/response types. |
| api | REST/API DTOs, request validation, auth context mapping, role enforcement. | Core business rules, persistence, OCR/AI/redaction work. |
| infrastructure | JPA, storage, audit, outbox, watched-folder adapter, native extraction/OCR/AI/redaction provider adapters, delivery adapters. | Product decisions beyond translating/performing requested operations. |
| worker | Background extraction/OCR/AI/evidence-merge/location/redaction/export stage execution, leases, bounded concurrency, transient retry/backoff and configured failover. | Direct DB mutation outside application use cases. |
| ai/agent | Provider-neutral contextual sensitive-content analysis contracts/adapters and model-specific integration support. | Tenant authorization, retention/deletion truth, audit truth, physical PDF mutation, autonomous legal interpretation. |
| redaction | Permanent redaction and verification contracts/implementation. | Customer policy decisions, AI classification, authorization, long-term storage ownership. |

# Important AI Boundary

AI contextual analysis is the primary sensitive-information discovery stage for every processed document. Deterministic recognizers/validators run independently as supporting evidence and do not gate AI execution. Application policy may automatically approve high-confidence findings, but AI is not given authority over tenant security, retention, delivery truth, legal truth, or audit truth.

The physical PDF modification remains in the redaction module. Native extraction/OCR supplies authoritative geometry when available; AI supplies semantic interpretation. This separation allows future AI-only semantic categories without making PDF mutation provider-specific.


---

Worker, Concurrency, and Outbox Design
Heavy-load processing strategy
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-28

# Processing Model

Intake requests should validate/record the submission and return quickly. OCR, AI/detection, location, redaction, verification, and export run asynchronously through worker-ready application use cases.

# Concurrency Rules

- Domain objects do not manage threads.
- Application/worker/infrastructure own concurrency.
- One document/intake stage must not be processed concurrently by multiple workers.
- Work claiming must be idempotent and tenant-safe.
- Worker pools are bounded so OCR, AI, redaction, and export cannot overwhelm CPU/memory/provider quotas.
- Successful stages are not repeated merely because another item in the batch needs review.

# Failure Classification

Do not use blanket N-times retries.

| Failure Type | Default Handling |
| --- | --- |
| Low confidence content | Business Manual Review; no technical retry. |
| Missing required identifier | Reject intake; no retry. |
| Corrupt/unsupported document | Technical exception/reject; no blind retry. |
| OCR genuinely unreadable | Technical exception; no blind retry. |
| Provider timeout / temporary network issue | Delayed retry/backoff. |
| 429 / temporary 503 | Delayed retry/backoff respecting provider guidance. |
| Secondary provider configured | Optional failover according to IT policy. |
| Exhausted transient recovery | Technical Exception queue/reporting. |

# Locking/Lease Strategy

Use status/version plus a processing claim/lease so a worker can safely own a stage. If a worker dies, a bounded lease may expire and work can be recovered without duplicate successful output.

# Outbox Strategy

When state change and event emission must be atomic, persist an outbox record in the same database transaction. External Kafka/SQS/broker delivery remains a later adapter, not a V1 dependency.


---

Deployment Architecture
Local, demo, and enterprise deployment modes
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-28

# Deployment Modes

| Mode | Shape |
| --- | --- |
| Local Developer | API + worker locally, PostgreSQL, secure local working storage, local output folder, local/mock OCR/AI as available. |
| On-Prem / Small Customer | API + worker deployment, PostgreSQL, secure filesystem or object storage, configured watched folders, local or external OCR/AI, local/S3/API delivery. |
| Enterprise Scale | Separately scalable API/worker deployment if needed, PostgreSQL, object storage, configured providers, outbox/broker adapter when justified, centralized telemetry, enterprise identity. |

# Storage and Delivery

- Working/original/redacted temporary artifacts use configured secure storage.
- Successful final redacted output is exported to local/on-prem filesystem, S3/object storage, or API/callback.
- Platform temporary redacted copy is deleted after 24h by default and never retained beyond 72h.
- Original lifecycle follows the configured post-export retention policy and reprocess/export cycle limits.

# Watched-Folder Ownership

Customer watched locations may be customer-owned. IT configuration defines whether the connector can move to Archive/Problem, copy/acknowledge, or leave source files untouched. Fingerprinting prevents duplicate intake regardless of source-file ownership.

# Guiding Rule

Do not require Kafka, cloud object storage, or external AI/OCR providers for local development. Keep technical integrations pluggable and customer-specific.


---

Architecture Decision Records - Current V1 Decisions
Decisions to preserve project reasoning
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# ADR 001 - Modular Monolith First

Decision: Start with a modular monolith using clean/hexagonal boundaries. Split workers/services only when operational scaling requires it.

# ADR 002 - Java/Spring Boot Core

Decision: Keep the control plane, domain, application, API, infrastructure, and worker in Java/Spring Boot. IDP/framework reuse is evaluated at edge/platform concerns, not forced into product domain logic.

# ADR 003 - PostgreSQL for Metadata and Operational Truth

Decision: PostgreSQL stores tenant metadata, business identifiers/attributes, intake state, findings, processing attempts, delivery records, reason codes, audit/outbox, and lifecycle deadlines. It does not store PDF binaries.

# ADR 004 - Pluggable Document Storage and Delivery

Decision: Working document binaries use secure filesystem/object storage adapters. Final redacted delivery supports local/on-prem filesystem, S3/object storage, and API/callback. Preserve customer filenames and document boundaries.

# ADR 005 - Automatic Redaction is the Product Core

Decision: V1 prioritizes automatic detection, location, permanent redaction, verification, and export. High-confidence happy-path processing should be straight-through unless Business Admin policy requires review/approval.

# ADR 006 - Permanent Redaction Only

Decision: Redaction must be irreversible. Visual masking that leaves underlying sensitive content recoverable is not supported.

# ADR 007 - Exception-Focused Human Review

Decision: Low-confidence content routes only the problematic finding/page to Manual Review. V1 does not expose task assignment, finding IDs, or manual X/Y coordinate entry to business users.

# ADR 008 - Smart Technical Retry

Decision: Classify failures before retry. Retry/backoff only transient failures; deterministic failures go directly to rejection/technical exception. Optional provider failover is IT-configurable.

# ADR 009 - Business Record Separate from Intake Batch

Decision: Customer business identifier identifies a long-lived BusinessRecord. Each upload/API/scan/trigger event creates a separate IntakeBatch. Do not treat a contract/loan/member record as one permanently open package.

# ADR 010 - Mandatory Identifier Validation at Intake

Decision: Business Admin defines required identifiers. Missing required values reject intake before document processing and create structured rejection/reporting facts.

# ADR 011 - Trigger-Gated Watched Folder with Fingerprint

Decision: Watched-folder files are considered complete only when the trigger/manifest appears. A deterministic fingerprint protects against duplicate ingestion. Archive/Problem/leave behavior is environment-specific.

# ADR 012 - Short-Lived Original and Working Artifacts

Decision: Originals are temporary recovery/reprocessing artifacts. Post-export original retention defaults to 24h and is Business Admin configurable. Same original batch lineage allows at most three successful export cycles by default. Temporary redacted platform copies default to 24h and have a hard 72h maximum.

# ADR 013 - No Original Retrieval Product Feature

Decision: LexaVault does not provide long-term original-document retrieval. Existing original-access implementation from earlier units is legacy relative to the current V1 direction and should be simplified/retired when touched.

# ADR 014 - Reporting-Ready Data Model

Decision: Persist structured statuses, timestamps, counts, locations, reason codes, attempts, and outcomes so customers can use built-in reporting or external BI/SQL without parsing application logs.

# ADR 015 - Separate Business Admin and IT/Platform Admin

Decision: Business configuration (terminology, identifiers, confidence/review, retention) is separate from technical configuration (providers, storage, watched folders, delivery, identity, retry/failover, health).

# ADR 016 - Outbox Before Mandatory Broker

Decision: Preserve outbox/event abstractions without requiring Kafka/SQS for V1.

# ADR 017 - Tenant Isolation Everywhere

Decision: Tenant context is mandatory on every tenant-owned record, operation, event, storage reference, job, finding, delivery, and audit record.

# ADR 018 - Generic Durable Naming

Decision: LexaVault remains a codename. Durable reusable code concepts should remain generic to avoid market-brand lock-in.

# ADR 019 - AI-First Sensitive-Information Discovery

Decision: Every processed document enters AI contextual analysis after native PDF/OCR content normalization. AI is the primary sensitive-information finder. Deterministic detectors/validators are parallel supporting evidence for structured data and must not gate AI execution or be required for every category. AI identifies meaning; native extraction/OCR supplies authoritative geometry; application policy decides REDACT/REVIEW; the deterministic redaction engine performs and verifies permanent modification.


---

ADR 019 - AI-First Sensitive-Information Discovery
Status: Accepted
Date: 2026-08-29

# Context

The V1 product direction is an AI-powered automatic redaction platform. Earlier implementation planning could be read as deterministic detectors being primary, with AI only assisting ambiguous categories. The product intent is stronger: AI should analyze every processed document and be able to discover sensitive information even when a deterministic detector misses it.

# Decision

- AI contextual analysis is the primary discovery stage for every processed document after native PDF/OCR normalization.
- Deterministic recognizers/validators run as independent supporting evidence for structured categories and do not gate AI.
- AI and deterministic results merge into one Finding/evidence model.
- Categories with no deterministic validator remain first-class.
- Native extraction/OCR normally supplies authoritative geometry; AI supplies semantic identification.
- Application policy decides REDACT/REVIEW.
- The controlled redaction engine performs permanent modification and verification; AI does not directly rewrite the PDF.

# Consequences

Positive:
- The product is genuinely AI-driven rather than a regex engine with AI fallback.
- Contextual cases such as credit score are handled naturally.
- Future semantic categories (company secrets, confidential sections, proprietary content) fit the same pipeline.
- Structured validators remain useful for precision and confidence without constraining product scope.

Tradeoffs:
- AI provider availability, latency, model quality, and failover become first-class operational concerns.
- Evidence/confidence merge rules need explicit design and testing.
- Unit 16 source must be reviewed to ensure implementation matches this decision.

# Unit 16 Transition

Kiro was already implementing Unit 16 when this ADR was accepted. Do not interrupt the current run. Review the returned code and, if necessary, correct Unit 16 before approval. Do not advance to Unit 17 until this architecture is represented correctly.


---

API and Data Model Plan
API contracts and system-of-record planning
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-28

# Core API/Use-Case Direction

The public/product API should use business concepts and hide internal workflow mechanics.

| Use Case | Purpose |
| --- | --- |
| Create/ResolveBusinessRecord | Resolve the tenant-owned long-lived business identity from configured identifiers. |
| CreateIntakeBatch | Create a new submission event under a business record. |
| Upload/AttachDocument | Add one or more PDFs to the intake batch. |
| SubmitManualOrScanIntake | Validate mandatory identifiers and submit the batch. |
| SubmitApiIntake | Accept identifiers + documents/document references from an API caller. |
| WatchedFolderIngest | Trigger-gated internal intake adapter, not a business-user REST workflow. |
| GetOperationalStatus | Return business identifier, received date, stage, documents, exception state, export status. |
| ReviewException | Retrieve only actionable low-confidence finding/page(s). |
| SubmitReviewCorrection | Confirm/adjust the problem redaction and resume pipeline. |
| QueryOperationalReport | Minimal V1 reporting endpoints over structured operational facts. |

Internal UUIDs remain necessary for persistence and API relationships but should not be the primary business-facing identity.

# Data Ownership

- PostgreSQL owns BusinessRecord/identifier metadata, IntakeBatch/Document metadata, processing state, findings, processing attempts, delivery/export records, retention deadlines, structured reason codes, audit/outbox, and processing claim/lease state.
- Secure filesystem/object storage owns temporary original/working/redacted binary artifacts.
- Customer-configured destination owns the durable exported redacted documents.
- TenantId is mandatory on every tenant-owned table/event/reference.

# Identifier Modeling

Use a flexible but minimal definition/value model so a tenant can configure fields such as Contract Number, Loan Number, Member Number, Patient Number, First Name, Last Name, or Company Name without schema changes.

Definition properties may include:
- key
- customer-visible label
- required at intake
- searchable
- display priority
- system-extractable
- uniqueness scope where configured

Do not build a generic low-code metadata engine.


---

Processing State Model
Processing states and transition discipline
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-28

# Design Principle

State names must reflect what a customer/operator can actually do next. A processing failure must not masquerade as `PENDING_REVIEW` if a human reviewer cannot resolve it.

# Logical Processing Flow

```text
INTAKE_DISCOVERED
  -> INTAKE_ACCEPTED / INTAKE_REJECTED / INTAKE_INCOMPLETE
  -> QUEUED
  -> EXTRACTING_OR_OCR
  -> DETECTING_AND_LOCATING
  -> POLICY_EVALUATION
      -> AUTO_REDACTION
      -> MANUAL_REVIEW_REQUIRED
      -> TECHNICAL_EXCEPTION
  -> REDACTION_VERIFYING
  -> READY_FOR_EXPORT
  -> EXPORTING
  -> EXPORTED
  -> RETENTION_WINDOW
  -> PURGED (temporary platform artifacts)
```

Exact enum names may reuse existing code where practical; do not create needless state proliferation. The important semantics are:

- `MANUAL_REVIEW_REQUIRED` means a business/content exception can be resolved by a reviewer.
- `TECHNICAL_EXCEPTION` means IT/operations attention or technical recovery is needed.
- `FAILED/REJECTED` is visible and structured, never silently treated as complete.
- High-confidence items should not wait for human review unless Business Admin policy requires it.

# Rules

- Every transition is legal, tenant-scoped, and auditable.
- Workers claim a stage before execution.
- Low-confidence findings do not force reprocessing of already successful findings.
- Transient technical retry/failover is bounded and classified.
- No work remains stuck without a current stage, timestamp, reason, and reporting visibility.
- Intake batch completion is distinct from the long-lived BusinessRecord; later documents can arrive in later batches under the same business identifier.


---

Audit and Outbox Event Model
Traceability and reliable event publishing
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-28

# Audit Event Fields

- eventId
- tenantId
- actorId or system actor
- correlationId
- businessRecordId / intakeBatchId / documentId / findingId when applicable
- action/eventType
- outcome
- occurredAt
- safe structured metadata only

# Operational Facts vs Audit

Operational reporting should not depend on parsing audit detail. Intake status, processing stage, retry/failover count, failure/rejection reason code, export status, retention deadlines, and current location are first-class structured data. Audit records answer who/what/when; operational tables answer current/history metrics and reporting questions.

# Outbox Event Fields

- outboxId
- eventType
- tenantId
- aggregateType
- aggregateId
- payloadVersion
- safe payloadJson
- createdAt
- publishedAt
- status
- retryCount
- safe lastErrorCategory

# Important Rule

Audit/outbox payloads use IDs, reason codes, counts, statuses, and safe metadata. Never persist raw document/OCR text, detected sensitive values, credentials, raw exception messages, full prompts, or customer file paths unless a separately approved safe location identifier is required.


---

Intake, Storage, and Reporting Data Model
Simple relational model for operations and external reporting
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-28

# Goal

Keep the relational model understandable to an IT/reporting person while supporting the full V1 lifecycle. Avoid storing binaries or creating a separate table for every small event.

# Recommended Core Relations

| Concept | Purpose |
| --- | --- |
| tenant | Tenant/customer boundary. |
| tenant_business_setting / identifier_definition | Customer-configured labels and identifier rules. |
| business_record | Long-lived business entity linked by configured identifiers. |
| business_attribute / identifier_value | Searchable required/optional/extracted metadata. |
| intake_batch | One manual/scan/API/watched-folder submission event; channel, fingerprint, status, source, trigger state, counts, timestamps. |
| document | One received file; original filename, MIME, size, pages, working-storage reference, stage/status. |
| finding | Sensitive category, confidence, location/evidence references, review status. Never raw sensitive value unless explicitly required and protected; prefer hashes/masked evidence. |
| processing_attempt | Stage, attempt number, start/end, outcome, reason code, provider/failover metadata. |
| delivery_record | Destination type/reference, checksum, export attempt/outcome, successful timestamp. |
| retention_state | First/last export, successful export cycle count, original delete deadline, temp-redacted delete deadline, exception hold deadline. |
| audit_record | Append-oriented safe action history. |

# Watched-Folder Reporting Fields

A watched-folder batch should be reportable with structured fields such as:
- firstSeenAt
- triggerSeenAt / triggerStatus
- sourceLocationReference
- postIngestDisposition (ARCHIVED / PROBLEM / LEFT_IN_PLACE / COPIED)
- fingerprint
- fileCount
- identifierValidationOutcome
- reasonCode
- acceptedAt / rejectedAt
- currentAge

A folder with files but no trigger must remain unprocessed. After a configured observation threshold it becomes an `INCOMPLETE/WAITING_FOR_TRIGGER` operational record so users can locate and fix it.

# Example Reason Codes

- MISSING_REQUIRED_IDENTIFIER
- INVALID_TRIGGER
- WAITING_FOR_TRIGGER
- DUPLICATE_INTAKE
- UNSUPPORTED_FILE
- CORRUPT_DOCUMENT
- OCR_UNREADABLE
- LOW_CONFIDENCE_REVIEW
- PROVIDER_TIMEOUT
- PROVIDER_RATE_LIMITED
- STORAGE_TEMPORARY_FAILURE
- EXPORT_FAILED
- RETENTION_PURGED

# Reporting Principle

V1 screens can stay minimal, but SQL/BI users must be able to answer counts, aging, outcomes, failure reasons, export status, and retention deadlines from structured columns/views without log scraping.


---

AI Governance and Redaction Model
AI-first discovery inside explicit customer policy
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# Core Governance Principle

AI contextual understanding is a primary V1 sensitive-information discovery stage. Every processed document enters AI analysis after native PDF extraction or OCR normalization. Deterministic recognizers/validators may add independent findings or strengthen/contradict evidence, but they do not gate AI and are not required for every sensitive category.

The application policy boundary remains authoritative for tenant security, enabled categories, confidence/review mode, retention/deletion, delivery rules, and audit truth.

# Why AI Is Primary

The product must understand meaning, not merely pattern shapes. For example, the number `720` may be a credit score in one context and an invoice number in another. AI can use labels, nearby text, document section, relationships, and semantic context to classify the value. Structured validation may then strengthen a finding where a reliable rule exists.

The architecture must also be capable of later detecting customer-defined semantic sensitive content such as confidential pricing, acquisition strategy, proprietary processes, internal plans, or other company secrets. Those concepts may have no deterministic validator at all.

# V1 Processing Responsibilities

| Component | Responsibility |
| --- | --- |
| Native PDF extraction | Extract usable embedded text and exact layout/character coordinates. |
| OCR | Recover text + bounding boxes + OCR confidence from scanned/image/unusable pages. |
| AI contextual analyzer | Identify what content means and produce sensitive candidate findings for every processed document. |
| Deterministic recognizers/validators | Independently discover/validate structured values where useful; provide supporting or contradictory evidence. |
| Application policy | Merge evidence, apply tenant categories/thresholds/review mode, decide REDACT vs REVIEW. |
| Redaction engine | Permanently modify the PDF using authoritative geometry. |
| Verification | Confirm sensitive content is not recoverable/searchable/copyable/extractable after redaction. |

# V1 Sensitive-Data Acceptance Baseline

- SSN
- Date of Birth
- Credit Score
- Credit Card Number

All four participate in the AI analysis path. Deterministic logic may validate structured formats, but V1 acceptance is not defined as “regex found all four.”

# Finding/Evidence Merge

Conceptually:

```text
AI finding only             -> candidate remains valid
Deterministic finding only  -> candidate remains valid
Both agree                  -> confidence/evidence strengthened
They disagree               -> policy/confidence may reduce certainty or require focused review
```

Exact confidence-combination mathematics are not frozen yet; they should be explicit, testable, and tenant-policy-compatible rather than hidden inside a provider-specific response.

# Confidence Handling

- Above configured threshold: automatically redact unless customer policy requires review-all/final approval.
- Below threshold: send only the problematic finding/relevant page to Manual Review.
- Low confidence is not a reason to blindly retry AI/OCR.
- AI/OCR provider failure is a technical failure; it may use bounded retry/failover when transient.
- The intended AI-first happy path should not silently downgrade to deterministic-only processing when required AI analysis never completed.

# AI Output Metadata

Where useful and safe:
- provider/model
- model/provider version
- policy/profile version
- confidence
- semantic category
- page/location reference
- evidence type/source without exposing raw sensitive content
- correlation ID
- review-required flag

Do not persist prompts/responses containing raw customer-sensitive values into ordinary audit/reporting records.

# Location Boundary

AI identifies the semantic target. Native PDF extraction/OCR normally provides authoritative text geometry. AI should not be asked to invent exact X/Y coordinates when those coordinates already exist. If a future vision model returns a region/bounding box, normalize it into the same location model and validate it before redaction.

# Redaction Boundary

AI does not directly rewrite, draw on, or regenerate the final PDF. The redaction engine must perform permanent removal/burn-in so underlying sensitive content cannot be recovered. Redaction verification is required before export.

# Policy Boundary

AI may identify a candidate as sensitive, but tenant-configured policy decides whether that category is enabled and whether the confidence/evidence is sufficient for automatic redaction. AI does not invent tenant security, retention, legal obligations, or audit truth.

# Future Semantic Content

The same AI -> Finding -> Policy -> Redaction path should support future categories that cannot be described by a deterministic pattern, including customer-specific confidential sections, company secrets, pricing, strategy, proprietary processes, or other semantic concepts. This future compatibility is a V1 architectural requirement even if those categories are not part of V1 acceptance testing.


---

Provider Abstraction Model
OCR, AI, storage, redaction, intake, and delivery isolation
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# Provider Ports

The exact code names may evolve during Unit 16 review, but the architecture needs stable responsibilities equivalent to:

- NativePdfContentPort / existing PDF extraction abstraction where appropriate
- OcrPort
- AiDetectionPort / ContextualSensitiveContentPort - first-class V1 AI analysis contract
- ContentLocationPort where location is separate from extraction/OCR
- StoragePort
- RedactionPort
- RedactionVerificationPort
- DeliveryPort
- WatchedFolderPort / IntakeSourcePort
- EventPublisher
- Identity integration at the API/platform edge

Embedding/RAG ports are later scope if the product adds search/Q&A.

# AI Contract Rule

The AI contract must accept provider-neutral normalized document/page content and return provider-neutral candidate findings/evidence. It must not require a deterministic detector to have already discovered the item.

Useful normalized output may include category, confidence, semantic span/reference, page, optional region reference, safe reason/evidence type, model/provider metadata, and review hint. Provider prompts/raw responses remain adapter details.

# Deterministic Validator Rule

Deterministic recognizers/validators are supporting components for categories where structural validation is meaningful. They may run in-process or behind a port if provider-backed. Do not create an architectural requirement that every finding type must implement a validator. AI-only semantic findings must remain representable.

# Adapter Rule

Vendor SDK types must not leak into domain/application models. Infrastructure adapters translate provider-specific request/response formats into stable platform DTOs.

# Local/On-Prem Direction

The preferred first AI deployment direction is local/on-prem-capable inference. The application should not be coupled to a specific cloud provider. Exact model/runtime choice is deliberately left for implementation review/validation rather than hardcoded into the domain.

# Failure/Failover Rule

Adapters must classify provider failures into stable categories (transient timeout/rate limit/unavailable vs deterministic unsupported/unreadable). IT/Platform Admin may configure a secondary provider. Application/worker orchestration decides bounded retry/failover; provider SDK exception strings are never product/audit truth.

If the configured AI analysis is required for the V1 path and all AI providers are unavailable, that is a technical exception. The system must not silently claim equivalent AI-first processing based solely on deterministic detection.


---

AI-First Sensitive Content Discovery Decision
V1 architecture clarification
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# Decision

LexaVault V1 uses AI contextual analysis as the primary sensitive-information discovery layer for every processed document. Deterministic detectors and validators are secondary/parallel evidence and must never be a prerequisite for AI discovery.

# Processing Model

```text
PDF
  |
  +-- native text page -> text + layout coordinates
  |
  +-- scanned/unusable page -> OCR -> text + bounding boxes
  |
  v
Normalized document/page content
  |
  v
AI contextual analysis (always for processed document)
  |
  +---- deterministic recognizers/validators in parallel where useful
  |
  v
Merged Finding + Evidence
  |
  v
Tenant policy + confidence
  |
  +-- REDACT -> controlled permanent-redaction engine -> verification -> export
  |
  +-- REVIEW -> focused item/page review -> resume pipeline
```

# Rationale

A pattern alone often does not establish meaning. `720` can be a credit score, invoice number, room number, or unrelated value. AI uses surrounding labels, sections, relationships, and document context to understand meaning. Deterministic validation remains useful for structured values such as SSNs or credit-card numbers, but it should strengthen or challenge the AI result rather than define the product's discovery boundary.

# Required Behaviors

- AI can identify a sensitive item even when deterministic logic misses it.
- Deterministic logic can independently surface a candidate even when AI misses it; the candidate still flows into the same policy/evidence merge.
- Agreement increases evidence strength.
- Disagreement/uncertainty is handled by explicit confidence/policy and focused review, not hidden provider behavior.
- Categories without deterministic validators are fully supported by the Finding model.
- AI does not directly modify PDFs.
- Native extraction/OCR supplies exact geometry when available; AI supplies semantic identification.
- The permanent redaction engine is deterministic/controlled and must be independently verifiable.

# V1 Scope vs Future Scope

V1 acceptance categories remain SSN, Date of Birth, Credit Score, and Credit Card Number. However, the architecture must already support future semantic categories such as confidential pricing, company secrets, internal strategy, proprietary procedures, or customer-defined sensitive sections without requiring a new detection/redaction pipeline.

# Unit 16 Handling — Historical Transition Note (Resolved)

When this clarification was frozen, Unit 16 was already in progress. That transition has now been completed: Unit 16 was reviewed/corrected so AI contextual analysis is in the mandatory primary discovery path, and Units 16–24 are approved. Preserve that accepted architecture; do not regress to deterministic-first/fallback-only AI.


---

Security, Audit, and Tenant Model
Security-first product controls
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-28

# Tenant Isolation

- TenantId is required for business records, identifier definitions/values, intake batches, documents, findings, processing attempts, delivery records, retention records, audit records, events, and jobs.
- User-facing repository access is tenant-scoped.
- Storage prefixes/object keys provide tenant separation but must also use real authorization/encryption controls.
- Workers carry tenant context throughout processing.

# Access Control

- Local/demo mode may use demo identities/roles; production identity will use trusted OIDC/JWT/enterprise identity.
- Authorization remains product logic and enforces role + tenant context.
- Business Admin and IT/Platform Admin are distinct personas; final production role names may be formalized during identity hardening.
- Manual Review is available only to authorized review roles.
- LexaVault does not provide a long-term original-document retrieval workflow. Existing original-access implementation from earlier units is legacy relative to the current V1 target.

# Document Security

- PDF binaries are not stored in PostgreSQL.
- Working storage uses encryption/access control and opaque internal object names.
- Splitting documents/pages may be used for processing/storage implementation but is not a substitute for encryption and authorization.
- Permanent redaction must remove underlying sensitive content, not merely cover it visually.

# Retention Security

- Originals are short-lived and deleted after the configured post-export retention window/lifecycle.
- Temporary platform redacted copies are deleted after 24h by default and never retained beyond 72h.
- Never-exported technical exceptions have a 72h default hold that can be explicitly extended by Business Admin for customer SLA; extensions and purge deadlines are visible/auditable.
- Purge actions are auditable but never retain deleted content.

# Audit Rules

- Important intake, processing, review, export, retention, purge, admin, and security actions create audit records.
- Correlation IDs cross API/worker/audit/event flows.
- Audit uses safe metadata and reason codes; never raw PII/OCR text, raw exception messages, credentials, or full document paths.
- Tamper-evident hash chaining remains a later enhancement if compliance requirements justify it.


---

Sensitive Logging Rules
What must never appear in logs/events/audit metadata
Project Codename: LexaVault | Product Category: Secure Document Intelligence and Redaction Vault Platform | Date: 2026-08-25

# Never Log

- Raw document text
- Full OCR output
- PII values
- Credit or financial identifiers
- Authentication tokens
- Secrets or keys
- Full LLM prompts containing customer content
- Full provider responses containing sensitive values

# Safe Logging

- tenantId
- packageId
- documentId
- artifactId
- correlationId
- event type
- status
- safe error category
- duration/latency
- provider name without payload


---

UI/UX and Customer Workflows
Modern minimalist customer experience
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# UX Principle

The application carries the implementation complexity. Normal business users operate on customer terminology, processing stages, and exception actions - not internal UUIDs, workflow tasks, assignment mechanics, or numeric coordinates.

# One Shared Dashboard

V1 uses one shared Dashboard layout across business roles. A role controls what records and actions a person is allowed to access; it does not create a separate duplicate dashboard. Business Admin and IT/Platform Admin remain separate configuration areas.

The Dashboard is both an overview and a working queue:
- top Overview/KPI cards summarize Processing, Needs Review, Exported, and Technical Attention
- KPI cards are clickable and filter the working list
- the Overview section is collapsible so the record list can use most of the page
- the user's collapsed/expanded preference should be remembered

# Business Identifiers and Columns

A Business Record can have multiple tenant-configured business identifiers, for example `Contract Number` and `Member Number`. Exactly one identifier is designated Primary for compact/canonical display; additional identifiers may also be required and visible. Required does not imply Primary.

Business Admin defines each identifier/field with attributes such as:
- display label
- Primary yes/no (exactly one Primary identifier)
- required/optional
- searchable
- extractable
- available on Dashboard
- default visible
- default column order

Recommended default operational columns:
1. Primary Business Identifier (using its configured label, e.g. `Contract Number`)
2. Received
3. Stage
4. Documents
5. Attention

Additional configured identifiers such as `Member Number`, and approved attributes such as customer/company name, intake source, export status, or last updated, may be available as optional columns. Business Admin controls the approved/default set; users may show/hide and reorder those approved columns for their personal view. Internal package/batch/document UUIDs are secondary technical metadata and are not normal business columns.

# Search and Filtering

Search operates across fields marked searchable by Business Admin. Placeholder/help text should reflect the configured fields rather than hardcoded mortgage terminology.

Stage is a first-class quick filter rather than a hidden enterprise-grid filter. Recommended one-click filters:
- All
- Processing
- Needs Review
- Exported
- Technical Attention
- Rejected

Less-common filters such as received date, intake source, or export status remain under a compact `More Filters` control. Filters combine with search. Filtering, sorting, and pagination should be server-side when dataset size warrants it.

# Role Behavior

Use the same Dashboard for all roles. Roles determine permissions, visibility, and available actions. A role may provide a sensible initial/default filter, but the user can change it; the user's last-used view should take precedence where practical.

Examples:
- Reviewer can quickly select `Needs Review` and open the focused Manual Review workbench.
- Business user can monitor active/exported business records if authorized.
- Business Admin sees the same operational Dashboard plus Business Admin configuration.
- IT/Platform Admin sees the same operational Dashboard plus deeper Technical Attention and platform configuration.

V1 does not require per-user review assignment.

# Manual Upload / Scan

1. User enters the configured required business identifiers.
2. UI prevents submission when mandatory fields are missing.
3. User uploads/scans PDF file(s).
4. The platform creates a new Intake Batch linked to the Business Record and takes over processing.

# Manual Review

Only actionable low-confidence items appear. The reviewer opens an item and sees the relevant page/finding with a visual redaction region. The reviewer confirms/adjusts or marks the item not sensitive, then submits.

Do not expose:
- Package ID entry
- Review Task ID entry
- Finding ID entry
- Create/Assign/Start task controls
- Manual normalized bounding-box numbers

# Technical Attention

Business users see a simple `Technical Attention` / `Needs technical attention` status. IT/Platform Admin sees stage, failure category, provider/failover history, age, and purge/hold deadline. Technical failures are not mixed into the business Manual Review queue.

# Target Mockup

The approved design direction is represented by `03_Dashboard_Target_Mockup_2026-08-29.png`. It is a visual direction, not a pixel-perfect implementation contract; the functional rules in this document are authoritative.

# Trust Signals

- Show what needs attention without exposing raw sensitive values.
- Show confidence/policy context when useful.
- Show clear processing/export state and retention deadlines to authorized users.
- Never display stack traces or provider exception text.


---

Admin Personas and Minimalist UX
Business configuration vs technical configuration
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# Business Admin

Business Admin configures the operating policy customers understand:
- one or more business identifiers (Contract Number / Loan Number / Member Number / Patient Number / similar)
- exactly one Primary business identifier for canonical/compact display
- required/optional/searchable/extractable behavior per identifier/attribute
- whether a field is available on Dashboard, default-visible, and its default column order
- sensitive-data categories
- confidence thresholds
- review-all/final-approval/straight-through mode
- original post-export retention
- never-exported technical-exception hold extension

Business Admin defines the approved business-facing field set. Individual users may personalize visibility/order within that approved set; users do not expose arbitrary internal technical fields.

Business Admin should not configure provider SDK details, filesystem credentials, or low-level retry codes.

# IT / Platform Admin

IT/Platform Admin configures technical deployment behavior:
- local/S3/object working storage
- local/S3/API final delivery
- watched-folder paths and post-ingest Archive/Problem/leave policy
- OCR/AI providers and optional failover
- technical transient retry/backoff policy
- identity/SSO and integrations
- runtime/health/operational connectivity

# Shared Dashboard Rule

Do not create separate duplicated role dashboards. Business roles use one common operational Dashboard. Roles control records, actions, and access to admin/deeper technical areas. Manual Review is a separate workbench because it is a distinct exception-resolution task.

# Minimalism Rule

Expose only what the persona can meaningfully act on. Default values should make the product usable without navigating every admin page. Prefer progressive disclosure over permanently visible enterprise controls.


---

Dashboard UX Decision Freeze - 2026-08-29
Approved V1 dashboard and business-field behavior
Project Codename: LexaVault

# Locked Decisions

- One shared operational Dashboard for all business roles; roles control permissions/actions, not separate page designs.
- Top Overview/KPI section is collapsible and its cards act as one-click stage filters.
- Stage quick filters include All, Processing, Needs Review, Exported, Technical Attention, and Rejected.
- Search spans Business Admin-configured searchable fields.
- A Business Record supports multiple business identifiers. Exactly one is Primary, while additional identifiers can also be required, searchable, extractable, visible, and ordered.
- Dashboard never uses a generic `Reference` label when a configured label such as `Contract Number` or `Member Number` is available.
- Default operational columns are Primary Business Identifier, Received, Stage, Documents, and Attention. Additional approved identifiers/attributes can be shown as extra columns.
- Business Admin controls available/default fields and default order; individual users can show/hide/reorder approved columns.
- Internal UUIDs and workflow IDs remain hidden from normal business views.
- Manual Review is a focused exception workbench, not a developer task console.
- Filtering/sorting/pagination should be performed server-side as scale requires.

# Visual Direction

See `03_Dashboard_Target_Mockup_2026-08-29.png`. The mockup demonstrates the desired clean/light SaaS direction, collapsible overview, stage chips, search, configurable columns, and single-dashboard approach. Functional requirements in the Markdown documents remain authoritative over pixels/sample data.


---

Implementation Unit Tracker
Kiro unit-by-unit progress tracker
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Updated: 2026-08-30

# Current Status

- Units 1–15: COMPLETE and previously reviewed.
- Units 16–24: **APPROVED** after scoped implementation + Kiro build/runtime verification.
- Unit 25: **IMPLEMENTED IN SOURCE; awaiting Kiro verification and approval.**
- Unit 25 decision freeze: APPROVED on 2026-08-30 after master-doc + code + Kiro-history reconciliation and Kiro design feedback.
- Final Security/Integration Tests + V1 Acceptance: NOT STARTED and must remain separate from Unit 25.

# Current Approval Baselines

- Unit 23 authoritative baseline is the final merged Dashboard/Reporting source containing Kiro's Liquibase 4.27 XSD correction plus the stronger Business Admin dashboard-column policy. Final re-verification required no Kiro source changes.
- Unit 24 authoritative baseline is the IT/Platform Admin source verified by Kiro with real PostgreSQL/Ollama/Tesseract and no source changes.
- Unit 25 must start from the Unit 24-approved baseline. Current Unit 25 source is only a candidate until Kiro verification is reviewed.

# Revised V1 Construction Sequence

| Unit | Name | Status | Primary Outcome / Current Result |
| --- | --- | --- | --- |
| 1–13 | Historical foundation/product units | Complete | Core modular-monolith/domain/application/infrastructure/API foundation. |
| 14 | Security / Tenant Access Hardening | Approved | Local-mode boundary, role authorization, tenant-scoped access, safe errors. |
| 15 | Audit Timeline & Traceability Foundation | Approved | Tenant-scoped audit query/API/UI and safe audit details. |
| 16 | Core AI-First Automatic Redaction Pipeline | **Approved** | AI contextual analysis is mandatory primary discovery; native PDF location; supporting deterministic evidence; permanent redaction + verification + export. |
| 17 | OCR / Image Redaction | **Approved** | Tesseract OCR/scanned pages feed the same AI-first normalized pipeline. |
| 18 | Confidence + Exception Handling | **Approved** | Evidence/confidence policy, selective retry/backoff, optional failover, Technical Exception separation. |
| 19 | Minimal Manual Review | **Approved** | Focused page/finding review and resume; no assignment/ID-entry workflow. |
| 20 | Business Record + Identifiers + Unified Intake | **Approved** | BusinessRecord + IntakeBatch, multiple identifiers, API/manual/watched-folder trigger + fingerprint. |
| 21 | Business Admin | **Approved** | Identifier/business policy, sensitive categories, confidence/review and retention/hold configuration. |
| 22 | Delivery + Lifecycle | **Approved** | Local/API delivery, S3 adapter, export cycles, retention/reprocessing/purge. |
| 23 | Shared Dashboard + Reporting | **Approved** | Shared business Dashboard, server-side working list, Business Admin column governance, five V1 reports. |
| 24 | IT / Platform Admin | **Approved** | Provider/runtime health, watched-folder operations/scheduler, resilience/integration visibility, Technical Exceptions. |
| 25 | Production Identity / Platform Hardening | **Implemented; verification pending** | LOCAL/OIDC boundary, trusted JWT tenant/actor/roles, admin persona split, tenant guard, safe current-user endpoint, production CORS/headers/callback auth, legacy-vault hardening. |
| Final | Security/Integration Tests + V1 Acceptance | Planned | High-value security/integration tests, consolidated AI-first acceptance, packaging and release candidate. |

# Kiro Rule

For each construction unit, Kiro performs scoped verification, reports exact files/build/runtime/security results, makes only minimal defect corrections, and stops for review. Unit 25 explicitly allows a **small focused security-boundary test suite**; broad generic test-generation remains out of scope. Do not start the Final V1 phase until Unit 25 is reviewed and approved.


---

Project Timeline and Roadmap
Revised V1 implementation priorities
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Updated: 2026-08-30

# Current Milestone

Units 16–24 are approved. Unit 25 Production Identity / Platform Hardening is implemented in source and is currently at the Kiro verification gate. The final V1 Security/Integration/Acceptance phase must not begin until Unit 25 is approved.

# Remaining V1 Roadmap

| Order | Work | Current Outcome / Next Gate |
| --- | --- | --- |
| 1 / Unit 25 | Production Identity / Platform Hardening | Verify OIDC/JWT trust boundary, tenant/actor/role mapping, BUSINESS_ADMIN/PLATFORM_ADMIN separation, TenantBoundaryGuard, spoof/cross-tenant protection, callback auth, CORS/security headers, LOCAL non-regression and focused security tests. Approve only after Kiro report review. |
| Final | Security/Integration Tests + V1 Acceptance | Run consolidated digital/OCR/AI-first/redaction/review/watched-folder/delivery/lifecycle/dashboard/admin/identity acceptance; runtime-test S3 if claimed; prepare release documentation and V1 release candidate. |

# Unit 25 Verification Checkpoint

Kiro has a strict 63-item verify-only prompt. It must validate backend/frontend builds, LOCAL + OIDC startup, JWT signature/issuer/audience/time/JWKS behavior, identity-header spoof prevention, trusted tenant/actor/role mapping, admin role matrix, central tenant guard, cross-tenant read/write isolation, same-ID mutation protection, `/api/v1/me`, PKCE/session token handling, legacy vault hardening, callback HTTPS/bearer policy, production credentials/CORS/security headers, focused security tests, and representative Units 16–24 regressions.

If Kiro changes any Unit 25 source file, review the exact correction before approving the unit.

# V1 Acceptance Story

A customer provides required business identifiers and a PDF. For each page the system uses native PDF text/layout when usable or OCR when needed, runs AI contextual analysis, merges supporting deterministic evidence, resolves exact location, applies explicit tenant policy/confidence, permanently redacts through the controlled engine, independently verifies the result, and exports under the original filename. Humans intervene only for configured review or specific low-confidence content; unresolved technical failures remain visible to IT/Platform Admin. Under production security, tenant/actor/roles come from verified OIDC/JWT identity rather than caller-controlled identity headers.

# Later Versions

- Additional semantic sensitive/confidential categories such as customer-defined company secrets and proprietary sections using the same AI -> Finding -> Policy -> Redaction pipeline
- Rich law/regulatory policy packs and AI-assisted policy interpretation
- Additional document classifiers
- Advanced provider routing/model management
- Kafka/SQS broker adapter when operational value justifies it
- Separate worker/service deployment where scale requires it
- Advanced analytics/reporting
- RAG/vector search if product scope expands
- Multi-region deployment


---

Open Risks and Decisions
Items to revisit as the product matures
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Updated: 2026-08-30

# Decisions Now Closed

- Automatic permanent redaction is the product core.
- AI contextual analysis is the mandatory primary V1 sensitive-information discovery stage for every processed document.
- Deterministic recognizers/validators are supporting/parallel evidence and never gate AI.
- Native PDF text/layout is preferred when usable; OCR is page-level fallback for scanned/unusable content; both normalize into one downstream model.
- Local OCR reference implementation is Tesseract.
- Kiro's repeated local AI acceptance reference has been Ollama `gemma3:12b`; product/provider architecture remains configurable/provider-neutral.
- AI determines semantic sensitivity; native extraction/OCR normally supplies authoritative geometry; application policy decides REDACT/REVIEW; controlled redaction performs irreversible modification and verification.
- High-confidence straight-through processing is recommended default; focused review is policy/exception driven.
- Technical failures remain separate from business Manual Review and use classified/bounded retry/failover behavior.
- BusinessRecord is separate from IntakeBatch; many intakes can attach to one long-lived business identity.
- Watched-folder trigger + fingerprint/idempotency semantics are implemented and verified.
- PostgreSQL stores metadata/operational truth, not PDF binaries.
- Delivery supports local filesystem, API/callback and S3 adapters; local/API are runtime proven, S3 runtime acceptance remains a release verification item if advertised.
- Preserve original filenames/file boundaries; accepted V1 publication is package-atomic rather than silently partially successful.
- Originals and temporary redacted working copies are short-lived; original retrieval is not a V1 product feature.
- Business Admin and IT/Platform Admin are separate personas.
- Shared Dashboard and reporting model are approved; Business Admin governs available/default columns and users personalize only approved fields.
- Unit 25 production role model is frozen as USER, REVIEWER, APPROVER, BUSINESS_ADMIN, PLATFORM_ADMIN and ADMIN super-role.
- Unit 25 production tenant trust is frozen to verified OIDC/JWT claims plus a lightweight TenantBoundaryGuard; no RLS redesign.
- Production callback authentication is frozen to HTTPS + deployment-managed bearer token for V1.
- Unit 25 IDP/framework reuse is edge-only and must not enter product domain/application/AI/redaction logic.

# Remaining Product / Technical Decisions and Acceptance Items

- Unit 25 is implemented but still requires authoritative Kiro build/runtime/security verification and review before approval.
- Runtime-test S3/object delivery during final integration if S3 is claimed as production-ready in V1 release materials.
- Final release documentation should distinguish the **reference acceptance AI model** (`gemma3:12b` in Kiro verification) from provider/model configurability.
- Exact customer-facing maximum choices for original-retention extension beyond the default should be documented consistently before release if the UI permits multiple options.
- Final Security/Integration/V1 Acceptance must prove the whole product under production identity, including cross-tenant isolation and safe secrets/error behavior.
- Decide/document whether current in-process scheduling limitations are explicitly accepted for the V1 deployment target; do not introduce distributed scheduling without a demonstrated release blocker.

# Current Risks to Guard Against

- Production OIDC implementation accidentally retaining trust in caller-supplied tenant/actor/role headers.
- Missing/unmapped JWT claims accidentally inferring ADMIN or another privileged role.
- A future repository/update path omitting tenant ownership checks; Unit 25 uses central tenant validation plus tenant-explicit repositories rather than RLS.
- Cross-tenant same-ID mutation through JPA merge/save behavior.
- JWKS/IdP outage causing an insecure fallback rather than fail-closed authentication.
- CORS/CSP/security-header hardening breaking the SPA/API workflow.
- Legacy original/vault access routes accidentally remaining reachable in production OIDC mode.
- Secrets/tokens appearing in frontend storage, logs, audit, error responses or Platform Admin payloads.
- AI provider failure being silently treated as successful deterministic-only processing.
- Visual masking leaving recoverable underlying sensitive content.
- Sending unnecessarily large document payloads to AI without preserving usable context/location references.
- Retaining originals/temporary copies longer than configured.
- UI exposing internal UUID/task/finding mechanics to business users.
- Marketing AI as autonomous legal interpretation before explicit policy intelligence is mature/validated.


---

Demo and Release Artifact Plan
What to prepare for V1 demos and releases
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# V1 Demo Story

The demo should prove the AI-first product core rather than a technical workflow console.

1. Business Admin has configured a business identifier label such as `Contract Number`, sensitive categories, confidence threshold, and straight-through mode.
2. User enters required identifier(s) and uploads a digital PDF containing V1 sensitive-data examples.
3. System uses native PDF text/layout, runs AI contextual analysis on the document, combines supporting deterministic evidence where useful, automatically locates the findings, permanently redacts them through the controlled redaction engine, verifies the result, and exports the document under the same filename to a local output folder.
4. Demonstrate at least one context-sensitive case such as a credit-score value whose meaning comes from surrounding text, not merely from number shape.
5. Dashboard shows the business identifier, received time, processing stage, and exported status.
6. A second document contains one below-threshold/disputed finding. Only the relevant page/finding appears in Manual Review; reviewer corrects/confirms and submits; export resumes.
7. A scanned PDF proves the approved Unit 17 OCR/image-redaction path. The customer workflow remains identical; only the page-normalization source changes internally.
8. A watched-folder sample proves the approved Unit 20 trigger/fingerprint and Archive/Problem/leave behavior, plus Unit 24 operational scan/scheduler visibility.
9. Audit/reporting shows safe structured outcomes and AI provider/model metadata where appropriate without raw PII/PCI.

# Core V1 AI Acceptance Evidence

The final V1 acceptance/demo should prove that:
- AI analysis runs on processed documents and is not merely a fallback after deterministic detection.
- A deterministic detector miss does not automatically prevent AI from discovering a sensitive item.
- Structured deterministic evidence can strengthen or challenge AI findings.
- Location is resolved from PDF/OCR geometry rather than user-entered X/Y values.
- AI does not directly modify the final PDF.
- Redacted values are permanently unrecoverable from the exported output.

# Demo Materials

- Sample digital PDF with SSN, DOB, credit score, credit card number
- Sample context-sensitive examples where the same numeric shape means different things in different contexts
- Sample scanned/image PDF with the same categories
- Sample trigger/manifest and watched-folder batch
- Sample low-confidence/manual-review exception
- Sample technical AI/OCR provider exception/report
- Local output destination containing successfully exported redacted files with unchanged filenames
- Operational dashboard/report screenshots
- Known limitations

# Release Materials

- Release notes
- Deployment/startup steps
- Business Admin configuration guide
- IT/Platform Admin configuration guide
- PostgreSQL migration notes
- Storage/delivery configuration
- AI/OCR provider/model configuration and data-handling notes
- Smoke/acceptance checklist
- Retention/purge behavior
- Known issues and rollback notes


---

Documentation Update Summary
Current construction continuity + approved Units 16–24 + Unit 25 security implementation
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Updated: 2026-08-30

# Updated through 2026-08-30

This master packet now reconciles the original 2026-08-29 V1 product/architecture freeze with the later implementation and Kiro verification history.

Current construction status:

- Units 1–15 complete/historical foundation.
- Units 16–24 approved.
- Unit 25 Production Identity / Platform Hardening decision freeze approved and source implemented.
- Unit 25 is **not approved yet**; Kiro verification is in progress using a strict 63-item verify-only prompt.
- Final Security/Integration Tests + V1 Acceptance remains a separate future phase and has not started.

Key later decisions now captured in this master packet include:

- Real local AI verification repeatedly used Ollama `gemma3:12b`; provider-neutral/on-prem architecture remains frozen.
- Real OCR verification uses Tesseract.
- Required AI analysis cannot silently downgrade to deterministic-only success.
- Technical Exceptions and Manual Review are distinct workflows.
- BusinessRecord/IntakeBatch/multiple-identifier/watched-folder trigger+fingerprint model is implemented.
- Business Admin owns business policy; Platform Admin owns technical operations/health.
- Unit 23 final merged Dashboard includes Business Admin Allowed/Default/Order governance for operational + identifier columns.
- Unit 24 Platform Admin was verified with zero Kiro source changes.
- Unit 25 replaces caller-controlled production identity headers with verified OIDC/JWT identity, introduces BUSINESS_ADMIN/PLATFORM_ADMIN roles with ADMIN super-role, a lightweight central tenant guard, safe `/api/v1/me`, PKCE/session token handling, callback HTTPS/bearer policy, CORS/security headers and production legacy-vault hardening.
- No PostgreSQL RLS, custom tenant platform, custom JWKS service, token blacklist DB, secrets-vault UI or microservices rewrite is part of Unit 25.

The `CURRENT PROJECT CONTINUITY UPDATE — 2026-08-30` section near the top of this file is the recommended new-thread entry point.


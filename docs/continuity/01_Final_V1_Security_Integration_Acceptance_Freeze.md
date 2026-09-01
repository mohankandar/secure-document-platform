# Final V1 Security / Integration + Acceptance — Decision Freeze & Verification Plan

**Project Codename:** LexaVault  
**Date:** 2026-08-31  
**Status:** UPDATED/FROZEN FOR FINAL ACCEPTANCE — includes 2026-08-31 customer-policy + safe-observability delta  
**Baseline:** Units 16–25 APPROVED  
**Next outcome if all gates pass:** V1 Release Candidate

> This is not Unit 26 and is not a new construction unit. It is the formal end-to-end release acceptance phase for the already-built V1.

---

## 1. Operating Rule

Preserve the established workflow:

- **ChatGPT owns implementation, pre-acceptance review, documentation, packaging, and any necessary code fix.**
- **Kiro owns independent structured verification.**
- Kiro must not reimplement the product, redesign architecture, generate broad generic tests, or start future work.
- Kiro may make only the smallest correction for a genuine acceptance-blocking defect.
- Do not burn Kiro credits repeating already-proven unit-level checks unless a final end-to-end scenario actually depends on them.

The current Kiro workspace after Unit 25 verification is authoritative for source because Kiro made two approved **test-only** fixture corrections:
- `JwtBoundaryTest.java`
- `RequestContextResolverSecurityTest.java`

No Unit 25 production code was changed by Kiro.

---

## 2. Purpose of Final Acceptance

Final acceptance must answer one question:

> Does the complete V1 product, under the approved Unit 25 security boundary, reliably accept supported PDFs, understand sensitive content with mandatory AI contextual analysis, permanently redact it, route only genuine exceptions for human/technical attention, deliver the expected output, preserve tenant/security boundaries, and provide sufficient operational/admin evidence for a V1 release candidate?

This phase is **not** a search for additional features.

---

## 3. Frozen V1 Product Invariants

The following are non-negotiable and must not be redesigned during acceptance:

1. Every processed document enters AI contextual analysis after native-PDF/OCR normalization.
2. AI is the primary semantic sensitive-information discovery layer.
3. Deterministic detectors/validators are parallel/supporting evidence and never gate AI discovery.
4. V1 sensitive categories:
   - SSN
   - DOB
   - Credit Score
   - Credit Card Number
5. Native PDF extraction supplies text/layout where usable.
6. Tesseract OCR is used for scanned/unusable pages in the approved local/on-prem path.
7. AI determines **what** is sensitive; PDF/OCR extraction determines **where** it is; application policy decides REDACT vs REVIEW.
8. AI does not physically modify PDFs.
9. Redaction is deterministic/permanent and must be verified as unrecoverable.
10. High-confidence findings are straight-through by default.
11. Low-confidence/actionable findings route to focused Manual Review.
12. Technical/provider failures route to Technical Exceptions/Attention, not business Manual Review.
13. BusinessRecord and IntakeBatch remain distinct.
14. Successful output is package-oriented; do not silently introduce partial publication semantics.
15. Tenant isolation applies to all tenant-owned operations.
16. Production identity is OIDC/JWT-derived; identity headers are LOCAL/demo-only.
17. Business Admin and Platform Admin remain separate roles/personas, with explicit ADMIN super-role support.
18. Working binaries are not stored in PostgreSQL.
19. Redacted output is the intended durable customer artifact; originals are short-lived according to lifecycle policy.
20. Product codename must not be unnecessarily hardcoded into reusable platform/domain concepts.

---


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




## Tenant-Driven Prompt / AI Provider Routing Acceptance Delta — 2026-08-31

The successful 61-page customer-package rerun validated the focused credit-card precision correction: final findings were exactly `CREDIT_SCORE=1` on page 59, the false page-31 card candidate was rejected by the PAN-shape guardrail, redaction verification passed, delivery succeeded, and the package completed. Provider/prompt separation may now proceed without reopening that precision behavior.

Final Acceptance must additionally prove:

1. Customer category policy is supplied to AI from `TenantRedactionPolicy`; `OllamaSensitiveContentAnalysisAdapter` does not own a hard-coded customer category prompt.
2. Prompt composition includes only enabled tenant categories and honors `maskSsnLastFour`.
3. AI provider/model configuration is independent from business redaction policy. Changing Gemma to Qwen under the same provider requires configuration only.
4. Deployment default and explicit tenant AI profiles route by trusted tenant identity, never by caller-supplied production headers.
5. Supported V1 provider transports are Ollama and OpenAI-compatible/vLLM. Customer-managed compatible endpoints use the same provider-neutral finding contract.
6. AI API credentials are deployment-managed, never browser-managed, persisted in business policy, logged, audited, or returned by Platform Admin.
7. `securedoc.ai.require-tenant-profile=true` fails closed for a tenant without an explicit AI profile and must not send its content to the default provider.
8. `heuristic` remains local/development-only and is not allowed as tenant AI profile or secondary provider.
9. Per-tenant AI profiles are deployment/restart managed for V1; no dynamic provider hot-swap or credential-editor UI is required.
10. No Liquibase migration is expected from the tenant-AI/provider-routing delta itself. The later Dashboard visibility correction adds migration `013-dashboard-package-visibility.xml`; the current acceptance workspace therefore expects **36 changesets**.

A proprietary customer AI protocol that is not OpenAI-compatible is outside the generic adapter contract and requires a dedicated provider adapter; this must not require changes to business policy/detection/redaction architecture.

## 4. Must-Pass Release-Candidate Acceptance Scenarios

### A. Digital PDF straight-through
Use a representative digital PDF containing supported V1 sensitive categories.

Prove:
- native extraction used where appropriate;
- AI runs on the document;
- findings are produced with authoritative geometry;
- policy selects redaction/review correctly;
- permanent redaction completes;
- delivery/export succeeds;
- Dashboard/report/audit reflect the result.

### B. Scanned PDF straight-through
Use a representative scanned/image PDF.

Prove:
- Tesseract OCR runs;
- normalized OCR content enters the same mandatory AI-first pipeline;
- findings obtain OCR geometry;
- redaction/verification/export completes.

### C. Representative mixed PDF
Use a PDF containing both native-text and scanned/image pages where practical.

Prove:
- page-by-page native/OCR routing;
- one coherent downstream AI/finding/redaction pipeline;
- no unnecessary whole-document OCR behavior that changes correctness.

### D. AI independence from deterministic detection
Use at least one case where deterministic recognition does not provide the primary signal but contextual AI identifies the sensitive item.

Prove:
- deterministic miss does not suppress AI discovery;
- item still enters the common Finding/policy/redaction pipeline.

### E. Evidence agreement/disagreement
Use representative supported cases to show deterministic evidence can strengthen or challenge an AI candidate without becoming a prerequisite.

Prove:
- application-owned evidence/confidence behavior remains active;
- no silent deterministic-only fallback.

### F. Context-sensitive semantic detection
Include a context-sensitive value such as `720`.

Prove:
- AI/context recognizes it as a credit score only when surrounding context supports that meaning;
- a bare/irrelevant number should not automatically become a credit-score redaction merely because of numeric shape.


### F1. Credit-card precision regression from real 61-page package

This is a mandatory focused regression before broader AI-provider/prompt architecture work.

Use the same representative 61-page package (or a synthetic equivalent) and prove:
- an actual payment-card PAN such as the standard synthetic `4111 1111 1111 1111` is detected as `CREDIT_CARD_NUMBER`;
- repeated barcode/control markers such as `7777777777777` are not credit-card findings;
- Contract Number / Member Number / loan or routing identifiers are not reclassified as credit-card numbers;
- dollar amounts, APRs, payment counts and dates are not credit-card numbers;
- a blank `Credit Card #` label is not a finding;
- a model response type `FINANCIAL_ACCOUNT` is not silently converted into `CREDIT_CARD_NUMBER`;
- deterministic Luhn validation remains supporting/challenging evidence and is not converted into a prerequisite for AI discovery;
- no rejected candidate value is written to logs.

For the inspected 61-page customer sample, the expected category result is `CREDIT_SCORE=1` on page 59 with zero DOB, SSN and Credit Card Number findings.

PASS/FAIL.

### G. Permanent-redaction irreversibility
For final redacted output verify:
- visual target removed/covered;
- underlying sensitive text cannot be searched;
- cannot be copied/extracted as original sensitive content;
- independent extraction does not recover original sensitive values;
- original filename/document boundaries are preserved.

### H. High-confidence straight-through
Prove a normal high-confidence package can:
`intake → AI/OCR as applicable → policy → redaction → verification → delivery/export`
without unnecessary manual intervention.

### I. Focused low-confidence Manual Review
Create/use a case requiring review.

Prove:
- only actionable finding/page enters review;
- reviewer confirms/dismisses;
- workflow resumes automatically;
- package does not become a developer-style task/assignment workflow.

### J. Technical-provider failure
Temporarily make a required provider such as Ollama or OCR unavailable.

Prove:
- no heuristic/deterministic substitution silently declares success;
- retry/backoff follows approved behavior;
- final technical failure becomes Technical Exception/Attention;
- it does not become business Manual Review.

### K. Watched-folder intake
Prove:
- trigger/manifest gating;
- valid accepted intake;
- waiting-for-trigger;
- missing-required-identifier rejection;
- duplicate fingerprint/idempotency;
- BusinessRecord reuse and separate IntakeBatch;
- configured disposition behavior.

### L. Business Admin policy in live processing
Prove configured policy changes affect runtime:
- sensitive category enablement;
- confidence/review policy;
- review-all/final approval where applicable;
- business identifier behavior;
- Dashboard-column governance.

### M. Platform Admin operational acceptance
Prove:
- PostgreSQL health;
- Ollama health;
- Tesseract health;
- storage/delivery visibility;
- watched-folder operational actions;
- Technical Exceptions;
- safe provider/config visibility without secrets.

### N. Lifecycle / retention / reprocess
Prove:
- successful export record;
- successful export-cycle counting;
- technical delivery retries do not consume a business reprocess cycle;
- original retention clock behavior;
- never-exported technical hold;
- bounded reprocessing;
- purge behavior.

### O. Shared Dashboard / reporting
Prove:
- shared role-aware Dashboard;
- configured business identifier labels/columns;
- search/filter/sort/pagination;
- operational KPIs;
- Technical Attention;
- five V1 reports;
- server/API data remains tenant-scoped.

### P. LOCAL security mode
Prove:
- LOCAL starts explicitly;
- default role is not ADMIN;
- demo roles remain useful for local verification;
- local PostgreSQL/Ollama/Tesseract/storage/delivery work without an external IdP.

### Q. Production OIDC/JWT security regression
Do **representative**, not exhaustive Unit-25 repetition.

Prove:
- verified JWT drives tenant/actor/roles;
- identity headers cannot spoof tenant/actor/role;
- wrong tenant resource ID cannot cross tenant boundary;
- BUSINESS_ADMIN vs PLATFORM_ADMIN separation;
- ADMIN explicit super-role;
- safe `/api/v1/me`;
- invalid/missing authentication fails closed;
- safe errors/no secrets.

### R. Audit / correlation
Prove:
- important intake/review/admin/security/processing actions have audit evidence;
- actor is real authenticated/system actor where applicable;
- tenant and correlation context persist;
- no raw PII/PCI, full OCR/document text, bearer token, credential, or raw stack trace is stored/exposed.

### S. Delivery
Must pass:
- local/filesystem delivery;
- authenticated API/callback delivery with HTTPS policy and bearer handling.

**S3/object storage rule:**
- If V1 release materials claim S3/object delivery is production-ready, run a real MinIO/S3-compatible integration test and require PASS.
- If that runtime environment is not available, do **not** block the entire V1 RC solely for that reason; instead document S3 as implemented but not part of the formally runtime-accepted V1 delivery claim.

### T. Enterprise IdP deployment validation
Unit 25 cryptographic OIDC behavior is already approved using a controlled RS256 issuer.

For final release:
- validate configuration against a real enterprise-compatible OIDC provider if credentials/environment are available;
- do not require Okta specifically as an architectural dependency;
- if a real tenant is unavailable, preserve the controlled-issuer security evidence and document real-enterprise IdP configuration as a deployment validation item rather than inventing or weakening the security model.

---


### U. Customer-specific masking-policy proof

For the current target customer, prove the tenant policy enables **only**:
- SSN
- DOB
- Credit Score
- Credit Card Number

Required positive cases:
- full SSN;
- partially masked SSN with only the last four digits exposed in explicit SSN context;
- DOB;
- contextual credit score;
- credit-card number.

Required negative controls:
- customer name remains visible;
- email remains visible;
- an unrelated four-digit number is not treated as SSN merely because it has four digits;
- Contract Number / Member Number remain unmasked unless a future customer policy explicitly changes that behavior.

This is a policy/configuration requirement, not a new global sensitive category.


## 4A. Pre-Acceptance Delta Added 2026-08-31

The following small product-policy/observability delta is part of the current acceptance baseline:

1. `maskSsnLastFour` is a tenant-owned Business Admin policy flag.
2. Liquibase migration `012-ssn-last-four-policy.xml` adds the flag with default `true` for existing rows.
3. When enabled, only four exposed digits in explicit SSN/Social Security context are treated as last-four-only SSN. Unrelated four-digit numbers are negative controls.
4. When disabled, last-four-only SSN is ignored while full SSN remains governed by the SSN category.
5. Name and email must remain unmasked for the current customer's four-category policy.
6. Final acceptance must inspect the safe high-level processing events and verify they expose only IDs/counts/category/page/timing metadata—not raw values or text.
7. The current source also merges the two approved Unit 25 test-fixture corrections. Do not replace it with the earlier pre-Kiro Unit 25 ZIP.

This delta does not reopen or redesign Unit 25 identity/security architecture.


## 4A. Ollama Large-PDF Reliability Regression — Added 2026-08-31

This is a focused release-blocking regression because the real 61-page customer package exposed whole-document AI retry behavior.

Required PASS evidence using Ollama `gemma3:12b` in LOCAL mode:

1. Startup/runtime log shows bounded configurable AI execution, expected LOCAL defaults:
   - `parallelism=2`
   - `timeoutSeconds=180`
   - `pageMaxAttempts=2`
2. A transient failure on one page retries only that page; previously successful pages are not replayed.
3. Exhausting the page retry budget does not cause the generic resilience decorator to restart the complete document.
4. Safe logs identify `page`, stable `reason` (`AI_TIMEOUT`, `AI_UNAVAILABLE`, etc.), `attempt`, `retrying`, and duration without raw customer content.
5. The same 61-page customer PDF that previously failed twice should complete or, if a genuinely pathological page still exceeds the new bounded policy, fail once with a specific safe page-level reason rather than replaying all 61 pages.
6. Existing optional secondary-provider failover remains functional.
7. AI-first behavior is unchanged: no deterministic prefilter may skip otherwise nonblank pages from required semantic analysis.

Focused automated regression: `OllamaPageRetryTest`. Do not expand this into a broad new test suite.

## 5. Security / Integration Checks Worth Running

Keep these high-value and narrow:

1. Cross-tenant package/document/business-record ID substitution.
2. Cross-tenant Business Admin mutation attempt.
3. Invalid/tampered/expired production token smoke check.
4. OIDC identity-header spoof attempt.
5. Unknown/unprovisioned tenant safe 4xx behavior.
6. Role matrix smoke check.
7. Secret/PII leak scan over representative API/admin/audit responses.
8. Legacy original/vault routes blocked in production security mode.
9. Callback bearer value absent from logs/API/UI/audit.
10. CORS/security headers do not break actual SPA→API use.

Do not generate a broad new security test suite unless a concrete defect requires a focused regression.

---

## 6. Release Documentation Required Before V1 RC

ChatGPT should consolidate/update these after technical acceptance:

1. V1 release notes.
2. Startup/deployment instructions.
3. LOCAL/demo startup guide.
4. Production OIDC configuration guide.
5. Business Admin guide.
6. IT / Platform Admin guide.
7. PostgreSQL setup/migration notes.
8. Working storage configuration.
9. Delivery configuration:
   - local
   - callback/API
   - S3 only to the level actually accepted
10. Ollama/model configuration and data-handling notes.
11. Tesseract/OCR configuration.
12. Retention/purge/reprocess behavior.
13. Audit/logging/security notes.
14. Smoke/acceptance checklist.
15. Known limitations.
16. Rollback/recovery notes.
17. Master continuity Markdown updated and stored inside the repository.

---

## 7. Accepted / Known Limitations That Do Not Automatically Block V1 RC

Unless final testing proves one is a real release blocker:

1. Retention/retry scheduling may remain in-process rather than a distributed durable scheduler.
2. Callback multipart handling may remain memory-buffered within the currently accepted V1 scale assumptions.
3. Unmapped `/api/v1/**` may return a safe generic 500 rather than 404 due to pre-existing interceptor behavior, provided no information leak/security bypass occurs. Prefer cleanup if trivial, but do not expand acceptance into a routing redesign.
4. Corporate Maven mirror availability is an environment/dependency-access concern, not application correctness.
5. Exact deploy-time AI model remains configurable. Official acceptance/reference model is currently Ollama `gemma3:12b`.
6. A real Okta tenant is not required to prove the provider-neutral OIDC architecture if a controlled standards-compliant issuer already proves cryptographic behavior; real enterprise configuration should still be documented/validated when available.
7. S3 runtime acceptance is required only if the release explicitly claims production-ready S3 delivery.

---

## 8. Explicitly Out of Scope / Post-V1

Do not introduce during final acceptance:

- Unit 26
- Kafka/SQS
- microservice conversion
- PostgreSQL RLS
- Hibernate multi-tenancy redesign
- custom identity platform
- custom JWKS subsystem
- server-side token blacklist
- browser credential vault
- dynamic AI/OCR/provider hot swapping
- distributed scheduler redesign
- RAG/vector search
- confidential-business-semantic categories beyond the frozen V1 four
- DOCX processing
- advanced assignment workflow
- long-term original-document vault/retrieval product
- broad analytics/data warehouse
- broad generic test generation
- unrelated refactoring

---

## 9. Kiro Credit-Saving Handoff Strategy

Do **not** give Kiro a giant “explore the application” prompt.

After ChatGPT's pre-acceptance review:

- send one structured acceptance prompt;
- reuse existing approved evidence where a unit-level detail does not need repetition;
- require runtime proof only for the end-to-end scenarios above;
- require PASS/FAIL;
- allow only minimal fixes for genuine defects;
- if one or two checks fail, rerun only those checks after correction;
- do not rerun the complete acceptance suite unnecessarily.

Kiro must report exact source changes if any and stop before any future work.

---

## 10. Final Approval Rule

V1 may be declared a **Release Candidate** only when:

- all mandatory acceptance scenarios that apply to the claimed V1 feature set PASS;
- no known cross-tenant/authentication/redaction-irreversibility blocker remains;
- any Kiro source correction has been reviewed by ChatGPT;
- backend/frontend final builds are green;
- test/verification artifacts are cleaned;
- release documentation and master continuity documentation are current;
- remaining limitations are explicitly documented rather than silently ignored.

Final V1 acceptance approval does not mean every future feature is complete. It means the frozen V1 scope is coherent, secure, demonstrable, and ready to package as a release candidate.


## Pre-Acceptance Runtime Regression Addendum — 2026-08-31

The real 61-page customer-package run established additional must-pass acceptance checks:

- Deterministic date recognition must **not** classify ordinary transaction/signature/payment/report/effective dates as DOB. DOB must require actual birth meaning/context.
- AI must not classify a conceptual mention such as `Social Security number` in privacy-policy prose as SSN when no exposed numeric SSN value is present.
- The known actual contextual credit score on page 59 of the customer sample must remain discoverable.
- Safe evidence-source logging must distinguish semantic raw/located/unlocated counts from deterministic counts without logging sensitive values.
- Manual Review finding-scoped page preview must render for valid pending-review items. Any rejected preview must expose a safe specific reason/correlation instead of an opaque generic 400; security/status/original-availability checks must not be weakened to accomplish this.


## Post-Unit-25 operational workflow acceptance addendum — 2026-08-31

The following are now required V1 acceptance behaviors and do not change the frozen AI/redaction architecture:

- Dashboard data must update automatically without full-page reload: approximately 5 seconds while processing, 30 seconds while idle, paused while the tab is hidden, immediate refresh on return/focus.
- Package/Contract Details → Manual Review must be package-scoped at the backend; global Manual Review remains tenant-wide.
- A selected review finding must render the exact one-based source PDF page and its stored/proposed bounding box. The client must reject a preview whose returned page metadata does not match the selected finding.
- Stale or unavailable review preview state must fail safely with specific diagnostics and no raw sensitive content; it must not be represented as a misleading generic client bad-request state.
- Manual Review active counts must exclude resolved/historical findings and packages no longer awaiting review.
- Approver may choose `⋮` → `Remove from Dashboard` only for a completed/exported package. For V1 this means hide from normal Dashboard only. The package DB record, audit/lifecycle history, reports, and retention schedule remain; the existing retention lifecycle is the sole physical artifact purge mechanism.
- Dashboard visibility must be stored independently from package lifecycle status.

A controlled Manual Review runtime case may be created by temporarily using Business Admin `REVIEW_ALL`, processing a fresh sample, verifying the exact-page workbench, then restoring the normal review mode. This is preferred over weakening AI confidence/detection logic merely to force a review case.


## Final-review-item responsiveness addendum — 2026-09-01

- Saving the final actionable Manual Review decision must not keep the reviewer HTTP request open while PDF redaction, verification, and export run.
- After the last decision is persisted, the package must leave review state and enter active `PROCESSING`; continuation is dispatched through the bounded V1 in-process processing dispatcher and the API returns promptly.
- The existing `TriggerPackageProcessingUseCase` remains the only post-review redaction/verification/export orchestrator. No duplicate processing path is allowed.
- The UI must visibly clear `Saving…`, report that review is complete and processing resumed in the background, and show no remaining actionable findings for the scoped package.
- Dashboard polling must subsequently expose Processing -> Exported/Completed (or safe technical attention) without manual refresh.
- Safe logs must include scheduled/started/completed/failed resume lifecycle metadata without raw sensitive values.
- Exactly one post-review export cycle must occur.
- The in-process dispatcher is an accepted V1 durability limitation; do not add a distributed broker solely for this correction.

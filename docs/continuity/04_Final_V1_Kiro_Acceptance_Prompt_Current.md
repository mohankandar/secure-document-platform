# Kiro Prompt — Final V1 Security / Integration + Acceptance

The current workspace is the authoritative LexaVault V1 source after Unit 25 verification plus the approved 2026-08-31 pre-acceptance customer-policy/safe-observability delta, the focused Ollama large-PDF page-retry reliability correction, the DOB/SSN precision + Manual Review preview correction, and the focused credit-card precision correction.

Current status:
- Units 1–15 complete
- Units 16–25 APPROVED
- Unit 25 Kiro verification completed with only two approved TEST-FIXTURE corrections
- Those two Kiro test corrections are already merged into the current source
- A small post-Unit-25 pre-acceptance delta adds tenant SSN-last-four policy, migration 012, safe high-level logs and LOCAL scripts
- A focused reliability correction makes Ollama page concurrency configurable, uses a 180s LOCAL timeout, retries only failed pages, and prevents outer whole-document replay after page retry exhaustion
- Final V1 Security / Integration + Acceptance is the ONLY verification task now
- Provider-neutral / tenant-prompt refactoring is intentionally DEFERRED until the focused credit-card precision rerun passes; do not implement it in this verification
- There is NO Unit 26 in the current frozen plan

## TASK

Perform **FINAL V1 SECURITY / INTEGRATION + ACCEPTANCE VERIFICATION ONLY**.

This is not a construction unit.

### Read first

Read these repository continuity documents before doing anything:

1. `docs/continuity/00_LexaVault_V1_Master_Current.md`
2. `docs/continuity/01_Final_V1_Security_Integration_Acceptance_Freeze.md`
3. `docs/continuity/02_Final_V1_Pre_Acceptance_Code_Review_Historical.md`
4. `docs/continuity/03_Final_V1_Pre_Acceptance_Policy_Observability_Update.md`
5. `docs/timeline/34-pre-acceptance-ollama-page-retry-reliability-fix-2026-08-31.md`
6. `docs/continuity/07_Detection_Precision_Manual_Review_Preview_Fix.md`
7. `docs/continuity/08_Credit_Card_Precision_Fix_Self_Review.md`
8. `docs/timeline/35-pre-acceptance-credit-card-precision-fix-2026-08-31.md`

Also use the existing Unit 16–25 steering/timeline documents only when a specific acceptance item needs implementation detail.

## CREDIT-SAVING RULES

- Do NOT reimplement Units 16–25.
- Do NOT broadly explore or refactor the application.
- Do NOT generate a large generic test suite.
- Do NOT rerun every Unit 25 JWT/JWKS test; Unit 25 is already approved.
- Reuse approved unit evidence where the final scenario does not need repetition.
- Spend runtime effort on the integrated V1 product story and only high-value security regressions.
- If a genuine acceptance blocker is found, make only the smallest possible correction.
- Explain every source correction and rerun only the affected checks plus final builds.
- Do NOT start future features, Unit 26, or post-V1 work.

## REQUIRED REAL STACK

Use the actual local stack:
- Java 21
- PostgreSQL
- Ollama
- Tesseract

For ALL formal V1 AI acceptance scenarios, explicitly use:
- `SECUREDOC_AI_PROVIDER=ollama`
- `SECUREDOC_AI_OLLAMA_MODEL=gemma3:12b`

The `heuristic` adapter is development-only and **must not count as Final V1 acceptance evidence**.

Use synthetic test data only. Do not use real personal data.

---

# ACCEPTANCE SCENARIOS

## 1. Baseline builds/startup

Verify:
- backend `mvn clean verify` succeeds;
- focused existing tests remain green;
- frontend `npx tsc --noEmit` succeeds;
- frontend `npm run build` succeeds;
- LOCAL application starts cleanly;
- PostgreSQL/Liquibase applies exactly the expected post-Unit-25 migration `012-ssn-last-four-policy.xml` and then reports the schema up to date (35 total changesets expected after this delta).

PASS/FAIL.

## 2. Real provider health

With the normal LOCAL stack verify:
- PostgreSQL healthy;
- Ollama reachable and actual configured model is `gemma3:12b`;
- Tesseract healthy;
- local working storage healthy;
- active delivery provider healthy.

Confirm no heuristic provider is being used.

PASS/FAIL.

## 3. Digital PDF — complete straight-through V1 flow

Create/use one synthetic **native-text PDF** containing representative V1 values, for example:
- SSN: `123-45-6789`
- DOB: `01/05/1980`
- valid test card: `4111 1111 1111 1111`
- `FICO score: 720`
- also include a non-credit-score control such as `Invoice quantity: 720 units`

Process through the real application.

Verify:
- native extraction is used where appropriate;
- real Ollama analysis runs;
- supported sensitive findings are created;
- unrelated `720` is not treated as a credit score merely because it is numeric;
- high-confidence findings can proceed straight through according to tenant policy;
- redaction, verification, delivery/export, Dashboard/reporting and audit complete.

PASS/FAIL.


## 3A. Customer-specific SSN-last-four policy

This is a mandatory 2026-08-31 pre-acceptance delta. Keep the test small.

With Business Admin policy limited to SSN, DOB, Credit Score and Credit Card Number and `maskSsnLastFour=true`, use a synthetic one-page fixture containing:
- one structurally valid full SSN;
- one explicit last-four-only representation such as `SSN: XXX-XX-1234`;
- one unrelated four-digit control such as `Room number: 1234`;
- a synthetic customer name and email.

Verify:
- full SSN is a finding/redaction;
- only the exposed four digits of the explicit partial SSN are a finding/redaction;
- unrelated four digits are not treated as SSN;
- name and email remain visible because they are not enabled categories for this customer;
- logs/API/audit do not print the raw test values.

Then set `maskSsnLastFour=false` and use a small fresh fixture containing both a full SSN and an explicit last-four-only SSN.

Verify:
- full SSN remains governed by the SSN category and is redacted;
- last-four-only SSN is ignored by policy;
- restore `maskSsnLastFour=true` after the test.

PASS/FAIL.

## 3B. Safe high-level processing trace

For one representative package, confirm the new INFO-level events are present and useful:
- `PACKAGE_PROCESSING_STARTED`
- `AI_ANALYSIS_STARTED` / `AI_ANALYSIS_COMPLETED`
- `DOCUMENT_EXTRACTION_COMPLETED`
- `DOCUMENT_DETECTION_COMPLETED`
- `DOCUMENT_REDACTION_COMPLETED`
- `PACKAGE_DELIVERY_COMPLETED`
- `PACKAGE_PROCESSING_FINISHED`

Verify the trace exposes only safe metadata such as IDs, provider/model, page/native/OCR counts, finding counts by category, page numbers by category, decisions and durations. It must not print raw document/OCR text, finding values, names, email addresses, tokens, secrets, full paths or raw stack traces.

PASS/FAIL.


## 3C. Ollama large-PDF page-retry reliability

This is a focused regression for the 2026-08-31 real-package failure. Do not redesign the provider layer.

Verify configuration/runtime evidence in LOCAL mode:
- `parallelism=2` by default (override remains deployment-configurable);
- `timeoutSeconds=180` by default for LOCAL `gemma3:12b`;
- `pageMaxAttempts=2`;
- focused test `OllamaPageRetryTest` passes.

Use a controlled synthetic multi-page fixture or fake-provider test to prove a single transient page failure:
- retries only the affected page;
- does not replay a page that already succeeded;
- logs `AI_PAGE_ATTEMPT_FAILED` with page/attempt/stable reason/retrying/duration and no raw content;
- logs `AI_PAGE_RECOVERED` if the retry succeeds;
- completes the document with `AI_ANALYSIS_COMPLETED ... pageRetries=<n>` when recovery succeeds.

Also prove retry exhaustion:
- after the configured page attempts are exhausted, the propagated failure is non-retryable to the generic outer document retry decorator;
- the whole document is not restarted;
- optional secondary-provider failover behavior remains available if configured.

Do not add deterministic page prefiltering: all nonblank normalized pages still enter AI analysis.

PASS/FAIL.


## 3D. Focused credit-card precision regression

This is the only newly added detection-precision scope. Do NOT refactor provider selection, prompt architecture, tenant AI profiles, or model abstraction as part of this check.

Run focused test `CreditCardPrecisionTest` and verify:
- `4111 1111 1111 1111` remains a valid synthetic `CREDIT_CARD_NUMBER` candidate;
- contract/member/account identifiers are rejected as card numbers;
- dollar amounts/dates/blank labels are rejected;
- repeated barcode/control markers such as `7777777777777` are rejected;
- `FINANCIAL_ACCOUNT` is not normalized to `CREDIT_CARD_NUMBER`;
- Luhn remains secondary evidence rather than a hard AI-discovery prerequisite;
- rejected AI candidates log only safe type/page/reason/digit-count metadata and never raw candidate text.

If practical, rerun the same 61-page customer-style synthetic/representative fixture and confirm the prior page-24/page-31 false card findings disappear while the page-59 contextual credit score remains.

PASS/FAIL.

## 4. AI discovery independent of deterministic recognition

Use a synthetic native PDF containing a semantic case the deterministic detector does not directly discover, preferably a textual DOB such as:

`Date of Birth: January 5, 1980`

The deterministic DOB detector only directly discovers numeric MM/DD/YYYY or MM-DD-YYYY forms, while semantic AI can recognize the textual DOB.

Verify:
- real Ollama identifies the textual DOB;
- deterministic non-discovery does not suppress the AI finding;
- the finding enters the same Finding/policy/review-redaction path;
- deterministic validation may be NOT_APPLICABLE, but AI discovery survives.

Do not modify production code to manufacture this result.

PASS/FAIL.

## 5. AI/deterministic evidence agreement and challenge

Verify with representative findings that:
- semantic + deterministic agreement can strengthen evidence/confidence;
- deterministic evidence is supporting evidence, not an AI prerequisite;
- a contradictory/unsupported structured candidate is not blindly auto-redacted at high confidence.

Prefer runtime evidence from the acceptance fixtures. If model variance makes a clean disagreement fixture unreliable, combine one runtime case with the existing approved focused test/source evidence rather than writing new product logic.

PASS/FAIL.

## 6. Scanned PDF — complete flow

Create/use a synthetic **image-only/scanned PDF** with supported V1 sensitive content.

Verify:
- page requires Tesseract OCR;
- OCR text and geometry are produced;
- normalized OCR content enters the same real Ollama AI-first pipeline;
- findings use trusted OCR geometry rather than model-generated X/Y;
- redaction/verification/export completes.

PASS/FAIL.

## 7. Mixed PDF — page-by-page normalization

If practical with the existing test tooling, create/use a PDF with:
- at least one native-text page;
- at least one scanned/image page.

Verify:
- native page stays native;
- scanned page uses OCR;
- both feed one coherent downstream AI/finding/policy/redaction flow.

Do not build elaborate tooling solely for this fixture. If fixture creation is genuinely impractical, report that limitation and support the result with source trace + existing digital/scanned runtime evidence.

PASS/FAIL or PASS WITH DOCUMENTED FIXTURE LIMITATION.

## 8. Permanent redaction / irreversibility

Using final redacted outputs from the above scenarios, verify independently:
- source page count preserved;
- expected regions visually covered;
- sensitive text is not extractable/searchable from the output PDF;
- independent PDF text extraction cannot recover original sensitive values;
- original document filename is preserved;
- separate documents remain separate; no merge and no `-redacted` rename convention.

This is a release-blocking requirement.

PASS/FAIL.

## 9. Focused Manual Review

Use Business Admin policy to force or create a review-required finding without changing product code.

Verify:
- package/document routes to Manual Review for business/content uncertainty;
- review is focused on the finding/page;
- masked/safe context is shown;
- reviewer can confirm/dismiss;
- processing resumes correctly;
- review is not converted into an assignment/task-management workflow.

Restore policy afterward.

PASS/FAIL.

## 10. Technical provider failure

Temporarily make real Ollama unavailable during processing of a fresh test package.

Verify:
- configured bounded retry behavior occurs;
- no heuristic AI substitution silently succeeds;
- failure becomes a Technical Exception/Technical Attention state;
- it does NOT become business Manual Review;
- safe reason only; no raw provider response/PII/stack trace.

Restore Ollama afterward.

PASS/FAIL.

## 11. Watched-folder acceptance

Using existing Unit 20/24 functionality, verify in a compact set of fixtures:
- valid trigger/manifest → accepted;
- no trigger → waiting;
- missing required identifier → rejected with safe reason;
- duplicate fingerprint → duplicate/idempotent;
- same business identifiers reuse the BusinessRecord;
- each accepted arrival has a distinct IntakeBatch;
- configured disposition behavior is respected.

PASS/FAIL.

## 12. Business Admin policy drives runtime

Verify one or two high-value live policy effects rather than replaying all Unit 21 tests:
- change review behavior/threshold or REVIEW_ALL and observe processing behavior;
- verify enabled sensitive categories behave according to policy;
- verify business identifier/dashboard-column configuration remains active.

Restore intended defaults after test.

PASS/FAIL.

## 13. Platform Admin acceptance

Verify:
- real PostgreSQL/Ollama/Tesseract/storage/delivery health;
- Platform Admin can run health checks;
- watched-folder Scan Now remains functional;
- Technical Exceptions are visible and remain separate from Manual Review;
- provider/config display does not expose credentials.

PASS/FAIL.

## 14. Lifecycle / retention / export acceptance

Use current package lifecycle evidence plus a representative fresh package.

Verify:
- successful export creates export record/cycle;
- redacted output is durable at destination;
- technical delivery retry does not incorrectly consume another business export cycle;
- original/temporary-artifact retention metadata remains bounded;
- technical hold remains bounded and separate from business review;
- reprocess/export-cycle limits remain intact.

Do not spend credits replaying all three historical cycles if the approved Unit 22 runtime evidence plus one current-cycle smoke check proves non-regression.

PASS/FAIL.

## 15. Shared Dashboard and reports

Verify current acceptance packages appear correctly in:
- shared Dashboard;
- configured business identifier columns;
- stage/status filters;
- KPI/overview cards;
- Technical Attention;
- V1 reports.

Verify displayed counts agree with underlying current records for a representative sample.

PASS/FAIL.

## 16. LOCAL security-mode acceptance

Verify:
- LOCAL mode is explicit;
- default local role is USER, not ADMIN;
- demo role selector works only as LOCAL convenience;
- USER/REVIEWER/APPROVER/BUSINESS_ADMIN/PLATFORM_ADMIN/ADMIN surfaces remain consistent with approved roles;
- local mode works without external IdP.

PASS/FAIL.

## 17. Representative OIDC security regression only

Do NOT repeat the full Unit 25 63-item suite.

Using the existing controlled standards-compliant RS256 issuer/JWKS setup or existing verified tooling, perform only these representative checks:
- valid JWT → protected API works;
- invalid/tampered token → fail closed;
- forged `X-Tenant-Id`, `X-Actor-Id`, `X-Actor-Roles: ADMIN` cannot override JWT;
- Tenant A cannot read a Tenant B package by substituting ID;
- one representative Tenant A write cannot mutate Tenant B data;
- BUSINESS_ADMIN cannot access Platform Admin;
- PLATFORM_ADMIN cannot access Business Admin;
- explicit ADMIN can access both;
- `/api/v1/me` exposes safe identity metadata only.

PASS/FAIL.

## 18. Unknown tenant / fail-closed regression

Using OIDC identity for an unprovisioned tenant, verify:
- safe 403/4xx;
- no persistence/FK 500;
- no tenant data exposure.

PASS/FAIL.

## 19. Legacy original/vault production surface

In OIDC mode verify:
- legacy vault/access-request endpoints are absent/blocked;
- no ORIGINAL artifact download route is available;
- normal REDACTED document access remains functional.

PASS/FAIL.

## 20. Audit + correlation + leakage scan

Inspect representative audit/API/admin responses from acceptance scenarios.

Verify:
- tenant and actor are correct;
- OIDC actor is derived from verified identity where applicable;
- correlation IDs remain useful across representative flow;
- no raw SSN/card/DOB/credit-score values in audit/log/admin responses;
- no raw OCR/full document text;
- no bearer/JWT/client secret/DB password/S3 credential/callback token;
- no raw stack traces.

PASS/FAIL.

## 21. Local delivery acceptance

Verify a complete multi-document package if practical:
- package-atomic publication;
- each document exported separately;
- original filename preserved;
- no partial visible package on failure;
- successful delivery recorded only after full batch success.

PASS/FAIL.

## 22. Authenticated API/callback delivery acceptance

Use a controlled callback receiver.

Verify:
- authenticated callback includes bearer token only in Authorization header;
- HTTPS requirement is enforced in non-local/production configuration;
- package-level idempotency key/behavior remains intact;
- token never appears in logs/API/UI/audit;
- successful package callback records delivery/export correctly.

PASS/FAIL.

## 23. S3/object delivery — conditional claim

First determine whether MinIO or an S3-compatible test endpoint is **already readily available**.

If available:
- run one package delivery;
- verify staged upload → final objects → `_COMPLETE` marker;
- verify document filenames/boundaries;
- verify successful export record.

If NOT readily available:
- do NOT spend credits installing/building an S3 environment just for this pass;
- report: `S3 IMPLEMENTED, NOT FORMALLY RUNTIME-ACCEPTED FOR THIS V1 RC`;
- recommendation may still be APPROVE provided release claims are restricted accordingly.

PASS / NOT CLAIMED.

## 24. Enterprise OIDC provider — conditional deployment validation

If a real enterprise OIDC tenant is already available and credentials can be used safely, perform a basic configuration smoke test.

If not available:
- do NOT spend credits obtaining/configuring Okta solely for this acceptance;
- rely on the already-approved standards-compliant RS256 OIDC verification;
- report real enterprise IdP as deployment validation, not an architecture defect.

PASS / CONTROLLED-ISSUER ACCEPTED.

## 25. No accidental production heuristic path in acceptance

Confirm all Final Acceptance processing evidence used `ollama` with `gemma3:12b`.

Report any occurrence of `local-semantic-heuristic` during formal acceptance as FAIL for that scenario.

Do not remove the dev adapter unless it creates an actual production bypass.

PASS/FAIL.

## 26. IDP/domain architecture non-regression

Source-check only; do not deeply re-review architecture.

Verify security/IdP framework remains at edge/platform layers and has not leaked into domain/AI/redaction/business rules.

PASS/FAIL.

## 27. Final hygiene

Remove temporary acceptance artifacts:
- synthetic PDFs/images;
- mock callback/JWKS temporary data;
- temporary tokens/keys;
- watched-folder fixtures;
- temporary storage/output not intended for source;
- debug scripts/logging not intended for repository.

Restore shipped/default configuration.

Do not delete intentional repository docs/tests.

PASS/FAIL.

## 28. Final builds after cleanup

Run:
- `mvn clean verify`
- `npx tsc --noEmit`
- `npm run build`

All must pass.

PASS/FAIL.

---

# SOURCE-CHANGE RULE

If you change ANY source/test/config file during Final Acceptance:

For every changed file report:
- exact path;
- observed acceptance defect;
- why a change was necessary;
- smallest correction made;
- exact affected checks rerun;
- regression impact.

Do not make cleanup/refactor/style changes unrelated to a failing acceptance item.

If no source change is required, state exactly:

`Source changes this Final Acceptance pass: NONE`

---

# FINAL REPORT — EXACT FORMAT

Return a numbered report with exactly these 39 items:

1. Backend initial build/startup/Liquibase — including expected migration 012 and final 35-changeSet state
2. Frontend initial build
3. Real provider health + confirmed Ollama `gemma3:12b`
4. Digital PDF straight-through acceptance
5. Customer policy persistence — only four enabled categories + `maskSsnLastFour`
6. SSN-last-four enabled behavior — full SSN + exposed last four redacted
7. SSN-last-four negative controls — unrelated four digits/name/email stay visible
8. SSN-last-four disabled behavior — full SSN still redacts, partial SSN stays visible
9. Safe high-level processing-log trace
10. Context-negative `720` control
11. AI-only/deterministic-miss textual DOB acceptance
12. AI/deterministic evidence agreement/challenge
13. Scanned PDF acceptance
14. Mixed PDF acceptance
15. Permanent-redaction irreversibility
16. Original filename/document-boundary preservation
17. High-confidence straight-through behavior
18. Focused Manual Review behavior
19. Technical-provider failure behavior
20. Watched-folder acceptance
21. Business Admin live-policy acceptance
22. Platform Admin acceptance
23. Lifecycle/retention/export acceptance
24. Shared Dashboard/reporting acceptance
25. LOCAL security-mode acceptance
26. Representative OIDC security regression
27. Cross-tenant read/write isolation
28. Unknown/unprovisioned tenant fail-closed behavior
29. Legacy original/vault production hardening
30. Audit/correlation/PII-secret leakage scan
31. Local package-atomic delivery
32. Authenticated API/callback delivery
33. S3 runtime result — PASS or NOT CLAIMED
34. Enterprise OIDC result — real-provider PASS or controlled-issuer accepted
35. Real-AI-only acceptance confirmation — no heuristic evidence
36. IDP/domain-boundary non-regression
37. Cleanup/hygiene
38. Exact source files changed + explanation, or NONE; final backend + frontend build results
39. Recommendation — exactly one:
    - `V1 RELEASE CANDIDATE APPROVE`
    - `DO NOT APPROVE V1 RELEASE CANDIDATE`

For any FAIL, state whether it is:
- RELEASE BLOCKER
- ACCEPTED LIMITATION / CLAIM RESTRICTION
- ENVIRONMENT LIMITATION

Stop after item 39.

Do NOT start Unit 26.
Do NOT start post-V1 features.
Do NOT create release documentation beyond factual verification notes; ChatGPT will consolidate release documentation after reviewing this report.


## Runtime-derived focused regression checks added 2026-08-31

During final verification, specifically prove:

1. Ordinary dates such as `Date of Sale: 04-16-2026`, payment dates and signature dates do not become `DATE_OF_BIRTH` findings merely because they match a date pattern.
2. A true labeled DOB remains detectable and only the DOB value span is selected.
3. Privacy-policy prose that contains the words `Social Security number` but no exposed SSN digits produces no SSN finding.
4. A full SSN and configured last-four-only SSN still work.
5. Manual Review page preview succeeds for a valid pending-review finding. If a preview is intentionally made invalid/unavailable, verify safe specific diagnostics and no raw document/sensitive-value leakage.
6. `FINDING_EVIDENCE_SUMMARY` contains only counts/types and no raw values.

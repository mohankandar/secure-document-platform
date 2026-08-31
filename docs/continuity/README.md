# LexaVault Continuity Entry Point

Read these files before any future ChatGPT coding session or Kiro verification session.

1. `00_LexaVault_V1_Master_Current.md` — current project source-of-truth continuity packet.
2. `01_Final_V1_Security_Integration_Acceptance_Freeze.md` — current frozen Final V1 acceptance scope.
3. `02_Final_V1_Pre_Acceptance_Code_Review_Historical.md` — 2026-08-30 review that found no code change was needed at that time; **superseded** by the explicitly approved 2026-08-31 customer-policy/observability delta.
4. `03_Final_V1_Pre_Acceptance_Policy_Observability_Update.md` — current post-Unit-25 pre-acceptance delta.
5. `04_Final_V1_Kiro_Acceptance_Prompt_Current.md` — current credit-conscious Kiro acceptance prompt including SSN-last-four and safe-log verification.
6. `05_Final_V1_Pre_Acceptance_Delta_Self_Review.md` — ChatGPT self-review/build evidence and explicit environment limitations.
7. `06_Ollama_Page_Retry_Fix_Self_Review.md` — focused self-review for the 2026-08-31 large-PDF Ollama retry correction.

## Operating model

- ChatGPT implements, self-reviews, documents and packages code.
- Kiro performs independent structured verification and makes only minimal genuine-defect corrections.
- Do not burn Kiro credits reimplementing approved work, broadly refactoring, generating generic tests, or exploring post-V1 features.
- Unit 25 remains approved. The current source includes the pre-acceptance customer-policy/observability delta plus the focused Ollama page-retry reliability correction; both must be verified before V1 Release Candidate approval.
- There is no Unit 26 in the current frozen plan.

## Current customer masking policy

Only SSN, DOB, Credit Score and Credit Card Number are enabled categories for the current customer. Name/email and other fields remain visible. SSN last-four-only exposure is controlled by the tenant `maskSsnLastFour` flag.

- **Ollama large-PDF reliability (2026-08-31):** current master and acceptance freeze include the focused page-level retry/concurrency/timeout correction triggered by the real 61-page package test.


- `07_Detection_Precision_Manual_Review_Preview_Fix.md` — runtime-derived precision and Manual Review preview correction/self-review.

- `08_Credit_Card_Precision_Fix_Self_Review.md` — focused page-24/page-31 credit-card false-positive correction; provider-neutral/tenant-prompt work remains deferred.

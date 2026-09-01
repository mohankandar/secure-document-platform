# LexaVault Continuity Entry Point

Read these files before any future ChatGPT coding session or Kiro verification session.

1. `00_LexaVault_V1_Master_Current.md` — current project source-of-truth continuity packet.
2. `01_Final_V1_Security_Integration_Acceptance_Freeze.md` — current frozen Final V1 acceptance scope.
3. `02_Final_V1_Pre_Acceptance_Code_Review_Historical.md` — historical 2026-08-30 pre-acceptance review; superseded where later runtime evidence/deltas apply.
4. `03_Final_V1_Pre_Acceptance_Policy_Observability_Update.md` — customer-policy/safe-observability delta.
5. `04_Final_V1_Kiro_Acceptance_Prompt_Current.md` — current credit-conscious Kiro verification prompt.
6. `05_Final_V1_Pre_Acceptance_Delta_Self_Review.md` — original pre-acceptance delta self-review.
7. `06_Ollama_Page_Retry_Fix_Self_Review.md` — page-level Ollama reliability correction.
8. `07_Detection_Precision_Manual_Review_Preview_Fix.md` — DOB/SSN precision + Manual Review preview diagnostics correction.
9. `08_Credit_Card_Precision_Fix_Self_Review.md` — credit-card precision correction, now runtime validated on the real 61-page customer package.
10. `09_Tenant_AI_Policy_Provider_Routing_Self_Review.md` — current tenant-driven prompt + per-tenant provider/model routing delta.
11. `10_Tenant_AI_Provider_Configuration_Guide.md` — deployment examples for Ollama/Gemma/Qwen, vLLM/OpenAI-compatible, per-tenant profiles, and fail-closed routing.
12. `11_Dashboard_Manual_Review_Lifecycle_Workflow_Correction.md` — smart Dashboard refresh, package-scoped/exact-page Manual Review, actionable counts, and Approver Dashboard-only package removal.
13. `12_Manual_Review_API_ID_Serialization_Fix.md` — runtime-proven `[object Object]` review-ID serialization defect and narrow API-boundary correction.

## Operating model

- ChatGPT implements, self-reviews, documents and packages code.
- Kiro performs independent structured verification and makes only minimal genuine-defect corrections.
- Do not burn Kiro credits reimplementing approved work, broadly refactoring, generating generic tests, or exploring post-V1 features.
- Unit 25 remains approved. Current post-Unit-25 pre-acceptance deltas require final verification before V1 Release Candidate approval.
- There is no Unit 26 in the current frozen plan.

## Current runtime evidence

The real 61-page customer PDF now completes with the expected precision result: one `CREDIT_SCORE` finding on page 59, no DOB/SSN/credit-card final false positives, verified permanent redaction, successful local delivery, package `COMPLETED`.

Manual Review package scoping and exact-page rendering are runtime validated, and the `[object Object]` API-ID correction is runtime validated. Dashboard polling and Approver `Remove from Dashboard` are also runtime validated. The final-review-item async-resume correction is now runtime validated: the reviewer save returns promptly and redaction/verification/export continue successfully in the background. Dashboard polling, package-scoped exact-page Manual Review, and Approver `Remove from Dashboard` are also runtime validated. The current role/capability + Technical Attention delta is implemented and awaits local verification.

## Current AI architecture delta

- Business Admin policy owns enabled redaction categories/options.
- `SensitiveContentPromptBuilder` composes only those categories; Ollama does not own customer policy text.
- Deployment/Platform configuration owns provider, endpoint, model and secrets.
- Ollama and OpenAI-compatible/vLLM transports are supported.
- Per-tenant profiles are deployment-managed in V1.
- `require-tenant-profile=true` fails closed instead of sending an unconfigured tenant to the default AI endpoint.
- No browser credential vault/hot swap and no DB migration were introduced.


## Current operational workflow delta

- Dashboard smart polling: 5s active / 30s idle / paused hidden / refresh on return.
- Contract/package entry to Manual Review is package-scoped; global review stays tenant-wide.
- Exact review page is verified using response page metadata; stale preview state is reported safely.
- Approver uses the row `⋮` menu → `Remove from Dashboard` for a `COMPLETED` package. This hides it from the normal Dashboard only; DB/audit/report history remains and existing retention cleanup handles physical files.

- `13_Dashboard_Action_Menu_and_Remove_Confirmation.md` — approved `⋮` package actions and cross-platform confirmation UX.
- `14_Dashboard_Action_Menu_Self_Review.md` — implementation/self-review for the approved dashboard package action UX polish.

15. `15_Manual_Review_Last_Item_Async_Resume_Fix.md` — final-review-item non-blocking resume correction; reviewer save returns promptly while redaction/verification/export continue through the existing processing orchestrator.
16. `16_Role_Capability_and_Technical_Attention_Workflow.md` — product-defined V1 role/capability mapping, three-admin role split, safe Technical Issue Details, and selective technical retry.

17. `17_Role_Capability_Technical_Attention_Self_Review.md` — implementation self-review and focused manual verification matrix for the role/capability + Technical Attention delta.
18. `18_Batch2_One_Page_Multi_PDF_Intake.md` — accepted one-page multi-PDF intake workflow.
19. `19_Batch2_One_Page_Multi_PDF_Intake_Self_Review.md` — Batch 2 verification record.
20. `20_Batch3_Breadcrumb_Navigation_Polish.md` — accepted breadcrumb/navigation scope.
21. `21_Batch3_Breadcrumb_Navigation_Polish_Self_Review.md` — Batch 3 verification record.
22. `22_Batch4_Executive_Operational_Reports.md` — Batch 4 reporting definitions, role model, filters, and exclusions.
23. `23_Batch4_Reports_Self_Review.md` — Batch 4 verification status and manual acceptance matrix.

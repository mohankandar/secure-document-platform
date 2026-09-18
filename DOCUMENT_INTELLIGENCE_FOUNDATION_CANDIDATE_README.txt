LexaVault V1 - Versioned & Reversible Document Intelligence Foundation
Candidate date: 2026-09-07

PURPOSE
Build the safety/versioning foundation before adaptive Document Profile Intelligence is allowed to influence processing.
This unit does NOT add adaptive document-profile detection itself and does NOT change the frozen R11 discovery/redaction behavior.

TENANT MODES
- DISABLED (default): frozen R11/general-AI baseline only; adaptive/profile intelligence is skipped.
- OBSERVE: installed intelligence may execute/observe but cannot affect redaction, review, or export decisions.
- ENABLED: installed intelligence may contribute context/priors; baseline discovery remains mandatory.

SAFETY GUARANTEES
- Baseline discovery is always required.
- Existing/customer tenants default to DISABLED.
- One immutable policy/bundle snapshot is captured per package processing cycle.
- Mid-cycle Business Admin changes affect only later cycles.
- Missing/unusable intelligence metadata falls back to frozen baseline and must not block package processing.
- DISABLED remains available even if the tenant's remembered bundle pin is retired.
- RETIRED bundles cannot be newly selected, but existing pins/last-known-good rollback targets remain executable.
- No raw PDF/OCR/prompt/model-response/sensitive values are stored in intelligence metadata or logs.
- Business Admin owns mode/version selection through TENANT_POLICY_MANAGE. Tenant IT and Platform Operations do not gain this capability.

VERSION CATALOG
The global catalog is deployment/release managed and read-only to tenant Business Admins. The foundation models CANDIDATE/PROMOTED/RETIRED status so future intelligence bundles can be regression-gated before promotion. Only PROMOTED bundles can be newly selected by tenants.

BASELINE BUNDLE
r11-baseline-v1
- model version: qwen3.5:4b (current LOCAL/demo reference)
- prompt: r11
- detector: r11
- OCR rules: r11
- profile: none
- validation rules: r11
- thresholds: r11

PERSISTENCE
Liquibase 023 adds one changeSet and three tables:
- document_intelligence_bundles
- tenant_document_intelligence_policies
- package_processing_intelligence_snapshots
Expected databasechangelog count after this candidate: 54.

SUPPORTABILITY
Safe intelligence mode/bundle/version flags are added to Structured JSON Logging and processing-incident fingerprints so Support Console diagnostics can tie failures to the exact processing-cycle intelligence context.

MINOR PRIOR FINDING INCLUDED
LOCAL/demo bootstrap repairs only the exact stale Service Entity allow-list {000,001,002} to the reference {000,001,002,003,004,005}. It does not overwrite arbitrary Business Admin configuration.

NEXT STEP
Run build-all.cmd, then follow DOCUMENT_INTELLIGENCE_FOUNDATION_LOCAL_CHECKLIST.md. Do not ask Kiro to verify until local runtime/UI validation is green.

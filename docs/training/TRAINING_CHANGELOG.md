# LexaVault Training Change Log

This file records training impact whenever an accepted feature changes how Business, Tenant IT, or Platform Support work.


**Entries before 2026-11-25 are kept as they were written.** Screen and role names in them may no longer exist.

---

## 2026-11-25 — Guides checked against the shipped screens (task 0095)

- Business guide: rewritten for Processor, Supervisor, Business Admin and Report Viewer with the real names. "Elevated User", "Submit Manual Processing", "Technical Attention" and reprocessing or removal by an elevated user are gone. Business Admin no longer "includes the business workflow".
- IT guide: rewritten; "Support Incidents" is **Support › Problems, Export, Status**. Says what IT can't do.
- Platform Operations guide: rewritten; says that Platform Operations reads problems and can't act on them.
- Added: entities and switcher (0058), notifications and approval links (0059), case fields (0061), catalog and packs (0062), reject (0071), person name (0072), priority (0073), page to check (0074), first entity (0075), bulk approve and four eyes (0076), queue and time left (0078), Bulk sources (0079), Reports tabs (0081), error-code guide (0082), second check (0083).
- New: `WHATS_NEW.md`.
- Pictures: 21, all new, made-up data. The two old ones were replaced under the same names; the old manual processing picture showed (made-up) values that LexaVault redacts.
- In-app help: kinds on by default, keeping times counted from the export, stopped and rejected cases, report breakdown fields, licence.
- Install guides: OFFBOARDING, MONITORING, SETUP, CASE_FIELDS, NOTIFICATIONS, LICENSING, backup-and-restore. Product pages: AUTHORIZATION, REPORTING, WORKFLOW_AND_LIFECYCLE, V1_SCOPE, INTEGRATIONS (two lines).

---

## 2026-09-06 — Training governance introduced

**Status:** ACCEPTED

### Business impact
No product behavior change. Establishes a maintained Business User Training Guide.

### Tenant IT impact
No product behavior change. Establishes a maintained Tenant IT Support Guide.

### Platform Support impact
No product behavior change. Establishes a maintained Platform Support / Operations Guide.

### Documentation impact
- Added training Definition of Done.
- Added audience-specific living guides.
- Repository documentation established as source of truth.

---

## 2026-09-06 — Business Continuity Manual Processing Fallback

**Status:** VERIFIED / FROZEN

### Business training impact
- Added `Continue Manually` behavior after automatic processing cannot safely complete.
- Standard User / Document Processor may continue without waiting for IT.
- Added full-document manual processing with visual page thumbnails.
- Added non-sequential page selection.
- Added manual redaction add/remove workflow.
- Added business attestation and normal redaction/verification/delivery continuation.
- Explicitly documented that there is no force-export bypass.

### Tenant IT impact
- Business can complete while the underlying technical incident remains open.
- Business completion must not be interpreted as technical resolution.
- Technical incident remains available for investigation.

### Platform Support impact
- Support must distinguish business outcome from technical incident status.
- Manual completion is a workaround, not proof that the processing defect is fixed.

### Screenshots
- `assets/manual-processing-business-continuity.png`

---

## 2026-09-06 — AI Processing Resilience

**Status:** VERIFIED / FROZEN

### Business training impact
No new normal business action. Automatic recovery occurs before business fallback is required.

### Tenant IT impact
- `AI_INVALID_RESPONSE` receives bounded targeted retry.
- Multi-page batch may split to page-level processing.
- Failure occurs only after safe recovery is exhausted.

### Platform Support impact
- Same-batch retries, split fallback, and page-level exhaustion are expected recovery behavior.
- Whole-document replay should not be used as the default recovery mechanism.
- Support should use safe reason/attempt evidence rather than raw AI responses.

---

## 2026-09-06 — Structured Processing Incident Foundation

**Status:** VERIFIED / FROZEN

### Business training impact
Minimal. Business sees that the technical incident remains recorded while Business Continuity is used.

### Tenant IT impact
- Technical Issue Details now has a durable structured Incident ID.
- Incident may remain `BUSINESS_CONTINUED` after the business outcome is `COMPLETED`.
- Processing fingerprint identifies provider/model/application profile/version.
- Incident history records lifecycle events.

### Platform Support impact
Introduces a generic incident foundation:
- `processing_incidents`
- `processing_incident_events`
- stable stage/reason/category fields
- durable Incident ID
- processing fingerprint
- business-continuity status/outcome
- safe metadata
- event history

Runtime lifecycle exercised:
`FAILURE_RECORDED -> BUSINESS_CONTINUITY_STARTED -> BUSINESS_CONTINUITY_SUBMITTED -> BUSINESS_COMPLETED`

Observed parent state after business completion:
- incident status: `BUSINESS_CONTINUED`
- business continuity used: `true`
- business outcome: `COMPLETED`
- `resolved_at`: `NULL`

**Independent verification:** Kiro PASS — backend 166 tests green, frontend build PASS, auth 8/8, Liquibase 52 applied changesets, no source modifications, no Critical/Major/Minor findings.

### Screenshots
- `assets/technical-issue-details-structured-incident.png`

---

## Earlier baseline — R11 customer-demo accuracy

**Status:** VERIFIED / FROZEN

### Business training impact
- Protected V1 categories: DOB, SSN, full PAN, Credit Score.
- Normal Needs Review remains system-guided and focused on uncertain findings/pages.
- Confirmed sensitive findings are masked in review preview.
- Export remains blocked until required review/redaction gates are satisfied.

### Tenant IT / Platform Support impact
- R11 accuracy behavior is a frozen baseline.
- Do not treat performance/resilience/support improvements as a reason to refactor accepted detection behavior.

---

## 2026-09-06 — Structured JSON Logging for SaaS/Production

**Status:** IN VALIDATION — final hardening follow-up

### Business training impact
No normal business workflow change. Business users continue to use the same processing, review, Business Continuity, approval, and delivery workflows.

### Tenant IT impact
- Production/SaaS deployments emit one JSON object per log event by default.
- JSON is now the default for Local/demo as well as production-style startup.
- Readable Local output is an explicit `-LoggingFormat Readable` override.
- Application log level can be changed at startup with `-LogLevel`.
- Optional local/on-prem bounded rolling JSON files can be enabled with `-EnableRollingFileLog`.
- `X-Correlation-Id` is preserved when valid and returned on the response; invalid/missing values are replaced with a generated UUID.
- Support tickets should continue to use Incident ID / Correlation ID rather than copying raw document content into tickets.

### Platform Support impact
Introduces privacy-safe structured fields when available:
- service / environment
- tenantId / actorId
- correlationId
- packageId / documentId / incidentId
- eventName
- stage / component / failureCategory / reasonCode
- provider / model
- attempt / maxAttempts / retryable
- durationMs / status / decision / scope / businessOutcome
- safe counters such as pageCount, batchCount, providerCalls, pageRetries, findingCount

The MDC/context layer uses an explicit allow-list. Raw prompt/provider response/OCR/document/matched text and protected values are not approved structured fields.

### Database / migration impact
None. Expected Liquibase count remains **52**.

### Verification evidence / final hardening
- Initial Structured JSON Logging candidate independently verified by Kiro: **PASS**
- backend 173 tests green, frontend build PASS, auth 8/8, Liquibase 52
- JSON and readable runtime modes verified
- correlation/MDC propagation and privacy allow-list verified
- final hardening changes now pending local validation:
  - JSON default for Local startup
  - configurable `-LogLevel`
  - optional bounded rolling JSON file

---

## 2026-09-06 — Support Console — Technical Incident Search & Triage

**Status:** IN VALIDATION

### Business training impact
No normal Business workflow change.

### Tenant IT impact
- Adds tenant-scoped Support Incidents search.
- Search by Primary Business Identifier, Incident ID, Correlation ID, stage/reason/status/outcome.
- Adds business-impact indicator and durable incident timeline.
- Reinforces privacy-safe ticket/escalation data.

### Platform Support impact
- Adds privacy-safe read-only incident case search and detail.
- Uses Incident ID as durable case identity and Correlation IDs as per-operation pivots.
- Primary Business Identifier remains tenant-configurable.
- Resolution/escalation actions and centralized-log deep links are deferred.

### Database / migration impact
No migration. Existing durable incident tables and indexes remain authoritative.

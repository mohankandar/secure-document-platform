# LexaVault Platform Support / Operations Guide

**Audience:** Platform Operations, Tier 2/Tier 3 Support, Engineering Support  
**Current structured-incident foundation status:** VERIFIED / FROZEN.

---

## 1. Support objective

Convert a customer ticket from:

> "This contract failed."

into a durable, structured case with:

- business identity
- Incident ID
- package/document correlation
- processing stage/component
- stable reason code
- attempts/retryability
- processing fingerprint
- business impact/outcome
- event timeline
- safe links into logs/traces/metrics

The incident record is the case file. Logs are supporting evidence, not the only source of truth.

---

## 2. Core incident entities

### `processing_incidents`

Represents the durable parent incident.

Key concepts:
- tenant
- package/document
- stage
- component
- failure category
- reason code
- retryable
- attempt count
- correlation ID
- status
- business continuity used
- business outcome
- safe summary
- processing fingerprint
- safe metadata
- first/last observed
- resolved at

### `processing_incident_events`

Append-oriented incident history.

Events observed in current Business Continuity validation:

- `FAILURE_RECORDED`
- `BUSINESS_CONTINUITY_STARTED`
- `BUSINESS_CONTINUITY_SUBMITTED`
- `BUSINESS_COMPLETED`

Each event is linked by `incident_id`. Correlation IDs may legitimately differ because separate requests/actions participate in one durable incident lifecycle.

---

## 3. Why Incident ID is different from Correlation ID

**Incident ID**
- durable parent identity
- survives multiple HTTP/workflow operations
- used for support case continuity

**Correlation ID**
- request/operation correlation
- may change between failure, manual-continuity start, submission, and completion
- used to pivot to logs/traces for one operation

Never use correlation ID as the parent relationship for the incident timeline.

---

## 4. Generic processing stages

The incident architecture is intentionally not AI-specific.

Supported/future stages should use one common model, for example:

- INTAKE
- TEXT_EXTRACTION
- OCR
- AI_ANALYSIS
- SENSITIVE_DETECTION
- DOCUMENT_PROFILE
- IDENTIFIER_EXTRACTION
- MANUAL_REVIEW
- REDACTION
- VERIFICATION
- DERIVED_DOCUMENT_SPLIT
- ROUTING
- DELIVERY
- STORAGE
- RETENTION
- MIGRATION_REVALIDATION

Do not introduce a parallel incident table per processor/provider.

---

## 5. Current AI incident example

Classification:

- stage: `AI_ANALYSIS`
- component: `AI`
- failure category: `DEPENDENCY_RESPONSE_ERROR`
- reason code: `AI_INVALID_RESPONSE`
- retryable: false after safe retry exhaustion
- attempt count: 2

Automatic resilience occurs before the incident becomes business-blocking:

`same batch retry`
`-> split fallback`
`-> page-level retry`
`-> technical failure`

Detailed provider batch attempts may remain in safe application logs in the current foundation; the durable incident DB records the resulting incident and lifecycle history.

---

## 6. Business impact is independent from technical resolution

Critical rule:

`Business COMPLETED != Incident RESOLVED`

Validated example:

- incident status: `BUSINESS_CONTINUED`
- business continuity used: true
- business outcome: `COMPLETED`
- resolved at: NULL

Interpretation:
- Business safely worked around the failure.
- The underlying automation defect remains unresolved.
- Incident should remain available for trend analysis and engineering follow-up.

---

## 7. Processing fingerprint

Use the fingerprint to correlate incidents with a processing/intelligence configuration.

Current fields observed:
- `applicationVersion`
- `profile`
- `aiProvider`
- `aiModel`

Target future fingerprint:
- applicationVersion
- provider/model/modelVersion
- promptVersion
- detectorVersion
- ocrVersion
- documentProfileVersion
- policyVersion
- configurationVersion / intelligence-bundle version

This enables questions such as:
- Did failures increase after a model change?
- Is one prompt version correlated with malformed responses?
- Is a document-profile version causing a tenant-specific regression?
- Which version is the last-known-good baseline?

---

## 8. Initial support query patterns

### Find recent incidents

```sql
SELECT
    id,
    tenant_id,
    package_id,
    document_id,
    stage,
    component,
    failure_category,
    reason_code,
    retryable,
    attempt_count,
    correlation_id,
    status,
    business_continuity_used,
    business_outcome,
    first_observed_at,
    last_observed_at,
    resolved_at
FROM processing_incidents
ORDER BY last_observed_at DESC
LIMIT 100;
```

### Incident history

```sql
SELECT
    incident_id,
    event_type,
    attempt_number,
    scope,
    reason_code,
    retryable,
    correlation_id,
    safe_metadata,
    occurred_at
FROM processing_incident_events
WHERE incident_id = :incident_id
ORDER BY occurred_at;
```

### Open/unresolved incidents where business completed

```sql
SELECT
    id,
    tenant_id,
    package_id,
    stage,
    reason_code,
    status,
    business_outcome,
    last_observed_at
FROM processing_incidents
WHERE resolved_at IS NULL
  AND business_outcome = 'COMPLETED'
ORDER BY last_observed_at DESC;
```

Use tenant-scoped application/support tooling in production; direct SQL is primarily an engineering/local diagnostic reference until a formal Support Console is completed.

---

## 9. Ticket workflow

1. Receive Contract Number / business reference.
2. Locate Incident ID.
3. Determine:
   - customer blocked?
   - Business Continuity available/used?
   - business outcome?
4. Read stage/reason/attempt evidence.
5. Read fingerprint.
6. Review incident timeline.
7. If required, pivot via correlation IDs into centralized logs/traces.
8. Compare with similar incidents across tenant/model/version/time.
9. Escalate with Incident ID as the primary case reference.
10. Resolve only when the technical condition is actually fixed/closed according to incident-resolution policy.

---

## 10. Platform incident detection

Future Support Console/metrics should make it possible to answer:

- incidents by reason code over time
- incidents by tenant
- incidents by provider/model/version
- retry/recovery success rate
- business-continuity usage rate
- customer-blocked vs business-completed counts
- widespread identical errors across tenants

A burst of the same stable reason code across multiple tenants should be treated as a potential platform incident rather than a series of unrelated customer tickets.

---

## 11. Privacy / logging policy

Never persist in ordinary incident/support metadata:
- raw prompt
- raw provider response
- OCR body
- document text
- matched sensitive text
- SSN
- full PAN
- DOB
- credit-score value

Safe:
- IDs
- stage/component
- reason code/category
- attempt counts
- retryability
- page/batch numbers when content-free
- durations
- timestamps
- provider/model/version identifiers
- business outcome
- sanitized summaries

If raw provider output is ever needed for deep diagnostics, it must be a separate, encrypted, restricted, short-retention, access-audited mechanism. It must not be added casually to `safe_metadata` or normal logs.

---

## 12. Current boundary and next observability work

The structured incident foundation is not the entire observability program.

Next related capabilities:
- Support Console search by Contract Number / Incident ID / correlation ID
- filters by status, stage, reason, tenant, time
- clear customer-blocked vs business-continued indicator
- incident resolution/escalation workflow
- structured JSON application logging
- centralized logs/traces
- operational metrics/SLOs
- version-aware regression and last-known-good analysis

Do not overload this foundation with every future support feature in one change. Preserve the generic incident model while adding bounded capabilities.

---

## 13. Structured JSON logging

**Status:** IN VALIDATION until local and independent verification is complete.

Production/SaaS uses structured JSON console logging by default. Local/demo uses a readable console pattern by default and can be switched to the JSON configuration for smoke testing.

### Stable operational fields

When the context is available, logs can carry:
- `service`, `environment`
- `tenantId`, `actorId`
- `correlationId`
- `packageId`, `documentId`, `incidentId`
- `eventName`
- `stage`, `component`, `failureCategory`, `reasonCode`
- `provider`, `model`
- `attempt`, `maxAttempts`, `retryable`
- `durationMs`, `status`, `decision`, `scope`, `businessOutcome`
- safe counters: `pageCount`, `batchCount`, `providerCalls`, `pageRetries`, `findingCount`

### Privacy contract

Structured MDC is explicit allow-list only. Do not add document/OCR text, matched values, prompts, raw provider responses, SSNs, PANs, DOBs, credit-score values, credentials, tokens, or document contents to the allow-list.

### Correlation semantics

`incidentId` remains the durable technical case identity. `correlationId` remains an operation/request identity and may differ across failure, Business Continuity, retry, and resolution actions. JSON logging makes it easier to pivot from either ID into centralized logs later; it does not replace the durable incident/event database.

### Logging defaults, levels, and retention ownership

- Default format: JSON.
- Local readable override: `-LoggingFormat Readable`.
- Local application-level override: `-LogLevel TRACE|DEBUG|INFO|WARN|ERROR`.
- SaaS/container: JSON stdout; centralized platform owns rotation/retention.
- Local/on-prem optional: `-EnableRollingFileLog` adds bounded rotating JSON files.
- Default application-managed rollover: daily and 100MB, 14 history periods, 2GB total cap.

Do not confuse operational log retention with document/original retention. They are independent policies.

## 13. Support Console — Incident Search & Triage

**Status:** IN VALIDATION

The Support Console is the read-only case-search layer over `processing_incidents` and `processing_incident_events`.

Search supports Primary Business Identifier, Incident ID, Correlation ID, package/document, stage, reason code, status, business outcome, and time range. Platform Operations may supply a tenant ID for an authorized cross-tenant investigation.

Use the Incident ID as the durable case identity and event-level Correlation IDs to pivot to structured logs. A business outcome of COMPLETED does not imply the technical incident is RESOLVED.

The current unit is read-only. Resolution/escalation actions and centralized log deep links are intentionally deferred.

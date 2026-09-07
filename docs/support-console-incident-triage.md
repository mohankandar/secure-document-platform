# Support Console — Technical Incident Search & Triage

**Status:** IN VALIDATION  
**Scope:** Read-only search and triage foundation.

## Purpose

Give Tenant IT and Platform Operations a privacy-safe way to locate durable processing incidents, understand customer/business impact, inspect the processing fingerprint and lifecycle, and correlate the case with operational logs.

This unit does **not** add incident resolution, escalation actions, metrics, or centralized-log deep links.

## Authorization

- `TENANT_IT_ADMIN`: tenant-scoped search and detail only.
- `PLATFORM_OPERATIONS_ADMIN`: may use the optional `tenantId` support query parameter for an authorized cross-tenant investigation.
- Business roles do not receive the Support Console.

Backend capability enforcement remains authoritative through `TECHNICAL_EXCEPTION_VIEW`.

## API

- `GET /api/v1/support/incidents`
- `GET /api/v1/support/incidents/{incidentId}`

Supported search fields include:
- primary business identifier
- incident ID
- correlation ID
- package/document ID
- stage
- reason code
- status
- business outcome
- business-continuity used
- retryable
- time range
- page / size

Results are tenant-scoped and paged.

## Primary business identity

The query resolves the tenant-configured Primary Business Identifier by joining:

`processing_incidents -> document_packages -> business_record_identifiers -> business_identifier_definitions`

No Contract Number is copied into `processing_incidents`, and the UI does not hardcode Contract Number.

## Business impact

The Support Console derives a simple triage indicator:

- `COMPLETED`: business outcome completed
- `CONTINUED`: Business Continuity is active/used but not yet completed
- `BLOCKED`: technical failure remains business-blocking

Business completion remains independent from technical resolution.

## Privacy boundary

The support API/UI intentionally excludes:
- document/OCR text
- protected values
- matched sensitive text
- raw prompts
- raw provider responses
- credentials/tokens

Safe operational identifiers, reason codes, stage, timestamps, counts, fingerprint values, correlation IDs and sanitized incident metadata remain available.

## Deferred

- Resolve action
- Escalate action
- centralized log deep-link
- incident analytics / trend dashboard
- platform-wide metrics/SLO views

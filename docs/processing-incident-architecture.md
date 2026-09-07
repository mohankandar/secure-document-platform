# Structured Processing Incident Architecture

## Purpose

LexaVault records technical processing failures in a durable, tenant-scoped incident model so support and future processors do not depend on parsing free-form logs or AI-specific audit strings.

Business workflow state and technical incident state are intentionally independent. A package may complete through Business Continuity Manual Processing while its technical incident remains open for IT investigation.

## Stable processing-stage taxonomy

The incident model is provider-neutral and supports current and foreseeable stages:

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
- DOCUMENT_PROCESSING
- UNKNOWN

Future document-profile intelligence, handwriting/government-ID processing, migration/revalidation, or new integrations should reuse this incident foundation rather than introduce provider-specific failure tables.

## Durable incident core

`processing_incidents` stores stable support facts such as:

- tenant/package/document scope
- stage and component/dependency
- stable reason code and failure category
- retryable classification and exhausted attempt count
- correlation ID
- incident status
- Business Continuity usage/outcome
- safe summary
- processing/version fingerprint
- privacy-safe extensible metadata
- first/last observed and resolution timestamps

`processing_incident_events` stores safe lifecycle history such as failure recorded, retry scheduled, Business Continuity started/submitted/completed, and automatic resolution after successful retry.

Provider-level logs may contain additional counters, but the incident database must never store document content or protected values.

## Backward compatibility

Technical Issue Details prefer the durable structured incident store. Historic failures created before this migration continue to work through the existing sanitized audit fallback.

## Business continuity semantics

- Automatic retry success may resolve the technical incident.
- Business Continuity Manual Processing changes the incident to `BUSINESS_CONTINUED`.
- Successful manual business completion records a business outcome of `COMPLETED` but does **not** automatically resolve the technical incident. IT may still investigate it.
- Incident persistence is support telemetry and must never block retry, redaction, manual business continuation, or delivery if incident storage itself is unavailable.

## Privacy boundary

Do not persist in incident fields, event metadata, or normal logs:

- raw prompts
- raw AI/provider responses
- OCR or extracted body text
- SSNs
- PANs
- dates of birth
- credit scores
- matched sensitive values
- document contents

Safe identifiers, stage/reason codes, counts, page/batch references, timings, provider/model names, version identifiers, and correlation IDs are allowed.

## Processing fingerprint

The first implementation captures the runtime application version, AI provider/model, and active profile. The fingerprint map is intentionally extensible for later additions such as prompt version, detector version, OCR version, document-profile version, policy version, and configuration/intelligence bundle version.

## Deliberately separate next steps

This foundation does not attempt to implement centralized JSON log shipping, metrics/SLO dashboards, or every future processor. Those should consume the same incident IDs, correlation IDs, stage taxonomy, reason codes, and processing fingerprint rather than creating competing observability models.

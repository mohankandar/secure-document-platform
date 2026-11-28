# LexaVault guide for Platform Operations and support

**For:** Platform Operations Administrator, second- and third-line support.
**Checked:** 2026-11-25 against the screens of commit `466906b1` (task 0095). Names in **bold** are the words on the screen.

## 1. What Platform Operations can and can't do

| Can | Can't |
|---|---|
| **Support**: read Problems (also of another organization, by **Organization ID** under More filters), Export and Status | Retry, keep or close a problem, or check a destination: that is the customer's IT Administrator |
| **Admin › Platform operations**: Overview and queue (every entity together), Processing storage, Workers, Branding, Usage data; **Run health checks** | Integrations, Sign-in, Notifications, the diagnostic bundle, the compliance report |
| **Admin › Audit trail** and its export, of the entity they are in | Cases, New case, Review, Reports, redaction policy, case fields, entities, licensing |

The role is given for every entity of the organisation (`../../install/SETUP.md`). The screens have no place to change the role rules.

## 2. Platform operations

| Section | Holds |
|---|---|
| **Overview** | Services (Working, Having problems, Down), watched folder, recorded technical incidents, the **Queue** |
| **Processing storage** | Add storage, test it from every server, make it the default, or give one customer its own. A change is for new work only |
| **Workers** | Workers of each step, and what each AI server takes at once. **Change maximums** sets a ceiling for every server; empty means LexaVault decides |
| **Branding** | Names, logos and colours; for everyone or one customer |
| **Usage data** | What is sent and what never is. Set at installation, not here |

Queue and workers: documents are always processed in steps (the staged pipeline; task 0121 removed the `inline` mode). The **time left** was within 7% on long loads, and up to 54% too long on loads of about 2 minutes. In steps, the work goes Urgent, Normal, Low, then Bulk (migration sources, in their times, at most half of the workers).

## 3. A case from a ticket

1. Ask for the LV code and the reference (`R-...`), or the Main ID of the case, and the time.
2. **Support › Status › Last failures** finds a reference (since that server started).
3. **Support › Problems** finds the case by its Main ID, or a problem by its incident number.
4. Read what happened, what it means for the case, and the timeline. Codes, ids and the processing fingerprint are under **Technical details**.
5. Look the code up: account menu › **Help: error codes**. "Support tells the customer" is the sentence to use.
6. Ask the customer's IT Administrator for the diagnostic bundle (Support › Status).
7. Compare with other problems of the same code. The same code in several organizations at once is a platform problem, not many tickets.

## 4. Business done is not problem solved

A case can be finished by hand (**Continue manually**, only after a stopped AI analysis) while its problem stays open: chip **Continued manually** or **Case finished**, status **Watching**. Close a problem only when its cause is gone.

## 5. The incident record

Tables `processing_incidents` (one row per problem) and `processing_incident_events` (its history). The incident number is the identity of the problem; a correlation ID belongs to one request and may differ from step to step.

Stages: INTAKE, TEXT_EXTRACTION, OCR, AI_ANALYSIS, SENSITIVE_DETECTION, DOCUMENT_PROFILE, IDENTIFIER_EXTRACTION, MANUAL_REVIEW, REDACTION, VERIFICATION, DERIVED_DOCUMENT_SPLIT, ROUTING, DELIVERY, STORAGE, RETENTION, MIGRATION_REVALIDATION, DOCUMENT_PROCESSING, UNKNOWN. Not every stage has problems today.

Events: FAILURE_RECORDED, RETRY_SCHEDULED, PROCESSING_RECOVERY_EXHAUSTED, BUSINESS_CONTINUITY_STARTED, BUSINESS_CONTINUITY_SUBMITTED, BUSINESS_COMPLETED, INVESTIGATION_HOLD_APPLIED, INVESTIGATION_HOLD_RELEASED, RESOLVED_NO_REPROCESS, SUPERSEDED, AUTO_CLOSED_BY_RETENTION and others.

Before an AI problem is recorded LexaVault has already tried: the same pages again, then split, then page by page.

Direct SQL is for engineering on a local copy; use the screens elsewhere.

```sql
SELECT id, tenant_id, package_id, document_id, stage, component, failure_category, reason_code, retryable, attempt_count,
       correlation_id, status, business_continuity_used, business_outcome, first_observed_at, last_observed_at, resolved_at
FROM processing_incidents
ORDER BY last_observed_at DESC
LIMIT 100;

SELECT incident_id, event_type, attempt_number, scope, reason_code, retryable, correlation_id, safe_metadata, occurred_at
FROM processing_incident_events
WHERE incident_id = :incident_id
ORDER BY occurred_at;
```

## 6. Logs

JSON, one event per line; also the default of local starts (`-LoggingFormat Readable` for plain text, `-LogLevel`, `-EnableRollingFileLog`: daily and 100 MB files, 14 periods, 2 GB).

Fields when known: service, environment, tenantId, actorId, correlationId, ref, requestId, packageId, documentId, incidentId, eventName, stage, component, failureCategory, reasonCode, provider, model, attempt, maxAttempts, retryable, durationMs, status, decision, scope, businessOutcome, and counters such as pageCount, batchCount, providerCalls, pageRetries, findingCount. The list is fixed in `logback-json.xml`.

## 7. Privacy

Never in an incident, log or ticket: prompts, AI answers, text of documents, found values, names, credentials. Safe: ids, codes, stages, counts, times, versions.

## 8. Not there

No links from a problem to a central log system, no reports across customers, no way for Platform Operations to act on a problem, and no screen that assigns a problem to a person.

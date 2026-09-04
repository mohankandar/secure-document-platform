# High-Level Processing Logs

These INFO-level events are intentionally safe operational summaries. They must not contain raw OCR text, document text, finding values, SSNs, DOBs, credit-card values, names, email addresses, tokens, credentials, or storage paths.

## Events

- `PACKAGE_PROCESSING_STARTED`
  - package ID, tenant ID, starting status, correlation ID
- `AI_ANALYSIS_STARTED`
  - provider, model, non-blank page count, bounded parallelism
- `AI_ANALYSIS_COMPLETED`
  - provider, model, page count, finding count, duration
- `DOCUMENT_EXTRACTION_COMPLETED`
  - document/package IDs, total pages, native pages, OCR pages, character count, duration
- `DOCUMENT_DETECTION_COMPLETED`
  - document/package IDs, finding count, counts by category, page numbers by category, auto-confirm count, review requirement, policy decision, enabled categories, SSN-last-four policy flag, duration
- `DOCUMENT_REDACTION_COMPLETED`
  - document/package IDs, expected targets, verified targets, page count, extractable-text verification result, final document status, duration
- `PACKAGE_DELIVERY_COMPLETED`
  - package ID, document count, destination type, successful export cycle, duration
- `PACKAGE_PROCESSING_FINISHED`
  - package ID, final status, total trigger duration

Failure logs remain deliberately generic and direct operators to safe audit/technical-exception metadata instead of printing raw exception payloads.

## Example safe trace

```text
PACKAGE_PROCESSING_STARTED package=<uuid> tenant=<uuid> status=SUBMITTED correlation=<uuid>
AI_ANALYSIS_STARTED provider=ollama model=qwen3.5:4b pages=10 batches=5 batchSize=2 parallelism=2 ...
AI_ANALYSIS_COMPLETED provider=ollama model=qwen3.5:4b pages=10 batches=5 providerCalls=5 findings=... durationMs=... promptTokens=... outputTokens=... outputTokensPerSecond=...
DOCUMENT_EXTRACTION_COMPLETED document=<uuid> package=<uuid> pages=61 nativePages=61 ocrPages=0 chars=... durationMs=...
DOCUMENT_DETECTION_COMPLETED document=<uuid> package=<uuid> findings=1 byType={CREDIT_SCORE=1} pagesByType={CREDIT_SCORE=[59]} autoConfirmed=1 reviewRequired=false decision=REDACT enabledTypes=[SSN, DATE_OF_BIRTH, CREDIT_CARD_NUMBER, CREDIT_SCORE] maskSsnLastFour=true durationMs=...
DOCUMENT_REDACTION_COMPLETED document=<uuid> package=<uuid> targets=1 verifiedTargets=1 pages=61 extractableText=false status=PROCESSED durationMs=...
PACKAGE_DELIVERY_COMPLETED package=<uuid> documents=1 destination=local cycle=1 durationMs=...
PACKAGE_PROCESSING_FINISHED package=<uuid> status=COMPLETED durationMs=...
```

The exact ordering of extraction and AI/detection log lines follows the processing call order. Do not add raw finding values merely to make troubleshooting easier.

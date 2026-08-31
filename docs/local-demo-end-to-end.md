# Local Demo: Unit 17 AI-First Digital + Scanned PDF Flow

This guide describes the Unit 17 flow built on the approved Unit 16 pipeline. Digital pages use native PDF text when usable; scanned/sparse pages use local Tesseract OCR and then enter the exact same AI-first finding/policy/redaction path.

## Prerequisites

- Java 21+
- Maven 3.9+
- Node.js 18+
- PostgreSQL 15+
- Local Ollama runtime with the configured model available (default `qwen2.5:7b`)
- Tesseract 5.x available on PATH (or configure `SECUREDOC_OCR_TESSERACT_EXECUTABLE`)

See `docs/local-ai-setup.md` for AI provider configuration and `docs/local-ocr-setup.md` for OCR configuration.

## 1. Start PostgreSQL

```bash
docker run -d --name securedoc-pg \
  -e POSTGRES_DB=securedoc \
  -e POSTGRES_USER=securedoc \
  -e POSTGRES_PASSWORD=securedoc_dev_password \
  -p 5432:5432 \
  postgres:15
```

## 2. Start the local AI provider

Ensure Ollama is reachable at the configured base URL and the configured model is already available.

```bash
export SECUREDOC_AI_PROVIDER=ollama
export SECUREDOC_AI_OLLAMA_BASE_URL=http://localhost:11434
export SECUREDOC_AI_OLLAMA_MODEL=qwen2.5:7b
```

Do not use `SECUREDOC_AI_PROVIDER=heuristic` as proof of the final AI-first acceptance; it is a bounded development fallback only.

## 3. Confirm local OCR

```bash
tesseract --version
```

Windows PowerShell example when Tesseract is not on PATH:

```powershell
$env:SECUREDOC_OCR_TESSERACT_EXECUTABLE = "C:\Program Files\Tesseract-OCR\tesseract.exe"
```

## 4. Build and start backend

```bash
cd backend
mvn clean verify
mvn spring-boot:run -pl securedoc-api -am -Dspring-boot.run.profiles=local
```

Backend: `http://localhost:8080`

## 5. Build/start frontend

```bash
cd frontend/securedoc-ui
npm ci
npm run build
npm run dev
```

Frontend: `http://localhost:5173`

## 6. Normal happy path

1. Create a `MANUAL_UPLOAD` package.
2. Upload one or more PDFs. Digital, scanned/image-only, and mixed native/scanned PDFs are supported by the Unit 17 normalization path.
3. Submit once.
4. Submission automatically starts page-aware normalization: meaningful native pages use PDFBox text/coordinates; sparse/image pages are rendered and OCR'd with Tesseract. Normalized pages then enter AI semantic discovery, deterministic evidence, trusted-location mapping, policy, permanent redaction, verification, and delivery.
5. No normal user enters X/Y/bounding-box coordinates and no manual processing trigger is required.

For a package where every finding is auto-confirmed by policy, the expected terminal package status is `COMPLETED`. Each document is `PROCESSED`, and each delivered file keeps its original customer filename.

Local delivery is isolated internally to avoid filename collisions:

```text
securedoc-output/
  {tenantId}/
    {packageId}/
      {documentId}/
        application.pdf
```

The internal directories are implementation details; the customer filename remains exactly `application.pdf`.

## 7. Review path

If one or more findings do not individually meet the V1 auto-redact threshold or do not have a trusted location:

- only those findings remain `DETECTED`;
- high-confidence, trusted-location findings are auto-confirmed and do not require re-review;
- the affected document remains `REDACTING` while review is required;
- the package becomes `PENDING_REVIEW`;
- **no package delivery occurs yet**.

The `/review` screen is now the focused Manual Review workbench. It shows only unresolved findings, renders the relevant source page, and lets the reviewer visually draw/move/resize the proposed redaction area. After the last `DETECTED` finding is confirmed/dismissed, processing resumes automatically; no separate Continue Processing action is required.

## 8. No-sensitive-data path

A PDF with no supported sensitive findings still produces a verified safe burn-flat output with zero redaction targets. It is delivered with the original filename and the package can become `COMPLETED`.

## 9. Failure behavior

- AI provider unavailable/invalid response → document/package technical failure; no deterministic-only silent fallback.
- Scanned/image-only PDF → page render + Tesseract OCR + same AI-first pipeline.
- Non-blank page with empty OCR or page OCR confidence below the configured minimum → fail closed as a technical processing failure; no silent clean-output success.
- Redaction verification failure → no delivery; technical failure.
- Delivery failure → package does not become `COMPLETED`; technical failure.
- One failed document prevents successful batch completion/delivery status.

## 10. Unit 17 acceptance matrix

Before approving Unit 17, verify at least:

| Scenario | Expected result |
| --- | --- |
| All four V1 categories, high confidence | AI participates; automatic permanent redaction; verified output; package `COMPLETED` |
| AI-only discovery that deterministic SSN rule misses (e.g. contextually labeled 9-digit SSN without separators) | Finding can still originate from semantic/AI path |
| AI finding text appears multiple times and cannot be mapped to one trusted occurrence | Finding is preserved as unlocated `DETECTED`; package requires review; nothing is silently dropped |
| AI structured candidate conflicts with deterministic validation (e.g. invalid Luhn PAN) | Confidence is bounded down; candidate is not auto-redacted |
| One deliberately below-threshold finding among high-confidence findings | Low item is not swept into redaction; package `PENDING_REVIEW`; no customer delivery yet |
| No sensitive findings | Verified safe output delivered; package `COMPLETED` |
| AI provider unavailable | Technical failure; no deterministic-only success |
| Delivery destination fails | Technical failure; package not `COMPLETED` |
| Same filename in different packages | Both outputs retained under different internal package/document paths; filename unchanged |
| Two documents, one needs review | No package delivery until the review condition is resolved |
| Two-document delivery, second staging write fails | No final package directory is exposed; package becomes technical failure |
| Scanned/image-only PDF with all four V1 categories | Real Tesseract OCR → real Ollama analysis → trusted location → permanent redaction → verified delivery → `COMPLETED` |
| Mixed native + scanned PDF | Native pages avoid OCR; scanned/sparse pages use OCR; both enter the same finding/policy/redaction pipeline |
| Low OCR-confidence sensitive text | Finding confidence is bounded by OCR span confidence and cannot auto-redact above policy merely because AI is confident |
| Non-blank unreadable/very-low-confidence scan | Fail closed as technical failure; no successful delivery |
| Genuinely blank scanned page | Safe zero-finding output can still complete |

## 11. Status summary

```text
Straight-through package:
CREATED → SUBMITTED → PROCESSING → COMPLETED

Review-required package:
CREATED → SUBMITTED → PROCESSING → PENDING_REVIEW → [IN_REVIEW] → COMPLETED

Technical failure:
... → FAILED

Document:
UPLOADED → EXTRACTING → DETECTING → REDACTING → PROCESSED
                                  └────────────→ FAILED
```

`PENDING_REVIEW` is reserved for a real human content decision. It is not the successful terminal status and is not used to represent a technical failure.

## 12. Current boundaries

- Unit 17 supports digital/text, scanned/image-only, and page-mixed PDFs through one normalized text/location model.
- Native extraction is preferred for meaningful digital pages; OCR is used only for sparse/image pages.
- Positional spans are not yet persisted. Detection re-normalizes the original artifact to recover trusted geometry, so OCR pages may be OCR'd twice in the current local path. This is a known performance optimization opportunity, not a separate detection architecture.
- The current submit path is synchronous for the local/demo happy path. Production-grade asynchronous orchestration remains a later hardening concern.
- Batch delivery is gated until all documents are review-free and processed. The local adapter stages the entire package and publishes it as one directory move, so a staging failure exposes no partial final package. Non-local provider transaction semantics remain later delivery-lifecycle work.
- The legacy finalize/vault/access backend remains from earlier units but is not part of the straight-through V1 happy path and will be retired/simplified in later scoped work.
- The focused Manual Review workbench is approved. The full shared Dashboard remains a later Unit 23 deliverable; Unit 20 only adds the transitional business-identifier views needed to exercise intake.

## 13. Cleanup

```bash
cd backend
mvn clean

cd ../frontend/securedoc-ui
rm -rf node_modules dist

rm -rf backend/securedoc-api/securedoc-storage
rm -rf backend/securedoc-api/securedoc-output
```

## 14. Unit 18 / Unit 19 status

Unit 18 is approved. Unit 19 implements the focused Manual Review workbench on top of the approved focused-review APIs.

Focused review API:

```text
GET  /api/v1/review-items
GET  /api/v1/review-items/{findingId}/page-preview
POST /api/v1/review-items/{findingId}/confirm
POST /api/v1/review-items/{findingId}/dismiss
```

Expected focused behavior:

- queue contains only unresolved `DETECTED` findings
- high-confidence auto-confirmed findings are not re-presented
- confirm requires trusted location; the Unit 19 visual workbench submits corrected bounds directly from the page preview
- dismiss removes the finding from redaction consideration
- resolving the final pending item automatically resumes redaction, verification, and package delivery
- no ReviewTask create/assign/start sequence is required for this V1 path

Technical resilience defaults:

```text
SECUREDOC_RETRY_MAX_ATTEMPTS=2
SECUREDOC_RETRY_INITIAL_BACKOFF_MS=250
```

Only explicitly typed transient failures retry. Low confidence/content uncertainty never retries. Real AI verification must use Ollama; do not silently fall back to the heuristic adapter. Optional AI secondary-provider settings use the `SECUREDOC_AI_SECONDARY_*` environment variables documented in the Unit 18 implementation record.

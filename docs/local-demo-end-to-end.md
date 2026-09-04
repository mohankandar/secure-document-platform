# Local Demo: End-to-End Flow

This guide walks through the complete SecureDoc document processing lifecycle using the local/demo environment.

## Prerequisites

- Java 21+
- Maven 3.9+
- Node.js 18+
- PostgreSQL 15+ running on `localhost:5432`
- Database `securedoc` created with user `securedoc` / password `securedoc`

## 1. Start PostgreSQL

```bash
# If using Docker:
docker run -d --name securedoc-pg \
  -e POSTGRES_DB=securedoc \
  -e POSTGRES_USER=securedoc \
  -e POSTGRES_PASSWORD=securedoc \
  -p 5432:5432 \
  postgres:15
```

## 2. Start Backend

```bash
cd backend
mvn spring-boot:run -pl securedoc-api -Dspring-boot.run.profiles=local
```

The backend starts on `http://localhost:8080`. Liquibase applies all migrations automatically.

## 3. Start Frontend

```bash
cd frontend/securedoc-ui
npm install
npm run dev
```

The frontend starts on `http://localhost:5173` and proxies API calls to the backend.

## 4. Demo Steps

### Step 1: Create Package

- Navigate to **Create Package** page
- Select package type (e.g., STANDARD)
- Click **Create Package**
- Note the package ID, click **View** to go to detail

**Expected status:** Package `CREATED`

### Step 2: Upload PDF

- On the Package Detail page, choose a PDF file
- Click **Upload**
- Document appears in the Documents table

**Expected status:** Package `CREATED`, Document `UPLOADED`

### Step 3: Submit Package

- Click **Submit Package**

**Expected status:** Package `SUBMITTED`

### Step 4: Trigger Processing

- Click **Trigger Processing**
- This runs: extract text → detect sensitive data
- If findings are detected but lack bounding boxes, redaction is **skipped** (document stays in REDACTING)
- Package moves to PENDING_REVIEW regardless

**Expected statuses after processing:**
- No sensitive data found: Package `PENDING_REVIEW`, Document `PROCESSED`
- Sensitive data found, all findings have bounding boxes: Package `PENDING_REVIEW`, Document `PROCESSED`, Findings `REDACTED`
- Sensitive data found, findings lack bounding boxes: Package `PENDING_REVIEW`, Document `REDACTING` (waiting for review)

### Step 5: Review (when documents are in REDACTING)

If documents are in REDACTING status (findings need bounding boxes):

1. Go to **Review** page
2. Enter the Package ID, click **Create** to create a review task
3. Copy the task ID, click **Assign to current actor**
4. Click **Start**
5. For each finding:
   - Enter the Finding ID and Review Task ID
   - Click **Confirm** (changes DETECTED → CONFIRMED)
   - Set bounding box coordinates (normalized 0.0-1.0, e.g., left=0.1, top=0.2, width=0.3, height=0.05)
   - Click **Update Bounding Box**
6. Click **Complete** when all findings have been confirmed with bounding boxes

### Step 6: Continue Processing (Redact)

- Go back to Package Detail page, click **Refresh**
- Click **Continue Processing (Redact)**
- This validates all confirmed findings have bounding boxes, then runs redaction
- After successful redaction: findings are marked REDACTED, document becomes PROCESSED

**Expected status:** Package `PENDING_REVIEW`, Document `PROCESSED`, Findings `REDACTED`

### Step 7: Finalize Package

- Click **Finalize & Approve**
- This validates:
  - All documents are PROCESSED
  - No DETECTED findings remain
  - No CONFIRMED findings remain (all must be REDACTED)
  - REDACTED, DISMISSED, and OVERRIDDEN findings are considered safe

**Expected status:** Package `APPROVED`

### Step 8: Register Vault Entry

- Go to **Vault / Access** page
- Enter Package ID, Document ID, and the REDACTED Artifact ID
- Click **Register Vault Entry**

**Expected:** Vault entry created

### Step 9: Download Redacted Artifact

- On the Vault page, enter the Document ID
- Click **Download Redacted**

The browser downloads the redacted PDF with a safe filename (`document-{id}-redacted.pdf`).

## 5. Status Flow Summary

```
Package:  CREATED → SUBMITTED → PROCESSING → PENDING_REVIEW → [IN_REVIEW] → APPROVED → VAULTED
Document: UPLOADED → EXTRACTING → DETECTING → [REDACTING] → PROCESSED
Finding:  DETECTED → CONFIRMED → REDACTED (or DISMISSED/OVERRIDDEN)
```

Key: Documents in REDACTING wait for human review to provide bounding boxes before redaction can proceed.

## 6. Known Limitations

- The rule-based detector creates text-only findings (character offsets, no bounding boxes)
- Redaction is skipped during initial processing when findings lack bounding boxes
- A reviewer must confirm findings and provide bounding boxes via the Review page
- If the reviewer dismisses all findings, Continue Processing marks the document PROCESSED without creating a redacted artifact (no redaction needed)
- After adding bounding boxes for confirmed findings, use "Continue Processing (Redact)" to run redaction
- The worker is disabled by default; processing must be triggered manually
- The current detector is deterministic (regex patterns) — no AI/ML detection yet
- Finalization requires every document to be PROCESSED (FAILED documents block approval)
- For V1 demo, the redacted artifact path is the main flow when findings are confirmed
- If all findings are dismissed, no redacted artifact is created; vault registration would use ORIGINAL (future policy decision)

## 7. Troubleshooting

| Issue | Cause | Fix |
|-------|-------|-----|
| Package stuck in SUBMITTED | Processing not triggered | Click "Trigger Processing" |
| Document in REDACTING after processing | Findings detected without bounding boxes | Normal — use Review page to add bounding boxes |
| "Cannot continue redaction: DETECTED findings" | Findings not confirmed yet | Confirm findings on Review page first |
| "confirmed findings without bounding boxes" | Bounding boxes not provided | Update bounding boxes on Review page |
| Finalize fails: "document not PROCESSED" | Document still REDACTING or FAILED | Continue processing after review, or fix failed docs |
| Finalize fails: "finding not reviewed" | DETECTED findings remain | Confirm or dismiss all findings |
| Finalize fails: "confirmed but not redacted" | Confirmed findings not yet processed | Run Continue Processing to trigger redaction |
| Upload fails | Package not in CREATED status | Cannot upload after submit |
| CORS errors | Frontend not proxied | Ensure Vite dev server is running |

## 8. Cleanup

```bash
# Stop services (Ctrl+C in each terminal)

# Clean build artifacts:
cd backend
mvn clean

cd ../frontend/securedoc-ui
rm -rf node_modules dist

# Remove local storage data:
rm -rf backend/securedoc-api/securedoc-storage

# Drop and recreate database:
docker exec securedoc-pg psql -U securedoc -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"
```

## 9. API Reference (Demo Endpoints)

| Endpoint | Purpose |
|----------|---------|
| `POST /api/v1/packages` | Create package |
| `POST /api/v1/packages/{id}/documents` | Upload PDF (multipart) |
| `POST /api/v1/packages/{id}/submit` | Submit for processing |
| `POST /api/v1/packages/{id}/process` | Trigger processing / continue redaction |
| `POST /api/v1/packages/{id}/finalize` | Approve package for vault |
| `POST /api/v1/vault/entries` | Register vault entry |
| `GET /api/v1/documents/{id}/artifacts/redacted/content` | Download redacted |

All requests should include headers:
- `X-Tenant-Id: 00000000-0000-0000-0000-000000000001`
- `X-Actor-Id: 00000000-0000-0000-0000-000000000099`

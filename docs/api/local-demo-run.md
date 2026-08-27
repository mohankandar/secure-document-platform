# Local Demo — Run Instructions

## Prerequisites

- Java 21
- Maven
- Docker (for PostgreSQL)

## 1. Start PostgreSQL

```bash
docker compose -f docker/compose/postgres.yml up -d
```

## 2. Build

```bash
cd backend
mvn clean package -DskipTests
```

## 3. Run

```bash
cd backend
mvn spring-boot:run -pl securedoc-api -Dspring-boot.run.profiles=local
```

The app starts on `http://localhost:8080`.

A demo tenant is auto-created on first startup:
- Tenant ID: `00000000-0000-0000-0000-000000000001`
- Slug: `local-demo`

## 4. Sample API Calls

### Create a package

```bash
curl -X POST http://localhost:8080/api/v1/packages \
  -H "Content-Type: application/json" \
  -d '{"packageType": "MANUAL_UPLOAD", "metadata": {"source": "manual"}}'
```

### Upload a PDF document

```bash
curl -X POST http://localhost:8080/api/v1/packages/{packageId}/documents \
  -F "file=@/path/to/document.pdf"
```

### Submit the package

```bash
curl -X POST http://localhost:8080/api/v1/packages/{packageId}/submit
```

### Get package detail

```bash
curl http://localhost:8080/api/v1/packages/{packageId}
```

### List packages

```bash
curl "http://localhost:8080/api/v1/packages?offset=0&limit=10"
```

## 5. Local File Storage

Uploaded files are stored at: `./securedoc-storage/`

Path structure: `{tenantId}/{packageId}/{documentId}/ORIGINAL/{artifactId}`

## 6. Inspect Audit and Outbox

Connect to PostgreSQL:

```bash
docker exec -it securedoc-postgres psql -U securedoc -d securedoc
```

```sql
SELECT id, action, outcome, detail FROM audit_records ORDER BY created_at DESC LIMIT 10;
SELECT id, event_type, aggregate_type, status FROM outbox_events ORDER BY created_at DESC LIMIT 10;
```

## 7. Custom Headers (Optional)

Override the demo tenant/actor context:

```bash
curl -H "X-Tenant-Id: 00000000-0000-0000-0000-000000000001" \
     -H "X-Actor-Id: 00000000-0000-0000-0000-000000000099" \
     -H "X-Correlation-Id: $(uuidgen)" \
     http://localhost:8080/api/v1/packages
```

## Notes

- V1 supports PDF uploads only (`application/pdf`)
- No authentication is enforced in local profile
- No document processing (OCR/redaction) is triggered on submit yet
- Outbox events are persisted but not relayed externally

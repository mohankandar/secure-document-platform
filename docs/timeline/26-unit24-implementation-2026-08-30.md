# Unit 24 — IT / Platform Admin — Implementation

Status: **Implemented in source; awaiting Kiro build/runtime/UI verification.** Unit 25 has not started.

## Implemented

- New ADMIN-only `/api/v1/platform-admin` surface.
- IT / Platform Admin UI route `/platform-admin` under Administration.
- Safe runtime/provider status for PostgreSQL, Ollama/selected AI, Tesseract OCR, internal local storage and selected final-delivery provider.
- Health probes for PostgreSQL, Ollama, Tesseract and local filesystem paths. API/S3 delivery surfaces configuration state without returning secrets.
- Retry/backoff + secondary AI failover visibility.
- Watched-folder operational status, manual Scan Now, last scan result and automatic scheduling.
- Automatic scheduling requires explicit `watched-folder-tenant-id` and `watched-folder-actor-id`; scheduler is disabled by default.
- Technical Exceptions queue from current-tenant FAILED packages, including safe age/document-count/technical-hold context and routing to Intake Details.
- Auditable `PLATFORM_HEALTH_CHECKED` and `WATCHED_FOLDER_SCAN_TRIGGERED` actions.
- Unit 25 identity boundary is explicitly visible in the UI; no OIDC/Okta implementation was added.

## Intentional V1 boundary

Provider endpoints/credentials remain deployment/environment managed. Unit 24 shows safe configuration/health and operational controls; it does not persist or expose credentials and does not attempt unsafe hot replacement of already-constructed provider beans.

## Self-review performed here

- New Unit 24 Java controller/service/scheduler/DTO slice compiled with Java 21 against targeted framework stubs plus the real project source graph.
- All frontend TS/TSX source files parsed with the TypeScript 5.8 parser with zero syntax errors.
- `application.yml` and `application-local.yml` were updated additively; no Unit 23 Liquibase files were changed.
- No Unit 25 implementation added.

Full Maven/TypeScript/Vite/real-stack verification remains Kiro's responsibility.

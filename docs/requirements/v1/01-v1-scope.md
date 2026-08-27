# V1 Scope

## Target: Local/Demo First

The first version targets a fully functional local/demo environment before cloud deployment.

## V1 Feature Scope

### In Scope for V1

- Manual document/package upload via API
- Document processing pipeline (OCR, text extraction)
- Sensitive-data detection with configurable rules
- Policy-driven automated redaction
- Human review workflow (assign, review, approve/reject)
- Secure vault registration
- Controlled document download
- Audit timeline for all operations
- Single-tenant configuration (multi-tenant structure, single tenant active)
- Local PostgreSQL persistence
- REST API

### Out of Scope for V1

- Shared-folder/watched-folder intake
- Kafka/event-driven architecture
- Cloud deployment (AWS/Azure/GCP)
- Full multi-tenant onboarding
- Frontend UI (separate delivery unit)
- SSO/OIDC integration (basic auth for demo)
- High-availability configuration

## Implementation Units

See `docs/timeline/00-implementation-units.md` for the unit breakdown.

## IDP Framework Usage

The IDP framework provides reusable enterprise plumbing at edge modules only:
- `securedoc-api` — web framework, security filters, logging
- `securedoc-infrastructure` — data access patterns, storage adapters
- `securedoc-worker` — observability, scheduling

Product-specific logic (OCR, redaction, AI, agents, vault, review) stays in SecureDoc modules.

# SecureDoc Platform V1 — Application Design

## Architecture

Modular monolith with clean/hexagonal boundaries.

## Modules

| Module | Responsibility |
|--------|---------------|
| securedoc-domain | Pure domain model, business rules |
| securedoc-application | Use cases, ports, orchestration |
| securedoc-api | REST endpoints, security, composition |
| securedoc-infrastructure | Persistence, storage, messaging adapters |
| securedoc-worker | Background processing |
| securedoc-ai | AI/LLM contracts |
| securedoc-agent | Agent contracts |
| securedoc-redaction | Redaction contracts |
| securedoc-test-support | Test utilities |

## Design Decisions

- See: docs/adr/
- See: docs/architecture/

## IDP Integration

Edge modules only. See: .kiro/steering/idp-usage-boundaries.md

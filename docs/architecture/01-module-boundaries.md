# Module Boundaries

## Dependency Rules

```
securedoc-domain
  ├── No dependencies on other securedoc modules
  ├── No Spring dependencies
  ├── No JPA dependencies
  └── No external SDK dependencies

securedoc-application
  ├── Depends on: securedoc-domain
  ├── May depend on: securedoc-ai, securedoc-agent, securedoc-redaction (contracts only)
  ├── No Spring dependencies
  ├── No JPA dependencies
  └── No IDP dependencies

securedoc-api
  ├── Depends on: securedoc-application
  ├── May use: Spring Boot, IDP (web/security/logging)
  └── Provides: REST endpoints, security config, API composition

securedoc-infrastructure
  ├── Depends on: securedoc-domain, securedoc-application
  ├── May use: Spring Data, JPA, IDP (data/storage/audit)
  └── Provides: Repository implementations, storage adapters, messaging

securedoc-worker
  ├── Depends on: securedoc-application, securedoc-infrastructure (later)
  ├── May use: Spring scheduling, IDP (logging/observability)
  └── Provides: Background processing, scheduled tasks

securedoc-ai
  ├── No dependencies on other securedoc modules
  └── Provides: Provider-neutral AI/LLM contracts

securedoc-agent
  ├── No dependencies on other securedoc modules
  └── Provides: Bounded agent contracts

securedoc-redaction
  ├── No dependencies on other securedoc modules
  └── Provides: Provider-neutral redaction contracts

securedoc-test-support
  ├── May depend on any module (test scope)
  └── Provides: Shared test helpers, fixtures, builders
```

## What Does NOT Go in IDP

- OCR abstraction
- LLM abstraction
- AI agent framework
- Redaction engine
- Document package workflow
- Vault workflow
- Review workflow
- Policy engine
- Tenant capability / product rules
- Shared-folder intake
- Kafka/outbox product events

All of the above are product-specific and belong in SecureDoc modules.

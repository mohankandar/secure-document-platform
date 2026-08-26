# Target Architecture

## Style: Modular Monolith with Hexagonal Boundaries

The platform uses a modular monolith architecture with clean/hexagonal architecture principles applied per module.

## Key Principles

1. **Domain isolation** — Domain module has zero framework dependencies
2. **Dependency inversion** — Application layer defines ports; infrastructure implements them
3. **Module boundaries** — Each module has a clear responsibility and explicit dependencies
4. **Composition at edges** — Spring Boot, IDP, and framework concerns only in api/infrastructure/worker
5. **Testability** — Core logic testable without infrastructure

## Module Overview

```
securedoc-domain          Pure domain model, value objects, domain events
securedoc-application     Use cases, ports (interfaces), orchestration
securedoc-api             REST controllers, DTOs, security configuration
securedoc-infrastructure  JPA entities, repositories, storage, messaging adapters
securedoc-worker          Background jobs, scheduled tasks
securedoc-ai              Provider-neutral AI/LLM contracts
securedoc-agent           Bounded agent contracts and orchestration
securedoc-redaction       Provider-neutral redaction engine contracts
securedoc-test-support    Shared test utilities
```

## Deployment Model (V1)

Single deployable Spring Boot application composed from all modules. Local PostgreSQL for persistence.

## Future Evolution

The modular monolith can evolve toward:
- Extracted microservices (if scale requires)
- Event-driven communication (Kafka/outbox)
- Cloud-native deployment (containers, managed services)

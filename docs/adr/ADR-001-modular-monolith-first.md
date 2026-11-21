# ADR-001: Modular Monolith First

## Status

Accepted

## Context

We need an architecture that supports complex document processing workflows, multiple integration points (OCR, AI, redaction), and a clear path toward future scalability — while keeping initial development and deployment simple.

## Decision

Start with a modular monolith using clean/hexagonal architecture boundaries per module.

## Rationale

1. **Faster iteration** — Single deployable unit, no distributed system overhead for V1
2. **Clear boundaries** — Module separation enforces clean dependencies from day one
3. **Testability** — Domain and application layers are framework-free and unit-testable
4. **Evolution path** — Module boundaries align with future service extraction if needed
5. **Local-first** — A single JAR + PostgreSQL is trivial to run locally

## Consequences

- All modules compile and deploy together
- Module boundaries are enforced by Maven dependencies, not network calls
- Cross-module communication is via in-process method calls (not HTTP/messaging)
- Future extraction to microservices requires adding API/messaging adapters at module boundaries
- Team discipline required to maintain boundary integrity

## Alternatives Considered

- **Microservices from start**: Too much overhead for a team starting a new product
- **Traditional layered monolith**: Weaker boundary enforcement, harder to extract later
- **CQRS/Event Sourcing from start**: Added complexity without proven scale need

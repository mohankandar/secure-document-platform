# Implementation Units

## Overview

Development proceeds in discrete units, each delivering a compilable, testable increment.

## Unit 1: Project Skeleton (Current)

- Maven multi-module structure
- Package namespace establishment
- Docker local infrastructure
- Documentation foundation
- Kiro steering files
- Build verification

## Unit 2: Domain Model Foundation

- Core domain entities (Document, DocumentPackage, VaultEntry)
- Value objects and domain events
- Repository port interfaces
- Domain validation rules

## Unit 3: Application Use Cases

- Upload use case
- Processing orchestration
- Review workflow use case
- Vault registration use case

## Unit 4: Infrastructure Adapters

- JPA entities and repositories
- Database migrations (Flyway)
- File storage adapter
- Spring configuration

## Unit 5: API Layer

- REST controllers
- DTOs and mappers
- Security configuration
- API documentation (OpenAPI)

## Unit 6: Document Processing

- OCR integration
- Text extraction
- Sensitive-data detection
- Processing pipeline

## Unit 7: Redaction Engine

- Policy rule engine
- Automated redaction
- Redaction verification

## Unit 8: Human Review

- Review task management
- Approval workflow
- Review UI support endpoints

## Unit 9: Audit and Compliance

- Audit event capture
- Timeline query
- Compliance reporting

## Unit 10: Local Demo Integration

- End-to-end local workflow
- Demo data seeding
- Local deployment guide

---

*Units may be split, merged, or reordered as development progresses.*

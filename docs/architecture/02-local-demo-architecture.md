# Local/Demo Architecture

## Overview

The first version targets a fully functional local development and demo environment.

## Components

```
Developer Machine
├── SecureDoc Application (Spring Boot, single JAR)
│   ├── API layer (REST endpoints)
│   ├── Application layer (use cases)
│   ├── Domain layer (business rules)
│   └── Infrastructure layer (persistence, storage)
└── PostgreSQL (Docker container)
    └── securedoc database
```

## Local Infrastructure

### PostgreSQL

- **Image**: postgres:16-alpine
- **Database**: securedoc
- **User**: securedoc
- **Password**: securedoc_dev_password
- **Port**: 5432
- **Start**: `docker compose -f docker/compose/postgres.yml up -d`

### File Storage (Future)

Local filesystem for demo. Cloud storage adapters added later.

## Running Locally

```bash
# Start infrastructure
docker compose -f docker/compose/postgres.yml up -d

# Build
cd backend
mvn clean package

# Run (when application is implemented)
java -jar securedoc-api/target/securedoc-api-0.1.0-SNAPSHOT.jar
```

## Limitations of Local/Demo

- Single-tenant only (structure supports multi-tenant)
- No message broker (synchronous processing)
- Local filesystem storage
- No SSL/TLS (development only)
- No external auth provider (basic auth for demo)

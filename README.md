# SecureDoc Platform

**Codename**: LexaVault  
**Market Name**: TBD  
**First Version Target**: Local/Demo

## Overview

SecureDoc Platform is a secure document intelligence, redaction, human review, vault, and audit platform.

### Planned Capabilities

- Manual document/package upload
- Shared-folder and API-based intake (future)
- Document processing (OCR, text extraction)
- Sensitive-data detection
- Policy-driven redaction
- Human review workflows
- Secure vault registration
- Controlled access and download
- Audit timeline
- Tenant-aware configuration

## Project Structure

```
secure-document-platform/
  backend/           — Java 21, Maven multi-module, modular monolith
  frontend/          — React + TypeScript (placeholder, not scaffolded yet)
  docker/            — Local infrastructure (PostgreSQL, etc.)
  docs/              — Requirements, architecture, ADRs, timeline
  scripts/           — Build and utility scripts
  .kiro/             — Kiro steering and specs
```

## Architecture

Modular monolith with clean/hexagonal boundaries. See `docs/architecture/` for details.

## Naming Strategy

- `LexaVault` is the internal project codename only
- Code uses generic namespace: `com.securedoc.platform.*`
- Market/product name will be decided separately
- No codename references in code packages or class names

## Building

```bash
cd backend
mvn clean compile
```

## Local Infrastructure

```bash
docker compose -f docker/compose/postgres.yml up -d
```

## IDP Framework

The existing IDP (Internal Developer Platform) framework provides reusable enterprise plumbing. It is used only at edge modules (api, infrastructure, worker) — never in domain or application layers. Product-specific logic stays in SecureDoc modules.

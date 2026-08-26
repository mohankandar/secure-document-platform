# Product Overview

**Codename**: LexaVault  
**Market Name**: TBD  
**Namespace**: com.securedoc.platform

## Purpose

SecureDoc Platform is a secure document intelligence, redaction, human review, vault, and audit platform designed for organizations that handle sensitive documents requiring policy-driven processing.

## Core Capabilities (Target)

1. **Document Intake** — Manual upload, shared-folder watch, API-based submission
2. **Document Processing** — OCR, text extraction, sensitive-data detection
3. **Policy-Driven Redaction** — Configurable rules for automated and assisted redaction
4. **Human Review** — Workflow for review tasks, approvals, and escalations
5. **Secure Vault** — Immutable registration of processed documents
6. **Controlled Access** — Fine-grained download and viewing permissions
7. **Audit Timeline** — Complete trail of all actions on every document
8. **Tenant Configuration** — Multi-tenant capability with per-tenant policies

## Naming Policy

- LexaVault is the internal project codename only
- Market/product name will be decided separately and may change
- All code uses generic namespace `com.securedoc.platform.*`
- No codename references in packages, classes, or public-facing artifacts

## Architecture Style

Modular monolith with clean/hexagonal architecture boundaries. See `docs/architecture/` for details.

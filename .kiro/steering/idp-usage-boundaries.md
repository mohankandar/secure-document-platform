# IDP Usage Boundaries

## Modules that MUST NOT use IDP

- securedoc-domain
- securedoc-application
- securedoc-ai (initially)
- securedoc-agent (initially)
- securedoc-redaction (initially)

## Modules that MAY use IDP (edge modules only)

- securedoc-api — web framework, security, logging
- securedoc-infrastructure — data access, storage, audit plumbing
- securedoc-worker — observability, scheduling

## What MUST NOT go into IDP

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

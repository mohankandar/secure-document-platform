# LexaVault Training Documentation

**Purpose:** Version-controlled training and support documentation for LexaVault features.

The repository copy is the **source of truth**. Polished copies may later be published to Google Workspace, a knowledge base, or customer documentation, but those copies should be generated from or reconciled back to this repository content.

## Audiences

| Guide | Primary audience | Purpose |
|---|---|---|
| `business/BUSINESS_USER_TRAINING.md` | Document Processor / Standard User, Elevated User, Business Admin | Day-to-day business workflows and user-visible behavior |
| `tenant-it/TENANT_IT_SUPPORT_GUIDE.md` | Tenant IT Admin | Tenant-side operational support, incident triage, integrations, safe escalation |
| `platform-operations/PLATFORM_SUPPORT_OPERATIONS_GUIDE.md` | Platform Operations / Tier 2+ support | Deep incident diagnosis, processing fingerprints, safe DB/log correlation, platform escalation |
| `TRAINING_CHANGELOG.md` | Product, Engineering, QA, Support, Training | What training changed with each accepted feature |
| `TRAINING_GOVERNANCE.md` | Engineering/Product | Definition of Done and documentation maintenance rules |

## Current training baseline

### Accepted / Frozen
- **R11 customer-demo accuracy baseline**
- **AI Processing Resilience**
- **Business Continuity Manual Processing Fallback**

### Verified / Frozen
- **Structured Processing Incident Foundation**
  - Independent Kiro verification: PASS
  - Runtime lifecycle has been exercised through:
    `OPEN -> BUSINESS_CONTINUED -> business outcome COMPLETED`
  - Durable incident/event records and Technical Issue Details have been demonstrated.

### In validation
- **Structured JSON Logging for SaaS/Production**
  - Production/SaaS JSON console logging
  - Local readable logging by default
  - Safe correlation/MDC propagation across request and async processing
  - No database migration; expected Liquibase count remains 52

## Documentation rule

Any user-visible, support-visible, operational, security, or workflow change is incomplete until the affected training documentation has been updated.

See `TRAINING_GOVERNANCE.md`.

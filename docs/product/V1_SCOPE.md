# LexaVault V1 Product Scope

## Product goal

Provide a secure AI-first document-redaction workflow that accepts business-identified PDF batches, understands configured sensitive content, permanently redacts it, verifies the output, and delivers the result while involving humans only when policy or uncertainty requires it.

## In scope

- PDF intake: digital/text, scanned/image, and mixed PDFs.
- Manual UI, local Scanner Bridge-assisted intake, manifest-first authenticated push API, watched-folder intake, and frozen scheduled pull/polling integration architecture.
- Multiple tenant-configured business identifiers with exactly one Primary identifier.
- AI contextual sensitive-content discovery on every normalized document.
- Deterministic recognizers/validators as supporting evidence for structured categories.
- Current V1 baseline categories: SSN, Date of Birth, Credit Card Number, Credit Score.
- Tenant policy enabling/disabling supported categories and related options such as contextual SSN-last-four masking.
- Trusted native/OCR geometry for location; no customer-entered X/Y coordinates.
- Permanent irreversible redaction plus verification.
- Straight-through processing where policy/confidence allows it.
- Focused Manual Review for configured/low-confidence content.
- Classified technical failures with selective technical retry.
- Local/on-prem-first provider architecture with configurable AI/OCR and tenant-scoped typed Integration Connections for sources/destinations.
- Temporary original retention, bounded reprocessing, technical holds, retention-owned physical purge, and incomplete machine-intake expiry/cleanup.
- Production redacted-master delivery boundary to independent SDV; LexaVault does not own customer long-term S3/object-storage choice.
- Derived redacted-document split/routing by deterministic BARCODE/QR_CODE/TEXT_PATTERN markers in fixed configured page regions.
- Shared Dashboard and role/capability-controlled actions.
- Executive and Operational reporting using tenant-scoped structured data.
- Production OIDC/JWT identity boundary plus explicit LOCAL/demo mode.

## Explicitly not V1

- DOCX intake.
- Long-term original-document vault/download as the primary workflow.
- Blanket human review of every page/document.
- Visual-overlay-only masking.
- Deterministic-only discovery or AI as an optional afterthought.
- Tenant-editable custom role/capability builders.
- Custom report builders, scheduled report distribution, or predictive forecasting.
- Browser-managed provider credential vaults.
- Mandatory Kafka/SQS/event-broker deployment without demonstrated need.
- RAG/vector search or advanced semantic knowledge retrieval.
- Multi-region/platform-scale decomposition without demonstrated need.

## Naming

`LexaVault` is the project codename. Reusable code remains generically named (`secure-document-platform`, `com.securedoc.platform.*`) so a future market name does not require a core-code rename.

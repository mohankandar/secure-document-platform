# V1 Scope — Current Freeze

## In Scope

- PDF intake: digital/text and scanned/image (OCR arrives in Unit 17).
- AI contextual sensitive-content discovery on every normalized document.
- Parallel deterministic validators/recognizers for structured evidence.
- SSN, Date of Birth, Credit Card Number, Credit Score.
- Automatic text/OCR location mapping; no customer-entered X/Y coordinates.
- Permanent irreversible redaction and verification.
- Straight-through automatic processing/export for policy-approved findings.
- Focused low-confidence review only when needed.
- Manual/API/watched-folder intake in the planned unified-intake unit.
- Multiple configurable business identifiers with one Primary identifier.
- Pluggable storage/delivery; local/on-prem first, S3/object/API adapters planned.
- Audit and reporting-ready structured operational metadata.
- Minimal shared Dashboard, Business Admin, IT Admin, and focused Manual Review workbench in planned units.
- Temporary original retention/reprocessing lifecycle; durable redacted output at customer destination.

## Not a V1 Product Direction

- Long-term original-document vault/download as the primary product workflow.
- Blanket human review of every page/document.
- Visual-overlay-only masking.
- OCRing every digital PDF page when usable native text/layout already exists.
- Deterministic-only discovery with AI as an optional fallback.
- Kafka/agent complexity without demonstrated V1 need.

## Authoritative Details

See `.kiro/steering/requirements/v1-decision-freeze-2026-08-29.md` and the current implementation tracker.

ADR 019 - AI-First Sensitive-Information Discovery
Status: Accepted
Date: 2026-08-29

# Context

The V1 product direction is an AI-powered automatic redaction platform. Earlier implementation planning could be read as deterministic detectors being primary, with AI only assisting ambiguous categories. The product intent is stronger: AI should analyze every processed document and be able to discover sensitive information even when a deterministic detector misses it.

# Decision

- AI contextual analysis is the primary discovery stage for every processed document after native PDF/OCR normalization.
- Deterministic recognizers/validators run as independent supporting evidence for structured categories and do not gate AI.
- AI and deterministic results merge into one Finding/evidence model.
- Categories with no deterministic validator remain first-class.
- Native extraction/OCR normally supplies authoritative geometry; AI supplies semantic identification.
- Application policy decides REDACT/REVIEW.
- The controlled redaction engine performs permanent modification and verification; AI does not directly rewrite the PDF.

# Consequences

Positive:
- The product is genuinely AI-driven rather than a regex engine with AI fallback.
- Contextual cases such as credit score are handled naturally.
- Future semantic categories (company secrets, confidential sections, proprietary content) fit the same pipeline.
- Structured validators remain useful for precision and confidence without constraining product scope.

Tradeoffs:
- AI provider availability, latency, model quality, and failover become first-class operational concerns.
- Evidence/confidence merge rules need explicit design and testing.
- Unit 16 source must be reviewed to ensure implementation matches this decision.

# Unit 16 Transition

Kiro was already implementing Unit 16 when this ADR was accepted. The returned code was reviewed and a focused correction pass was applied to make the active path conform to this ADR. **Conformance was verified with real Ollama runtime acceptance and Unit 16 was approved on 2026-08-29.** Unit 17 must preserve this architecture and feed OCR-normalized content into the same pipeline.

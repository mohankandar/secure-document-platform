Core Automatic Redaction Pipeline
The V1 product engine
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# Objective

Prove one simple customer promise: give the platform a valid document batch and required identifiers; the platform understands the document using AI, automatically identifies and locates configured sensitive content, and produces permanently redacted documents at the configured destination unless a specific exception genuinely needs human/IT attention.

# V1 Detection Principle

AI is the primary sensitive-information finder for every processed document. Deterministic detectors/validators are parallel supporting evidence, not a prerequisite for AI analysis.

The intended merge behavior is:

```text
AI finding only             -> valid candidate
Deterministic finding only  -> valid candidate/evidence
AI + deterministic agree    -> stronger confidence/evidence
AI + deterministic disagree -> controlled validation evidence lowers confidence / routes uncertainty to review
```

A deterministic miss must not prevent an AI finding. For structured AI candidates, deterministic validation may explicitly `SUPPORT`, `CONTRADICT`, or be `NOT_APPLICABLE`; the application bounds the model-reported confidence rather than treating it as a calibrated probability. A deterministic validator is optional for categories where no reliable structural rule exists.

# Page Content Normalization

Do not OCR every PDF. Process each page according to the best available source:

```text
PDF page
  |
  +-- usable embedded text/layout? -- yes --> native text + coordinates
  |
  +-- no / scanned / unusable ---------> OCR text + bounding boxes + OCR confidence

Both paths -> normalized page content -> AI + supporting evidence
```

A mixed PDF can therefore use native extraction on some pages and OCR on others without changing the customer workflow.

# Happy Path

1. Accept intake and validate mandatory business identifiers.
2. Link/create the BusinessRecord and create a new IntakeBatch.
3. Persist file metadata and secure working-storage references.
4. Normalize page content: use native PDF text/layout where usable; OCR scanned/unusable pages.
5. Run AI contextual analysis on every processed document to identify configured sensitive information and semantic context.
6. Run deterministic recognizers/validators independently where useful for structured categories and merge their evidence with AI results.
7. Resolve each accepted candidate to exact page geometry using native extraction/OCR coordinates. AI is not asked to guess X/Y when authoritative coordinates exist. If a semantic finding cannot be mapped unambiguously to one trusted occurrence, preserve it as an unlocated review finding rather than dropping or guessing.
8. Evaluate merged confidence/evidence and Business Admin policy.
9. Permanently redact all accepted findings through the controlled redaction engine.
10. Verify the sensitive content is not recoverable from the produced PDF using independent source expectations. For the V1 burn-flat adapter this includes source page count, no extractable text layer, redaction-count consistency, and visual coverage of every expected target region.
11. Once the entire submitted package is processed and review-free, deliver the complete package under original filenames. The local adapter stages all documents first and publishes the package as one directory move so staging failure cannot expose a partial final batch.
12. Record delivery checksum/status/timestamp and start the post-export retention clock.
13. Audit important transitions using safe metadata without storing raw sensitive values.

# Responsibility Boundary

```text
AI                    -> WHAT is sensitive / what the content means
Native PDF / OCR      -> WHERE the text/region is on the page
Deterministic rules   -> structured validation/supporting evidence
Policy                -> REDACT or REVIEW
Redaction engine      -> permanently modifies the PDF
Verification          -> proves the result is not recoverable
```

Future vision models may return useful regions directly, but those regions still normalize into the same location model and the controlled redaction engine remains responsible for physical document modification.

# Content Exception Path

If one finding falls below the configured threshold, only that finding/relevant page enters Manual Review. Already acceptable findings are not re-reviewed. Reviewer adjusts/confirms the visual redaction and submits; the pipeline resumes automatically.

# Technical Exception Path

Deterministic failure does not burn resources on repeated identical attempts. Transient AI/OCR/storage/delivery failures may back off/retry or fail over to a configured secondary provider. If the required AI analysis cannot complete, the system must not silently treat deterministic-only processing as equivalent to the intended AI-first V1 happy path. Unresolved technical exceptions remain visible to IT/operations and reporting with an age/purge deadline.

# File Integrity

The platform preserves the customer's file boundaries and filenames. It does not merge files or add `-redacted` suffixes.

# Future Semantic Expansion

The same Finding/policy/redaction path should later accept AI-discovered semantic categories such as confidential pricing, company secrets, internal strategy, proprietary sections, or tenant-defined sensitive concepts. Those categories may have no deterministic validator, which is why validators cannot be mandatory gates in the V1 architecture.

Project Timeline and Roadmap
Revised V1 implementation priorities
Project Codename: LexaVault | Product Category: AI-Powered Secure Document Redaction and Processing Platform | Date: 2026-08-29

# Current Milestone

Units 1-24 are approved. **Unit 25 Production Identity / Platform Hardening is implemented in source and awaiting Kiro verification.** Unit 25 replaces the pre-production header trust boundary with explicit LOCAL/OIDC modes, verified JWT identity, trusted tenant/actor/role mapping, least-privilege admin roles, a central tenant guard, safe callback/security-edge hardening, and a narrow executable security-boundary test suite. Final Security/Integration + V1 Acceptance remains separate after Unit 25.

# Remaining V1 Roadmap

| Order | Work | Outcome |
| --- | --- | --- |
| 1 / Unit 16 | Core AI-First Automatic Redaction Pipeline | Digital/text PDF: native content/layout normalization, AI contextual discovery on every document, deterministic supporting evidence, merged findings, automatic location, permanent redaction, verification, local export, straight-through high-confidence path. |
| 2 / Unit 17 | OCR / Image Redaction | Page-aware OCR for scanned/unusable pages feeding the same normalized AI-first analysis and redaction pipeline. |
| 3 / Unit 18 | Confidence + Exception Handling | Focused low-confidence review path, evidence/confidence rules, technical failure classification, selective retry/backoff, optional failover. |
| 4 / Unit 19 | Minimal Manual Review | **Approved.** Relevant page/finding only; visual correction and submit. |
| 5 / Unit 20 | Business Record + Identifiers + Unified Intake | **Approved.** BusinessRecord/IntakeBatch model; multiple required/Primary identifiers; manual/API/watched folder; trigger + fingerprint + structured rejection/observation. |
| 6 / Unit 21 | Business Admin | **Approved.** Identifier definitions/order, sensitive-data policy, confidence threshold, and review mode. |
| 7 / Unit 22 | Delivery + Lifecycle | **Approved.** Local/S3/API delivery, export records, final approval, 3 export cycles, reprocess, retention/technical hold and purge. |
| 8 / Unit 23 | Shared Dashboard + Reporting | **Approved.** Shared operational visibility, configurable business columns and compact V1 reports on reporting-ready data. |
| 9 / Unit 24 | IT / Platform Admin | **Approved.** Providers, storage/delivery, watched-folder behavior, technical failover, integrations, health and Technical Exceptions. |
| 10 / Unit 25 | IDP / Production Identity / Platform Hardening | **Implemented in source; awaiting verification.** Provider-neutral OIDC/JWT trust boundary, trusted tenant/actor/role mapping, granular admin roles, tenant/security hardening, callback authentication and legacy surface retirement. |
| Final | Tests + V1 Acceptance | High-value security/integration tests, packaging, demo/acceptance, release candidate. |

# Unit 19 Approval Checkpoint

**Passed 2026-08-29.** Focused unresolved-only queue, safe single-page review preview, visual bounding-box adjustment/draw behavior, Confirm & Submit / Not Sensitive, automatic last-item resume, role/tenant isolation, audit safety, OCR/native review compatibility, and Units 16–18 regression were verified. The broader Dashboard remains intentionally deferred.

# Unit 20 Approval Checkpoint

**Approved 2026-08-29.** Multiple required identifiers with one Primary, Business Record reuse across separate Intake Batches, conflict rejection, manual/API intake, watched-folder trigger/fingerprint/idempotency/history, tenant isolation, and Units 16–19 regressions were verified against the real local stack. One minimal TypeScript unused-variable correction was made during verification.

# Unit 21 Approval Checkpoint

**Approved 2026-08-29.** Business Admin identifier configuration, optional identifiers, Primary switching, tenant-sensitive policy, threshold, `REVIEW_ALL`, disabled-category behavior, tenant isolation, audit safety, and Units 16–20 regressions were verified with clean backend/frontend builds.

# Unit 22 Approval Checkpoint

**Approved 2026-08-29.** Delivery now records successful package export cycles and supports local/API/S3-compatible providers. Business Admin lifecycle policy controls original/redacted temporary retention and optional final-delivery approval. Reprocess is bounded to three successful export cycles; never-exported failures receive a bounded technical hold; temporary artifacts are purged by lifecycle deadlines.

# Unit 17 Approval Checkpoint

**Passed 2026-08-29.** Real Tesseract OCR, mixed/native routing, AI-only discovery through OCR, OCR-confidence safety, unreadable/blank scan handling, multi-page geometry, permanent redaction/verification, and Unit 16 digital regression were all verified. Proceed with Unit 18 without redesigning the normalized AI-first pipeline.

# Unit 16 Approval Checkpoint

**Passed 2026-08-29.** AI-first discovery, per-finding confidence behavior, automatic location, permanent redaction/verification, batch-gated delivery, technical export-failure behavior, duplicate filename isolation, no-finding delivery, and the successful `COMPLETED` terminal state were all verified. Proceed to Unit 17 without redesigning this pipeline.

# V1 Acceptance Story

A customer provides required identifiers and a PDF containing configured sensitive data. For each page the system uses native PDF text/layout when available or OCR when needed, runs AI contextual analysis, combines supporting deterministic evidence, locates the sensitive content, applies explicit policy/confidence, permanently redacts, verifies, and exports under the original filename. A human is involved only for configured review or specific low-confidence content. Technical failures are visible to IT and handled according to classified retry/failover policy.

# Later Versions

- Additional semantic sensitive/confidential categories such as customer-defined company secrets and proprietary sections using the same AI -> Finding -> Policy -> Redaction pipeline
- Rich law/regulatory policy packs and AI-assisted policy interpretation
- Additional document classifiers
- Advanced provider routing/model management
- Kafka/SQS broker adapter when operational value justifies it
- Separate worker/service deployment where scale requires it
- Advanced analytics/reporting
- RAG/vector search if product scope expands
- Multi-region deployment


# Unit 23 Approval Checkpoint

**Approved 2026-08-30.** The final merged Dashboard/reporting source passed real-stack verification, including Business Admin allowed/default/order column policy, server-side search/filter/sort/pagination, five reports, tenant isolation and Units 16–22 regressions.

# Unit 24 Approval Checkpoint

**Approved 2026-08-30.** Platform Admin provider/runtime health, watched-folder manual/scheduled operation, fail-closed scheduler tenant binding, Technical Exceptions, safe auditing and Units 16–23 regressions passed with zero source corrections.

# Unit 25 Implementation Checkpoint

**Implemented in source 2026-08-30; awaiting Kiro verification.** Production OIDC/JWT security is now an edge concern only; LOCAL remains explicit demo mode. Production identity headers are no longer trust inputs, tenant context is centrally validated, external roles map explicitly to product roles, Business Admin and Platform Admin permissions are separated, and security-critical identity behavior has a small focused regression suite.

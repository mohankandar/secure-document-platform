# Unit 16 Focused Correction Report — 2026-08-29

## Why the correction was required

Kiro's original Unit 16 implementation proved the digital PDF mechanics but still used deterministic discovery as the active baseline and left the AI contract beside, rather than inside, the runtime. Review also found mixed-confidence redaction, successful `PENDING_REVIEW`, best-effort export, no-finding delivery, verification, and filename-collision issues.

## Corrected source behavior

- Added application-level `SensitiveContentAnalysisPort` for independent page/document semantic discovery.
- Added real local Ollama adapter and an explicitly development-only semantic heuristic adapter.
- AI semantic analysis now runs for every text-bearing digital PDF before results are merged with deterministic evidence.
- Deterministic recognizers remain independent supporting evidence; they do not gate AI.
- AI-only and deterministic-only findings are both valid; overlapping independent evidence is merged. Semantic findings that cannot be mapped unambiguously to trusted coordinates are preserved as unlocated review findings instead of being silently dropped.
- Added explicit deterministic candidate validation evidence (`SUPPORTS`, `CONTRADICTS`, `NOT_APPLICABLE`) and bounded application confidence so an LLM self-reported score is not treated as a calibrated probability.
- V1 entity policy is explicit: SSN, DATE_OF_BIRTH, CREDIT_CARD_NUMBER, CREDIT_SCORE. Credit card is now distinct from generic financial-account identifiers.
- Per-finding thresholding prevents one high-confidence item from sweeping a low-confidence item into automatic redaction.
- Only `CONFIRMED` findings are physically redacted. Any unresolved `DETECTED` item blocks final artifact generation/delivery and routes the package to real review.
- No-finding PDFs still produce a verified safe burn-flat output.
- Added redaction verification before artifact storage/delivery: readable PDF, independently sourced page count, no extractable text layer, expected redaction-count consistency, and visual coverage checks for every redaction target.
- Added straight-through package terminal state `COMPLETED`.
- Delivery failures are technical failures; they no longer get swallowed as successful processing.
- Customer filename is preserved while internal tenant/package/document directories prevent collisions.
- Final delivery is batch-gated until all package documents are processed and review-free, then local delivery stages the complete package and publishes it as one directory move so failed staging does not expose a partial customer batch.
- Credit-card deterministic recognition/validation supports 13–19 digit PAN candidates with Luhn validation.
- Ollama page analysis uses bounded page concurrency (maximum four simultaneous page calls) instead of a purely serial page-by-page chain.
- Normal submit starts processing automatically; frontend manual trigger/finalize controls are not part of the happy path.
- Repository/Kiro steering documentation was synchronized to the current AI-first, shared-dashboard, multi-business-identifier decisions.

## Intentionally deferred

- OCR/image pages — Unit 17.
- Full technical retry/backoff/failover taxonomy — Unit 18.
- Focused Manual Review UX — Unit 19.
- Business Record / multiple identifiers / unified intake — Unit 20.
- Business Admin — Unit 21.
- Delivery lifecycle/retention and non-local provider transaction semantics — later delivery/retention unit. The local V1 adapter now provides package-atomic publication.
- Approved shared Dashboard/reporting UX — Unit 23.
- IT Admin, identity/IDP, and final hardening — later roadmap units.

## Verification status

The correction environment performed source-level checks, after which Kiro executed the final local verification gate against the corrected workspace. Final results:

- `mvn clean verify`: **BUILD SUCCESS** across all backend reactor modules.
- Frontend `npx tsc --noEmit`: **PASS**.
- Frontend `npm run build`: **PASS**.
- Runtime provider: **real Ollama** using `gemma3:12b` with PostgreSQL and the local profile; heuristic fallback was not used.
- Acceptance A high-confidence straight-through: **PASS**.
- Acceptance B AI-only discovery when deterministic detection misses: **PASS**.
- Acceptance C mixed confidence / genuine review condition: **PASS**.
- Acceptance D no-sensitive-information document still delivered: **PASS**.
- Acceptance E delivery failure becomes technical failure with no partial publication: **PASS**.
- Acceptance F duplicate filenames across packages remain isolated without renaming: **PASS**.
- Acceptance G multi-document package is batch-gated with no partial final delivery: **PASS**.
- One verification-blocking persistence defect was found and minimally corrected in `DocumentMapper`: persisted `page_count` is now rehydrated on domain reload so independent redaction verification has the source page count.

**Final status: UNIT 16 APPROVED — 2026-08-29.**

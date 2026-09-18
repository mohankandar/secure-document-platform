LexaVault V1 — Versioned & Reversible Document Intelligence Foundation candidate

Use this ZIP as a clean replacement of the prior accepted Business Identifier Validation candidate, not as a file-by-file overlay.

Current unit:
- tenant Document Intelligence modes: DISABLED / OBSERVE / ENABLED
- global version bundle catalog with CANDIDATE / PROMOTED / RETIRED lifecycle semantics
- Business Admin tenant pinning + last-known-good + rollback
- immutable per-package-processing-cycle intelligence snapshots
- fail-safe fallback to the frozen R11 baseline
- safe intelligence version evidence in structured logs / processing incident fingerprints
- no adaptive Document Profile Intelligence yet

Safety baseline:
- existing/customer tenants default to DISABLED
- baseline discovery is mandatory in every mode
- OBSERVE cannot influence redaction/review/export
- DISABLED always returns processing to the frozen baseline layer
- technical/admin roles do not gain Business Admin intelligence-policy authority

Liquibase:
- adds changeSet 023-01-document-intelligence-foundation
- expected applied changeSet count after startup: 54

Minor prior finding included:
- LOCAL-only exact stale Service Entity {000,001,002} reference data is repaired to {000..005}

After extraction:
  build-all.cmd

Then follow:
  DOCUMENT_INTELLIGENCE_FOUNDATION_LOCAL_CHECKLIST.md

Do not treat this unit as accepted/frozen until local runtime/UI validation and the independent Kiro checkpoint pass.

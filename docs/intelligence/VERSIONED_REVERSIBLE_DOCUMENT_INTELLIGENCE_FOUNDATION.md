# Versioned & Reversible Document Intelligence Foundation

## Scope

This unit creates the safety and versioning boundary required before adaptive Document Profile Intelligence can influence LexaVault processing. It intentionally does **not** implement profile recognition or profile-driven finding decisions.

The frozen R11/general-AI, OCR, deterministic validation, Manual Review, permanent-redaction verification, and export-gating paths remain authoritative.

## Tenant modes

| Mode | Intelligence execution | May influence decisions | Frozen baseline discovery |
|---|---:|---:|---:|
| `DISABLED` | No | No | Required |
| `OBSERVE` | Yes, when an adaptive bundle exists | No | Required |
| `ENABLED` | Yes, when an adaptive bundle exists | Yes | Required |

Existing tenants have an effective default of `DISABLED` even when no tenant policy row has yet been persisted.

## Version bundle

A bundle is an immutable, privacy-safe descriptor of an intelligence release. It carries safe version identifiers for the model, prompt, detectors, OCR rules, document profile, validation rules, and thresholds. It never contains credentials, prompts, OCR text, raw model responses, or protected document content.

Bundle lifecycle states are:

- `CANDIDATE`: not selectable or executable by tenants.
- `PROMOTED`: regression-approved and selectable for new tenant pins.
- `RETIRED`: no longer selectable for a new pin, but still executable for an existing tenant pin or last-known-good rollback target. This prevents retirement from breaking a customer already pinned to a known-good version.

The catalog is release/deployment managed. Business Admins select promoted versions; they do not create or promote bundles.

## Reversibility

Each tenant policy stores:

- activation mode,
- active bundle version,
- last-known-good bundle version,
- monotonically increasing configuration version.

Business Admin can disable intelligence, pin a promoted bundle, mark the current bundle last-known-good, or roll back to the recorded last-known-good bundle. Disabling is a safety operation and is not blocked merely because a remembered bundle has since been retired.

## Per-processing-cycle snapshot

One immutable `package_processing_intelligence_snapshots` row is captured for each `(tenant, package, processingCycle)`. It records requested/effective mode, selected bundle, policy version, execution/influence flags, baseline-required flag, fallback state, and capture time.

The same snapshot is reused when a package pauses for review/failure and later resumes. A reprocess cycle gets a new snapshot. This prevents an administrator changing configuration in the middle of a package from silently changing that package's intelligence behavior.

## Fail-safe baseline fallback

If intelligence configuration/snapshot metadata cannot be used, the optional intelligence layer must not take the frozen processing path down. The effective context falls back to `DISABLED` + `r11-baseline-v1` whenever possible, and orchestration treats snapshot-capture failure as non-blocking telemetry/configuration failure.

Future adaptive intelligence code must consume the captured processing-cycle snapshot rather than re-reading mutable tenant policy during the cycle.

## Support evidence

Structured operational fields include the requested/effective intelligence mode, bundle version, policy version, execution/influence flags, baseline-required flag, processing cycle, and baseline-fallback flag. These fields are also added to processing-incident fingerprints when a snapshot is available.

The privacy boundary remains unchanged: no raw document content, OCR text, prompts, model responses, or sensitive values are allowed in ordinary logs or incident fingerprints.

## Current baseline bundle

`r11-baseline-v1` is seeded as `PROMOTED` with profile version `none`. Therefore switching the foundation setting to `OBSERVE` or `ENABLED` while only this baseline bundle exists does not introduce adaptive profile behavior. The next Document Profile Intelligence unit will add profile-capable bundles only after regression evidence exists.

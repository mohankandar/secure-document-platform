# LexaVault V1 - Customer-Demo Accuracy Hardening

Date: 2026-09-04

## Baseline determination

The current uploaded workspace is the working baseline for this hardening pass. Its source ZIP SHA-256 is:

`d0beaaacf36e2d47de2f8f3a74186c11ffee3d62247f610b461e48b0ac753a0a`

It already contains the Primary-identifier manual-intake candidate (Primary-only BusinessRecord resolution, secondary reconciliation, migration 019, and regression tests). Do not revert it to the earlier functional-freeze ZIP.

Functional V1 remains frozen. Customer-demo accuracy remains unfrozen until local build/runtime verification, Qwen precision/performance rerun, and the Kiro checkpoint pass.

## Reproduced defects

### Manual submit blocks the UI during processing

The manual `/packages/{id}/submit` controller invoked the full processing use case synchronously. That made the HTTP request wait through AI/redaction and kept the browser in `Submitting...`, which also disabled the PDF picker for the duration.

Correction: manual submit now uses the same `PackageProcessingDispatcher` post-commit contract as machine intake. The package becomes `SUBMITTED`, the transaction commits, the HTTP call returns, and expensive processing starts asynchronously after commit. This preserves the R3 stale-state/rollback race fix.

### AI candidates were allowed into review even when type-impossible

A model candidate could be confidence-capped by deterministic evidence but still become a low-confidence review finding. That allowed examples such as a person name classified as DOB to reach Manual Review.

Correction: AI remains the primary discovery layer, but grounded candidates now pass a type-specific plausibility gate before confidence/review policy. Impossible or contextually contradictory candidates are discarded; plausible ambiguity still reaches review.

### Primary identifier persistence had a concurrency hole

Migration 019 correctly removed tenant-wide uniqueness from secondary identifier values and enforced uniqueness only for an active Primary definition. Its trigger used an existence check, which alone can race under concurrent inserts of the same new Primary value.

Correction: migration 020 replaces that trigger function with transaction-scoped advisory locking for the tenant + definition + normalized Primary value before the duplicate check. This keeps secondary values repeatable while making Primary uniqueness concurrency-safe without modifying the already-existing 019 changeset.

## Direct PDF comparison - contract 00224-2500668

The 78-page original and 78-page processed PDF are a direct content/identifier pair. The processed PDF has no fonts/text layer, which is consistent with burn/flatten output rather than a visual overlay retaining the original searchable text.

Confirmed prior-output behavior:

- Page 1: address, phone, and Contract Number were blacked. All are false positives for the current V1 configurable categories (SSN, DOB, card PAN, credit score).
- Page 2: Date of Sale plus phone values were blacked. These are false positives.
- Page 4: masked card ending 6304 remained visible. This matches the V1 last-four-only card policy.
- Page 7: actual consumer credit score 780 was blacked while the explanatory 300-850 range remained visible. This is correct and must not regress.
- Page 8: generic privacy language `Social Security number and income` remained visible. This is correct because no actual SSN value is present.
- Page 10: Enrollment Agreement date 12-20-2025 was blacked. False DOB-type over-redaction.
- Page 19: First Payment Date 01-12-2026 was blacked. False DOB-type over-redaction.

A render-diff scan across all 78 pages found the high-confidence new black-box regions above; low-occupancy rasterization differences were not treated as redactions.

## 00371 documents are not a direct pair

`003712500558_ori.pdf` is contract `00371-2500558` while processed `003712600805.pdf` is contract `00371-2600805`. They must not be page-diffed as the same source/result pair.

They are still useful as separate real-world regression material. In the source-only `00371-2500558` document:

- page 3 Date of Sale 04-21-2025 is a negative DOB case;
- page 23 generic `Social Security number and income` is a negative SSN case;
- page 45 First Payment Date 06-05-2025 is a negative DOB case.

The processed `00371-2600805` page 14 shows an actual credit-score field blacked; retain that positive behavior when the matching source is available for a direct comparison.

## Precision policy implemented

The candidate flow is now:

AI discovery -> exact-page matched-text grounding -> trusted local context -> deterministic supporting validation -> type-specific plausibility -> confidence/policy -> auto-redact / manual review / discard.

Key rules:

- DOB: must parse as a plausible date and be associated with birth context; transaction/document/payment/date labels closer to the candidate defeat a nearby unrelated birth label.
- SSN: actual nine-digit value (or allowed last four) plus candidate-associated SSN semantics; business/reference context wins when it is the closer label. Generic SSN discussion cannot legitimize a nearby reference number.
- Card: full 13-19 digit PAN only for V1; masked last-four-only displays are not findings by default. Explicit card context can keep an AI-found full PAN plausible even when Luhn contradicts, so Luhn is supporting evidence rather than the AI discovery gate.
- Credit score: exactly 300-850, candidate-associated credit-score context, and not an explanatory range value. An actual 780 remains valid even when the same page also explains the 300-850 range.

The compact Qwen prompt was strengthened without materially increasing prompt size: exact value only, never a name as DOB, no masked/last-four card display, no generic privacy/category/example language, no transaction dates as DOB, no business identifiers as SSN/PAN, and no explanatory score range.

## Regression coverage added

- `SensitiveCandidatePlausibilityPolicyTest`
- `DeterministicContextPrecisionTest`
- `PackageControllerManualSubmitTest`
- prompt assertions in `TenantAiPolicyAndRoutingTest`
- existing `BusinessIdentityServiceTest` covers repeated secondary values, same-Primary reuse, and contradictory secondary rejection
- `customer-demo-precision-regression-cases.csv` records synthetic + real positive/negative acceptance cases with page/context and expected FIND/REDACT decisions

## Verification status

Static/JDK-level harness checks passed for the new plausibility policy and deterministic detector, including:

- name-as-DOB rejected;
- payment/document dates rejected as DOB;
- nearby generic SSN discussion does not rescue a business reference;
- labeled SSN retained;
- masked card last four rejected as standalone PAN;
- Luhn-valid contract identifier rejected as card;
- actual card-context full PAN retained;
- actual score 780 retained even with a 300-850 explanation on the same page;
- range endpoints rejected.

The container does not have Maven, so the authoritative full build must be run locally with:

`./Build-LexaVault-All.ps1` (PowerShell: `.\Build-LexaVault-All.ps1`)

Do not run the Kiro checkpoint until that build succeeds.

After build success, runtime verification must rerun the synthetic gold-standard, the two real-contract sets, manual multi-PDF intake, identifier reconciliation, Qwen timing, and Requirements 1-5 focused regression. Only after those pass should the customer-demo baseline be frozen.

# LexaVault Tenant Licensing — R1 / Iteration 2 FIX1 — VERIFIED / FROZEN

**Status:** VERIFIED / FROZEN  
**Accepted:** 2026-09-18  
**Schema baseline:** 70 Liquibase changeSets through `029-tenant-licensing.xml` (**unchanged**)

## Accepted scope

Iteration 2 completes the Platform Operations licensing administration experience on top of the frozen Iteration 1 enforcement foundation. The accepted scope includes:

- Platform Operations `Licensing` tab;
- enforcement and verification-key readiness;
- all-tenant summary and tenant selection;
- license status, processing admission, customer, edition, issued/not-before/expiry, entitlements and limits;
- vendor-signed `LV1...` install/update;
- immutable license history with current-record indication;
- 30-day attention and 7-day urgency treatment;
- minute/hour/day relative expiry rendering;
- audit-history preservation across verification-key changes;
- explicit offline Ed25519 verification-mode messaging;
- raw signed token secrecy.

Optional connected/online validation is not part of this frozen unit and remains deferred until a remote licensing-service contract, authentication policy, availability/SLA expectations and air-gap behavior are defined.

## FIX1 findings closed

Focused Iteration 2 testing exposed two issues:

1. Licenses with only minutes remaining were rounded to `Expires in 1 day` and were not counted in `Needs attention` until expiration.
2. Historical licenses that had been validly installed under a previous/test verification key lost readable edition/expiry/entitlement metadata after the configured key changed.

FIX1 closes both while preserving fail-closed active authorization.

## Accepted runtime/UI evidence

### 1. Build candidate correction

The first FIX1 package contained a test-source helper type mismatch: `HistoricalVerifier` could not be passed to a helper declared as `ConfigurableVerifier`. The helper was corrected to accept the shared `LicenseTokenVerifier` interface. This was test code only and did not change runtime licensing semantics. The corrected candidate was then used for the focused runtime/UI validation below.

### 2. Malformed token — PASS

Submitting `LV1.invalid.invalid` from the Licensing UI returned a clear malformed-token error. The existing valid current license remained ACTIVE / processing allowed and no bogus replacement became current.

### 3. Missing DOCUMENT_PROCESSING entitlement — PASS

A correctly signed, correct-tenant, unexpired license without `DOCUMENT_PROCESSING` rendered:

- license status `ACTIVE`;
- Processing `Blocked`;
- Processing allowed `0`;
- Needs attention `1`;
- detail message `DOCUMENT_PROCESSING entitlement missing`;
- entitlement list without `DOCUMENT_PROCESSING`.

This matches the frozen Iteration 1 enforcement semantics.

### 4. Near-expiry attention — PASS

An ACTIVE / processing-allowed license expiring within seven days rendered Needs attention `1`. This confirms near-expiry attention is independent from processing admission.

### 5. Sub-day expiration formatting — PASS

A 120-second signed license rendered `Expires in less than 1 minute` near the end of its validity window rather than rounding up to `1 day`.

### 6. Automatic expiry transition — PASS

Without the operator clicking Refresh, the same Licensing page automatically transitioned after the signed expiration instant to:

- status `EXPIRED`;
- Processing `Blocked`;
- Active licenses `0`;
- Processing allowed `0`;
- Needs attention `1`;
- current history row `EXPIRED`.

This closes the risk of a stale ACTIVE visual state after the countdown crosses `expiresAt`.

### 7. Historical metadata across key changes — PASS

Historical records installed under temporary/previous test keys retained readable:

- edition;
- expiry;
- entitlement count;
- installer/time;
- license ID.

Recovered rows are labeled `Verified at install`. Inactive records still within their validity window render `SUPERSEDED`; elapsed records render `EXPIRED`. They no longer collapse to `Unknown edition / INVALID SIGNATURE / 0 entitlements` solely because the current verification key differs.

Historical recovery is display/audit-only. It verifies the persisted payload SHA-256 plus immutable tenant/license/schema identity. It does **not** authorize processing.

### 8. Active/current fail-closed authorization — PASS / preserved

The current record remains authorized only when its signature validates under the currently configured Ed25519 public key and all tenant/time/entitlement rules pass. A current-key mismatch remains an authorization failure; readable history metadata cannot turn an invalid current license into an allowed license.

### 9. Normal-license restoration — PASS

After restoring the normal public key and original development license, the Local Demo Tenant returned to:

- `ACTIVE`;
- Processing `Allowed`;
- Active licenses `1`;
- Processing allowed `1`;
- expected six entitlements;
- Needs attention `1` because the license expires within seven days.

The current history record is clearly marked `Current / ACTIVE`; prior test records remain immutable historical entries.

### 10. Raw-token secrecy — PASS / preserved

Overview/history continue to omit the raw signed `LV1...` token. The UI states that the token is submitted only to the licensing API for verification/storage and is not returned by platform overview/history.

## Security and architecture conclusions

- The UI now distinguishes license validity, processing admission and operational attention instead of conflating them.
- Near-expiry warning logic does not weaken authorization.
- Historical readability survives verification-key changes without treating old-key verification as current authorization evidence.
- Active/current licensing remains fail-closed.
- No signing private key is introduced into the customer runtime.
- No raw signed token is exposed through overview/history UX.
- No machine binding is added; immutable tenant identity remains the licensed identity.

## Explicitly unchanged

- Tenant Licensing R1 Iteration 1 enforcement semantics remain frozen.
- Platform-Managed Processing Storage remains VERIFIED / FROZEN.
- Final master delivery, SDV delivery and derived-document delivery are unchanged.
- OCR, redaction, review, AI, scanner, reporting and routing semantics are unchanged.
- No database migration is added; Liquibase remains 70 changeSets.
- Connected/online licensing validation remains future scope.

## Final disposition

**Tenant Licensing R1 Iteration 2 FIX1 is VERIFIED / FROZEN.** Reopen only for a reproducible defect or an explicitly scoped future licensing enhancement.

The next planned product workstream is **UI/UX Modernization with responsive iPad/tablet support**, followed by **On-Prem Installer & First-Run Setup**.

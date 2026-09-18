# LexaVault Tenant Licensing — R1 / Iteration 2 FIX1 — Candidate

**Status:** IMPLEMENTED CANDIDATE / LOCAL VALIDATION REQUIRED  
**Date:** 2026-09-18  
**Schema baseline:** 70 Liquibase changeSets through `029-tenant-licensing.xml` (**unchanged**)

## Why FIX1 exists

Focused Iteration 2 runtime/UI validation passed the primary licensing workflows but exposed two presentation/audit-history gaps:

1. A valid license with only minutes remaining was rendered as `Expires in 1 day`, and the summary reported `Needs attention = 0` until the license actually expired.
2. Historical licenses that had been validly installed with an earlier/test signing key rendered as `INVALID SIGNATURE`, `Unknown edition`, no expiry and zero entitlements after the configured verification key changed.

The licensing enforcement engine itself behaved correctly: the short-lived license transitioned to `EXPIRED` at the signed instant and document processing failed closed; entitlement-missing and malformed-token cases were also correctly denied.

## FIX1 changes

### Expiration / attention UX

- Sub-day expiry is now rendered in minutes/hours rather than rounded up to one day.
- The Licensing page refreshes relative-time presentation every 30 seconds without requiring a backend refresh.
- `Needs attention` now includes an otherwise ACTIVE/processing-allowed tenant whose license expires within 30 days.
- Missing, invalid, expired and processing-blocked tenants continue to require attention.
- Existing 7-day and 30-day warning tones are retained.

### Historical-license/key-rotation hardening

- **Current/active authorization is unchanged and remains fail-closed.** Every active license is still cryptographically verified with the currently configured Ed25519 public key before processing is permitted.
- History can recover the claims of a previously verified persisted record even when the currently configured public key no longer verifies that historical signature.
- Historical recovery validates the immutable persisted payload fingerprint (`payload_sha256`) plus tenant ID, license ID and supported schema before rendering claims.
- Recovered metadata is explicitly labeled **Verified at install**; it is never treated as current-key authorization evidence.
- Inactive, still-time-valid historical licenses render as `SUPERSEDED`; expired historical licenses render as `EXPIRED`.
- If an **active** record no longer validates under the current key, it remains `INVALID_SIGNATURE` / processing blocked. Historical claim recovery may make its audit metadata readable, but never makes it processing-authorized.
- Raw signed `LV1...` tokens remain absent from overview/history responses and UI.

## No schema migration

No new persistence column is required. Existing license records already retain:

- the original signed token internally;
- SHA-256 of its signed payload component;
- immutable tenant/license IDs;
- installation actor/time.

Those fields are sufficient to recover display-only claims for a record that was successfully verified before persistence while keeping runtime authorization tied to the current verification key.

Liquibase remains **70 changeSets**.

## Focused local validation requested

1. Build the full candidate.
2. With a valid license expiring in less than 24 hours, confirm the UI shows minutes/hours rather than `1 day`.
3. Confirm `Needs attention = 1` for an ACTIVE/processing-allowed license expiring within 30 days.
4. Let the short-lived license expire and confirm `EXPIRED`, processing blocked and `Needs attention = 1` remain correct.
5. Restore the normal public key/license and open history.
6. Confirm prior licenses installed under temporary keys retain edition, expiry and entitlement metadata instead of `Unknown edition / 0 entitlements`.
7. Confirm those recovered rows are labeled `Verified at install` and show `SUPERSEDED` or `EXPIRED` as appropriate.
8. Confirm the current valid license remains `ACTIVE` / processing allowed.
9. Temporarily configure a mismatching public key and confirm the **current** license becomes `INVALID_SIGNATURE` / processing blocked even though its history metadata can still be read.
10. Confirm overview/history responses still contain no raw `LV1...` signed token.

## Expected disposition

If the focused checks pass, Tenant Licensing R1 Iteration 2 can be documented as **VERIFIED / FROZEN** without reopening frozen V1 processing behavior.

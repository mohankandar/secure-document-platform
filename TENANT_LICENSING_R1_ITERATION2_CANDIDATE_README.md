> **FIX1 update (2026-09-18):** Focused UI validation exposed sub-day expiry/Needs-attention and historical key-change rendering gaps. The active candidate is now `TENANT_LICENSING_R1_ITERATION2_FIX1_CANDIDATE_README.md`. This file remains the original Iteration 2 scope/evidence plan.

# LexaVault Tenant Licensing — R1 / Iteration 2 Platform Operations UI — Candidate

**Status:** IMPLEMENTED CANDIDATE / LOCAL UI VALIDATION REQUIRED  
**Date:** 2026-09-18  
**Schema baseline:** 70 Liquibase changeSets through `029-tenant-licensing.xml` (unchanged)

## Scope

Iteration 2 adds the Platform Operations licensing experience on top of the VERIFIED / FROZEN Iteration 1 backend foundation. No licensing persistence, cryptography, entitlement enforcement, processing gates, Processing Storage behavior, or delivery behavior is changed by this candidate.

The candidate adds a `Licensing` tab under **Administration → Platform Operations / Technical Administration** for users with the existing `PLATFORM_OPERATIONS` capability.

### UI capabilities

- All-tenant licensing overview using the existing Platform Operations licensing API.
- Runtime enforcement state and public verification-key readiness.
- Summary counts for provisioned tenants, active licenses, processing-allowed tenants and tenants needing attention.
- Per-tenant status, processing admission state, edition and expiration visibility.
- Expiration UX with stronger warnings at 30 days and 7 days, plus explicit expired state.
- Tenant detail view with customer, edition, license ID, issued/not-before/expiry timestamps, entitlements and numeric limits.
- Secure install/update workflow for signed `LV1...` tokens using the existing install API.
- Raw signed token is held only in the form while entered, is cleared after a successful install, and is never shown in overview/history.
- Immutable license-history view using the existing history API, including current-record indication, runtime validation status, edition, expiry, entitlements and installer identity.
- Explicit offline verification-mode messaging: Ed25519 signed/offline validation remains authoritative for on-prem operation.

## Connected validation boundary

Optional connected/online validation is deliberately **not activated** in this candidate because there is not yet an agreed remote licensing-service contract, authentication model, availability/SLA policy or air-gap behavior. The UI states that connected validation is not configured rather than pretending that an external check exists. This preserves the Iteration 1 offline/on-prem trust model.

## Security boundaries retained

- Only `PLATFORM_OPERATIONS` users can access the licensing API/tab.
- The private license signing key is never part of LexaVault.
- Signed tokens are not returned by APIs and are not rendered in history.
- A UI state never bypasses backend fail-closed enforcement.
- Tenant identity remains the immutable licensed identity; no machine binding is introduced.

## Local validation requested

1. Start LOCAL with licensing enforcement enabled and a valid development public key.
2. Select Platform Operations and open **Technical Administration → Licensing**.
3. Confirm enforcement/key status and tenant summary render correctly.
4. Confirm the current valid license shows ACTIVE / processing allowed and its entitlements/expiry.
5. Confirm expiry warning text matches the license expiration date.
6. Open history and confirm current/prior records render and no `LV1...` raw token appears.
7. Install a newly generated valid signed license through the UI and confirm the tenant/status/history update without a page reload.
8. Attempt a tampered or wrong-tenant token through the UI and confirm the backend rejection is shown and the prior active license remains unchanged.
9. Confirm TENANT_IT_ADMIN cannot access the Platform Operations Licensing tab/API.
10. Restore the normal valid license and run one document intake regression to confirm processing remains unchanged.

## Expected disposition

If the focused UI/security/runtime checks pass, update continuity/master status to **VERIFIED / FROZEN**. No Kiro/full-V1 rerun is required unless the validation exposes a regression outside this explicit UI scope.

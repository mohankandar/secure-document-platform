# LexaVault Continuity — Current State

**Authoritative date:** 2026-10-06  
**Product:** LexaVault  
**Repository/code namespace:** `secure-document-platform` / `com.securedoc.platform.*`

This file is the primary handoff for a new ChatGPT thread, Kiro review, or developer session. Current source code and current canonical documentation override removed historical candidate notes.

## 1. Product baseline

LexaVault V1 is **COMPLETE / VERIFIED / FROZEN**. The accepted baseline includes digital/scanned PDF intake, OCR, sensitive-data discovery, automated/permanent redaction, Manual Review/manual processing, business identifiers, watched-folder/API/polling/scanner intake, master/derived delivery, reporting, support/audit, retention, multi-node processing coordination, managed processing storage, tenant licensing, operational continuity, and responsive administration/operator workflows.

Frozen behavior must not be silently reopened while performing visual-design work.

## 2. Identity and authorization

Enterprise Identity is a separate reusable project/repository. LexaVault consumes Maven artifacts:

- `enterprise-identity-core`
- `enterprise-identity-spring`
- version `0.2.0`

Human OIDC identity normalization is shared; LexaVault owns product role-to-capability authorization. Machine/integration identity remains a separate boundary.

Production tenant resolution prefers the persisted **ACTIVE Enterprise Identity connection** (provider + verified issuer + audience -> internal tenant). A configured tenant binding remains a compatibility path, and the legacy tenant claim is only a narrow fallback when no trusted connection/configured binding is available. Caller-supplied tenant/actor/role headers are not trusted in OIDC mode.

Enterprise Identity Administration R1 Checkpoints A/B/C are **VERIFIED / FROZEN**. Tenant IT owns tenant authentication configuration through `TENANT_AUTH_MANAGE`; Platform Operations does not implicitly receive that capability. Activation requires a fresh successful connection test and exact-version activation; activation/rollback/recovery remain fail-closed and tenant scoped.

Browser OIDC Authorization Code + PKCE session continuity is **VERIFIED / FROZEN**. Refresh tokens remain in browser `sessionStorage`, access tokens refresh before expiry, refresh-token rotation is supported, concurrent refresh is guarded, rejected refresh fails closed, and explicit sign-out clears the session.

## 3. Current schema and build boundary

- Java: **21**.
- Backend: Maven multi-module build under `backend/`.
- Frontend: React 18 + TypeScript + Vite under `frontend/securedoc-ui/`.
- Liquibase master includes `001` through `034-enterprise-identity-activation.xml`.
- This source contains **76 Liquibase changeSets**.
- Enterprise Identity `0.2.0` must be available to Maven; its source is intentionally not embedded in LexaVault.

## 4. SecureDocs UI Framework status

The implementation-level UI framework consolidation is **PASS / COMPLETE** after independent Kiro re-audit.

Accepted framework properties:

- one token source (`tokens.css`);
- canonical `PageScaffold` for all top-level routes;
- canonical Button, Card, Alert, EmptyState, Field, Skeleton, StatusBadge, AttentionLink, HorizontalTabs, InfoGrid, WorkflowStepper and related UI primitives;
- one documented responsive breakpoint policy: `640, 768, 900, 1024, 1200, 1280, 1400`;
- retired primitive families are banned;
- raw hex is restricted to `tokens.css`;
- the old card-family `!important` unification workaround cannot return;
- framework checks are wired into the normal repository build.

The framework is now a **frozen implementation foundation**, not the active redesign target. Visual-marketability work should consume these primitives and may refine their visual tokens/styles without recreating competing primitive families.

## 5. Market-Ready Visual Design R1 status

Market-Ready Visual Design R1 (R1A-R1D) is **VERIFIED / FROZEN**. The accepted visual baseline covers the global shell, Dashboard, Upload Documents, Package Details, Manual Review, Reporting, remaining operator/admin/support/audit screens, and responsive finishing for desktop/laptop/iPad.

Design target remains **Enterprise Precision**: restrained, confident, information-dense enterprise software suitable for bank/mortgage/insurance/customer demos. Typography, alignment, spacing, hierarchy, subtle separators, brand accent and semantic color take precedence over decorative cards or effects.

Canonical plan: `docs/design/LEXAVAULT_MARKET_READY_VISUAL_DESIGN_R1_PLAN.md`. Historical phase-candidate notes are intentionally excluded from the clean source tree.

## 6. Visual principles

- Tesla-like cleanliness is an influence, not a template.
- Prefer enterprise information density over giant whitespace.
- Cards are a grouping tool, not the default container for every piece of information.
- Color is semantic or brand-directed, not decorative.
- Tables should feel deliberate and premium: strong rhythm, alignment, hover/selection and restrained status treatment.
- No glassmorphism, rainbow KPI cards, excessive gradients, giant in-app marketing headings, decorative animation, or tiny text to fake density.
- Keep legitimate dynamic inline styles for document bounding-box geometry and data-driven chart dimensions.

## 7. Known cleanup/fix completed with this clean source

The audit-identified `review-pui-loading-state` typo was corrected to the existing `review-preview-loading` class in both Manual Review and Manual Processing. This is a styling correctness cleanup, not a workflow change.

Historical root candidate/FIX readmes, changed-file lists, archived unit notes, old verification-candidate snapshots, editor metadata, and superseded cleanup records were removed from this clean source package. Kiro/AIDLC steering is retained intentionally.

## 8. Verification boundaries

Do not reopen these unless an actual regression is demonstrated:

- Enterprise Identity Administration R1;
- OIDC session continuity;
- SecureDocs UI Framework consolidation;
- accepted V1 business/security/tenant isolation behavior.

For Market-Ready Visual Design, run `npm run test:ui-framework` after each implementation checkpoint. Use Kiro at visual/design phase gates or when local-machine/runtime access materially improves verification; do not spend Kiro credits on trivial CSS edits.

## 9. Current engineering posture

Do not reopen frozen V1, identity, UI-framework, or visual-design work without a demonstrated regression or an explicitly approved new iteration. Current dependency-security hardening is recorded in root `SECURITY-DEPS.md`; two moderate React Router advisories remain accepted based on current SPA usage, while the broader backend OSV transitive scan still carries a documented ALPN-resolution limitation.

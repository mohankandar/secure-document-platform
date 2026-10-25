# LexaVault dependency-security hardening — 2026-10-06

Status: **VERIFIED / FROZEN WITH 2 ACCEPTED MODERATE FRONTEND FINDINGS**.

## Frontend

Validated dependency state:

- `react-router-dom 6.30.6`
- `react-router 6.30.6`
- `@remix-run/router 1.23.4`
- `vite 6.4.3`
- `esbuild 0.25.12`
- `source-map-js 1.2.2`

Validation completed successfully:

- `npm run build` — PASS
- `npm run test:ui-framework` — 17/17 PASS
- `npm run test:auth` — 8/8 PASS
- `npm audit` — 2 moderate findings remain
- `npm audit --omit=dev` — the same 2 moderate findings remain

The previous Vite/esbuild/source-map high-severity dev-tooling findings were cleared without a major framework upgrade.

Two React Router advisories remain:

1. open redirect via backslash handling in `<Link>` / `useNavigate`;
2. arbitrary constructor injection in React Router SSR hydration.

Current LexaVault routing uses internally constructed SPA routes and does not use React Router SSR hydration. These findings are therefore documented as low-applicability / non-applicable to the current implementation. React Router 7 migration is deferred because the available npm remediation is a breaking major upgrade and should be handled as a separate compatibility change.

Do **not** claim "zero vulnerabilities".

## Backend

Jackson was upgraded and aligned as one family at `2.18.9` across the relevant modules. The final dependency tree confirmed consistent `2.18.9` resolution for `jackson-databind`, `jackson-core`, `jackson-annotations`, `jackson-datatype-jdk8`, `jackson-datatype-jsr310`, and `jackson-module-parameter-names` where used.

Validation completed successfully:

- full Maven `clean install` across all backend modules — PASS;
- full repository build (`Build-LexaVault-All.ps1`) — PASS;
- local OIDC runtime startup — PASS;
- follow-up OSV output no longer reported the previous Jackson findings.

The initial one-artifact Jackson upgrade caused a mixed-family runtime and a `NoSuchMethodError`; the final fix centralizes the Jackson version and aligns the Jackson BOM so core/databind/annotations/datatypes resolve coherently.

## Scanner limitation / residual risk

The repository OSV scan still could not fully enrich Maven transitive dependencies because OSV's Maven resolver failed TLS/ALPN negotiation in this environment. Therefore the specific Jackson issue is verified fixed, but the backend must **not** be described as exhaustively scanned with zero vulnerabilities.

Current recorded posture:

- known critical findings from completed checks: **0**;
- previous Jackson backend finding: **remediated and verified**;
- previous frontend high-severity dev-tooling findings: **remediated and verified**;
- remaining accepted findings: **2 moderate React Router advisories**;
- backend transitive OSV coverage: **partial due to ALPN resolver limitation**.

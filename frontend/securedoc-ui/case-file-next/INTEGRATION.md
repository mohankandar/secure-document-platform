# Case File integration candidate

These components are copied exactly from prototype commit `984604a` to the real frontend package boundary. They are **not yet mounted** or compiled as part of the production application. They deliberately live outside `src` until their API/auth/type dependencies are adapted and production builds can be proven green.

Next: integrate into `frontend/securedoc-ui/src/studio` as ported components with real API contracts, OIDC and authorization; update dependency lockfile for SWR/lucide-react; mount replacement routes only after preserving support, technical, fallback and branding functions in `integration-reference/FUNCTIONAL_GAPS.md`.

No production UI feature may be removed before its equivalent is verified. Do not import `src/demo` into the live frontend.

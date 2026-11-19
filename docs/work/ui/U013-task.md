# Task U013: the sign-in "no access" screen in the new design (finding 21, part 2)

Written: 2026-11-17, by the design chat. Spec: `docs/work/ui/SPEC.md` (locked). Read `docs/work/ui/README.md` first. The owner approved this change in a frozen area (OIDC sign-in) on 2026-11-17, after report U001.

Design: `designs/NoAccess.html`.

## Do

1. Give the OIDC sign-in "no access" screen (what a signed-in person with no LexaVault role sees, for example `dave.none` in the Phase 1 environment) the same design and words as the Case File no-access page from U001: "You don't have access to anything in LexaVault yet", the signed-in email, "Ask your administrator to add you to a LexaVault group, then sign in again", with Sign out and Try again.
2. Change only the screen's look and words. Do not change how sign-in, session continuity or the refusal itself work.
3. Update the Phase 1 browser script's expected wording in the same commit, and say exactly which lines changed.

## Checks

- `npm run build` and `npm run test:standalone` pass; give counts.
- Phase 1: `Invoke-Phase1BrowserSignin.mjs --mode dev` passes with the new wording; `Invoke-Phase1Checks.ps1` still passes in full.
- Light and dark, 1440 px and 1024 px.
- Commit on `feature/ui-polish`. Do not push or merge.

# LexaVault Reporting R3 FIX1 — Dimension Eligibility + Target Status

## Scope
Focused correction to the R3 Business Dimensions + SLA candidate. No reporting UX redesign and no database migration.

### Fixed
- Reporting dimension eligibility now uses the actual low-cardinality safety property: an identifier must define **1–100 tenant-controlled allowed values**.
- This allows the current **Service Entity** definition (`NUMERIC`, exact length 3, allowed values `000`–`005`) to be used as a reporting dimension.
- Contract Number / Member Number remain ineligible because they do not define a bounded allowed-value set.
- Backend enforcement and frontend eligibility now match.
- Focused application test now proves a NUMERIC identifier with allowed values is eligible while Contract Number remains rejected.

### SLA presentation
- Business Admin `Targets vs actual` now shows explicit target status such as **Target met** or **Below target · N.N pts** instead of requiring a user to mentally compare percentages.
- No-data review timing remains explicit until completed review-start samples exist.

## Validation
Run `build-all.cmd`, start locally, then:
1. Business Admin → Reporting & Business Targets: Service Entity should appear under Approved reporting dimensions.
2. Enable Service Entity and save.
3. Business Admin Reporting should show a Service Entity breakdown for captured values.
4. Target status should show delivery below/above target and straight-through target status explicitly.

No Kiro checkpoint is needed for this focused local validation.

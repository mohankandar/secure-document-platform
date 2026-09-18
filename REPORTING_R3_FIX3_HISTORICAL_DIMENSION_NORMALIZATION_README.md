# LexaVault Reporting R3 FIX3 — Historical Dimension Normalization

## Scope
Focused data-quality correction for retained business-dimension reporting. No reporting UX redesign and no database migration.

### Fixed
- Approved dimension values continue to come from the tenant's current bounded allowed-value set.
- Historical retained values that no longer match that set are grouped as **Legacy / Unmapped** instead of appearing as current valid business categories.
- Current configured allowed values can appear with zero activity, so Business Admin can distinguish a valid but unused value from a historical/unmapped value.
- Legacy values are retained; this fix does not delete or rewrite historical reporting metadata.
- The UI explains the meaning of Legacy / Unmapped and gives it a visually distinct treatment.

### Example
For Service Entity with current allowed values `000`–`005`:
- `000`, `001`, `002`, `003`, `004`, `005` remain current reporting values.
- Historical values such as `ENT-POLL` or `ENT-1` are aggregated into **Legacy / Unmapped**.

## Validation
Run `build-all.cmd`, start locally, then open Business Admin Reporting:
1. Service Entity should show current approved values, including zero-activity configured values where applicable.
2. Historical invalid values should no longer appear individually.
3. If historical invalid values exist, one **Legacy / Unmapped** row should summarize them.
4. Existing SLA/target status and other reporting totals should remain unchanged.

No Kiro checkpoint is needed for this focused local validation.

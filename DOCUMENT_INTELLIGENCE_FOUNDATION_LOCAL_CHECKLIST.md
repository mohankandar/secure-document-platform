# LexaVault V1 - Document Intelligence Foundation Local Validation

Use this after `build-all.cmd` succeeds. This is local validation before Kiro.

## 1. Build / migration

1. Run `build-all.cmd`.
2. Start the local profile with the normal project launcher.
3. Confirm application health is green.
4. Confirm Liquibase applied exactly one new changeSet for this unit and the total is **54**:

```sql
select count(*) from databasechangelog;
```

5. Confirm the seeded bundle:

```sql
select version, status, model_version, prompt_version, detector_version,
       ocr_rules_version, profile_version, validation_rules_version,
       thresholds_version
from document_intelligence_bundles
order by version;
```

Expected current baseline row: `r11-baseline-v1`, `PROMOTED`, model `qwen3.5:4b`, profile `none`.

## 2. Business Admin UI

Open Business Admin -> Document Intelligence.

Expected initial/effective configuration for an existing tenant with no saved policy:
- Mode = `DISABLED`
- Active = `r11-baseline-v1`
- Last known good = `r11-baseline-v1`
- Baseline discovery message is visible
- Version fingerprint is visible (model/prompt/detector/OCR/profile/validation/threshold versions)

Current foundation has only the baseline bundle. That is intentional; do not create a fake adaptive profile just to test this unit.

## 3. Mode persistence

As BUSINESS_ADMIN:
1. Set `OBSERVE`, Save, refresh. It must remain `OBSERVE`.
2. Set `ENABLED`, Save, refresh. It must remain `ENABLED`.
3. Set `DISABLED`, Save, refresh. It must remain `DISABLED`.

Check persistence:

```sql
select tenant_id, mode, active_bundle_version, last_known_good_bundle_version,
       configuration_version, updated_at
from tenant_document_intelligence_policies
order by updated_at desc;
```

Each successful Business Admin change should increment `configuration_version` and append a safe audit entry.

## 4. Authorization

- BUSINESS_ADMIN: configuration GET/mutations allowed.
- TENANT_IT_ADMIN: must not gain Document Intelligence policy mutation authority.
- PLATFORM_OPERATIONS_ADMIN: must not gain Document Intelligence policy mutation authority.
- Standard USER: no Business Admin policy mutation authority.

Expected unauthorized mutation: 403, no tenant policy change.

## 5. Per-cycle processing snapshots

Process a normal package in each mode and inspect:

```sql
select package_id, processing_cycle, requested_mode, effective_mode,
       bundle_version, policy_configuration_version,
       intelligence_execution_allowed, decision_influence_allowed,
       baseline_discovery_required, fallback_to_baseline, captured_at
from package_processing_intelligence_snapshots
order by captured_at desc;
```

Expected with the current promoted baseline bundle:

### DISABLED
- requested/effective = DISABLED
- bundle = `r11-baseline-v1`
- execution = false
- influence = false
- baseline_discovery_required = true

### OBSERVE
- requested/effective = OBSERVE
- bundle = `r11-baseline-v1`
- execution = true
- influence = false
- baseline_discovery_required = true

### ENABLED
- requested/effective = ENABLED
- bundle = `r11-baseline-v1`
- execution = true
- influence = true
- baseline_discovery_required = true

Important: profile version is currently `none`, so OBSERVE/ENABLED do not yet add adaptive-profile behavior. They establish the control boundary for the next unit.

## 6. Snapshot immutability

For a package that is paused in review/technical attention, note its snapshot row and policy version. Change the tenant Document Intelligence mode, then resume the same processing cycle.

Expected: the existing `(tenant, package, processing_cycle)` snapshot does not change.

When a later reprocess/new cycle starts, it should receive a new snapshot using the then-current tenant policy.

## 7. Existing processing safety

Run at least one normal valid package through the existing pipeline in `DISABLED` mode.

Expected:
- R11/general-AI discovery still runs
- OCR/deterministic supporting paths unchanged
- Manual Review gating unchanged
- redaction/export safety unchanged
- no new requirement to have a Document Intelligence policy row

The foundation must not block processing if intelligence snapshot/configuration telemetry fails.

## 8. Support / privacy boundary

If a technical incident is produced during testing, inspect Support Console processing fingerprint. When an intelligence snapshot exists, safe fields should include mode/bundle/policy-version/baseline flags.

Confirm normal logs/incident metadata contain no raw PDF content, OCR text, prompts, raw model responses, SSNs, PANs, DOBs, or other protected values.

## 9. Service Entity minor-finding cleanup

On local startup, inspect Business Admin identifier configuration. If the previous stale demo value was exactly `{000,001,002}`, it should now be repaired to:

`000, 001, 002, 003, 004, 005`

The cleanup must not overwrite arbitrary customer/admin-defined allow-lists.

## 10. Rollback/LKG foundation

With only the baseline bundle, Active == Last Known Good, so rollback/LKG buttons can legitimately be disabled. Automated service tests cover version transitions, retired rollback eligibility, and candidate rejection. Full multi-version UI rollback will be exercised when the first adaptive profile bundle exists.

## Acceptance gate before Kiro

Proceed to Kiro only if:
- build succeeds
- Liquibase = 54
- DISABLED/OBSERVE/ENABLED save correctly
- authorization is unchanged
- processing snapshot is created and immutable per cycle
- DISABLED preserves current frozen behavior
- no privacy leak
- Service Entity demo cleanup is correct where applicable

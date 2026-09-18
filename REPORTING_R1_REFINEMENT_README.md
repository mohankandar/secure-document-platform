# LexaVault Business / Operational Reporting R1 Refinement

Status: **BUILD CANDIDATE — LOCAL BUILD + VISUAL REVIEW REQUIRED**

This is a focused refinement of Reporting R1 after local visual review. It does not broaden scope into the durable reporting-fact/SLA/business-dimension work that still remains before Reporting can be frozen.

## Why this refinement exists

The first R1 UX was visually strong, but local review exposed several business-semantic ambiguities that could confuse leadership even when the underlying data was technically correct:

- a sequential-looking `Received → Completed → Delivered` flow could show Delivered greater than Completed because delivery activity can include packages received earlier;
- straight-through delivery and the current manual-review queue could appear contradictory because one is period delivery history and the other is point-in-time backlog;
- `Needs attention` and total open work were shown together without enough distinction;
- median turnaround could render as `—` while P95 was populated because sub-hour timings were rounded to `0.0h` before display;
- Period was duplicated as both a control value and an active-filter chip;
- Usage & Adoption repeated Scanner volume even though the source-mix visualization already exposed it.

## Refinements

### 1. Executive reporting now separates period performance from current risk

The Executive view has two explicit sections:

- **Period performance** — selected-period intake, selected-intake delivery conversion, straight-through delivery and turnaround;
- **Current operational risk — Right now** — total open work, packages needing action, oldest open work, current attention queues and backlog aging.

This prevents point-in-time backlog from being mistaken for a selected-period outcome.

### 2. Cohort-safe intake-to-delivery measure

The API adds `periodIntakeDelivered` and `periodIntakeDeliveryRate`.

For the selected period, this counts only packages that were received in that period and had a successful export by the end of that period. It is therefore safe to present as a conversion measure and cannot exceed selected-period intake.

`delivered` remains period delivery activity and may legitimately include packages received before the selected period. The trend view now says this explicitly.

### 3. Straight-through semantics clarified

Straight-through is labeled **Straight-through delivery** and explicitly describes deliveries during the selected period that had no recorded review workflow event.

The manual-review count in Current Operational Risk is labeled as the **current review queue**. The screen explains that both values can be true simultaneously.

### 4. Turnaround no-data vs fast-processing distinction

Timing now carries eligible sample counts. Percentile values retain millihour precision instead of rounding to one decimal hour at the data layer.

The UI therefore distinguishes:

- **No data** — no eligible delivered/processing/review sample exists;
- **<1m / Xm** — a real, very fast measured duration exists.

This removes the prior case where a valid fast median appeared as an em dash while P95 showed a value.

### 5. Modern filter polish

- Period/source/outcome controls now read visually as large touch buttons rather than desktop form fields.
- The duplicate period chip was removed.
- Removable chips are reserved for additional active source/outcome filters.
- Existing responsive popover/bottom-sheet behavior is preserved.

### 6. Usage & Adoption improved

The top adoption cards now show:

- Packages
- Documents
- Pages
- **Automated intake** — API + watched-folder + scanner share
- **Reprocess rate** — percentage plus package count

Scanner remains visible in the source-mix visualization instead of being duplicated as a hero metric.

## Still intentionally not frozen

The next reporting foundation step still needs:

1. durable package-independent reporting facts retained after package/document deletion;
2. tenant-approved configurable business reporting dimensions;
3. tenant-configurable SLA/target goals and breach measures;
4. final Excel/CSV/executive-PDF export design and reconciliation;
5. focused authorization/tenant-isolation/performance verification.

Do not use Kiro for this refinement yet. Perform the normal local build and visual review first.

## Local validation

```powershell
.\build-all.cmd
.\start-local.cmd -LoggingFormat Readable
```

On Executive reporting, verify:

- Period Performance and Current Operational Risk are visually separate;
- selected-intake delivered never reads as more than selected-period intake;
- daily Delivered activity is clearly described as potentially including earlier intake;
- straight-through delivery no longer appears to contradict the current manual-review queue;
- Open Now vs Needs Action Now are understandable;
- median turnaround displays `No data` only when there is no sample, not when processing was simply very fast;
- active source/outcome chips still clear correctly;
- Usage & Adoption shows Automated Intake and Reprocess Rate.

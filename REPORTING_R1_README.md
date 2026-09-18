# LexaVault Business / Operational Reporting R1

Status: **BUILD CANDIDATE — R1 UX/DATA-SEMANTICS REFINEMENT / LOCAL BUILD + VISUAL REVIEW REQUIRED**

This candidate starts the active Reporting unit after Production AI Inference Tier R3 was independently VERIFIED / FROZEN.

## What changed

### Modern reporting UX

Reports is now split into coordinated experiences instead of one compact page:

- **Executive** — leadership pulse and attention;
- **Business Admin** — workflow performance, automation and elapsed-time analysis;
- **Usage & Adoption** — packages/documents/pages and intake-channel adoption;
- **Operational Details** — pipeline, intake controls, export and retention investigation.

The page is touch-first and responsive. Desktop uses popovers; tablet/mobile turns the same controls into large bottom-sheet-style selectors.

### Period/filter interaction

Period is a button rather than a small desktop select. Presets include Today, Yesterday, Last 7 days, Last 30 days, This/Last Month, This/Last Quarter and This Year. **Other** supports custom date range, month, quarter and year. Source/outcome use matching touch-friendly controls and active-filter chips.

### Business-useful timing

The API now exposes:

- median/P95 intake-to-first-delivery turnaround;
- median/P95 active processing time from structured package-processing audit cycles;
- median review wait time;
- median review handling time;
- current backlog age buckets: `<1h`, `1–4h`, `4–24h`, `1–3d`, `>3d`.

Timing now carries eligible sample counts. A true no-sample condition renders as `No data`; real fast timings render in minutes instead of being rounded down to an apparent no-data value.

### Usage/adoption

Adds average documents/pages per package, automated-intake share, touchless/reviewed delivery counts and reprocess rate/count for the selected period.


### Executive semantic refinement

The Executive experience explicitly separates **Period Performance** from **Current Operational Risk**. A new cohort-safe `periodIntakeDelivered` / `periodIntakeDeliveryRate` measure tracks only packages received in the selected period and delivered by period end, while daily Delivered activity remains a throughput measure that may include earlier intake. This prevents misleading funnel comparisons. Straight-through is clearly scoped to selected-period deliveries; current manual-review/technical-attention values are clearly labeled as point-in-time queues.

### Role boundary

`REPORT_VIEWER` is now Executive/aggregate-only. It no longer receives `REPORT_OPERATIONAL_VIEW` by role alone and still cannot export. Business Admin keeps deeper reporting/export. Tenant IT / Platform Operations retain operational reporting without gaining Business Admin configuration powers.

### Drill-down

Where the principal also has Dashboard access, attention/stage counts can open the Dashboard with the corresponding stage filter. Executive-only Report Viewer remains aggregate-only.

### Export

CSV is expanded with source mix, automation/review, timing, backlog aging and reprocessing metrics.

## Deliberately not frozen yet

R1 still reads several measures from live package/audit/review/export tables. Before Reporting can be VERIFIED / FROZEN, the unit still needs:

1. package-independent durable reporting facts so historical reporting survives package/document content deletion;
2. tenant-approved business dimensions sourced from configured identifiers/metadata;
3. tenant-configurable SLA/target goals and breach measures;
4. final export formats (spreadsheet/executive PDF) and reconciliation validation;
5. focused authorization/tenant-isolation/performance verification.

Raw document content, OCR text, model prompts/responses and protected sensitive values must never enter the reporting fact projection.

## Local validation

Run the normal project build:

```powershell
.\build-all.cmd
```

Then:

```powershell
.\start-local.cmd -LoggingFormat Readable
```

Recommended visual checks:

- `REPORT_VIEWER`: Executive + Usage & Adoption only, no export, no Operational Details;
- `BUSINESS_ADMIN`: Executive + Business Admin + Usage & Adoption + Operational Details + export;
- `TENANT_IT_ADMIN`: Executive + Usage & Adoption + Operational Details + export, but no Business Admin tab;
- on an iPad-width browser, open Period and verify the large bottom-sheet interaction;
- choose **Other** and exercise Date Range / Month / Quarter / Year;
- verify source/outcome chips can be removed;
- verify timing/backlog values load without exposing package/document content;
- where Dashboard access exists, click an attention/stage item and confirm the matching Dashboard stage is selected.

Do not spend Kiro credits on this R1 candidate yet. First perform local build + visual review and use that feedback to finish the reporting experience/data projection.

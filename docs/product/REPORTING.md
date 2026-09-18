# LexaVault V1 Reporting

## Product intent

Reporting is a decision workspace, not a single static dashboard. It must let an authorized user answer quickly:

- how much the organization is using LexaVault;
- how work enters the platform;
- how much is automated versus touched by people;
- how long packages take to process and deliver;
- where work is waiting or at risk;
- whether delivery and backlog are improving or getting worse;
- where an authorized user should drill in next.

The experience is responsive and touch-first for desktop and iPad. No report interaction depends on hover.

## Reporting experiences

### Executive

Audience: `REPORT_VIEWER`, Business Admin, Tenant IT, Platform Operations and explicit Admin when otherwise authorized.

Purpose: leadership pulse. It presents aggregate business measures only and does not expose document contents.

Primary measures:

- packages received;
- documents/pages processed;
- delivered packages and delivery rate;
- straight-through rate;
- median/P95 end-to-end turnaround;
- current attention/backlog and oldest open work;
- received-versus-delivered trend;
- intake-source mix;
- open-work aging;
- leadership attention by review/approval/technical category.

`REPORT_VIEWER` is intentionally executive/aggregate-only and cannot export or open protected operational records by role alone.

### Business Admin

Audience: `BUSINESS_ADMIN` and explicit Admin.

Purpose: understand workflow performance and business value.

Includes:

- straight-through versus reviewed delivery;
- active-processing median/P95;
- end-to-end median/P95;
- review wait and review handling time;
- backlog aging;
- intake quality/duplicates/rejections;
- reprocessing volume;
- delivery/export activity;
- retention lifecycle measures;
- later tenant-approved reporting dimensions sourced from configured identifiers/metadata.

### Usage & Adoption

Audience: all report viewers with aggregate-report access.

Purpose: show how LexaVault is actually being used.

Includes:

- package/document/page volume;
- average documents/pages per package;
- intake-source adoption (manual/API/watched folder/scanner);
- scanner share;
- reprocessing volume;
- reviewed versus touchless volume;
- usage trend and delivery trend.

### Operational Details

Audience: roles with `REPORT_OPERATIONAL_VIEW`.

Purpose: pipeline investigation and operational risk.

Includes current stage counts, observed-folder health, export activity, retention risk, backlog aging and active-processing time. Where the principal also has Dashboard access, actionable counts may drill into the corresponding filtered Dashboard stage.

## Modern period/filter interaction

The old desktop-style 7/30/90 dropdown is replaced by touch-friendly buttons/popovers.

Presets:

- Today;
- Yesterday;
- Last 7 days;
- Last 30 days;
- This month;
- Last month;
- This quarter;
- Last quarter;
- This year;
- Other.

`Other` supports:

- custom date range;
- month;
- quarter;
- year.

On tablet/mobile the selector behaves as a large bottom-sheet-style control. Intake source and outcome use the same large-tap interaction pattern. Active filters are visible as removable chips.

## Metric definitions

- **Packages received:** packages created within the selected period and matching selected source/current-outcome filters.
- **Documents/pages:** documents and page counts belonging to selected-period intake packages.
- **Scanner source:** `SCANNER_INTAKE` is first-class alongside `MANUAL_UPLOAD`, `API_SUBMISSION`, and `FOLDER_INTAKE`; source must never be inferred from logs or filenames.
- **Completed packages:** selected-period intake packages whose current terminal workflow status is `COMPLETED`.
- **Delivered packages:** unique packages with a structured package export record in the selected period. Delivery remains distinct from workflow completion.
- **End-to-end turnaround:** package creation to first successful package export.
- **Active processing time:** sum of completed package-processing cycles identified by structured `PACKAGE_PROCESSING_STARTED` / `PACKAGE_PROCESSING_COMPLETED` audit facts for the package. It intentionally excludes time waiting for a reviewer between completed processing cycles.
- **Review wait:** review-task creation until structured `REVIEW_STARTED`.
- **Review handling:** structured `REVIEW_STARTED` until `REVIEW_APPROVED` / `REVIEW_REJECTED`.
- **Current backlog:** point-in-time count of non-terminal packages matching current source/outcome filters.
- **Backlog aging:** current open work grouped into `<1h`, `1–4h`, `4–24h`, `1–3d`, `>3d`.
- **Straight-through delivery:** delivered package has no structured `review_tasks` record.
- **Reprocessed packages:** packages with a structured `PACKAGE_REPROCESS_REQUESTED` audit event in the selected period.
- **Retention overdue:** scheduled deletion timestamp is before report generation and matching purge timestamp is absent.

A zero timing value means no eligible completed sample exists in the selected period; the UI renders that as an em dash rather than implying a zero-duration process.

## Historical reporting retention

Package/document content may be deleted according to retention policy, but reporting metadata is not deleted merely because the underlying package content is removed. The durable reporting projection must retain privacy-safe business/operational facts needed for historical trend, SLA, adoption and turnaround reporting unless tenant policy explicitly requires complete deletion.

The reporting projection must not retain raw document content, OCR text, model prompts/responses, SSNs, PANs, DOBs or other protected document values.

The R1 UX/data candidate expands current structured reporting and timing but does **not yet** complete the durable package-independent historical fact projection. That persistence projection is part of the reporting implementation sequence before this unit can be frozen.

## Business dimensions

Business dimensions must not be hard-coded to the current demo identifiers. A later reporting candidate will let Business Admin select approved tenant-configured identifiers/metadata as report dimensions. Primary and sensitive identifiers are not automatically reportable merely because they exist.

## SLA/targets

The reporting design supports tenant-configured goals such as delivery-within-target and review-rate targets. V1 must not invent red/green SLA labels until a target has been configured.

## Export

Roles with `REPORT_EXPORT` can export the active filtered aggregate report. CSV contains source mix, automation/review, turnaround/processing timing, backlog aging and reprocessing counts in addition to the original volume/delivery measures. Executive PDF and spreadsheet-style export remain planned within the reporting unit; scheduled distribution is later work unless explicitly brought into V1.

## Explicit exclusions

V1 does not include:

- predictive forecasting;
- arbitrary custom SQL/report builders;
- scheduled report email distribution;
- custom presentation templates;
- inferred SLA health without a configured target.

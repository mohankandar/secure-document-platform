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

Reports has three tabs (task 0081; checked 2026-11-25). One period, one source system filter and one level filter hold for all three.

| Tab | Read by | Holds |
| --- | --- | --- |
| Overview | Report Viewer, Business Admin | What LexaVault did for you (with the review time saved, an estimate), the headline, received and delivered per day, service levels |
| Daily intake and delivery | Report Viewer, Business Admin | Received, exported, not exported yet, needs attention; by reason (LV code), destination, source system, result, sensitivity level; a note for a quiet source |
| Operations | Business Admin (a Report Viewer sees a line that says so) | The queue and time left, pipeline, backlog age, retention, timing and volume, breakdowns by case field |

The IT Administrator and Platform Operations have no Reports; the queue is also on Admin › Platform operations. Counts link only for a reader who may open what they count.

## Period and filters

Today, Yesterday, 7 days, 30 days, Custom (from and to). Days are in the entity's time zone. Until a period is chosen, Daily shows today and the others the last 30 days. Filters: All source systems or one; All levels or one.

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

The Business Admin chooses them in Admin › Redaction & retention, card Report targets, "Break reports down by". Only case fields with 1 to 100 allowed values that are not sensitive and take one value can be chosen. Off for every field by default.

## SLA/targets

Set by the Business Admin in the same card. The defaults are 4 hours for 95% of cases, 90% needing no review, and 1 hour for 80% of reviews.

## Export

Roles with `REPORT_EXPORT` (Business Admin) export the tab shown, with its filters, as CSV; each export is audited. The compliance report (PDF) is made from Reports (summary, Business Admin) or Admin › Audit trail (with the full audit trail, IT Administrator). Scheduled distribution is not part of V1.

## Explicit exclusions

V1 does not include:

- predictive forecasting;
- arbitrary custom SQL/report builders;
- scheduled report email distribution;
- custom presentation templates;
- inferred SLA health without a configured target.

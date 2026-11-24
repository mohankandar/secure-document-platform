/**
 * UI task U022: the Compliance report dialog's rules, kept free of React so they run under `node --test`.
 *
 * The period maths copies the server (CompliancePeriod.java): in the person's time zone, the last complete calendar
 * month, quarter or year before today; a custom period must end before today and be 1 to 366 days long. The browser
 * works it out only to show the range and to stop a request the server would refuse; the server decides.
 */

import { dayIn, dayText } from '../entityZone.ts';

export const COMPLIANCE_SUMMARY_CAP = 'COMPLIANCE_REPORT_SUMMARY';
export const COMPLIANCE_FULL_CAP = 'COMPLIANCE_REPORT_FULL';

/**
 * Which edition the "Compliance report" button opens on a page, or null when the page shows no button. Reports needs
 * the summary permission (Business Administrator), Admin › Audit trail the full one (IT Administrator); the same
 * capability check the page uses for its own Export, so the button never leads to a 403.
 */
export function complianceEdition(page: 'reports' | 'audit', caps: readonly string[]): 'SUMMARY' | 'FULL' | null {
  if (page === 'reports') return caps.includes(COMPLIANCE_SUMMARY_CAP) ? 'SUMMARY' : null;
  return caps.includes(COMPLIANCE_FULL_CAP) ? 'FULL' : null;
}

export type PeriodKey ='LAST_MONTH' | 'LAST_QUARTER' | 'LAST_YEAR' | 'CUSTOM';

export const PERIODS: { key: PeriodKey; label: string }[] = [
  { key: 'LAST_MONTH', label: 'Last month' },
  { key: 'LAST_QUARTER', label: 'Last quarter' },
  { key: 'LAST_YEAR', label: 'Last year' },
  { key: 'CUSTOM', label: 'Custom' },
];

export const DEFAULT_PERIOD: PeriodKey = 'LAST_QUARTER';
export const MAX_CUSTOM_DAYS = 366;

/** A calendar day as `YYYY-MM-DD`, the form a date field and the server both use. */
export type Day = string;

const DAY = /^(\d{4})-(\d{2})-(\d{2})$/;
const MONTHS = ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'];

/** Day arithmetic in UTC, so a daylight-saving change never moves a calendar day. */
function parts(day: Day): [number, number, number] | null {
  const m = DAY.exec(day);
  if (!m) return null;
  const [y, mo, d] = [Number(m[1]), Number(m[2]), Number(m[3])];
  const check = new Date(Date.UTC(y, mo - 1, d));
  if (check.getUTCFullYear() !== y || check.getUTCMonth() !== mo - 1 || check.getUTCDate() !== d) return null;
  return [y, mo, d];
}

function format(y: number, mo: number, d: number): Day {
  const date = new Date(Date.UTC(y, mo - 1, d));
  return `${String(date.getUTCFullYear()).padStart(4, '0')}-${String(date.getUTCMonth() + 1).padStart(2, '0')}-${String(date.getUTCDate()).padStart(2, '0')}`;
}

export function isDay(value: string): boolean {
  return parts(value) !== null;
}

export function addDays(day: Day, n: number): Day {
  const p = parts(day);
  if (!p) throw new Error(`Not a day: ${day}`);
  return format(p[0], p[1], p[2] + n);
}

/** Days from `from` to `to`, both counted (Jan 1 to Jan 1 is 1). */
export function daysInclusive(from: Day, to: Day): number {
  const a = parts(from);
  const b = parts(to);
  if (!a || !b) return NaN;
  return Math.round((Date.UTC(b[0], b[1] - 1, b[2]) - Date.UTC(a[0], a[1] - 1, a[2])) / 86_400_000) + 1;
}

/** Today in the zone sent to the server as `timeZone`: the entity's (task 0058), else the browser's. */
export function localToday(now: Date = new Date(), zone?: string): Day {
  if (zone) return dayText(dayIn(now, zone));
  return format(now.getFullYear(), now.getMonth() + 1, now.getDate());
}

/** The browser's IANA time zone, for example America/New_York. */
export function browserTimeZone(): string {
  return Intl.DateTimeFormat().resolvedOptions().timeZone;
}

/** The range a preset covers, as the server works it out. */
export function presetRange(key: Exclude<PeriodKey, 'CUSTOM'>, today: Day): { from: Day; to: Day } {
  const p = parts(today);
  if (!p) throw new Error(`Not a day: ${today}`);
  const [y, mo] = p;
  switch (key) {
    case 'LAST_MONTH': {
      const from = format(y, mo - 1, 1);
      return { from, to: format(y, mo, 0) };
    }
    case 'LAST_QUARTER': {
      const thisQuarterMonth = Math.floor((mo - 1) / 3) * 3 + 1;
      return { from: format(y, thisQuarterMonth - 3, 1), to: format(y, thisQuarterMonth, 0) };
    }
    case 'LAST_YEAR':
      return { from: format(y - 1, 1, 1), to: format(y - 1, 12, 31) };
  }
}

/** The default custom period: the 30 days that end yesterday. */
export function defaultCustomRange(today: Day): { from: Day; to: Day } {
  const to = addDays(today, -1);
  return { from: addDays(to, -29), to };
}

/** Why a custom range can't be used, in plain words; null when it can. Same rules as the server. */
export function customRangeProblem(from: string, to: string, today: Day): string | null {
  if (!from || !to) return 'Choose a start and an end date.';
  if (!isDay(from) || !isDay(to)) return 'Enter the dates as a day, month and year.';
  const days = daysInclusive(from, to);
  if (days < 1) return 'The end date must be on or after the start date.';
  if (daysInclusive(to, today) < 2) return 'The period must end before today.';
  if (days > MAX_CUSTOM_DAYS) return `The period can be at most ${MAX_CUSTOM_DAYS} days.`;
  return null;
}

/** "Jul 1 – Sep 30, 2026"; the first year is shown too when the range crosses a year. */
export function rangeLabel(from: Day, to: Day): string {
  const a = parts(from);
  const b = parts(to);
  if (!a || !b) return '';
  const start = `${MONTHS[a[1] - 1]} ${a[2]}`;
  const end = `${MONTHS[b[1] - 1]} ${b[2]}, ${b[0]}`;
  return a[0] === b[0] ? `${start} – ${end}` : `${start}, ${a[0]} – ${end}`;
}

export interface ComplianceReportBody {
  period: PeriodKey;
  from?: Day;
  to?: Day;
  timeZone: string;
  includeFullAuditTrail: boolean;
}

/**
 * The request body. `from` and `to` are sent only for Custom (the server refuses them otherwise). The full audit trail
 * is only ever asked for by someone allowed it: the summary edition always sends false.
 */
export function requestBody(period: PeriodKey, custom: { from: Day; to: Day }, timeZone: string,
  edition: 'SUMMARY' | 'FULL', includeFullAuditTrail: boolean): ComplianceReportBody {
  const body: ComplianceReportBody = { period, timeZone, includeFullAuditTrail: edition === 'FULL' && includeFullAuditTrail };
  if (period === 'CUSTOM') { body.from = custom.from; body.to = custom.to; }
  return body;
}

/**
 * Task 0019 A1: the query for GET /audit-records/count, the same period values the report request sends, so the
 * count is the appendix's row count. `from` and `to` only for Custom.
 */
export function countQuery(period: PeriodKey, custom: { from: Day; to: Day }, timeZone: string): Record<string, string> {
  const q: Record<string, string> = { period, timeZone };
  if (period === 'CUSTOM') { q.from = custom.from; q.to = custom.to; }
  return q;
}

/** The count after "added as an appendix": "1,204 records", "1 record". Anything that is not a count gives null. */
export function recordCountWords(count: unknown): { number: string; unit: string } | null {
  if (typeof count !== 'number' || !Number.isSafeInteger(count) || count < 0) return null;
  return { number: count.toLocaleString('en-US'), unit: count === 1 ? 'record' : 'records' };
}

export const FALLBACK_FILE_NAME = 'compliance-report.pdf';

/**
 * The file name from `Content-Disposition`. Only a plain PDF name is used: anything with a path, a control character
 * or another extension falls back to a fixed name.
 */
export function fileNameFromDisposition(header: string | null | undefined): string {
  if (!header) return FALLBACK_FILE_NAME;
  let name: string | null = null;
  const star = /filename\*\s*=\s*(?:UTF-8|utf-8)''([^;]+)/.exec(header);
  if (star) {
    try { name = decodeURIComponent(star[1].trim()); } catch { name = null; }
  }
  if (!name) {
    const quoted = /filename\s*=\s*"([^"]*)"/.exec(header);
    const bare = /filename\s*=\s*([^;"\s]+)/.exec(header);
    name = quoted ? quoted[1] : bare ? bare[1] : null;
  }
  if (!name) return FALLBACK_FILE_NAME;
  name = name.trim();
  // eslint-disable-next-line no-control-regex
  if (!/^[^\\/:*?"<>|\u0000-\u001f]{1,200}\.pdf$/i.test(name) || name.startsWith('.')) return FALLBACK_FILE_NAME;
  return name;
}

/** What the dialog says when the server refuses or fails. */
export function errorText(httpStatus: number | undefined): string {
  if (httpStatus === 400) return "This period can't be used. Choose another period.";
  if (httpStatus === 403) return "You don't have access to create this report.";
  return "The report couldn't be created. Try again. If it keeps failing, tell your IT Administrator.";
}

export const SUMMARY_CONTENTS = [
  'Redaction policy as it stood each day, and every change: who, when, old → new',
  'Keeping and deleting settings, and proof that deletions ran',
  'Case outcomes and review numbers',
  'Audit activity, as counts',
];

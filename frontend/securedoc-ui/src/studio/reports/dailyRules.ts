/**
 * Task 0081: the words of Reports › Daily intake and delivery. Counts, LV codes and connection names only. Kept free of
 * React so the rules run under `node --test`.
 */
import type { DailyReport } from '../../types';
import { dayFrom, dayIn, dayText, plusDays, startOfDay, type Day } from '../entityZone.ts';

export type Preset = 'TODAY' | 'YESTERDAY' | '7D' | '30D' | 'CUSTOM';
export const PRESETS: { key: Preset; label: string }[] = [
  { key: 'TODAY', label: 'Today' },
  { key: 'YESTERDAY', label: 'Yesterday' },
  { key: '7D', label: '7 days' },
  { key: '30D', label: '30 days' },
  { key: 'CUSTOM', label: 'Custom' },
];

export type Tab = 'overview' | 'daily' | 'operations';
export const TABS: { key: Tab; label: string }[] = [
  { key: 'overview', label: 'Overview' },
  { key: 'daily', label: 'Daily intake and delivery' },
  { key: 'operations', label: 'Operations' },
];
export const tabFrom = (raw: string | null): Tab => TABS.find((t) => t.key === raw)?.key ?? 'overview';
/** Until a period is chosen, the Daily tab shows today and the others the last 30 days; a chosen one is for all three. */
export const defaultPreset = (tab: Tab): Preset => (tab === 'daily' ? 'TODAY' : '30D');

export interface Period { from: Date; to: Date; fromDay: Day; toDay: Day; label: string }

/** Days of the entity's time zone (task 0058; the browser's when none is set), exclusive `to`. */
export function periodFor(preset: Preset, customFrom: string, customTo: string, zone: string, now = new Date()): Period | null {
  const today = dayIn(now, zone);
  const range = (fromDay: Day, toDay: Day, label: string): Period => ({ from: startOfDay(fromDay, zone), to: startOfDay(plusDays(toDay, 1), zone), fromDay, toDay, label });
  switch (preset) {
    case 'TODAY': return range(today, today, 'Today');
    case 'YESTERDAY': return range(plusDays(today, -1), plusDays(today, -1), 'Yesterday');
    case '7D': return range(plusDays(today, -6), today, 'Last 7 days');
    case '30D': return range(plusDays(today, -29), today, 'Last 30 days');
    case 'CUSTOM': {
      const from = dayFrom(customFrom);
      const to = dayFrom(customTo);
      if (!from || !to || dayText(to) < dayText(from)) return null;
      return range(from, to, `${customFrom} – ${customTo}`);
    }
  }
}

/** "Received today", "Received yesterday"; any other period is named above, so just "Received". */
export function inPeriod(word: string, preset: Preset): string {
  return preset === 'TODAY' ? `${word} today` : preset === 'YESTERDAY' ? `${word} yesterday` : word;
}

const WEEKDAYS = ['Sunday', 'Monday', 'Tuesday', 'Wednesday', 'Thursday', 'Friday', 'Saturday'];
export const weekday = (day: Day) => WEEKDAYS[new Date(Date.UTC(day.y, day.m - 1, day.d)).getUTCDay()];

export const count = (n: number, one: string, many: string) => `${n.toLocaleString('en-US')} ${n === 1 ? one : many}`;
export const cases = (n: number) => count(n, 'case', 'cases');
export const documents = (n: number) => count(n, 'document', 'documents');
export const pages = (n: number) => count(n, 'page', 'pages');

/** "Usual for a Tuesday: 38–52"; null when there is nothing to compare with. */
export function usualText(usual: { low: number; high: number } | null | undefined, day: Day): string | null {
  if (!usual) return null;
  const range = usual.low === usual.high ? usual.low.toLocaleString('en-US') : `${usual.low.toLocaleString('en-US')}–${usual.high.toLocaleString('en-US')}`;
  return `Usual for a ${weekday(day)}: ${range}`;
}

/** "every hour", "every 20 minutes", "every 2 hours". */
export function everyText(minutes: number): string {
  if (minutes < 45) return minutes <= 1 ? 'every minute' : `every ${minutes} minutes`;
  const h = Math.round(minutes / 60);
  return h <= 1 ? 'every hour' : `every ${h} hours`;
}

export function clock(iso: string, zone: string): string {
  return new Intl.DateTimeFormat('en-US', { timeZone: zone, hour: 'numeric', minute: '2-digit' }).format(new Date(iso));
}

/** A time of today alone, else with its day: "9:12 AM", "Nov 23, 4:05 PM". */
export function whenText(iso: string | null | undefined, zone: string, now: string): string {
  if (!iso) return 'Never';
  if (dayText(dayIn(new Date(iso), zone)) === dayText(dayIn(new Date(now), zone))) return clock(iso, zone);
  const date = new Intl.DateTimeFormat('en-US', { timeZone: zone, month: 'short', day: 'numeric' }).format(new Date(iso));
  return `${date}, ${clock(iso, zone)}`;
}

/** "Branch scanners has sent nothing since 9:12 AM. It usually sends every hour on a Tuesday." */
export function quietText(q: DailyReport['quiet'][number], zone: string, now: string): string {
  const since = q.lastReceivedAt ? ` since ${whenText(q.lastReceivedAt, zone, now)}` : '';
  return `${q.name} has sent nothing${since}. It usually sends ${everyText(q.usualMinutes)} on a ${weekday(dayIn(new Date(now), zone))}.`;
}

export const WHERE_WORDS: Record<string, string> = {
  REFUSED_AT_INTAKE: 'Refused at intake',
  STOPPED_IN_PROCESSING: 'Stopped in processing',
  EXPORT_FAILED: 'Export failed',
  REJECTED: 'Rejected',
};

/** The cases behind a reason, for someone who may open Cases. A refused one made no case, so there is none to open. */
export function reasonLink(r: DailyReport['reasons'][number], mayOpenCases: boolean): string | null {
  return mayOpenCases && r.where !== 'REFUSED_AT_INTAKE' ? `/?code=${encodeURIComponent(r.code)}` : null;
}

export const NOT_BY_LEVEL = 'Refused and duplicate counts are not known by level';
export const CLOSING_LINE = 'Counts only: no names, file names or content. They come from LexaVault’s own records.';

export function isDailyReport(v: unknown): v is DailyReport {
  const r = v as DailyReport;
  return typeof v === 'object' && v !== null && typeof r.generatedAt === 'string' && [r.quiet, r.reasons, r.destinations, r.sources, r.levels].every(Array.isArray)
    && [r.received, r.exported, r.notExported, r.attention, r.handled].every((x) => typeof x === 'object' && x !== null);
}

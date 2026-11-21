/**
 * Task 0029: the export screens in words (Waiting for you, Resend all, the "Exporting" status, Support's export
 * section, the Business Admin's retention per destination). Pure functions over the API answers of tasks 0027/0028,
 * so node's test runner checks every word without a browser. Codes are always shown as "Title · LV-XXX-NNN" from the
 * generated registry (lvCodes.ts); no second list of code titles.
 */
import { lvLabel } from './lvCodes.ts';

/* ---------- API shapes (codes, ids and times only) ---------- */

export interface ExportJobView {
  jobId: string;
  packageId: string;
  stage: string;
  documentId?: string | null;
  destinationId?: string | null;
  destinationName?: string | null;
  status: string;
  attempts: number;
  lvCode?: string | null;
  detail?: string | null;
  since?: string | null;
  nextAttemptAt?: string | null;
  createdAt?: string | null;
  firstFailedAt?: string | null;
  resend?: boolean;
}

export interface WaitingGroup {
  destinationId: string | null;
  destinationName: string | null;
  total: number;
  countsByCode: Record<string, number>;
  jobs: ExportJobView[];
}
export interface WaitingForYou { total: number; destinations: WaitingGroup[] }

export interface ItGroup {
  destinationId: string | null;
  destinationName: string | null;
  lvCode: string;
  count: number;
  firstFailedAt?: string | null;
  caseIds: string[];
  jobs: ExportJobView[];
}
export interface NeedsIt { total: number; groups: ItGroup[] }

export interface DestinationView {
  destinationId: string;
  name: string;
  purpose: string;
  enabled: boolean;
  state: string;
  pausedAt?: string | null;
  lastProbeAt?: string | null;
  lastLvCode?: string | null;
  lastCheckFailedAt?: string | null;
  nextCheckAt?: string | null;
  openJobs?: number;
}

export interface ReachabilityAlert { raised: boolean; lvCode?: string | null; raisedAt?: string | null; lastCheckAt?: string | null }

export interface CaseExport { exportState: string; jobs: ExportJobView[] }

export interface RetentionPeriod { destinationId: string | null; name: string | null; setHours: number | null; hours: number }
export interface RetentionSettings {
  master: RetentionPeriod;
  masterDefaultHours: number;
  minHours: number;
  maxHours: number;
  splitDestinations: RetentionPeriod[];
}

/* ---------- The "Exporting" status (owner's name, 0027 answer 4) ---------- */

export const EXPORT_STATES = ['NONE', 'READY', 'DESTINATION_PAUSED', 'WAITING_FOR_YOU', 'NEEDS_IT', 'EXPORTED'] as const;
export type ExportState = (typeof EXPORT_STATES)[number];

/** The state as sent; anything this screen does not know (or nothing) is NONE: the case shows its status as today. */
export function exportStateOf(raw: string | null | undefined): ExportState {
  return raw && (EXPORT_STATES as readonly string[]).includes(raw) ? raw as ExportState : 'NONE';
}

/** True while an export job is open: the case shows "Exporting · …" instead of its review status. */
export function isExporting(state: ExportState): boolean {
  return state === 'READY' || state === 'DESTINATION_PAUSED' || state === 'WAITING_FOR_YOU' || state === 'NEEDS_IT';
}

export const EXPORTING = 'Exporting';
export const EXPORTED = 'Exported';

const DETAIL: Record<string, string> = {
  READY: 'ready',
  DESTINATION_PAUSED: 'destination paused',
  WAITING_FOR_YOU: 'waiting for you',
  NEEDS_IT: 'needs IT',
};

/** "Exporting · ready", …, "Exported"; null for NONE (the case keeps its status as today). */
export function exportStatusLabel(state: ExportState): string | null {
  if (state === 'EXPORTED') return EXPORTED;
  return isExporting(state) ? `${EXPORTING} · ${DETAIL[state]}` : null;
}

/** The chip's colour: neutral while ready, review yellow when paused, the mark for you, danger for IT, green when done. */
export type ExportTone = 'ready' | 'paused' | 'you' | 'it' | 'done';
export function exportTone(state: ExportState): ExportTone | null {
  switch (state) {
    case 'READY': return 'ready';
    case 'DESTINATION_PAUSED': return 'paused';
    case 'WAITING_FOR_YOU': return 'you';
    case 'NEEDS_IT': return 'it';
    case 'EXPORTED': return 'done';
    default: return null;
  }
}

/** The next step in a Cases row while exporting. */
export function exportNextStep(state: ExportState): string | null {
  switch (state) {
    case 'READY': return 'Waiting to be sent';
    case 'DESTINATION_PAUSED': return 'Destination paused';
    case 'WAITING_FOR_YOU': return 'Refused: send it again';
    case 'NEEDS_IT': return 'Waiting for IT';
    default: return null;
  }
}

/** A case needs the person when a destination refused it and they may resend (EXPORT_RESEND). */
export function exportNeedsYou(state: ExportState, caps: readonly string[]): boolean {
  return state === 'WAITING_FOR_YOU' && caps.includes(EXPORT_RESEND);
}

export const EXPORT_RESEND = 'EXPORT_RESEND';

/* ---------- Codes ---------- */

/** "Destination refused the file · LV-DLV-004", from the registry. */
export function reasonLabel(code: string | null | undefined): string {
  return lvLabel(code);
}

/** The most frequent code in a group (ties: the lower code), for "Most often: …". */
export function mostFrequentCode(counts: Record<string, number> | null | undefined): string | null {
  const entries = Object.entries(counts ?? {}).filter(([, n]) => n > 0);
  if (!entries.length) return null;
  entries.sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]));
  return entries[0][0];
}

/** What a refusal was for, as the confirmation's box says it ("refused for the sensitivity level"). */
const REFUSED_FOR: Record<string, string> = {
  'LV-DLV-003': 'the sensitivity level',
  'LV-DLV-004': 'the file',
};

/** One line of the confirmation's box: "2 refused for the sensitivity level · LV-DLV-003". */
export function reasonCountLine(code: string, count: number): { lead: string; what: string; code: string } {
  const what = REFUSED_FOR[code];
  if (what) return { lead: `${count} refused for`, what, code };
  return { lead: `${count} ·`, what: reasonLabel(code).replace(` · ${code}`, ''), code };
}

/* ---------- Waiting for you ---------- */

export const WAITING_WORDS = {
  nav: 'Waiting for you',
  title: 'Waiting for you',
  intro: 'A destination refused these cases. Once the cause is fixed with the destination’s team, send them again. Open a case to send just that one.',
  footnoteBefore: 'Each case is sent once more. If the destination refuses it again, it goes to IT with code ',
  footnoteCode: 'LV-DLV-015',
  footnoteAfter: '. Cases waiting for a destination that is down are not listed here: they go out on their own when it is back.',
  empty: 'Nothing is waiting for you',
  emptyText: 'Cases a destination refuses appear here, grouped by destination.',
  mostOften: 'Most often',
  columnCase: 'Case',
  columnCaseDocument: 'Case · document',
  columnWhy: 'Why it was refused',
  columnSince: 'Waiting since',
} as const;

/** The nav item shows only when something waits (in inline mode nothing ever does). */
export function showWaitingNav(caps: readonly string[], total: number | null | undefined): boolean {
  return caps.includes(EXPORT_RESEND) && typeof total === 'number' && total > 0;
}

export function isSplit(stage: string | null | undefined): boolean {
  return stage === 'SPLIT_EXPORT';
}

/** "full case" or "split by barcode", from a group's jobs. */
export function groupKind(group: Pick<WaitingGroup, 'jobs'>): 'full case' | 'split by barcode' {
  return group.jobs.some((j) => isSplit(j.stage)) ? 'split by barcode' : 'full case';
}

/** "3 cases", "2 documents" (a split destination receives documents). */
export function groupCount(group: Pick<WaitingGroup, 'jobs' | 'total'>): string {
  const split = groupKind(group) === 'split by barcode';
  const noun = split ? (group.total === 1 ? 'document' : 'documents') : (group.total === 1 ? 'case' : 'cases');
  return `${group.total} ${noun}`;
}

export function destinationLabel(name: string | null | undefined): string {
  return name?.trim() || 'Its destination';
}

export function resendAllLabel(count: number): string {
  return `Resend all ${count}`;
}

/* ---------- Resend all: confirmation ---------- */

export const RESEND_WORDS = {
  body: 'They go back in the export queue and are sent one by one, in the order they were refused. You can leave this page; it carries on.',
  check: 'Has the destination’s team fixed the cause? If not, they will be refused again. Each case is sent once more; a case refused again goes to IT.',
  notNow: 'Not now',
} as const;

const casesWord = (n: number) => `${n} case${n === 1 ? '' : 's'}`;

export function resendTitle(count: number, destination: string | null | undefined): string {
  return `Resend ${casesWord(count)} to ${destinationLabel(destination)}?`;
}

export function resendConfirmLabel(count: number): string {
  return `Resend ${casesWord(count)}`;
}

/** The lines of the confirmation's box, most frequent first. */
export function resendReasonLines(counts: Record<string, number>): Array<{ lead: string; what: string; code: string }> {
  return Object.entries(counts).filter(([, n]) => n > 0)
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([code, n]) => reasonCountLine(code, n));
}

export function resendToast(queued: number, destination: string | null | undefined): string {
  return `${casesWord(queued)} queued for ${destinationLabel(destination)}. They’ll show as “Exporting · ready” until sent.`;
}

/* ---------- The case panel ---------- */

export const CASE_EXPORT_WORDS = {
  stage: EXPORTING,
  waitingEyebrow: 'Exporting · waiting for you',
  resendThis: 'Resend this case',
  afterRefusal: 'Once the destination’s team has fixed it, send it again. It gets one more attempt.',
  readyHeading: 'Waiting to be sent',
  readyBody: 'The case is in the export queue and goes out on its own.',
  pausedBody: 'The destination isn’t answering. LexaVault checks it every 5 minutes and sends this case on its own when it is back. Nothing is lost.',
  itHeading: 'Waiting for IT',
  itBody: 'IT has been told. Nothing is needed from you.',
  resent: 'Sent again.',
  refusedAgain: 'The destination refused it again, so it went to IT',
} as const;

export function refusedHeading(destination: string | null | undefined): string {
  return `${destinationLabel(destination)} refused this case`;
}

export function pausedHeading(destination: string | null | undefined): string {
  return `${destinationLabel(destination)} is paused`;
}

/** The job the case panel talks about: the one that decides the case's state. */
export function jobForState(jobs: readonly ExportJobView[], state: ExportState): ExportJobView | undefined {
  const want = state === 'WAITING_FOR_YOU' ? ['WAITING_FOR_YOU'] : state === 'NEEDS_IT' ? ['NEEDS_IT'] : ['READY', 'WAITING_RETRY', 'RUNNING', 'PAUSED'];
  return jobs.find((j) => want.includes(j.status)) ?? jobs.find((j) => j.status !== 'DONE' && j.status !== 'STOPPED');
}

/* ---------- Times ---------- */

function sameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate();
}

/** "9:41 AM" in the person's own clock format. */
export function clockTime(iso: string): string {
  return new Date(iso).toLocaleTimeString(undefined, { hour: 'numeric', minute: '2-digit' });
}

/** "Today 9:41 AM", "Yesterday 4:12 PM", "Nov 18, 4:12 PM". */
export function dayAndTime(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return '—';
  const at = new Date(iso);
  const today = new Date(now);
  const yesterday = new Date(now - 864e5);
  if (sameDay(at, today)) return `Today ${clockTime(iso)}`;
  if (sameDay(at, yesterday)) return `Yesterday ${clockTime(iso)}`;
  return `${at.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })}, ${clockTime(iso)}`;
}

/** "Refused today at 9:41 AM." / "Refused yesterday at 4:12 PM." / "Refused Nov 18 at 4:12 PM." */
export function refusedWhen(iso: string | null | undefined, now = Date.now()): string {
  if (!iso) return 'Refused.';
  const at = new Date(iso);
  if (sameDay(at, new Date(now))) return `Refused today at ${clockTime(iso)}.`;
  if (sameDay(at, new Date(now - 864e5))) return `Refused yesterday at ${clockTime(iso)}.`;
  return `Refused ${at.toLocaleDateString(undefined, { month: 'short', day: 'numeric' })} at ${clockTime(iso)}.`;
}

/** A time later today as "11:07 AM", on another day with its date. */
function timeOrDay(iso: string, now: number): string {
  return sameDay(new Date(iso), new Date(now)) ? clockTime(iso) : dayAndTime(iso, now);
}

/* ---------- Support (IT Admin) ---------- */

export const SUPPORT_EXPORT_WORDS = {
  title: 'Export',
  tabProblems: 'Problems',
  tabExport: 'Export',
  needsIt: 'Needs IT',
  nothingForIt: 'No export is waiting for IT.',
  destinations: 'Destinations',
  destinationsNote: 'A paused destination is checked every 5 minutes and resumes on its own, in order, when it answers.',
  columnDestination: 'Destination',
  columnState: 'State',
  columnWaiting: 'Waiting',
  checkNow: 'Check now',
  setBackToWorking: 'Set back to working',
  alertTitle: 'LexaVault can’t reach its destinations',
  alertLine: 'The problem is likely LexaVault’s own network, not the destinations.',
  working: 'Working',
  needsItState: 'Needs IT',
  checkPassed: 'answers its health check again. Its waiting exports go out in order.',
  checkStillFailing: 'still fails its health check. It stays paused and is checked again in 5 minutes.',
} as const;

/** What was already tried for a group, by its code (the retry schedule of task 0028). */
const TRIED: Record<string, string> = {
  'LV-DLV-001': 'The destination answers its health check, so it’s these cases. Each was retried over 24 hours and rebuilt once.',
  'LV-DLV-002': 'The destination answers its health check, so it’s these cases. Each was retried over 24 hours and rebuilt once.',
  'LV-DLV-007': 'The destination answers its health check, so it’s these cases. Each was retried over 24 hours and rebuilt once.',
  'LV-DLV-008': 'The destination answers its health check, so it’s these cases. Each was retried over 24 hours and rebuilt once.',
  'LV-DLV-015': 'A person sent these again after the destination refused them, and it refused them again.',
  'LV-PRC-008': 'The export was interrupted 3 times while being sent.',
};

export function triedLine(code: string): string | null {
  return TRIED[code] ?? null;
}

export const SIGN_IN_CODE = 'LV-DLV-005';

export function isSignInGroup(group: Pick<ItGroup, 'lvCode'>): boolean {
  return group.lvCode === SIGN_IN_CODE;
}

/** "4 cases failing to Document vault since 10:00 AM". */
export function itGroupTitle(group: Pick<ItGroup, 'count' | 'destinationName' | 'firstFailedAt'>, now = Date.now()): string {
  const since = group.firstFailedAt ? ` since ${timeOrDay(group.firstFailedAt, now)}` : '';
  return `${casesWord(group.count)} failing to ${destinationLabel(group.destinationName)}${since}`;
}

export function signInTitle(destination: string | null | undefined): string {
  return `${destinationLabel(destination)} refused LexaVault’s sign-in`;
}

export function signInBody(waiting: number): string {
  return `Exports to it are on hold: ${waiting} waiting, nothing lost. Fix the credential in Admin › Integrations, then set it back to working.`;
}

export function retryAllLabel(count: number): string {
  return `Retry all ${count}`;
}

/** Support's needs-attention badge counts the IT groups, not the cases in them. */
export function supportBadgeCount(needsIt: Pick<NeedsIt, 'groups'> | null | undefined): number {
  return needsIt?.groups?.length ?? 0;
}

export function destinationKind(purpose: string | null | undefined): 'full case' | 'split by barcode' {
  return purpose === 'DERIVED_DOCUMENT_DELIVERY' ? 'split by barcode' : 'full case';
}

/** The state column: "Working"; "Paused since 10:42 AM" with "Health check failing · next check 11:07 AM"; "Needs IT" with its code. */
export function destinationStateWords(d: Pick<DestinationView, 'state' | 'pausedAt' | 'nextCheckAt' | 'lastLvCode'>, now = Date.now()): {
  tone: 'working' | 'paused' | 'it'; state: string; detail?: string;
} {
  if (d.state === 'PAUSED') {
    const state = d.pausedAt ? `Paused since ${timeOrDay(d.pausedAt, now)}` : 'Paused';
    const next = d.nextCheckAt ? ` · next check ${timeOrDay(d.nextCheckAt, now)}` : '';
    return { tone: 'paused', state, detail: `Health check failing${next}` };
  }
  if (d.state === 'NEEDS_IT') return { tone: 'it', state: SUPPORT_EXPORT_WORDS.needsItState, detail: d.lastLvCode ? reasonLabel(d.lastLvCode) : undefined };
  return { tone: 'working', state: SUPPORT_EXPORT_WORDS.working };
}

/** "3 ready" for a working destination, "27 waiting" otherwise; "Nothing waiting" at 0. */
export function waitingText(state: string, open: number | null | undefined): string {
  const n = open ?? 0;
  if (n <= 0) return 'Nothing waiting';
  return state === 'WORKING' ? `${n} ready` : `${n} waiting`;
}

/* ---------- Business Admin: retention per destination ---------- */

export const RETENTION_WORDS = {
  title: 'Keeping exported copies',
  description: 'How long LexaVault keeps the redacted copy after each destination confirms it received it. Nothing is deleted while its export is still open.',
  master: 'Main export',
  saved: 'Keeping exported copies saved.',
} as const;

export function retentionPlaceholder(kind: 'master' | 'split', hours: number): string {
  const h = `${hours} hour${hours === 1 ? '' : 's'}`;
  return kind === 'master' ? `Same as redacted copies (${h})` : `Same as the main export (${h})`;
}

/** A typed value: empty is "the default" (null); anything else must be a whole number in the range (the API checks too). */
export function retentionValueError(value: number | null, min: number, max: number): string | null {
  if (value === null) return null;
  if (!Number.isInteger(value) || value < min || value > max) return `Enter a whole number of hours from ${min} to ${max}, or leave it empty.`;
  return null;
}

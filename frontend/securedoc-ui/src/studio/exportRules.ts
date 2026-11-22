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

/** Task 0036: a case as the IT card lists it, by its case number (null when the case has none). */
export interface CaseRef { packageId: string; caseNumber: string | null }

export interface ItGroup {
  destinationId: string | null;
  destinationName: string | null;
  lvCode: string;
  count: number;
  firstFailedAt?: string | null;
  caseIds: string[];
  cases?: CaseRef[];
  jobs: ExportJobView[];
}
export interface NeedsIt { total: number; groups: ItGroup[]; timings?: ExportTimings | null }

/**
 * Task 0036: the timings the screens name, from the server's settings (sent with the case's export and with the IT
 * queue): how often a paused destination is checked, how long a failing case is retried, whether it is rebuilt once,
 * and how many interruptions send an export to IT.
 */
export interface ExportTimings {
  probeIntervalSeconds: number;
  retrySpanSeconds: number;
  retries: number;
  rebuiltOnce: boolean;
  maxInterruptions: number;
}

/** Task 0036: a destination's open exports per state. */
export interface WaitingCounts { queued: number; waitingForYou: number; needsIt: number }

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
  waiting?: WaitingCounts | null;
}

export interface ReachabilityAlert { raised: boolean; lvCode?: string | null; raisedAt?: string | null; lastCheckAt?: string | null }

export interface CaseExport { exportState: string; jobs: ExportJobView[]; timings?: ExportTimings | null }

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

/**
 * Task 0036: the Cases page tile of a case. A case whose chip says "Exporting · …" counts under the Exporting tile
 * (key 'exporting'), whatever its status; any other case under the tile of its status (`stage`).
 */
export function caseTile<S extends string>(stage: S, exportState: string | null | undefined): S | 'exporting' {
  return isExporting(exportStateOf(exportState)) ? 'exporting' : stage;
}
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
  itHeading: 'Waiting for IT',
  itBody: 'IT has been told. Nothing is needed from you.',
  resent: 'Sent again.',
  refusedAgain: 'The destination refused it again, so it went to IT',
} as const;

/* ---------- Task 0036: timings in words, from the settings ---------- */

const unit = (n: number, word: string) => `${n} ${word}${n === 1 ? '' : 's'}`;

/**
 * A span in plain words: seconds under a minute, minutes under an hour, hours above, rounded, with “about” when
 * rounding changed it. “5 minutes”, “about 17 hours”, “4 seconds”. Null for no usable value.
 */
export function spanWords(seconds: number | null | undefined): { about: boolean; text: string } | null {
  if (typeof seconds !== 'number' || !Number.isFinite(seconds) || seconds <= 0) return null;
  const s = Math.round(seconds);
  if (s < 60) return { about: false, text: unit(Math.max(1, s), 'second') };
  const minutes = Math.round(s / 60);
  if (minutes < 60) return { about: minutes * 60 !== s, text: unit(minutes, 'minute') };
  const hours = Math.round(s / 3600);
  return { about: hours * 3600 !== s, text: unit(hours, 'hour') };
}

/** “every 5 minutes”, “every minute”, “about every 2 hours”; null without a usable value. */
export function everyWords(seconds: number | null | undefined): string | null {
  const span = spanWords(seconds);
  if (!span) return null;
  const text = span.text.startsWith('1 ') ? span.text.slice(2) : span.text;
  return `${span.about ? 'about ' : ''}every ${text}`;
}

/** “5 minutes”, “about 17 hours”; null without a usable value. */
export function overWords(seconds: number | null | undefined): string | null {
  const span = spanWords(seconds);
  return span ? `${span.about ? 'about ' : ''}${span.text}` : null;
}

/** The case panel while its destination is paused. Without the timings it names no number. */
export function pausedBody(timings: ExportTimings | null | undefined): string {
  const every = everyWords(timings?.probeIntervalSeconds);
  return `The destination isn’t answering. LexaVault checks it ${every ?? 'regularly'} and sends this case on its own when it is back. Nothing is lost.`;
}

/** Support › Destinations, the note under the heading. */
export function destinationsNote(timings: ExportTimings | null | undefined): string {
  const every = everyWords(timings?.probeIntervalSeconds);
  return `A paused destination is checked ${every ?? 'regularly'} and resumes on its own, in order, when it answers.`;
}

/** “Check now” that failed: “… is checked again in 5 minutes.” */
export function checkStillFailing(timings: ExportTimings | null | undefined): string {
  const after = overWords(timings?.probeIntervalSeconds);
  return `still fails its health check. It stays paused and is checked again ${after ? `in ${after}` : 'on its own'}.`;
}

/* ---------- Task 0030: the destination's own code, under Technical details ---------- */

/** The job's stored detail is LexaVault's reason, then "; destination=" and the destination's code (backend task 0030). */
const DESTINATION_CODE_PREFIX = '; destination=';
/** The same safe pattern as the backend: letters, digits, '-' and '_', 1 to 40 characters. */
const SAFE_DESTINATION_CODE = /^[A-Za-z0-9_-]{1,40}$/;

/** LexaVault's internal reason (for example DELIVERY_SENSITIVITY_REFUSED), or null. */
export function internalReasonOf(detail: string | null | undefined): string | null {
  if (!detail) return null;
  const at = detail.indexOf(DESTINATION_CODE_PREFIX);
  const reason = (at < 0 ? detail : detail.slice(0, at)).trim();
  return reason || null;
}

/** The destination's own code (for example SDV-SEN-006), or null when there is none or it is not a safe code. */
export function destinationCodeOf(detail: string | null | undefined): string | null {
  if (!detail) return null;
  const at = detail.indexOf(DESTINATION_CODE_PREFIX);
  if (at < 0) return null;
  const code = detail.slice(at + DESTINATION_CODE_PREFIX.length);
  return SAFE_DESTINATION_CODE.test(code) ? code : null;
}

export const DESTINATION_CODE_LABEL = 'Destination code';

/** "Destination code SDV-SEN-006". */
export function destinationCodeLine(code: string): string {
  return `${DESTINATION_CODE_LABEL} ${code}`;
}

/** The distinct destination codes of a group's jobs, in the order first met. */
export function destinationCodesOf(jobs: readonly ExportJobView[]): string[] {
  const codes: string[] = [];
  for (const job of jobs) {
    const code = destinationCodeOf(job.detail);
    if (code && !codes.includes(code)) codes.push(code);
  }
  return codes;
}

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
} as const;

/** The codes a single failing case is retried for on the schedule (task 0028), then sent to IT. */
const SCHEDULED_CODES = ['LV-DLV-001', 'LV-DLV-002', 'LV-DLV-007', 'LV-DLV-008'];

/**
 * What was already tried for a group, by its code. Task 0036: the span and the rebuild come from the settings
 * (“retried over about 17 hours and rebuilt once” with the default schedule); without them no number is named.
 */
export function triedLine(code: string, timings?: ExportTimings | null): string | null {
  if (SCHEDULED_CODES.includes(code)) {
    const over = overWords(timings?.retrySpanSeconds);
    const retried = over ? `retried over ${over}` : 'retried on its schedule';
    const rebuilt = timings?.rebuiltOnce ? ' and rebuilt once' : '';
    return `The destination answers its health check, so it’s these cases. Each was ${retried}${rebuilt}.`;
  }
  if (code === 'LV-DLV-015') return 'A person sent these again after the destination refused them, and it refused them again.';
  if (code === 'LV-PRC-008') {
    const n = timings?.maxInterruptions;
    return `The export was interrupted ${typeof n === 'number' && n > 0 ? unit(n, 'time') : 'several times'} while being sent.`;
  }
  return null;
}

export const SIGN_IN_CODE = 'LV-DLV-005';

export function isSignInGroup(group: Pick<ItGroup, 'lvCode'>): boolean {
  return group.lvCode === SIGN_IN_CODE;
}

/**
 * "4 cases failing to Document vault since 10:00 AM". Task 0036: when the destination passes its health check (it
 * is working), it is these cases, not the destination: "2 cases can't be delivered to Local export folder since
 * 2:12 AM". While its check fails (paused), the wording stays.
 */
export function itGroupTitle(group: Pick<ItGroup, 'count' | 'destinationName' | 'firstFailedAt'>, now = Date.now(), destinationAnswers = false): string {
  const since = group.firstFailedAt ? ` since ${timeOrDay(group.firstFailedAt, now)}` : '';
  const destination = destinationLabel(group.destinationName);
  return destinationAnswers
    ? `${casesWord(group.count)} can’t be delivered to ${destination}${since}`
    : `${casesWord(group.count)} failing to ${destination}${since}`;
}

/** The codes whose title says the destination can't be reached (a connection failure). */
const CONNECTION_CODES = ['LV-DLV-001', 'LV-DLV-002'];

/**
 * The reason line of an IT card. Task 0036: a connection code at a destination that answers its health check is not
 * "Destination can't be reached": the destination answers, these cases fail. Every other code keeps its title.
 */
export function itReason(code: string, destinationAnswers: boolean): { text: string; code: string } {
  if (destinationAnswers && CONNECTION_CODES.includes(code)) return { text: 'Delivering these cases fails', code };
  const label = reasonLabel(code);
  const at = label.lastIndexOf(' · ');
  return { text: at < 0 ? label : label.slice(0, at), code };
}

/** A case without a case number, as the Cases list names it. */
export const UNTITLED_CASE = 'Untitled case';
const MAX_CASES_LISTED = 5;

/**
 * Task 0036: the IT card's cases, by case number (never the internal id): "Case 100200300400.", "Cases A, B.",
 * more than 5 → "Cases A, B, C, D, E and 3 more."
 */
export function itCasesLine(cases: readonly CaseRef[] | null | undefined): string | null {
  const list = cases ?? [];
  if (!list.length) return null;
  const names = list.slice(0, MAX_CASES_LISTED).map((c) => c.caseNumber?.trim() || UNTITLED_CASE);
  const more = list.length - names.length;
  return `${list.length === 1 ? 'Case' : 'Cases'} ${names.join(', ')}${more > 0 ? ` and ${more} more` : ''}.`;
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

/**
 * Task 0036: the Waiting column, each state on its own: "2 ready" (or "27 waiting" while the destination is not
 * working), "1 waiting for you", "2 need IT" (tone 'it', shown in the red style). Needs IT is never counted as
 * ready. Without the counts (an older server) the one total as before.
 */
export function waitingParts(state: string, open: number | null | undefined, waiting?: WaitingCounts | null): Array<{ text: string; tone: 'plain' | 'it' }> {
  if (!waiting) {
    const n = open ?? 0;
    if (n <= 0) return [{ text: 'Nothing waiting', tone: 'plain' }];
    return [{ text: state === 'WORKING' ? `${n} ready` : `${n} waiting`, tone: 'plain' }];
  }
  const parts: Array<{ text: string; tone: 'plain' | 'it' }> = [];
  if (waiting.queued > 0) parts.push({ text: state === 'WORKING' ? `${waiting.queued} ready` : `${waiting.queued} waiting`, tone: 'plain' });
  if (waiting.waitingForYou > 0) parts.push({ text: `${waiting.waitingForYou} waiting for you`, tone: 'plain' });
  if (waiting.needsIt > 0) parts.push({ text: `${waiting.needsIt} ${waiting.needsIt === 1 ? 'needs' : 'need'} IT`, tone: 'it' });
  return parts.length ? parts : [{ text: 'Nothing waiting', tone: 'plain' }];
}

/** The Waiting column as one line: "2 ready · 1 waiting for you · 2 need IT"; "Nothing waiting" at 0. */
export function waitingText(state: string, open: number | null | undefined, waiting?: WaitingCounts | null): string {
  return waitingParts(state, open, waiting).map((p) => p.text).join(' · ');
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

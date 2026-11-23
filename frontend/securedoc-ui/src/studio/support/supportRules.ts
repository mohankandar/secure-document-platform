/**
 * Support, the problem page and their words (UI task U012, findings 9, 10, 30). Pure functions, so the words and the
 * role rules are tested without a browser. The backend still enforces every rule; these only hide what it would refuse.
 *
 * What the support records hold today (see the U012 report): the case's primary identifier, but no other case field
 * (no borrower name) and no file name. IT Administrator and Platform Operations cannot open the case itself.
 */
import { problemReason, processingStageWords, sentenceCase } from '../words.ts';
import { lvLabel } from '../lvCodes.ts';

export const SUPPORT_CAPS = {
  view: 'TECHNICAL_EXCEPTION_VIEW',
  retry: 'TECHNICAL_RETRY',
  hold: 'TECHNICAL_HOLD_MANAGE',
  platformOps: 'PLATFORM_OPERATIONS',
  manual: 'MANUAL_REVIEW',
} as const;

/** The most rows the Support list asks for at once (the server's own limit). */
export const SUPPORT_PAGE_SIZE = 100;

/* ---------- Words for one problem ---------- */

export type ImpactStage = 'stopped' | 'approval' | 'delivered' | 'unknown';

/** What the problem did to the case: the chip on a row and on the problem page. */
export function impactChip(impact: string | null | undefined): { stage: ImpactStage; label: string } {
  switch ((impact ?? '').toUpperCase()) {
    case 'BLOCKED': return { stage: 'stopped', label: 'Case stopped' };
    case 'CONTINUED': return { stage: 'approval', label: 'Continued manually' };
    case 'COMPLETED': return { stage: 'delivered', label: 'Case finished' };
    default: return { stage: 'unknown', label: impact ? sentenceCase(impact) : 'Not known' };
  }
}

const CLOSED = new Set(['RESOLVED', 'AUTO_CLOSED']);

export function isClosed(status: string | null | undefined): boolean {
  return CLOSED.has((status ?? '').toUpperCase());
}

/** The problem's own state: Open, Watching (the case went on manually) or Resolved. */
export function statusWord(status: string | null | undefined): 'Open' | 'Watching' | 'Resolved' {
  if (isClosed(status)) return 'Resolved';
  if ((status ?? '').toUpperCase() === 'BUSINESS_CONTINUED') return 'Watching';
  return 'Open';
}

/* ---------- The Support list ---------- */

export interface SupportRowLike {
  incidentId: string;
  reasonCode: string;
  stage: string;
  status: string;
  businessImpact: string;
  attemptCount: number;
  /** Task 0025: the LV code, when the server sends it; otherwise it comes from reasonCode. */
  lvCode?: string | null;
  primaryIdentifierLabel?: string | null;
  primaryIdentifierValue?: string | null;
}

export type StatusChip = 'open' | 'resolved' | 'all';
export const STATUS_CHIPS: ReadonlyArray<{ key: StatusChip; label: string }> = [
  { key: 'open', label: 'Open' },
  { key: 'resolved', label: 'Resolved' },
  { key: 'all', label: 'All' },
];

export function inChip(row: Pick<SupportRowLike, 'status'>, chip: StatusChip): boolean {
  if (chip === 'all') return true;
  return chip === 'resolved' ? isClosed(row.status) : !isClosed(row.status);
}

export function chipCounts(rows: ReadonlyArray<Pick<SupportRowLike, 'status'>>): Record<StatusChip, number> {
  const resolved = rows.filter((r) => isClosed(r.status)).length;
  return { open: rows.length - resolved, resolved, all: rows.length };
}

/** How many open problems still hold a case up ("1 is blocking a case"). */
export function blockingCount(rows: ReadonlyArray<Pick<SupportRowLike, 'status' | 'businessImpact'>>): number {
  return rows.filter((r) => !isClosed(r.status) && (r.businessImpact ?? '').toUpperCase() === 'BLOCKED').length;
}

/** "What happened" on a row: the reason as "title · LV code", and a short note (tries, or that the case went on manually). */
export function rowWhat(row: SupportRowLike): { text: string; note?: string } {
  const text = problemReason(row.reasonCode, row.lvCode).label;
  if ((row.businessImpact ?? '').toUpperCase() === 'CONTINUED') return { text, note: 'case continued manually' };
  if (row.attemptCount > 1) return { text, note: `tried ${row.attemptCount} times` };
  return { text };
}

/**
 * Task 0025: the reason codes (TechnicalFailureCode) a support incident can carry, for the Reason filter. Internal
 * identifiers only; their words come from the LV code registry.
 */
export const SUPPORT_REASON_CODES: readonly string[] = [
  'AI_TIMEOUT', 'AI_RATE_LIMITED', 'AI_UNAVAILABLE', 'AI_REJECTED', 'AI_INVALID_RESPONSE',
  'OCR_TIMEOUT', 'OCR_UNAVAILABLE', 'OCR_PROCESS_FAILED',
  'DELIVERY_IO', 'DELIVERY_DESTINATION_INVALID', 'DELIVERY_SENSITIVITY_REFUSED', 'STORAGE_IO',
  'PROCESSING_DISPATCH_EXHAUSTED', 'PROCESSING_LICENSE_DENIED',
  'REDACTION_LOCATION_MISSING', 'REDACTION_SOURCE_PAGES_UNKNOWN', 'REDACTION_PAGE_COUNT_MISMATCH', 'REDACTION_TARGETS_NOT_APPLIED',
  'REDACTION_OUTPUT_EMPTY', 'REDACTION_TEXT_LAYER_REMAINS', 'REDACTION_ANNOTATIONS_REMAIN', 'REDACTION_REGION_NOT_COVERED',
  'REDACTION_OUTPUT_UNPARSABLE', 'UNKNOWN_TECHNICAL_FAILURE',
];

export interface ReasonFilterOption {
  /** The LV code: the select's value. */
  code: string;
  /** "Text reading timed out · LV-PRC-004". */
  label: string;
  /** The reason codes the server is asked for (one request each; the server filters by one reason code). */
  reasonCodes: string[];
}

/** The Reason filter: one option per LV code, in the order first met; reason codes that share an LV code are grouped. */
export function reasonFilterOptions(codes: readonly string[] = SUPPORT_REASON_CODES): ReasonFilterOption[] {
  const byCode = new Map<string, string[]>();
  for (const reasonCode of codes) {
    const code = problemReason(reasonCode).code;
    byCode.set(code, [...(byCode.get(code) ?? []), reasonCode]);
  }
  return [...byCode.entries()].map(([code, reasonCodes]) => ({ code, label: lvLabel(code), reasonCodes }));
}

/**
 * The answers for a grouped Reason filter (one per reason code) as one list: newest first, at most size rows, the
 * totals added up. Each server answer keeps its own filtering and limit.
 */
export function mergeSupportPages<T extends { incidentId: string; lastObservedAt: string }>(
  pages: ReadonlyArray<{ items: T[]; total: number }>, size: number,
): { items: T[]; total: number; page: number; size: number } {
  const seen = new Set<string>();
  const items = pages.flatMap((p) => p.items).filter((r) => (seen.has(r.incidentId) ? false : (seen.add(r.incidentId), true)))
    .sort((a, b) => (Date.parse(b.lastObservedAt) || 0) - (Date.parse(a.lastObservedAt) || 0));
  return { items: items.slice(0, size), total: pages.reduce((n, p) => n + p.total, 0), page: 0, size };
}

/** The second line under the case: where in processing it happened. */
export function rowWhere(row: Pick<SupportRowLike, 'stage'>): string {
  return `At ${processingStageWords(row.stage)}`;
}

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

/** The one search box: an incident number (a UUID) is looked up exactly; anything else matches the primary identifier. */
export function supportSearch(text: string): { incidentId?: string; businessIdentifier?: string } {
  const value = text.trim();
  if (!value) return {};
  return UUID.test(value) ? { incidentId: value.toLowerCase() } : { businessIdentifier: value };
}

/** The search placeholder uses the configured primary field's name, never a hard-coded one. */
export function searchPlaceholder(rows: ReadonlyArray<Pick<SupportRowLike, 'primaryIdentifierLabel'>>): string {
  const label = rows.find((r) => r.primaryIdentifierLabel?.trim())?.primaryIdentifierLabel?.trim();
  return label ? `${label} or incident number` : 'Case or incident number';
}

/** "3h ago" on a row. */
export function lastSeen(iso: string | null | undefined, now: number): string {
  if (!iso) return '—';
  const at = Date.parse(iso);
  if (Number.isNaN(at)) return '—';
  const minutes = Math.max(0, Math.round((now - at) / 60000));
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  const days = Math.round(hours / 24);
  return `${days} day${days === 1 ? '' : 's'} ago`;
}

/* ---------- The problem page ---------- */

export interface IncidentEventLike {
  eventType: string;
  attemptNumber?: number | null;
  scope?: string | null;
  reasonCode?: string | null;
  correlationId?: string | null;
  occurredAt: string;
  safeMetadata?: Record<string, string>;
}

export interface IncidentLike {
  incidentId: string;
  tenantId: string;
  packageId: string;
  documentId?: string | null;
  /** Task 0038: where the problem's document sits in its case; absent when the problem is about the whole case. */
  documentPosition?: { position: number; count: number; page?: number | null } | null;
  packageStatus?: string | null;
  primaryIdentifierLabel?: string | null;
  primaryIdentifierValue?: string | null;
  status: string;
  businessOutcome?: string | null;
  businessImpact: string;
  businessContinuityUsed: boolean;
  stage: string;
  component: string;
  failureCategory: string;
  reasonCode: string;
  /** Task 0025: the LV code, when the server sends it. */
  lvCode?: string | null;
  retryable: boolean;
  attemptCount: number;
  correlationId: string;
  safeSummary: string;
  artifactAvailability?: string | null;
  technicalAttentionRetainedUntil?: string | null;
  artifactsPurgedAt?: string | null;
  processingFingerprint: Record<string, string>;
  safeMetadata: Record<string, string>;
  firstObservedAt: string;
  lastObservedAt: string;
  resolvedAt?: string | null;
  events: IncidentEventLike[];
}

export interface TechnicalLike {
  packageId: string;
  documentId?: string | null;
  status: string;
  failureCategory: string;
  failedStage: string;
  retryable: boolean;
  failedAt: string;
  correlationId?: string | null;
  originalRetainedUntil?: string | null;
  explanation: string;
  /** Task 0025: the LV code, when the server sends it. */
  lvCode?: string | null;
  incidentId?: string | null;
  incidentStatus?: string | null;
  businessContinuityUsed?: boolean;
  businessOutcome?: string | null;
  processingFingerprint?: Record<string, string>;
  incidentEvents?: IncidentEventLike[];
  operatorDiagnostics?: { dependency: string; internalErrorCode: string; attempts: number; environment: string } | null;
}

export interface TimelineLine { at: string; text: string }

export interface ProblemView {
  /** Present when the problem has a support incident (the actions need it). */
  incidentId?: string;
  packageId: string;
  caseValue?: string;
  title: string;
  impact: { stage: ImpactStage; label: string };
  status: 'Open' | 'Watching' | 'Resolved';
  closed: boolean;
  blocked: boolean;
  packageStatus?: string;
  retryable: boolean;
  happened: string;
  meaning: string;
  timeline: TimelineLine[];
  earlierSteps: number;
  /** Every code and id, for Technical details only. */
  technical: Array<[string, string]>;
}

/** How a date and time is written on the page (the screen passes the browser's own format). */
export type When = (iso: string) => string;

function tries(count: number, lastAt: string | null | undefined, when: When): string {
  if (count <= 0) return '';
  if (count === 1) return lastAt ? ` It happened at ${when(lastAt)}.` : '';
  return ` LexaVault tried ${count} times${lastAt ? `, the last at ${when(lastAt)}` : ''}.`;
}

function meaningFor(impact: string, closed: boolean, keptUntil: string | null | undefined, purged: boolean, when: When): string {
  const stillOpen = closed ? '' : ' The problem stays open so IT can look into it.';
  switch (impact.toUpperCase()) {
    case 'CONTINUED':
      return `A person is finishing the case with Continue manually. It still goes through approval before delivery.${stillOpen}`;
    case 'COMPLETED':
      return `The case was finished and sent.${stillOpen}`;
    default: {
      if (closed) return 'The case was not finished. The problem is closed and kept for the record.';
      const kept = purged
        ? ' The original has been deleted, so the case can’t be finished.'
        : keptUntil ? ` The original is kept until ${when(keptUntil)}; after that the case can’t be finished.` : '';
      return `The case is stopped. Nothing was sent.${kept}`;
    }
  }
}

const EVENT_WORDS: Record<string, string> = {
  RETRY_SCHEDULED: 'LexaVault will try again',
  PROCESSING_RECOVERY_EXHAUSTED: 'No tries left; case stopped and support told',
  INVESTIGATION_HOLD_APPLIED: 'Original kept longer for IT to look into it',
  INVESTIGATION_HOLD_RELEASED: 'Original no longer kept longer',
  RESOLVED_NO_REPROCESS: 'Closed without processing again',
  SUPERSEDED: 'Closed: replaced by another case',
  AUTO_CLOSED_BY_RETENTION: 'Closed automatically when the original was deleted',
  BUSINESS_CONTINUITY_STARTED: 'A person started Continue manually',
  BUSINESS_CONTINUITY_SUBMITTED: 'Manual marks sent on to approval',
  BUSINESS_COMPLETED: 'Case finished and sent',
  RESOLVED: 'Problem resolved',
  ESCALATED: 'Passed on for more help',
};

/** One timeline step in words. An event the screen doesn't know yet shows as words, never as a code. */
export function timelineText(event: IncidentEventLike, fallbackReason?: string | null): string {
  const type = (event.eventType ?? '').toUpperCase();
  if (type === 'FAILURE_RECORDED') {
    const reason = problemReason(event.reasonCode || fallbackReason).title;
    const lower = reason.charAt(0).toLowerCase() + reason.slice(1);
    const lead = event.attemptNumber ? `Try ${event.attemptNumber} failed` : 'Failed';
    // A name or an abbreviation keeps its capital ("AI model timed out", "PDF can't be opened", "LexaVault …").
    const keep = /^(LexaVault|Scan Bridge)\b/.test(reason) || /^[A-Z]{2}/.test(reason);
    return `${lead}: ${keep ? reason : lower}`;
  }
  return EVENT_WORDS[type] ?? sentenceCase(type || 'Something happened');
}

/** The short timeline: oldest first, the latest few steps. */
export const TIMELINE_STEPS = 6;

function timelineOf(events: readonly IncidentEventLike[] | undefined, reason: string | null | undefined) {
  const sorted = [...(events ?? [])].sort((a, b) => Date.parse(a.occurredAt) - Date.parse(b.occurredAt));
  const shown = sorted.slice(-TIMELINE_STEPS);
  return {
    timeline: shown.map((e) => ({ at: e.occurredAt, text: timelineText(e, reason) })),
    earlierSteps: sorted.length - shown.length,
    eventFields: sorted.map((e, i): [string, string] => [`Step ${i + 1}`,
      [e.eventType, e.reasonCode, e.scope, e.attemptNumber != null ? `attempt ${e.attemptNumber}` : null, e.correlationId,
        ...Object.entries(e.safeMetadata ?? {}).map(([k, v]) => `${k}=${v}`)].filter(Boolean).join(' · ')]),
  };
}

/**
 * Task 0038: "document 2 of 3, page 14" (the page only when the problem recorded one). Never the file name. Null for a
 * problem about the whole case, or a position that does not add up.
 */
export function documentPositionText(p: IncidentLike['documentPosition']): string | null {
  if (!p || !Number.isInteger(p.position) || !Number.isInteger(p.count) || p.position < 1 || p.position > p.count) return null;
  const page = Number.isInteger(p.page) && (p.page as number) > 0 ? `, page ${p.page}` : '';
  return `document ${p.position} of ${p.count}${page}`;
}

const field = (label: string, value: unknown): [string, string] => [label, value == null || value === '' ? '—' : String(value)];

const where = (p: IncidentLike['documentPosition']) => { const text = documentPositionText(p); return text ? `, in ${text}` : ''; };

/** The problem page from a support incident (Support › a row). */
export function fromIncident(d: IncidentLike, when: When): ProblemView {
  const reason = problemReason(d.reasonCode, d.lvCode);
  const closed = isClosed(d.status);
  const purged = (d.artifactAvailability ?? '').toUpperCase() === 'PURGED' || Boolean(d.artifactsPurgedAt);
  const t = timelineOf(d.events, d.reasonCode);
  return {
    incidentId: d.incidentId,
    packageId: d.packageId,
    caseValue: d.primaryIdentifierValue || undefined,
    title: reason.label,
    impact: impactChip(d.businessImpact),
    status: statusWord(d.status),
    closed,
    blocked: (d.businessImpact ?? '').toUpperCase() === 'BLOCKED',
    packageStatus: d.packageStatus ?? undefined,
    retryable: d.retryable,
    happened: `${reason.sentence} It stopped at ${processingStageWords(d.stage)}${where(d.documentPosition)}.${tries(d.attemptCount, d.lastObservedAt, when)}`,
    meaning: meaningFor(d.businessImpact ?? '', closed, d.technicalAttentionRetainedUntil, purged, when),
    timeline: t.timeline,
    earlierSteps: t.earlierSteps,
    technical: [
      field('Incident', d.incidentId),
      field('Reason', d.reasonCode),
      field('Stage', d.stage),
      field('Component', d.component),
      field('Category', d.failureCategory),
      field('Incident status', d.status),
      field('Business impact', d.businessImpact),
      field('Business outcome', d.businessOutcome),
      field('Continued manually', d.businessContinuityUsed ? 'yes' : 'no'),
      field('Retry allowed', d.retryable ? 'yes' : 'no'),
      field('Attempts', d.attemptCount),
      field('Correlation', d.correlationId),
      field('Case', d.packageId),
      field('Case status', d.packageStatus),
      field('Document', d.documentId),
      field('Document position', documentPositionText(d.documentPosition)),
      field('Organization', d.tenantId),
      field('First seen', d.firstObservedAt),
      field('Last seen', d.lastObservedAt),
      field('Resolved', d.resolvedAt),
      field('Original', d.artifactAvailability),
      field('Kept until', d.technicalAttentionRetainedUntil),
      field('Deleted', d.artifactsPurgedAt),
      field('Server summary', d.safeSummary),
      ...Object.entries(d.processingFingerprint ?? {}).map(([k, v]) => field(k, v)),
      ...Object.entries(d.safeMetadata ?? {}).map(([k, v]) => field(k, v)),
      ...t.eventFields,
    ],
  };
}

/** The internal reason of a stopped case: the operator diagnostics' code, else the latest recorded failure's reason. */
export function technicalReasonCode(d: Pick<TechnicalLike, 'operatorDiagnostics' | 'incidentEvents'>): string | undefined {
  return d.operatorDiagnostics?.internalErrorCode
    ?? [...(d.incidentEvents ?? [])].reverse().find((e) => e.reasonCode)?.reasonCode ?? undefined;
}

/** Task 0025: the case panel's problem text for a stopped case, "title · LV code" (the reason itself stays under Technical details). */
export function technicalProblemText(d: Pick<TechnicalLike, 'operatorDiagnostics' | 'incidentEvents' | 'lvCode'>): string {
  return problemReason(technicalReasonCode(d), d.lvCode).label;
}

/** The problem page from a stopped case that has no support incident (an older link, /technical-exceptions/<case>). */
export function fromTechnical(d: TechnicalLike, when: When): ProblemView {
  const code = technicalReasonCode(d);
  const reason = problemReason(code, d.lvCode);
  const t = timelineOf(d.incidentEvents, code);
  const closed = isClosed(d.incidentStatus);
  const impact = (d.businessOutcome ?? '').toUpperCase() === 'COMPLETED' ? 'COMPLETED' : d.businessContinuityUsed ? 'CONTINUED' : 'BLOCKED';
  return {
    incidentId: d.incidentId ?? undefined,
    packageId: d.packageId,
    title: reason.label,
    impact: impactChip(impact),
    status: statusWord(d.incidentStatus),
    closed,
    blocked: impact === 'BLOCKED',
    packageStatus: undefined,
    retryable: d.retryable,
    happened: `${reason.sentence} It stopped at ${processingStageWords(d.failedStage)}.${tries(1, d.failedAt, when)}`,
    meaning: meaningFor(impact, closed, d.originalRetainedUntil, false, when),
    timeline: t.timeline,
    earlierSteps: t.earlierSteps,
    technical: [
      field('Incident', d.incidentId),
      field('Reason', code),
      field('Stage', d.failedStage),
      field('Category', d.failureCategory),
      field('Status', d.status),
      field('Incident status', d.incidentStatus),
      field('Retry allowed', d.retryable ? 'yes' : 'no'),
      field('Failed', d.failedAt),
      field('Correlation', d.correlationId),
      field('Case', d.packageId),
      field('Document', d.documentId),
      field('Kept until', d.originalRetainedUntil),
      field('Server explanation', d.explanation),
      ...(d.operatorDiagnostics ? [
        field('Dependency', d.operatorDiagnostics.dependency),
        field('Attempts', d.operatorDiagnostics.attempts),
        field('Environment', d.operatorDiagnostics.environment),
      ] : []),
      ...Object.entries(d.processingFingerprint ?? {}).map(([k, v]) => field(k, v)),
      ...t.eventFields,
    ],
  };
}

/* ---------- Who may do what on the problem page ---------- */

export interface ProblemActions {
  /** Retry processing: IT Administrator only (TECHNICAL_RETRY). */
  retry: boolean;
  /** Keep the original 7 more days, and the other keep/close actions (TECHNICAL_HOLD_MANAGE). */
  keep: boolean;
  /** The note that a Processor or Supervisor can also finish the case with Continue manually. */
  manualHint: boolean;
}

/**
 * Mirrors the server: incident actions need an open incident on a stopped case in the person's own organization;
 * Retry needs TECHNICAL_RETRY, the keep and close actions TECHNICAL_HOLD_MANAGE. Without an incident only Retry is
 * possible, and only when the server says the failure can be retried.
 */
export function problemActions(view: ProblemView, caps: readonly string[], otherOrganization: boolean): ProblemActions {
  const has = (c: string) => caps.includes(c);
  if (otherOrganization) return { retry: false, keep: false, manualHint: false };
  const manualHint = view.blocked && !view.closed;
  if (view.incidentId && view.packageStatus !== undefined) {
    const open = !view.closed && view.packageStatus === 'FAILED';
    return { retry: open && has(SUPPORT_CAPS.retry), keep: open && has(SUPPORT_CAPS.hold), manualHint };
  }
  return { retry: view.retryable && !view.closed && has(SUPPORT_CAPS.retry), keep: false, manualHint };
}

/** The keep and close actions beyond "Keep the original 7 more days", with the words the confirmation uses. */
export const MORE_ACTIONS: ReadonlyArray<{ action: string; holdDays?: number; label: string; consequence: string; closes: boolean }> = [
  { action: 'PRESERVE_FOR_INVESTIGATION', holdDays: 14, label: 'Keep the original 14 more days', consequence: 'The original is kept 14 more days so IT can look into it.', closes: false },
  { action: 'PRESERVE_FOR_INVESTIGATION', holdDays: 30, label: 'Keep the original 30 more days', consequence: 'The original is kept 30 more days so IT can look into it.', closes: false },
  { action: 'RELEASE_HOLD', label: 'Stop keeping it longer', consequence: 'The original goes back to the normal keeping time and is deleted when that ends.', closes: false },
  { action: 'RESOLVE_NO_REPROCESS', label: 'Close without processing again', consequence: 'The problem is closed and the case stays stopped. The original is deleted when its keeping time ends.', closes: true },
  { action: 'SUPERSEDED', label: 'Close: replaced by another case', consequence: 'The problem is closed because another case replaces this one. This case stays stopped.', closes: true },
  { action: 'DUPLICATE', label: 'Close: duplicate case', consequence: 'The problem is closed because this case is a duplicate. This case stays stopped.', closes: true },
];

/* ---------- Continue manually ---------- */

/** Continue manually is for Document Processor and Document Supervisor (MANUAL_REVIEW). */
export function canContinueManually(caps: readonly string[]): boolean {
  return caps.includes(SUPPORT_CAPS.manual);
}

/** "3 pages · 1 marked". */
export function pagesLine(pageCount: number, markedPages: number): string {
  return `${pageCount} page${pageCount === 1 ? '' : 's'} · ${markedPages} marked`;
}

/** The finish button waits for the confirmation and for no other action to be running. */
export function canFinish(confirmed: boolean, busy: boolean): boolean {
  return confirmed && !busy;
}

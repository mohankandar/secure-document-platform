/**
 * UI task U023: the rules behind the "Sensitive data" card's presets, preview and confirm step.
 * Pure functions, so the words and the preset rule are tested without a browser (policyRules.test.ts).
 */

export type ReviewMode = 'UNCERTAIN_ONLY' | 'REVIEW_ALL';
export type Preset = 'STRICT' | 'BALANCED' | 'LIGHT' | 'CUSTOM';

/** The part of the saved policy a preset covers, plus the fields it never touches. */
export interface PolicyValues {
  reviewMode: ReviewMode;
  autoRedactThreshold: number;
  enabledEntityTypes: string[];
  maskSsnLastFour: boolean;
}

const BALANCED_THRESHOLD = 0.80;
const LIGHT_THRESHOLD = 0.70;
/** Thresholds are saved with two decimals; this only absorbs binary floating-point noise (as on the server). */
const SAME = 1e-9;

/**
 * The one preset rule, ported from the server's `PolicyPreset.of` (task 0016): REVIEW_ALL at any threshold is
 * Strict; UNCERTAIN_ONLY at exactly 0.80 is Balanced and at exactly 0.70 is Light; anything else is Custom.
 */
export function presetOf(reviewMode: ReviewMode, threshold: number): Preset {
  if (reviewMode === 'REVIEW_ALL') return 'STRICT';
  if (Math.abs(threshold - BALANCED_THRESHOLD) < SAME) return 'BALANCED';
  if (Math.abs(threshold - LIGHT_THRESHOLD) < SAME) return 'LIGHT';
  return 'CUSTOM';
}

export const PRESETS: { key: Exclude<Preset, 'CUSTOM'>; name: string; line: string }[] = [
  // Owner's wording, 2026-11-20 (task 0019 question 1).
  { key: 'STRICT', name: 'Strict', line: 'A person checks every finding. Cases with nothing found are delivered without a check.' },
  { key: 'BALANCED', name: 'Balanced', line: 'A person checks findings below 80% confidence. The rest are redacted automatically.' },
  { key: 'LIGHT', name: 'Light', line: 'A person checks findings below 70% confidence. The rest are redacted automatically.' },
];

export function presetName(preset: Preset): string {
  return PRESETS.find((p) => p.key === preset)?.name ?? 'Custom';
}

/** Choosing a preset sets only the review mode and the threshold; Strict leaves the threshold as it is. */
export function applyPreset<T extends PolicyValues>(values: T, preset: Exclude<Preset, 'CUSTOM'>): T {
  if (preset === 'STRICT') return { ...values, reviewMode: 'REVIEW_ALL' };
  return { ...values, reviewMode: 'UNCERTAIN_ONLY', autoRedactThreshold: preset === 'BALANCED' ? BALANCED_THRESHOLD : LIGHT_THRESHOLD };
}

/** The preview shows only when the review mode or the threshold differs from the saved policy. */
export function reviewChanged(saved: PolicyValues, draft: PolicyValues): boolean {
  return saved.reviewMode !== draft.reviewMode || Math.abs(saved.autoRedactThreshold - draft.autoRedactThreshold) >= SAME;
}

/** "Save Strict", "Save Balanced", "Save Light"; "Save" for Custom or when only the types or the switch changed. */
export function saveLabel(saved: PolicyValues, draft: PolicyValues): string {
  if (!reviewChanged(saved, draft)) return 'Save';
  const preset = presetOf(draft.reviewMode, draft.autoRedactThreshold);
  return preset === 'CUSTOM' ? 'Save' : `Save ${presetName(preset)}`;
}

/** The query for GET /business-admin/sensitive-data-policy/estimate. The server ignores the threshold for Strict. */
export function estimateQuery(draft: Pick<PolicyValues, 'reviewMode' | 'autoRedactThreshold'>): Record<string, string> {
  return draft.reviewMode === 'REVIEW_ALL'
    ? { reviewMode: 'REVIEW_ALL' }
    : { reviewMode: 'UNCERTAIN_ONLY', threshold: draft.autoRedactThreshold.toFixed(2) };
}

/** The estimate as the server sends it (task 0019). Under 20 cases the two counts are left out. */
export interface PolicyEstimate {
  windowDays: number;
  cases: number;
  enoughData: boolean;
  current: { reviewMode: string; preset: string; casesToPerson?: number; findingsToPerson?: number };
  proposed: { reviewMode: string; preset: string; casesToPerson?: number; findingsToPerson?: number };
}

const count = (n: number) => n.toLocaleString('en-US');

/** "If you save Balanced, based on the last 30 days in Residential lending (512 cases)". */
export function previewHeadline(draft: Pick<PolicyValues, 'reviewMode' | 'autoRedactThreshold'>, estimate: Pick<PolicyEstimate, 'windowDays' | 'cases'>, entityName: string): string {
  const preset = presetOf(draft.reviewMode, draft.autoRedactThreshold);
  const what = preset === 'CUSTOM' ? 'these settings' : presetName(preset);
  const cases = `${count(estimate.cases)} ${estimate.cases === 1 ? 'case' : 'cases'}`;
  return `If you save ${what}, based on the last ${estimate.windowDays} days in ${entityName} (${cases})`;
}

export interface PreviewTile {
  label: string;
  current: string;
  proposed: string;
  change: { direction: 'more' | 'fewer' | 'none'; text: string };
}

function tile(label: string, current: number, proposed: number): PreviewTile {
  const diff = proposed - current;
  const change = diff > 0 ? { direction: 'more' as const, text: `▲ ${count(diff)} more` }
    : diff < 0 ? { direction: 'fewer' as const, text: `▼ ${count(-diff)} fewer` }
      : { direction: 'none' as const, text: 'no change' };
  return { label, current: count(current), proposed: count(proposed), change };
}

/** The two tiles, or null when there is not enough data (or a count is missing: nothing is read as zero). */
export function previewTiles(estimate: PolicyEstimate): PreviewTile[] | null {
  const { current: c, proposed: p } = estimate;
  if (!estimate.enoughData) return null;
  if ([c.casesToPerson, c.findingsToPerson, p.casesToPerson, p.findingsToPerson].some((n) => typeof n !== 'number')) return null;
  return [
    tile('Cases a person would have checked', c.casesToPerson!, p.casesToPerson!),
    tile('Findings a person would have checked', c.findingsToPerson!, p.findingsToPerson!),
  ];
}

export const PREVIEW_WORDS = {
  loading: 'Working out the estimate…',
  notEnough: 'Not enough recent cases to estimate.',
  failed: 'The estimate couldn’t be worked out. You can still save.',
  note: 'An estimate from past findings (type and confidence only). Nothing changes until you save, and only cases received after saving use it.',
} as const;

/**
 * The confirm step shows only when the estimate says fewer findings would go to a person than under the saved
 * policy. Not when the estimate is missing, has too little data, or is not fewer.
 */
export function needsConfirm(estimate: PolicyEstimate | null | undefined): boolean {
  if (!estimate || !estimate.enoughData) return false;
  const now = estimate.current.findingsToPerson;
  const next = estimate.proposed.findingsToPerson;
  return typeof now === 'number' && typeof next === 'number' && next < now;
}

export interface ConfirmWords {
  oldName: string;
  newName: string;
  fewerFindings: string;
  /** Null when the cases don't drop: the sentence then leaves "and N fewer cases" out. */
  fewerCases: string | null;
  days: number;
  threshold: string;
  auditLine: string;
}

export function confirmWords(saved: PolicyValues, draft: PolicyValues, estimate: PolicyEstimate): ConfirmWords {
  const oldName = presetName(presetOf(saved.reviewMode, saved.autoRedactThreshold));
  const newName = presetName(presetOf(draft.reviewMode, draft.autoRedactThreshold));
  const f = (estimate.current.findingsToPerson ?? 0) - (estimate.proposed.findingsToPerson ?? 0);
  const c = (estimate.current.casesToPerson ?? 0) - (estimate.proposed.casesToPerson ?? 0);
  return {
    oldName, newName,
    fewerFindings: count(f),
    fewerCases: c > 0 ? count(c) : null,
    days: estimate.windowDays,
    threshold: `${Math.round(draft.autoRedactThreshold * 100)}%`,
    auditLine: `This change, and who made it, is recorded in the audit trail and shown in the compliance report as ${oldName} → ${newName}.`,
  };
}

/**
 * Only the latest answer counts: each request takes a ticket, and an answer is used only if its ticket is still
 * the newest when it arrives.
 */
export function latestOnly() {
  let newest = 0;
  return {
    take(): number { newest += 1; return newest; },
    isLatest(ticket: number): boolean { return ticket === newest; },
  };
}

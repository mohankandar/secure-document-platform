/**
 * Pure rules for Review (UI task U004): the masked text in queue items, "Redact all like this", and zoom steps.
 * No imports beyond words.ts, so node's test runner can check it directly. The backend still enforces every rule.
 */
import { sensitiveTypeKey, sensitiveTypeName, sensitiveTypePlural } from './words.ts';

/** The fields these rules read from a review queue item. */
export interface ReviewItemLike {
  findingId: string;
  packageId: string;
  entityType: string;
  hasBoundingBox: boolean;
}

const MASK = '••••';

/**
 * The found text as a queue item shows it (owner's decision, 2026-11-18): the last 4 characters only when the value
 * has 8 or more characters; a shorter value is masked in full. The mask has a fixed width so it does not reveal
 * the length.
 */
export const MIN_LENGTH_FOR_LAST_FOUR = 8;

export function maskFoundText(text: string | null | undefined): string | null {
  if (typeof text !== 'string') return null;
  const value = text.trim();
  if (!value) return null;
  if (value.length < MIN_LENGTH_FOR_LAST_FOUR) return MASK;
  return MASK + value.slice(-4);
}

export interface BulkPlan<T extends ReviewItemLike> {
  /** Open findings of the same type in the same case, the selected one first. */
  all: T[];
  /** The ones that will be confirmed, each through its own call. */
  toRedact: T[];
  /** The ones whose place was not found: skipped, they stay in the queue. */
  skipped: T[];
}

/**
 * "Redact all like this": the selected finding plus every other open finding of the same type in the same case.
 * Null when there is no other one, so the action is not offered. `selectedHasMark` is true when the person drew
 * a mark for the selected finding, which then counts as placed.
 */
export function bulkPlan<T extends ReviewItemLike>(items: readonly T[], selected: T | null | undefined, selectedHasMark = false): BulkPlan<T> | null {
  if (!selected) return null;
  const key = sensitiveTypeKey(selected.entityType);
  const others = items.filter((item) =>
    item.findingId !== selected.findingId
    && item.packageId === selected.packageId
    && sensitiveTypeKey(item.entityType) === key);
  if (others.length === 0) return null;
  const all = [selected, ...others];
  const placed = (item: T) => item.hasBoundingBox || (item.findingId === selected.findingId && selectedHasMark);
  return { all, toRedact: all.filter(placed), skipped: all.filter((item) => !placed(item)) };
}

/** "1 more Social Security Number is waiting in this case." */
export function bulkWaitingText(plan: BulkPlan<ReviewItemLike>): string {
  const more = plan.all.length - 1;
  const type = plan.all[0].entityType;
  return more === 1
    ? `1 more ${sensitiveTypeName(type)} is waiting in this case.`
    : `${more} more ${sensitiveTypePlural(type)} are waiting in this case.`;
}

/** "Redact all 2 like this" */
export function bulkButtonText(plan: BulkPlan<ReviewItemLike>): string {
  return `Redact all ${plan.all.length} like this`;
}

/** The confirmation before a bulk run (owner's decision, 2026-11-18): "Redact 2 Social Security Number findings?" */
export function bulkConfirmTitle(plan: BulkPlan<ReviewItemLike>): string {
  const n = plan.toRedact.length;
  return `Redact ${n} ${sensitiveTypeName(plan.all[0].entityType)} ${n === 1 ? 'finding' : 'findings'}?`;
}

/** What the confirmation says will happen. */
export function bulkConfirmBody(plan: BulkPlan<ReviewItemLike>): string {
  const skipped = plan.skipped.length;
  const each = 'Each one is redacted and recorded as its own decision.';
  return skipped === 0
    ? each
    : `${each} ${skipped} with no place on the page ${skipped === 1 ? 'is' : 'are'} skipped and ${skipped === 1 ? 'stays' : 'stay'} in the queue.`;
}

/** The sentence after a bulk run: what was redacted, what was skipped, what failed. */
export function bulkResultText(redacted: number, skipped: number, failed: number): string {
  const parts = [`Redacted ${redacted} ${redacted === 1 ? 'finding' : 'findings'}.`];
  if (skipped > 0) parts.push(`${skipped} skipped because ${skipped === 1 ? 'its place was' : 'their place was'} not found; ${skipped === 1 ? 'it stays' : 'they stay'} in the queue.`);
  if (failed > 0) parts.push(`${failed} could not be saved and ${failed === 1 ? 'stays' : 'stay'} in the queue.`);
  return parts.join(' ');
}

/* ---------- Zoom ---------- */

export const ZOOM_STEPS = [50, 75, 100, 125, 150, 200, 300] as const;

/** The next zoom step above (+1) or below (-1) the current percentage. */
export function nextZoom(current: number, direction: 1 | -1): number {
  if (direction > 0) return ZOOM_STEPS.find((step) => step > current + 0.5) ?? ZOOM_STEPS[ZOOM_STEPS.length - 1];
  return [...ZOOM_STEPS].reverse().find((step) => step < current - 0.5) ?? ZOOM_STEPS[0];
}

/** The preview is rendered at 144 DPI; CSS pixels are 96 per inch. 100% means the page at actual size. */
export const PREVIEW_PIXELS_PER_CSS_PIXEL = 144 / 96;

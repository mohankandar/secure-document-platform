import { lvLabel } from './lvCodes.ts';

/** Task 0076: bulk approval on Cases › Approval. The server holds every rule; this only words and limits the choice. */
export interface BulkOffer { enabled: boolean; maximum: number; reviewedByYou: readonly string[] }
export interface BulkOutcome { packageId: string; approved: boolean; reasonCode?: string | null }

export const BULK_WORDS = {
  own: 'You reviewed this case, so another supervisor approves it.',
  consequence: 'Each case is delivered and recorded separately. Delivery starts immediately and cannot be recalled from the destination.',
  failed: 'The cases could not be approved. Reload the list to see which ones were.',
} as const;

const cases = (n: number) => `${n} ${n === 1 ? 'case' : 'cases'}`;

/** Why a row can't be ticked; null when it can. A full selection blocks only further ticks. */
export function blockedReason(id: string, selected: readonly string[], offer: BulkOffer): string | null {
  if (offer.reviewedByYou.includes(id)) return BULK_WORDS.own;
  if (!selected.includes(id) && selected.length >= offer.maximum) return `Up to ${offer.maximum} cases at once.`;
  return null;
}

/** Only cases still waiting and not the person's own stay selected after a refresh. */
export function keepSelectable(selected: readonly string[], waiting: readonly string[], offer: BulkOffer): string[] {
  return selected.filter((id) => waiting.includes(id) && !offer.reviewedByYou.includes(id)).slice(0, offer.maximum);
}

export const approveSelectedLabel = (n: number) => `Approve selected (${n})`;
export const confirmTitle = (n: number) => `Approve ${cases(n)} for delivery?`;

/** One line per case that stayed, and the count of those that went. */
export function resultLines(outcomes: readonly BulkOutcome[], name: (id: string) => string): { done: string; failed: string[] } {
  const approved = outcomes.filter((o) => o.approved).length;
  return {
    done: `${cases(approved)} approved.`,
    failed: outcomes.filter((o) => !o.approved).map((o) => `${name(o.packageId)}: ${lvLabel(o.reasonCode ?? 'LV-SYS-001')}`),
  };
}

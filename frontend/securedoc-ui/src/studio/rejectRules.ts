import { lvEntry } from './lvCodes.ts';

/** Task 0071: rejecting a case. The reasons are the server's fixed list (RejectionReason), shown by their LV titles. */
export const REJECTION_CODES = ['LV-REV-010', 'LV-REV-011', 'LV-REV-012', 'LV-REV-013', 'LV-REV-014'] as const;

export const REJECT_WORDS = {
  action: 'Reject case',
  reason: 'Reason',
  choose: 'Choose a reason',
  consequence: 'The case ends here as Rejected. Nothing is exported, and its files are deleted when their keeping time ends. This can’t be undone: the source has to send a new case.',
  otherHint: 'Write the details in your ticket system, not here.',
  confirm: 'Reject case',
  done: 'Case rejected.',
  failed: 'The case could not be rejected. Nothing was changed.',
} as const;

/** "Wrong document": the LV title without "Rejected: ". */
export function rejectionName(code: string): string {
  const name = lvEntry(code).title.replace(/^Rejected: /, '');
  return name.charAt(0).toUpperCase() + name.slice(1);
}

export const isRejectionCode = (code: string | null | undefined): boolean =>
  (REJECTION_CODES as readonly string[]).includes(code ?? '');

/** Where this person may reject a case in this state; null when they may not. The server checks again. */
export function rejectStep(status: string, caps: readonly string[]): 'review' | 'approval' | null {
  if ((status === 'PENDING_REVIEW' || status === 'IN_REVIEW') && caps.includes('MANUAL_REVIEW')) return 'review';
  if (status === 'PENDING_FINAL_APPROVAL' && caps.includes('PACKAGE_APPROVE_DELIVERY')) return 'approval';
  return null;
}

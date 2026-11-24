/**
 * Pure rules for the Cases rows (UI task U003): the next step in words, and the one action a row offers.
 * The action follows the same capability rules as caseActions and needsYou in data.ts; the backend still
 * enforces every rule, the row only hides what would be refused. Imports only pure modules (words.ts and
 * supportRules.ts), so node's test runner can check it directly.
 */
import { ACTION_WORDS, NEXT_STEP, PROCESSING_STEP_WORDS, findingsToReview, sentenceCase } from './words.ts';
import { canContinueManually } from './support/supportRules.ts';
import { isLvCode, lvEntry } from './lvCodes.ts';

export const CAPS = {
  review: 'MANUAL_REVIEW',
  approve: 'PACKAGE_APPROVE_DELIVERY',
  technicalRetry: 'TECHNICAL_RETRY',
} as const;

export type RowActionKind = 'review' | 'approve' | 'technical-retry' | 'manual-fallback';

export interface RowAction {
  kind: RowActionKind;
  label: string;
}

/**
 * @param openFindings open findings for this case in the review queue, or undefined when the person cannot see
 *                     the queue (no MANUAL_REVIEW)
 */
export function nextStepText(status: string, openFindings: number | undefined): string {
  switch (status) {
    case 'CREATED':
    case 'DRAFT': return NEXT_STEP.waitingForDocuments;
    case 'UPLOADED': return NEXT_STEP.readyToSubmit;
    case 'SUBMITTED': return NEXT_STEP.queued;
    case 'PROCESSING': return NEXT_STEP.finding;
    case 'REPROCESSING': return NEXT_STEP.processingAgain;
    case 'PENDING_REVIEW':
    case 'IN_REVIEW': return openFindings ? findingsToReview(openFindings) : NEXT_STEP.waitingForReview;
    case 'PENDING_FINAL_APPROVAL': return NEXT_STEP.readyToApprove;
    case 'COMPLETED':
    case 'EXPORTED':
    case 'DELIVERED': return NEXT_STEP.sent;
    case 'FAILED': return NEXT_STEP.stopped;
    case 'REJECTED': return NEXT_STEP.notAccepted;
    default: return NEXT_STEP.unknown;
  }
}

/** Export states in which a case waits (task 0029), so the row says why. */
const WAITING_EXPORT = new Set(['NEEDS_IT', 'WAITING_FOR_YOU', 'DESTINATION_PAUSED', 'READY']);

/**
 * Task 0038: the plain stop reason a row shows under its next step: the title of the case's LV code (the short form
 * of the case panel's reason), only while the case is stopped, rejected or waiting. Null when the case neither
 * stopped nor waits, or when the server sent no code it knows: the row never shows text of unknown origin.
 */
export function rowReason(pkg: { status: string; exportState?: string; stopReasonCode?: string | null }): string | null {
  const stoppedOrWaiting = pkg.status === 'FAILED' || pkg.status === 'REJECTED'
    || (pkg.exportState !== undefined && WAITING_EXPORT.has(pkg.exportState));
  if (!stoppedOrWaiting || !isLvCode(pkg.stopReasonCode)) return null;
  return lvEntry(pkg.stopReasonCode).title;
}

/** Task 0038: the delivery destination once one is chosen ("To Records archive"), else null. */
export function rowDestination(pkg: { destinationName?: string | null }): string | null {
  const name = pkg.destinationName?.trim();
  return name ? `To ${name}` : null;
}

/** The one action a row shows, or null when this person cannot move the case forward. */
export function rowAction(status: string, caps: readonly string[], openFindings: number): RowAction | null {
  const has = (cap: string) => caps.includes(cap);
  switch (status) {
    case 'PENDING_REVIEW':
    case 'IN_REVIEW':
      return has(CAPS.review) && openFindings > 0 ? { kind: 'review', label: ACTION_WORDS.review } : null;
    case 'PENDING_FINAL_APPROVAL':
      return has(CAPS.approve) ? { kind: 'approve', label: ACTION_WORDS.approve } : null;
    case 'FAILED':
      // Same order as the case panel: an IT Administrator retries; a Processor or Supervisor continues manually.
      if (has(CAPS.technicalRetry)) return { kind: 'technical-retry', label: ACTION_WORDS.retry };
      if (canContinueManually(caps)) return { kind: 'manual-fallback', label: ACTION_WORDS.continueManually };
      return null;
    default:
      return null;
  }
}

/**
 * Task 0023 B4: the name of a split delivery in the case panel. Its destination's label; without one, the document's
 * type, then a short document id. Never the file name: file names often hold a person's name.
 */
export function deliveryLabel(delivery: { destinationLabel?: string | null; sourceDocumentId?: string | null }, documentType?: string | null): string {
  const destination = delivery.destinationLabel?.trim();
  if (destination) return destination;
  const type = documentType?.trim();
  if (type) return sentenceCase(type);
  const id = delivery.sourceDocumentId?.trim();
  if (id) return `Document ${id.slice(0, 8)}`;
  return 'Destination';
}

/* ---------- Task 0048: where a case is in processing (case panel) ---------- */

export type ProcessingStepKey = 'text' | 'find' | 'review' | 'redact' | 'exporting';

/** The chip's colour family (the existing [data-stage] styles): processing steps pulse like Redacting. */
const STEP_CHIP_STAGE: Record<ProcessingStepKey, 'redacting' | 'review' | 'exporting'> = {
  text: 'redacting', find: 'redacting', redact: 'redacting', review: 'review', exporting: 'exporting',
};

/**
 * Task 0048: which processing step a case is at, from its status and its documents' statuses (the same in the inline
 * and the staged pipeline). Text while any document is still read, Find while any is searched, Review while a finding
 * waits for a person, Redact while any document waits for redaction, Exporting while an export job is open. Null when
 * the case is not in processing (new, waiting for approval, finished, stopped): the timeline says enough then.
 *
 * @param openFindings findings for a person, or undefined when the viewer cannot see the review queue
 */
export function processingStepOf(status: string, documentStatuses: readonly string[], exporting: boolean,
                                 openFindings: number | undefined): { key: ProcessingStepKey; label: string; chipStage: 'redacting' | 'review' | 'exporting' } | null {
  const step = ((): ProcessingStepKey | null => {
    if (exporting) return 'exporting';
    const any = (...wanted: string[]) => documentStatuses.some(s => wanted.includes(s));
    if (status === 'SUBMITTED') return 'text';
    if (status === 'PROCESSING') {
      if (any('UPLOADED', 'EXTRACTING')) return 'text';
      if (any('DETECTING')) return 'find';
      if (any('REDACTING')) return 'redact';
      return null;
    }
    if (status === 'PENDING_REVIEW' || status === 'IN_REVIEW') {
      if ((openFindings ?? 1) > 0) return 'review';
      return any('REDACTING') ? 'redact' : 'review';
    }
    return null;
  })();
  return step ? { key: step, label: PROCESSING_STEP_WORDS[step], chipStage: STEP_CHIP_STAGE[step] } : null;
}

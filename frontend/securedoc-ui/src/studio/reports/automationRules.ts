import type { AutomationPeriod, AutomationReport } from '../../types';

/** Task 0105: the three periods the Automation card offers, in its order. */
export const AUTOMATION_PERIODS: { key: AutomationPeriod; label: string }[] = [
  { key: 'TODAY', label: 'Today' },
  { key: 'WEEK', label: 'This week' },
  { key: 'MONTH', label: 'This month' },
];

function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

/** The parts of the answer the card reads; anything else is an answer this screen does not understand. */
export function isAutomationReport(v: unknown): v is AutomationReport {
  return isRecord(v) && typeof v.period === 'string' && typeof v.automatic === 'number'
    && typeof v.placedByPerson === 'number' && typeof v.drawnByPerson === 'number'
    && typeof v.byText === 'number' && typeof v.byVision === 'number'
    && (v.automaticPercent === null || typeof v.automaticPercent === 'number');
}

/** The headline: the share LexaVault found and placed with no person. */
export function automationHeadline(r: AutomationReport): string {
  return r.automaticPercent === null ? 'Nothing redacted yet in this period' : `Redacted automatically: ${r.automaticPercent}%`;
}

/** The line under the counts: which reader found what LexaVault redacted. */
export function foundByLine(r: AutomationReport): string {
  const all = r.byText + r.byVision;
  if (all === 0) return 'Nothing was found by the text or the vision model in this period.';
  const text = Math.round((100 * r.byText) / all);
  return `Found by the page's text or OCR: ${text}%. By the vision model: ${100 - text}%.`;
}

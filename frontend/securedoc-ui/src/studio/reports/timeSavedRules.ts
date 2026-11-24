/**
 * UI task U024: the words of "What LexaVault did for you" on Reports and of the minutes-per-page field in Admin ›
 * Report targets. The numbers come from the server (task 0020, `timeSaved`); the word rules are the PDF's
 * (`TimeSavedRule.java`), ported once here. Kept free of React so the rules run under `node --test`.
 */
import { SENSITIVE_TYPES, findSensitiveType } from '../words.ts';

/**
 * The "New" pill beside "Time saved estimate" in Report targets. A one-release marker: set to false (or delete it and
 * its one use in PolicyArea.tsx) in the next release.
 */
export const TIME_SAVED_SETTING_IS_NEW = true;

export const DEFAULT_MINUTES_PER_PAGE = 1;
export const MIN_MINUTES_PER_PAGE = 0.5;
export const MAX_MINUTES_PER_PAGE = 30;
export const MINUTES_PER_PAGE_ERROR = 'Enter a number from 0.5 to 30, in steps of 0.5.';

/** The canvas's short names for the tile's grey line, keyed by the vocabulary key (words.ts SENSITIVE_TYPES). */
export const SHORT_TYPE_NAMES: Readonly<Record<string, string>> = {
  SSN: 'SSN',
  DATE_OF_BIRTH: 'date of birth',
  CREDIT_CARD_NUMBER: 'card',
  CREDIT_SCORE: 'credit score',
  BANK_ROUTING_NUMBER: 'routing number',
  BANK_ACCOUNT_NUMBER: 'account number',
  ITIN: 'ITIN',
  EIN: 'EIN',
  DRIVERS_LICENSE_NUMBER: 'license number',
  PASSPORT_NUMBER: 'passport number',
  PERSON_NAME: 'name',
  SIGNATURE: 'signature',
  FACE_PHOTO: 'face photo',
  CVV: 'card code',
  PIN: 'PIN',
};

/** One vocabulary name ("Social Security Number") as its short name ("SSN"). An unknown name stays as sent. */
export function shortTypeName(name: string): string {
  const type = SENSITIVE_TYPES.find((t) => t.name === name) ?? findSensitiveType(name);
  return (type && SHORT_TYPE_NAMES[type.key]) || name;
}

/** "SSN, date of birth, card, credit score", or "—" when nothing was redacted. */
export function shortTypeList(names: readonly string[]): string {
  return names.length ? names.map(shortTypeName).join(', ') : '—';
}

/** "68%", or "—" with no delivered cases. */
export function percentWords(percent: number | null): string {
  return percent === null ? '—' : `${percent}%`;
}

const count = (n: number) => n.toLocaleString('en-US');

/** TimeSavedRule.timeWords: "≈ 161 h" (rounded down), "≈ 45 min" under an hour, "0 min" for none. */
export function timeWords(minutes: number): string {
  if (!(minutes > 0)) return '0 min';
  if (minutes < 60) return `≈ ${Math.floor(minutes)} min`;
  return `≈ ${count(Math.floor(minutes / 60))} h`;
}

/** TimeSavedRule.minutesPerPageWords: 1, 1.5, 2.5 (no trailing ".0"). */
export function minutesPerPageWords(m: number): string {
  return String(Number(m.toFixed(1)));
}

/**
 * The formula's result before rounding: pages × minutes per page, with one decimal only when needed ("9,690",
 * "302.5"). Worked out in half minutes so 0.5 steps never pick up binary rounding noise.
 */
export function minutesBeforeRounding(pagesNoPerson: number, minutesPerPage: number): string {
  const halves = pagesNoPerson * Math.round(minutesPerPage * 2);
  return (halves / 2).toLocaleString('en-US', { maximumFractionDigits: 1 });
}

/** The grey text under the formula. "deliberately low" only for the default of 1 minute. */
export function formulaNote(minutesPerPage: number): string {
  const where = 'is set by your Business Administrator in Admin › Redaction & retention › Report targets';
  const what = minutesPerPage === DEFAULT_MINUTES_PER_PAGE
    ? `The 1 minute per page is deliberately low and ${where}.`
    : `The ${minutesPerPageWords(minutesPerPage)} minutes per page ${where}.`;
  return `Pages a person reviewed count as zero. ${what} This is an estimate, not a measurement.`;
}

/** TimeSavedRule.allowedMinutesPerPage: 0.5 to 30, in steps of 0.5. The error to show, or null when it is fine. */
export function minutesPerPageError(value: number | undefined): string | null {
  if (value === undefined || !Number.isFinite(value) || value < MIN_MINUTES_PER_PAGE || value > MAX_MINUTES_PER_PAGE) return MINUTES_PER_PAGE_ERROR;
  return Number.isInteger(value * 2) ? null : MINUTES_PER_PAGE_ERROR;
}

/** The `timeSaved` block as the tiles read it. Anything else is an answer this screen does not understand. */
export interface TimeSaved {
  casesDelivered: number; casesNoPerson: number; percentNoPerson: number | null; pagesChecked: number; documentsChecked: number;
  itemsRedacted: number; itemTypes: string[]; pagesNoPerson: number; minutesPerPage: number; minutesSaved: number;
}

export function isTimeSaved(v: unknown): v is TimeSaved {
  if (typeof v !== 'object' || v === null) return false;
  const t = v as Record<string, unknown>;
  const n = (k: string) => typeof t[k] === 'number' && Number.isFinite(t[k]) && (t[k] as number) >= 0;
  return ['casesDelivered', 'casesNoPerson', 'pagesChecked', 'documentsChecked', 'itemsRedacted', 'pagesNoPerson', 'minutesSaved'].every(n)
    && (t.percentNoPerson === null || n('percentNoPerson'))
    && n('minutesPerPage') && minutesPerPageError(t.minutesPerPage as number) === null
    && Array.isArray(t.itemTypes) && t.itemTypes.every((x) => typeof x === 'string');
}

/** The four tiles and the formula panel, in words. */
export function timeSavedWords(t: TimeSaved) {
  return {
    noPerson: { value: percentWords(t.percentNoPerson), sub: `${count(t.casesNoPerson)} of ${count(t.casesDelivered)} cases` },
    pages: { value: count(t.pagesChecked), sub: `in ${count(t.documentsChecked)} delivered documents` },
    items: { value: count(t.itemsRedacted), sub: shortTypeList(t.itemTypes) },
    saved: { value: timeWords(t.minutesSaved) },
    formula: {
      pages: count(t.pagesNoPerson),
      minutesPerPage: minutesPerPageWords(t.minutesPerPage),
      result: `= ${minutesBeforeRounding(t.pagesNoPerson, t.minutesPerPage)} minutes, rounded down`,
      resultValue: timeWords(t.minutesSaved),
      note: formulaNote(t.minutesPerPage),
    },
  };
}

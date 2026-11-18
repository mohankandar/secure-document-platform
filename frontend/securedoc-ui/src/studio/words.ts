/**
 * One name for everything people see in the Case File design (docs/work/ui/SPEC.md, "Vocabulary").
 * Screens take their words from here; they do not spell names of stages, places or sensitive types themselves.
 * Plain TypeScript with no imports, so node's test runner can check it directly.
 */

/* ---------- Places in the app (routes stay as they are) ---------- */

export const NAV = {
  cases: 'Cases',
  newCase: 'New case',
  review: 'Review',
  reports: 'Reports',
  admin: 'Admin',
  support: 'Support',
} as const;

/* ---------- Stages ---------- */

export type StageWordKey = 'intake' | 'redacting' | 'review' | 'approval' | 'delivered' | 'stopped' | 'unknown';

export const STAGE_NAMES: Record<StageWordKey, string> = {
  intake: 'New',
  redacting: 'Redacting',
  review: 'Review',
  approval: 'Approval',
  delivered: 'Delivered',
  stopped: 'Stopped',
  unknown: 'Unrecognized',
};

/** The groups on the Cases page. */
export const CASE_GROUPS = {
  needsYou: 'Needs you',
  inProgress: 'In progress',
  inProgressNote: 'LexaVault or a colleague has these',
  stopped: STAGE_NAMES.stopped,
  delivered: STAGE_NAMES.delivered,
} as const;

/* ---------- Sensitive types ---------- */

export interface SensitiveType {
  /** The policy and detection key, as the server sends it. */
  key: string;
  /** The one name people see, in Review, Policy, the case panel and Continue manually. */
  name: string;
  /** What the detection finds, for the policy screen. */
  detects: string;
}

/**
 * The types the detection acts on (SensitiveContentPromptBuilder and the heuristic adapters). Each finds an exact
 * text span and redacts only that span; none redacts a whole page, image or document.
 */
export const SENSITIVE_TYPES: readonly SensitiveType[] = [
  { key: 'SSN', name: 'Social Security Number', detects: 'Full Social Security Numbers in text, including clearly labeled test values. Form, reference and case numbers are excluded.' },
  { key: 'DATE_OF_BIRTH', name: 'Date of birth', detects: 'A person’s birth date only. Signature, payment, effective and other dates are excluded.' },
  { key: 'CREDIT_CARD_NUMBER', name: 'Credit card number', detects: 'Full 13–19 digit payment card numbers. Masked or last-four displays and account numbers are excluded.' },
  { key: 'CREDIT_SCORE', name: 'Credit score', detects: 'A consumer credit or FICO score tied to a person or report. Bare three-digit numbers are excluded.' },
];

/** Other spellings the server or older data use for the same types. */
const SENSITIVE_ALIASES: Record<string, string> = {
  US_SSN: 'SSN',
  SOCIAL_SECURITY_NUMBER: 'SSN',
  DOB: 'DATE_OF_BIRTH',
  BIRTH_DATE: 'DATE_OF_BIRTH',
  CARD_NUMBER: 'CREDIT_CARD_NUMBER',
  PAYMENT_CARD_NUMBER: 'CREDIT_CARD_NUMBER',
};

function normalizeTypeKey(raw: string): string {
  const key = raw.trim().replace(/[\s-]+/g, '_').toUpperCase();
  return SENSITIVE_ALIASES[key] ?? key;
}

export function findSensitiveType(raw: string | null | undefined): SensitiveType | undefined {
  if (!raw) return undefined;
  const key = normalizeTypeKey(raw);
  return SENSITIVE_TYPES.find((t) => t.key === key);
}

/** The name for a sensitive type. An unknown type is shown in sentence case, never as a raw code. */
export function sensitiveTypeName(raw: string | null | undefined): string {
  if (!raw) return 'Sensitive value';
  return findSensitiveType(raw)?.name ?? sentenceCase(raw);
}

/* ---------- Review ---------- */

export const REVIEW_WORDS = {
  placeNotFound: 'place not found',
} as const;

/** Why a finding needs a person, in plain words. */
export const REASON_TEXT: Record<string, string> = {
  LOW_CONFIDENCE: 'LexaVault isn’t sure this text is sensitive.',
  LOCATION_UNCERTAIN: 'Sensitive text was found, but its exact place on the page is unclear.',
  POLICY_REVIEW: 'Your policy asks a person to confirm this type.',
};

export function reasonText(code: string | null | undefined): string {
  if (!code) return 'A person needs to confirm this.';
  return REASON_TEXT[code] ?? 'A person needs to confirm this.';
}

/* ---------- Search ---------- */

export interface FieldLabel {
  label: string;
  primary?: boolean;
  searchable?: boolean;
}

/**
 * The placeholder for case search, built from the configured case fields exactly as they are written (for example
 * "Loan number or Borrower name"). Falls back to "case" words when no field is configured; never a hard-coded name.
 */
export function caseSearchPlaceholder(fields: readonly FieldLabel[] | undefined, withActions = false): string {
  const list = fields ?? [];
  const primary = list.find((f) => f.primary);
  const searchable = list.filter((f) => f.searchable && f !== primary);
  const labels = [primary, ...searchable].filter((f): f is FieldLabel => Boolean(f && f.label.trim()))
    .slice(0, 2)
    .map((f) => f.label.trim());
  if (!labels.length) return withActions ? 'A case or an action…' : 'Filter cases';
  const subject = labels.join(' or ');
  return withActions ? `${subject}, or an action…` : `Filter by ${subject}`;
}

/* ---------- Helpers ---------- */

const ACRONYMS = new Set(['SSN', 'ID', 'EIN', 'ITIN', 'IBAN', 'TIN', 'VIN', 'NPI', 'ABA', 'FICO', 'SDV', 'PDF', 'API', 'URL']);

/** UPPER_SNAKE or Title Case to "Sentence case", keeping known acronyms. */
export function sentenceCase(raw: string): string {
  const words = raw.trim().split(/[_\s]+/).filter(Boolean);
  return words.map((w, i) => {
    if (ACRONYMS.has(w.toUpperCase())) return w.toUpperCase();
    const lower = w.toLowerCase();
    return i === 0 ? lower.charAt(0).toUpperCase() + lower.slice(1) : lower;
  }).join(' ');
}

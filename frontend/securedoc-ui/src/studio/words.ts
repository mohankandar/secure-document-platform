/**
 * One name for everything people see in the Case File design (docs/work/ui/SPEC.md, "Vocabulary").
 * Screens take their words from here; they do not spell names of stages, places or sensitive types themselves.
 * Plain TypeScript whose only import is the LV code registry (task 0025), so node's test runner can check it directly.
 */
import { hasLvCode, isLvCode, lvCodeFor, lvEntry, lvLabel } from './lvCodes.ts';

/** The brand link's accessible name. Branding without a name reads "LexaVault home", never "undefined home". */
export function brandHomeName(applicationName: string | null | undefined): string {
  const name = typeof applicationName === 'string' ? applicationName.trim() : '';
  return `${name || 'LexaVault'} home`;
}

/* ---------- Places in the app (routes stay as they are) ---------- */

export const NAV = {
  cases: 'Cases',
  newCase: 'New case',
  review: 'Review',
  /** Task 0029: the cases a destination refused (EXPORT_RESEND). */
  waiting: 'Waiting for you',
  reports: 'Reports',
  admin: 'Admin',
  support: 'Support',
} as const;

/**
 * U026 (WCAG 2.4.2 Page titled): the browser tab names the screen first, then the product, for example
 * "Review · LexaVault". The admin area comes from the address (/admin/<area>).
 */
const ADMIN_TITLES: Record<string, string> = {
  policy: 'Redaction & retention', fields: 'Case fields', integrations: 'Integrations', identity: 'Sign-in', health: 'Platform operations', audit: 'Audit trail',
};
export function screenTitle(pathname: string, product: string): string {
  const [first, second, third] = pathname.split('/').filter(Boolean);
  let screen: string;
  if (!first) screen = NAV.cases;
  else if (first === 'case') screen = `Case · ${NAV.cases}`;
  else if (first === 'intake') screen = NAV.newCase;
  else if (first === 'review') screen = NAV.review;
  else if (first === 'waiting') screen = NAV.waiting;
  else if (first === 'reports') screen = NAV.reports;
  else if (first === 'admin') screen = second && ADMIN_TITLES[second] ? `${ADMIN_TITLES[second]} · ${NAV.admin}` : NAV.admin;
  else if (first === 'support') screen = second === 'incidents' && third ? `Problem · ${NAV.support}` : second === 'export' ? `Export · ${NAV.support}` : NAV.support;
  else if (first === 'technical-exceptions') screen = `Problem · ${NAV.support}`;
  else if (first === 'packages' && third === 'manual-processing') screen = 'Continue manually';
  else screen = '';
  return screen ? `${screen} · ${product}` : product;
}

/* ---------- Stages ---------- */

export type StageWordKey = 'intake' | 'redacting' | 'review' | 'approval' | 'exporting' | 'delivered' | 'stopped' | 'unknown';

export const STAGE_NAMES: Record<StageWordKey, string> = {
  intake: 'New',
  redacting: 'Redacting',
  review: 'Review',
  approval: 'Approval',
  exporting: 'Exporting',
  delivered: 'Exported',
  stopped: 'Stopped',
  unknown: 'Unrecognized',
};

/** Task 0048: the processing step a case is at (case panel chip). Same words in the inline and staged pipelines. */
export const PROCESSING_STEP_WORDS = {
  text: 'Text',
  find: 'Find',
  review: 'Review',
  redact: 'Redact',
  exporting: 'Exporting',
} as const;

/** Task 0048: the line that holds the step chip, and what it means for someone who hovers it. */
export const PROCESSING_STEP_LINE = {
  label: 'Now at',
  hint: 'Text: reading the documents. Find: looking for sensitive data. Review: a person decides. Redact: blacking out and checking. Exporting: sending.',
} as const;

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
  /** The plural, for counts such as "2 more Dates of birth". */
  plural: string;
  /** What the detection finds, for the policy screen. */
  detects: string;
}

/**
 * The types the detection acts on (SensitiveContentPromptBuilder and the heuristic adapters). Each finds an exact
 * text span and redacts only that span; none redacts a whole page, image or document.
 */
export const SENSITIVE_TYPES: readonly SensitiveType[] = [
  { key: 'SSN', name: 'Social Security Number', plural: 'Social Security Numbers', detects: 'Full Social Security Numbers in text, including clearly labeled test values. Form, reference and case numbers are excluded.' },
  { key: 'DATE_OF_BIRTH', name: 'Date of birth', plural: 'Dates of birth', detects: 'A person’s birth date only. Signature, payment, effective and other dates are excluded.' },
  { key: 'CREDIT_CARD_NUMBER', name: 'Credit card number', plural: 'Credit card numbers', detects: 'Full 13–19 digit payment card numbers. Masked or last-four displays and account numbers are excluded.' },
  { key: 'CREDIT_SCORE', name: 'Credit score', plural: 'Credit scores', detects: 'A consumer credit or FICO score tied to a person or report. Bare three-digit numbers are excluded.' },
  // Task 0043: pattern kinds that are off for every entity until a Business Admin switches them on.
  { key: 'BANK_ROUTING_NUMBER', name: 'Bank routing number', plural: 'Bank routing numbers', detects: 'A 9-digit ABA routing number with a valid checksum, next to a routing label or on a check’s bottom line. Off by default.' },
  { key: 'BANK_ACCOUNT_NUMBER', name: 'Bank account number', plural: 'Bank account numbers', detects: 'A 4–17 digit number next to an account label, or the account field on a check’s bottom line. Phone numbers, dates and amounts are excluded. Off by default.' },
  { key: 'ITIN', name: 'ITIN', plural: 'ITINs', detects: 'An Individual Taxpayer Identification Number (9NN-NN-NNNN) next to an ITIN label. Kept apart from SSNs. Off by default.' },
  { key: 'EIN', name: 'EIN', plural: 'EINs', detects: 'A business’s Employer Identification Number (NN-NNNNNNN) next to an EIN or tax ID label. Off by default.' },
  { key: 'DRIVERS_LICENSE_NUMBER', name: 'Driver’s license number', plural: 'Driver’s license numbers', detects: 'A driver’s license number, only next to a driver’s license or DL label. Off by default.' },
  { key: 'PASSPORT_NUMBER', name: 'Passport number', plural: 'Passport numbers', detects: 'A passport number, only next to a passport label. Off by default.' },
  // Task 0064: found on the page image, not in its text.
  { key: 'SIGNATURE', name: 'Signature or initials', plural: 'Signatures and initials', detects: 'A handwritten signature or initials next to a signature or initials label, and any the vision model finds. Off by default.' },
  { key: 'FACE_PHOTO', name: 'Face photo', plural: 'Face photos', detects: 'A photo of a person’s face, for example on an ID copy. Found by the vision model only. Off by default.' },
];

/** Task 0064: not a type to switch on: a page LexaVault could not read and a person must look at. */
export const UNREAD_PAGE = 'UNREAD_PAGE';
const NOT_CONFIGURABLE: readonly SensitiveType[] = [
  { key: UNREAD_PAGE, name: 'Page to check', plural: 'Pages to check', detects: 'A page the vision model could not read.' },
  { key: 'GOVERNMENT_ID', name: 'Government ID', plural: 'Government IDs', detects: 'A driver’s license, ID card or passport number the vision model found.' },
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
  return SENSITIVE_TYPES.find((t) => t.key === key) ?? NOT_CONFIGURABLE.find((t) => t.key === key);
}

/** The name for a sensitive type. An unknown type is shown in sentence case, never as a raw code. */
export function sensitiveTypeName(raw: string | null | undefined): string {
  if (!raw) return 'Sensitive value';
  return findSensitiveType(raw)?.name ?? sentenceCase(raw);
}

/** The plural for counts. An unknown type becomes "<Name> findings" rather than a guessed plural. */
export function sensitiveTypePlural(raw: string | null | undefined): string {
  if (!raw) return 'Sensitive values';
  return findSensitiveType(raw)?.plural ?? `${sentenceCase(raw)} findings`;
}

/** One key per type, so aliases (US_SSN, SSN) count as the same type. */
export function sensitiveTypeKey(raw: string | null | undefined): string {
  return raw ? normalizeTypeKey(raw) : '';
}

/* ---------- Review ---------- */

export const REVIEW_WORDS = {
  placeNotFound: 'place not found',
  textFound: 'Text found',
} as const;

/** Task 0064: which path found an item, in words. Null when the server sent nothing usable (older findings). */
const FOUND_BY: Record<string, string> = {
  TEXT: 'Page text',
  SIGNATURE: 'Signature detector',
  VISION: 'Vision model',
  PAGE_CHECK: 'Page check',
};
export function foundByText(foundBy: readonly string[] | null | undefined): string | null {
  if (!Array.isArray(foundBy)) return null;
  const names = foundBy.map((path) => FOUND_BY[path]).filter((name): name is string => Boolean(name));
  return names.length > 0 ? names.join(' and ') : null;
}

/** Task 0064: what a person is asked about a page the vision model did not read. */
export const UNREAD_PAGE_WORDS = {
  why: 'This page needs the vision model, and it could not be read that way. Nothing on it was passed unread: look over the whole page.',
  hint: 'If it holds something to redact, drag one mark that covers all of it, then redact. If it holds nothing, keep it visible.',
} as const;

/* ---------- Continue manually ---------- */

/** The owner's U021 wording: on a tablet, marking by hand is possible but not the aim. Shown only on a touch device. */
export const MANUAL_WORDS = {
  touchNote: 'Marking values by hand works best on a computer.',
} as const;

/**
 * Why a person is asked, in one sentence that names the policy level when it applies (U004). The threshold is the
 * tenant's automatic-redaction level (0..1) from the review queue; without it the sentence leaves the number out.
 */
export function whyAsked(reason: string | null | undefined, confidence: number, threshold: number | null | undefined): string {
  const sure = `LexaVault is ${Math.round(confidence * 100)}% sure this is sensitive.`;
  switch (reason) {
    case 'LOW_CONFIDENCE':
      return typeof threshold === 'number' && threshold > 0 && threshold <= 1
        ? `${sure} Your policy redacts automatically from ${Math.round(threshold * 100)}%, so a person decides.`
        : `${sure} That is below your policy’s level for automatic redaction, so a person decides.`;
    case 'LOCATION_UNCERTAIN':
      return 'LexaVault found this text but not its exact place on the page, so a person decides.';
    case 'POLICY_REVIEW':
      return `${sure} Your policy sends findings like this to a person before anything is redacted.`;
    default:
      return reasonText(reason);
  }
}

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

/** The case filter's placeholder when no field is configured, or when the field names don't fit the box (U019). */
export const CASE_SEARCH_FALLBACK = 'Filter cases';

/**
 * The placeholder for case search, built from the configured case fields (for example "Filter by loan number or
 * borrower name"). Field names inside a sentence are lower case (fieldNameInSentence). Two names that end in the same
 * word say it once: "contract or member number" (U019). Falls back to "case" words when no field is configured;
 * never a hard-coded name.
 */
export function caseSearchPlaceholder(fields: readonly FieldLabel[] | undefined, withActions = false): string {
  const list = fields ?? [];
  const primary = list.find((f) => f.primary);
  const searchable = list.filter((f) => f.searchable && f !== primary);
  const labels = [primary, ...searchable].filter((f): f is FieldLabel => Boolean(f && f.label.trim()))
    .slice(0, 2)
    .map((f) => fieldNameInSentence(f.label));
  if (!labels.length) return withActions ? 'A case or an action…' : CASE_SEARCH_FALLBACK;
  const subject = joinFieldNames(labels);
  return withActions ? `${capitalizeFirst(subject)}, or an action…` : `Filter by ${subject}`;
}

/** "contract number" + "member number" → "contract or member number"; any other pair → "a or b". */
function joinFieldNames(names: readonly string[]): string {
  if (names.length !== 2) return names.join(' or ');
  const first = names[0].split(/\s+/);
  const second = names[1].split(/\s+/);
  const shared = first.length > 1 && second.length > 1 && first[first.length - 1] === second[second.length - 1];
  return shared ? `${first.slice(0, -1).join(' ')} or ${names[1]}` : names.join(' or ');
}

/**
 * The case filter's placeholders, longest first, for the box to try in order (owner's U019 answer 1): the full
 * wording ("Filter by loan number or borrower name"), then the main field alone ("Filter by loan number"), then
 * "Filter cases". No duplicates; always ends with the fallback.
 */
export function caseSearchPlaceholders(fields: readonly FieldLabel[] | undefined): string[] {
  const list = fields ?? [];
  const primary = list.find((f) => f.primary && f.label.trim());
  const candidates = [caseSearchPlaceholder(list), primary ? caseSearchPlaceholder([primary]) : CASE_SEARCH_FALLBACK, CASE_SEARCH_FALLBACK];
  return candidates.filter((text, i) => candidates.indexOf(text) === i);
}

/**
 * The first text whose measured width fits the room, otherwise the last text (fail closed: a width or room that
 * is unknown never fits, so the result is the last, shortest text).
 */
export function firstThatFits(texts: readonly string[], widthOf: (text: string) => number | null, room: number | null): string {
  const last = texts[texts.length - 1] ?? CASE_SEARCH_FALLBACK;
  if (room === null || !Number.isFinite(room)) return last;
  for (const text of texts.slice(0, -1)) {
    const width = widthOf(text);
    if (width !== null && Number.isFinite(width) && width <= room) return text;
  }
  return last;
}

/* ---------- Helpers ---------- */

const ACRONYMS = new Set(['SSN', 'ID', 'EIN', 'ITIN', 'IBAN', 'TIN', 'VIN', 'NPI', 'ABA', 'FICO', 'PDF', 'API', 'URL']);

/**
 * A configured case field name as it reads inside a sentence (owner, U014 review): "Loan number" becomes
 * "loan number", "Borrower Name" becomes "borrower name". Only plain capitalised or lower-case words are lowered;
 * known acronyms, all-capital words and mixed-case words ("SSN", "MERS", "eNote") keep their configured spelling.
 */
export function fieldNameInSentence(label: string): string {
  return label.trim().split(/(\s+)/).map((w) => {
    if (!w.trim() || ACRONYMS.has(w.toUpperCase())) return w;
    return /^\p{Lu}?[\p{Ll}\d'’-]*$/u.test(w) ? w.toLowerCase() : w;
  }).join('');
}

/** The first letter in upper case and the rest as written: for a sentence that starts with a field name. */
export function capitalizeFirst(text: string): string {
  return text.charAt(0).toUpperCase() + text.slice(1);
}

/** UPPER_SNAKE or Title Case to "Sentence case", keeping known acronyms. */
export function sentenceCase(raw: string): string {
  const words = raw.trim().split(/[_\s]+/).filter(Boolean);
  return words.map((w, i) => {
    if (ACRONYMS.has(w.toUpperCase())) return w.toUpperCase();
    const lower = w.toLowerCase();
    return i === 0 ? lower.charAt(0).toUpperCase() + lower.slice(1) : lower;
  }).join(' ');
}

/**
 * The heading over the sensitive types in a policy ("Sensitive types (4)"). In this product "entity" means only a
 * legal entity (the entity switch), never a kind of sensitive data.
 */
export const SENSITIVE_TYPES_HEADING = 'Sensitive types';

/** Task 0044: the entity's "searchable redacted pages" switch on the policy screen (on by default). */
export const SEARCHABLE_PAGES_WORDS = {
  label: 'Searchable redacted pages',
  hint: 'When on, a redacted page also gets hidden text read from the blacked-out image, so it can be searched and copied. The redacted values are never in that text. When off, redacted pages are images only.',
} as const;

/** Task 0064: the entity's vision settings. */
export const VISION_WORDS = {
  label: 'Read hard pages with the vision model',
  hint: 'Handwriting, ID copies, checks, photos and poor scans are read from the page image by a model on your own servers. When off, or when the model is not available, those pages go to a person. No page passes unread.',
  threshold: 'A scanned page is hard when its text-reading confidence is below',
} as const;

/* ---------- Columns on Cases (owner's U007 answer) ---------- */

/** LexaVault's own Cases columns, by the stable key the server sends (DashboardColumnConfigurationService). */
export const SYSTEM_COLUMN_NAMES: Record<string, string> = {
  received: 'Received',
  stage: 'Stage',
  documents: 'Documents',
  attention: 'Attention',
  intakeSource: 'Intake source',
  exportStatus: 'Export status',
  lastUpdated: 'Last updated',
  exportCycles: 'Export cycles',
};

/**
 * The name of a Cases column. A case field keeps its configured label as it is; a LexaVault column is named from
 * its key, and an unknown LexaVault column falls back to its server label in sentence case.
 */
export function dashboardColumnName(column: { key: string; label: string; kind: string }): string {
  if (column.kind === 'IDENTIFIER') return column.label;
  return SYSTEM_COLUMN_NAMES[column.key] ?? sentenceCase(column.label);
}

/* ---------- Cases rows and the case panel (UI task U003) ---------- */

/** The next step in words, shown in each Cases row instead of the small bars. */
export const NEXT_STEP = {
  waitingForDocuments: 'Waiting for documents',
  readyToSubmit: 'Ready to submit',
  queued: 'Queued',
  finding: 'Finding sensitive data…',
  processingAgain: 'Processing again…',
  waitingForReview: 'Waiting for review',
  readyToApprove: 'Redaction done, ready to approve',
  sent: 'Redacted and sent',
  stopped: 'Processing stopped',
  notAccepted: 'Not accepted at intake',
  unknown: 'Status not recognised',
} as const;

export function findingsToReview(count: number): string {
  return `${count} finding${count === 1 ? '' : 's'} to review`;
}

/** The one action a row offers, by kind. */
export const ACTION_WORDS = {
  review: 'Review',
  approve: 'Approve',
  retry: 'Retry',
  continueManually: 'Continue manually',
  processAgain: 'Process again',
} as const;

export const CASE_SECTIONS = {
  keeping: 'Keeping and deleting',
  details: 'Case details',
  whatWentWrong: 'What went wrong',
  sentTo: 'Sent to',
} as const;

/* ---------- Sensitivity levels (task 0021) ----------
 * The source system sets a level; LexaVault keeps it and passes it on, and never changes it. It limits nothing in
 * LexaVault, so it appears only as one read-only line in the case panel (no badge anywhere else). */

export const SENSITIVITY_LEVELS = ['STANDARD', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL'] as const;
export type SensitivityLevelCode = (typeof SENSITIVITY_LEVELS)[number];

export const SENSITIVITY_NAMES: Record<SensitivityLevelCode, string> = {
  STANDARD: 'Standard',
  CONFIDENTIAL: 'Confidential',
  HIGHLY_CONFIDENTIAL: 'Highly Confidential',
};

/**
 * Task 0023 A4: the value a level dropdown starts at. Standard by default everywhere: nothing saved, nothing sent, or a
 * value this screen does not know all preselect Standard (the server treats them the same way).
 */
export function sensitivityChoice(raw: string | null | undefined): SensitivityLevelCode {
  return raw && (SENSITIVITY_LEVELS as readonly string[]).includes(raw) ? raw as SensitivityLevelCode : 'STANDARD';
}

/** The plain name of a level code. Nothing sent means Standard. */
export function sensitivityName(raw: string | null | undefined): string {
  if (!raw) return SENSITIVITY_NAMES.STANDARD;
  return (SENSITIVITY_NAMES as Record<string, string>)[raw] ?? sentenceCase(raw);
}

/**
 * The case panel's one line: "Sensitivity: Confidential, as sent by the source system". It shows the case's own level
 * as sent (nothing sent = Standard), not the highest level in the case (owner's answer, task 0022 A1). A document whose
 * own level differs is marked next to its name instead (documentSensitivityNote).
 */
/** Task 0050: the redacted copy of a signed document. */
export const SIGNATURE_REMOVED_LINE = 'A digital signature was removed by redaction. The redacted copy is not signed.';

export function caseSensitivityLine(caseLevel: string | null | undefined): string {
  return `Sensitivity: ${sensitivityName(caseLevel)}, as sent by the source system`;
}

/** A document's own level, shown next to it only when it differs from the case's level; otherwise null. */
export function documentSensitivityNote(documentLevel: string | null | undefined, caseLevel: string | null | undefined): string | null {
  const doc = documentLevel || 'STANDARD';
  const kase = caseLevel || 'STANDARD';
  return doc === kase ? null : sensitivityName(doc);
}

export const SENSITIVITY_FIELD = {
  label: 'Sensitivity',
  hint: 'Passed on unchanged when the case is sent.',
  folderLabel: 'Sensitivity of files from this folder',
  folderHint: 'Every file picked up from this folder gets this level.',
} as const;

/** Where a case was delivered, from the lifecycle's destination type ("document-store", "local-folder"). */
export function destinationName(raw: string | null | undefined): string {
  if (!raw) return 'its destination';
  const key = raw.trim().toLowerCase();
  if (key === 'document-store') return 'the document store';
  if (key === 'local-folder' || key.includes('folder') || key.includes('filesystem')) return 'the delivery folder';
  return sentenceCase(raw.replace(/-/g, ' '));
}

/** "2 of 3 runs left": how many more times a delivered case can be processed again. */
export function runsLeft(used: number, maximum: number): string {
  const left = Math.max(0, maximum - used);
  return `${left} of ${maximum} run${maximum === 1 ? '' : 's'} left`;
}

/* ---------- Processing problems (UI task U012; task 0025: words from the LV code registry) ---------- */

export interface ProblemReason {
  /** The registry title of the LV code, for example "Text reading timed out". */
  title: string;
  /** One plain sentence for "What happened": the registry's short description of the code. */
  sentence: string;
  /** False when the reason has no LV code of its own: it reads as LV-SYS-001 and the reason stays under Technical details. */
  known: boolean;
  /** The LexaVault error code, for example "LV-PRC-004". */
  code: string;
  /** "Text reading timed out · LV-PRC-004": the Support row, the problem page title and the case panel. */
  label: string;
}

/**
 * A processing problem in words. The server's lvCode wins when it sent a known one; otherwise the reason code
 * (TechnicalFailureCode) is mapped through the generated registry; anything else reads as LV-SYS-001.
 * There is no second hand-written list: every word comes from lvErrorCodes.generated.ts.
 */
export function problemReason(reasonCode: string | null | undefined, lvCode?: string | null): ProblemReason {
  const code = lvCodeFor(reasonCode, lvCode);
  const entry = lvEntry(code);
  return { title: entry.title, sentence: entry.description, known: isLvCode(lvCode) || hasLvCode(reasonCode), code, label: lvLabel(code) };
}

/** Where in processing a problem happened (ProcessingStage), for "at text recognition". */
export const PROCESSING_STAGE_WORDS: Record<string, string> = {
  INTAKE: 'receiving',
  TEXT_EXTRACTION: 'reading the text',
  OCR: 'text recognition',
  AI_ANALYSIS: 'AI analysis',
  SENSITIVE_DETECTION: 'finding sensitive data',
  DOCUMENT_PROFILE: 'recognising the document type',
  IDENTIFIER_EXTRACTION: 'reading the case fields',
  MANUAL_REVIEW: 'review',
  REDACTION: 'redacting',
  VERIFICATION: 'checking the redaction',
  DERIVED_DOCUMENT_SPLIT: 'splitting the documents',
  ROUTING: 'choosing where to send it',
  DELIVERY: 'sending',
  STORAGE: 'storing documents',
  RETENTION: 'keeping and deleting',
  MIGRATION_REVALIDATION: 'checking again after an update',
  DOCUMENT_PROCESSING: 'processing',
};

export function processingStageWords(stage: string | null | undefined): string {
  return (stage && PROCESSING_STAGE_WORDS[stage.trim().toUpperCase()]) || 'processing';
}

/**
 * UI task U007: the plain-word rules of Admin › Redaction & retention and Case fields (UI spec findings 11, 12, 13,
 * 17, 23). Kept free of React so they run under `node --test`.
 */

// ---------------------------------------------------------------- durations

export interface Unit { name: string; one: string; factor: number }

/** Units for a value stored in hours. */
export const HOUR_UNITS: readonly Unit[] = [
  { name: 'hours', one: 'hour', factor: 1 },
  { name: 'days', one: 'day', factor: 24 },
];

/** Units for a value stored in minutes. */
export const MINUTE_UNITS: readonly Unit[] = [
  { name: 'minutes', one: 'minute', factor: 1 },
  { name: 'hours', one: 'hour', factor: 60 },
  { name: 'days', one: 'day', factor: 1440 },
];

/** The largest unit the stored value is a whole number of; the smallest unit otherwise. */
export function pickUnit(base: number, units: readonly Unit[]): Unit {
  const fits = units.filter((u) => Number.isInteger(base) && base >= u.factor && base % u.factor === 0);
  return fits.length ? fits[fits.length - 1] : units[0];
}

/**
 * What the person typed, in the stored unit. NaN when it is not a whole number of the stored unit (for example
 * 1.3 hours stored in minutes is 78 minutes and fine; 0.5 hours stored in hours is not), so it can never be
 * rounded into something the person did not enter.
 */
export function toBase(value: number, unit: Unit): number {
  if (!Number.isFinite(value)) return Number.NaN;
  const base = value * unit.factor;
  const rounded = Math.round(base);
  return Math.abs(base - rounded) < 1e-9 ? rounded : Number.NaN;
}

/** "72 hours", "1 day", "30 days", "90 minutes". */
export function durationText(base: number, units: readonly Unit[]): string {
  const u = pickUnit(base, units);
  const v = base / u.factor;
  return `${v} ${v === 1 ? u.one : u.name}`;
}

// ---------------------------------------------------------------- AI version

export interface BundleLike { version: string; promotedAt?: string | null }

const MONTHS = ['January', 'February', 'March', 'April', 'May', 'June', 'July', 'August', 'September', 'October', 'November', 'December'];
const SHORT = MONTHS.map((m) => m.slice(0, 3));

export interface BundleName {
  /** "November 2026 update", or the version itself when nothing gives a month. */
  name: string;
  /** "Nov 1, 2026", when the bundle has a release date. */
  released: string | null;
  /** Where the name came from, so the screen never claims more than it knows. */
  from: 'version' | 'release date' | 'version only';
}

function parts(iso: string | null | undefined, timeZone?: string) {
  if (!iso) return null;
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return null;
  const f = new Intl.DateTimeFormat('en-US', { year: 'numeric', month: 'numeric', day: 'numeric', timeZone });
  const p = Object.fromEntries(f.formatToParts(d).map((x) => [x.type, x.value]));
  return { y: Number(p.year), m: Number(p.month), d: Number(p.day) };
}

/**
 * The plain name of an AI version. Bundles have no display name, so it is derived:
 * 1. a year and month in the version string ("2026.11.1", "2026-11-r2") give "November 2026 update";
 * 2. otherwise the release (promotion) date gives the month;
 * 3. otherwise the version string is shown as it is.
 */
export function bundleName(b: BundleLike, timeZone?: string): BundleName {
  const rel = parts(b.promotedAt, timeZone);
  const released = rel ? `${SHORT[rel.m - 1]} ${rel.d}, ${rel.y}` : null;
  const m = /(?:^|[^0-9])(20\d\d)[.\-_/](0?[1-9]|1[0-2])(?![0-9])/.exec(b.version);
  if (m) return { name: `${MONTHS[Number(m[2]) - 1]} ${m[1]} update`, released, from: 'version' };
  if (rel) return { name: `${MONTHS[rel.m - 1]} ${rel.y} update`, released, from: 'release date' };
  return { name: b.version, released: null, from: 'version only' };
}

/** Names for a list of versions; two versions that would read the same also show their version. */
export function bundleNames(bundles: readonly BundleLike[], timeZone?: string): Map<string, BundleName> {
  const named = bundles.map((b) => [b.version, bundleName(b, timeZone)] as const);
  const counts = new Map<string, number>();
  for (const [, n] of named) counts.set(n.name, (counts.get(n.name) ?? 0) + 1);
  return new Map(named.map(([v, n]) => [v, (counts.get(n.name) ?? 0) > 1 && n.name !== v ? { ...n, name: `${n.name} (${v})` } : n]));
}

/** "November 2026 update · released Nov 1, 2026". */
export const bundleLabel = (n: BundleName) => (n.released ? `${n.name} · released ${n.released}` : n.name);

// ---------------------------------------------------------------- case field rules

export interface FieldRuleInput {
  validationType: string;
  exactLength?: number | null;
  minLength?: number | null;
  maxLength?: number | null;
  caseNormalization: string;
  stripSeparators: boolean;
  allowedValues: string[];
  customPattern?: string | null;
  currency?: string | null;
  sensitive?: boolean;
  multiValue?: boolean;
}

const TYPE_WORDS: Record<string, string> = {
  TEXT: 'Text',
  NUMBER: 'Whole number',
  DECIMAL: 'Number with decimals',
  MONEY: 'Amount',
  DATE: 'Date',
  DATE_TIME: 'Date and time',
  EMAIL: 'Email address',
  UUID: 'System ID',
  ADDRESS: 'US address',
  CUSTOM_PATTERN: 'Must match a set pattern',
};

/** "Numbers only" and "Letters and numbers" are a type with a pattern (the same texts as identity.ts). */
export function fieldKind(i: Pick<FieldRuleInput, 'validationType' | 'customPattern'>): string {
  if (i.validationType === 'NUMBER' && i.customPattern === '[0-9]+') return 'DIGITS';
  if (i.validationType === 'TEXT' && i.customPattern === '[A-Za-z0-9]+') return 'LETTERS_DIGITS';
  return i.validationType;
}
const KIND_WORDS: Record<string, string> = { ...TYPE_WORDS, DIGITS: 'Numbers only', LETTERS_DIGITS: 'Letters and numbers' };

const characters = (n: number) => `${n} character${n === 1 ? '' : 's'}`;

/** A case field's rule, spelled out: "Letters and numbers · 6–12 characters · saved in capitals · spaces and dashes removed". */
export function fieldRule(i: FieldRuleInput): string {
  const values = i.allowedValues.filter((v) => v.trim()).length;
  const valuesText = `one of ${values} allowed value${values === 1 ? '' : 's'}`;
  const out: string[] = [];
  if (i.validationType === 'DROPDOWN') out.push(valuesText);
  else out.push((KIND_WORDS[fieldKind(i)] ?? 'Text') + (i.validationType === 'MONEY' && i.currency ? ` in ${i.currency}` : ''));
  if (i.exactLength != null) out.push(`exactly ${characters(i.exactLength)}`);
  else if (i.minLength != null && i.maxLength != null) out.push(i.minLength === i.maxLength ? `exactly ${characters(i.minLength)}` : `${i.minLength}–${i.maxLength} characters`);
  else if (i.minLength != null) out.push(`at least ${characters(i.minLength)}`);
  else if (i.maxLength != null) out.push(`up to ${characters(i.maxLength)}`);
  if (i.multiValue) out.push('several values');
  if (i.caseNormalization === 'UPPER') out.push('saved in capitals');
  if (i.caseNormalization === 'LOWER') out.push('saved in lower case');
  if (i.stripSeparators) out.push('spaces and dashes removed');
  if (i.sensitive) out.push('sensitive');
  const text = out.join(' · ');
  return text.charAt(0).toUpperCase() + text.slice(1);
}

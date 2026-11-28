import type { IdentifierDefinition } from '../types';

/**
 * Task 0061: one field's values as one text, as the server takes them: several values are separated by U+001E, the parts
 * of an address (street, second line, city, state, ZIP, country) by U+001F.
 */
export const VALUE_SEPARATOR = String.fromCharCode(0x1e);
export const PART_SEPARATOR = String.fromCharCode(0x1f);
/** Shown in place of a sensitive value that is not read back. */
export const HIDDEN_VALUE = String.fromCharCode(0x2022).repeat(6);
/** The pattern of "Numbers only" and "Letters and numbers". */
export const DIGITS_PATTERN = '[0-9]+';
export const LETTERS_DIGITS_PATTERN = '[A-Za-z0-9]+';

export interface AddressParts { street: string; street2: string; city: string; state: string; zip: string }

export function readAddress(text: string): AddressParts {
  const [street = '', street2 = '', city = '', state = '', zip = ''] = text.split(PART_SEPARATOR);
  return { street, street2, city, state, zip };
}

export function writeAddress(a: AddressParts): string {
  const parts = [a.street, a.street2, a.city, a.state, a.zip];
  return parts.some((p) => p.trim()) ? [...parts, ''].join(PART_SEPARATOR) : '';
}

export const readValues = (text: string): string[] => (text === '' ? [''] : text.split(VALUE_SEPARATOR));
export const writeValues = (values: string[]): string => values.join(VALUE_SEPARATOR);

export function sortDefinitions(definitions: IdentifierDefinition[] | undefined): IdentifierDefinition[] {
  return [...(definitions ?? [])].sort((a, b) => Number(b.primary) - Number(a.primary) || a.displayOrder - b.displayOrder);
}

/** "2026-11-21T10:15" from a date and time input, with this computer's offset: the server needs the moment. */
function withOffset(local: string): string {
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2})?$/.test(local)) return local;
  const minutes = -new Date(local).getTimezoneOffset();
  if (Number.isNaN(minutes)) return local;
  const two = (n: number) => String(n).padStart(2, '0');
  return `${local}${local.length === 16 ? ':00' : ''}${minutes < 0 ? '-' : '+'}${two(Math.floor(Math.abs(minutes) / 60))}:${two(Math.abs(minutes) % 60)}`;
}

function canonicalOne(definition: IdentifierDefinition, raw: string): string {
  if (definition.validationType === 'ADDRESS') {
    const a = readAddress(raw);
    return writeAddress({ street: a.street.trim(), street2: a.street2.trim(), city: a.city.trim(), state: a.state.trim().toUpperCase(), zip: a.zip.trim() });
  }
  let value = raw.trim().replace(/\s+/g, ' ');
  if (definition.validationType === 'DATE_TIME') return withOffset(value);
  if (definition.stripSeparators) value = value.replace(/[ -]/g, '');
  if (definition.caseNormalization === 'UPPER') value = value.toUpperCase();
  if (definition.caseNormalization === 'LOWER') value = value.toLowerCase();
  return value;
}

export function canonicalValue(definition: IdentifierDefinition, raw: string): string {
  if (raw === HIDDEN_VALUE) return raw;
  return writeValues(readValues(raw).map((one) => canonicalOne(definition, one)).filter(Boolean));
}

/**
 * Mirrors the portable server-side rules. A custom pattern is intentionally left to the backend because its Java regex
 * dialect is not guaranteed to match JavaScript's.
 */
function oneProblem(definition: IdentifierDefinition, value: string): string | null {
  const pattern = definition.customPattern;
  switch (definition.validationType) {
    case 'NUMBER':
      if (pattern === DIGITS_PATTERN && !/^[0-9]+$/.test(value)) return 'Digits only';
      if (!/^-?[0-9]+$/.test(value)) return 'Enter a whole number';
      break;
    case 'DECIMAL': if (!/^-?[0-9]+(\.[0-9]+)?$/.test(value)) return 'Enter a number'; break;
    case 'MONEY': if (!/^([A-Z]{3} ?|\$)?-?[0-9][0-9,]*(\.[0-9]+)?$/i.test(value)) return 'Enter an amount'; break;
    case 'DATE': if (!/^(\d{4}-\d{2}-\d{2}|\d{1,2}\/\d{1,2}\/\d{4})$/.test(value)) return 'Enter a date'; break;
    case 'DATE_TIME': if (!/^\d{4}-\d{2}-\d{2}T.+(Z|[+-]\d{2}:\d{2})$/.test(value)) return 'Enter a date and time'; break;
    case 'EMAIL': if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return 'Enter a valid email address'; break;
    case 'UUID': if (!/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(value)) return 'Enter a valid UUID'; break;
    case 'ADDRESS': {
      const a = readAddress(value);
      if (!a.street || !a.city) return 'Enter the street and city';
      if (!/^[A-Z]{2}$/.test(a.state)) return 'Two-letter state';
      if (!/^\d{5}(-?\d{4})?$/.test(a.zip)) return 'ZIP: 5 or 9 digits';
      return null;
    }
    case 'TEXT': if (pattern === LETTERS_DIGITS_PATTERN && !/^[A-Za-z0-9]+$/.test(value)) return 'Letters and digits only'; break;
    default: break;
  }
  if (definition.exactLength != null && value.length !== definition.exactLength) return `Exactly ${definition.exactLength} characters`;
  if (definition.minLength != null && value.length < definition.minLength) return `At least ${definition.minLength} characters`;
  if (definition.maxLength != null && value.length > definition.maxLength) return `No more than ${definition.maxLength} characters`;
  if (definition.allowedValues.length > 0) {
    const allowed = definition.allowedValues.map((item) => canonicalOne(definition, item));
    if (!allowed.includes(value)) return `One of: ${definition.allowedValues.join(', ')}`;
  }
  return null;
}

export function validateValue(definition: IdentifierDefinition, raw: string): string | null {
  const value = canonicalValue(definition, raw);
  if (!value) return definition.required ? 'Required' : null;
  if (value === HIDDEN_VALUE) return null;
  const values = readValues(value);
  for (const one of values) {
    const problem = oneProblem(definition, one);
    if (problem) return problem;
  }
  if (new Set(values).size !== values.length) return 'The same value twice';
  return null;
}

export function identityErrors(definitions: IdentifierDefinition[], values: Record<string, string>): Record<string, string> {
  const errors: Record<string, string> = {};
  for (const definition of definitions) {
    const error = validateValue(definition, values[definition.key] ?? '');
    if (error) errors[definition.key] = error;
  }
  return errors;
}

export function canonicalIdentity(definitions: IdentifierDefinition[], values: Record<string, string>): Record<string, string> {
  const identity: Record<string, string> = {};
  for (const definition of definitions) {
    const value = canonicalValue(definition, values[definition.key] ?? '');
    if (value) identity[definition.key] = value;
  }
  return identity;
}

/** Task 0061: the keys whose values the Scan Bridge keeps in memory only. */
export const sensitiveKeys = (definitions: IdentifierDefinition[]): string[] => definitions.filter((d) => d.sensitive).map((d) => d.key);

/** Task 0113 (U10): what people see for a stored value: its display name, or the code itself when it has none. */
export function valueName(definition: Pick<IdentifierDefinition, 'allowedValueNames'>, code: string): string {
  return definition.allowedValueNames?.[code] || code;
}

/**
 * Task 0113 (U10): the choices of a dropdown in the order people see them: the Business Admin's order once values have
 * names, the codes' order otherwise (task 0111).
 */
export function choiceOrder(definition: Pick<IdentifierDefinition, 'allowedValues' | 'allowedValueNames'>,
                            byCode: (codes: string[]) => string[]): string[] {
  const named = Object.keys(definition.allowedValueNames ?? {}).length > 0;
  return named ? [...definition.allowedValues] : byCode(definition.allowedValues);
}

import type { IdentifierDefinition } from '../types';

export function sortDefinitions(definitions: IdentifierDefinition[] | undefined): IdentifierDefinition[] {
  return [...(definitions ?? [])].sort((a, b) => Number(b.primary) - Number(a.primary) || a.displayOrder - b.displayOrder);
}

export function canonicalValue(definition: IdentifierDefinition, raw: string): string {
  let value = raw.trim().replace(/\s+/g, ' ');
  if (definition.stripSeparators) value = value.replace(/[ -]/g, '');
  if (definition.caseNormalization === 'UPPER') value = value.toUpperCase();
  if (definition.caseNormalization === 'LOWER') value = value.toLowerCase();
  return value;
}

/**
 * Mirrors the portable server-side identifier rules. CUSTOM_PATTERN is intentionally left to the
 * backend because its Java regex dialect is not guaranteed to match JavaScript's.
 */
export function validateValue(definition: IdentifierDefinition, raw: string): string | null {
  if (!raw.trim()) return definition.required ? 'Required' : null;
  const value = canonicalValue(definition, raw);
  if (definition.validationType === 'NUMERIC' && !/^[0-9]+$/.test(value)) return 'Digits only';
  if (definition.validationType === 'ALPHANUMERIC' && !/^[A-Za-z0-9]+$/.test(value)) return 'Letters and digits only';
  if (definition.validationType === 'EMAIL' && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value)) return 'Enter a valid email address';
  if (definition.validationType === 'UUID' && !/^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$/.test(value)) return 'Enter a valid UUID';
  if (definition.exactLength != null && value.length !== definition.exactLength) return `Exactly ${definition.exactLength} characters`;
  if (definition.minLength != null && value.length < definition.minLength) return `At least ${definition.minLength} characters`;
  if (definition.maxLength != null && value.length > definition.maxLength) return `No more than ${definition.maxLength} characters`;
  if (definition.allowedValues.length > 0) {
    const allowed = definition.allowedValues.map((item) => canonicalValue(definition, item));
    if (!allowed.includes(value)) return `One of: ${definition.allowedValues.join(', ')}`;
  }
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

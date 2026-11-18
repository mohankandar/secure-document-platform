import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { NAV, SENSITIVE_TYPES, STAGE_NAMES, caseSearchPlaceholder, findSensitiveType, sensitiveTypeName } from './words.ts';

const here = dirname(fileURLToPath(import.meta.url));
const repoRoot = resolve(here, '..', '..', '..', '..');

/** The sensitive types a tenant policy can enable, read from the backend (TenantRedactionPolicy.V1_CONFIGURABLE_TYPES). */
function configurableTypes(): string[] {
  const source = readFileSync(resolve(repoRoot, 'backend', 'securedoc-domain', 'src', 'main', 'java', 'com', 'securedoc',
    'platform', 'domain', 'business', 'TenantRedactionPolicy.java'), 'utf8');
  const block = /V1_CONFIGURABLE_TYPES\s*=\s*Set\.of\(([^)]*)\)/s.exec(source);
  assert.ok(block, 'V1_CONFIGURABLE_TYPES must be readable from TenantRedactionPolicy.java');
  return [...block[1].matchAll(/EntityType\.([A-Z_]+)/g)].map((m) => m[1]);
}

test('every sensitive type the policy can enable has a name in the vocabulary', () => {
  const types = configurableTypes();
  assert.ok(types.length >= 4, `expected the configurable types, found ${types.join(', ')}`);
  for (const type of types) {
    const known = findSensitiveType(type);
    assert.ok(known, `${type} has no name in words.ts`);
    assert.ok(known.detects.length > 0, `${type} has no description for the policy screen`);
  }
});

test('one plain name per sensitive type, whatever spelling the server sends', () => {
  for (const spelling of ['SSN', 'Ssn', 'ssn', 'US_SSN', 'Us SSN']) assert.equal(sensitiveTypeName(spelling), 'Social Security Number');
  assert.equal(sensitiveTypeName('DATE_OF_BIRTH'), 'Date of birth');
  assert.equal(sensitiveTypeName('Date Of Birth'), 'Date of birth');
  assert.equal(sensitiveTypeName('CREDIT_CARD_NUMBER'), 'Credit card number');
  assert.equal(sensitiveTypeName('CREDIT_SCORE'), 'Credit score');
  assert.equal(sensitiveTypeName('MEDICAL_RECORD'), 'Medical record', 'an unknown type is sentence case, never a raw code');
  const names = SENSITIVE_TYPES.map((t) => t.name);
  assert.equal(new Set(names).size, names.length, 'names are unique');
});

test('stage and place names follow the spec', () => {
  assert.equal(STAGE_NAMES.intake, 'New');
  assert.equal(STAGE_NAMES.stopped, 'Stopped');
  assert.equal(NAV.cases, 'Cases');
  assert.equal(NAV.newCase, 'New case');
});

test('search placeholders use the configured field labels, never "contract"', () => {
  const fields = [
    { label: 'Loan number', primary: true, searchable: true },
    { label: 'Borrower name', primary: false, searchable: true },
  ];
  assert.equal(caseSearchPlaceholder(fields), 'Filter by Loan number or Borrower name');
  assert.equal(caseSearchPlaceholder(fields, true), 'Loan number or Borrower name, or an action…');
  assert.equal(caseSearchPlaceholder([]), 'Filter cases');
  assert.equal(caseSearchPlaceholder(undefined, true), 'A case or an action…');
  for (const text of [caseSearchPlaceholder(fields), caseSearchPlaceholder([]), caseSearchPlaceholder([], true)]) {
    assert.doesNotMatch(text, /contract/i);
  }
});

import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { SENSITIVITY_LEVELS, caseSensitivityLine, documentSensitivityNote, problemReason, sensitivityName } from './words.ts';
import { readdirSync } from 'node:fs';
import { join } from 'node:path';
import { MANUAL_WORDS, NAV, SENSITIVE_TYPES, brandHomeName, SENSITIVE_TYPES_HEADING, STAGE_NAMES, CASE_SEARCH_FALLBACK, firstThatFits, caseSearchPlaceholder, caseSearchPlaceholders, dashboardColumnName, fieldNameInSentence, findSensitiveType, sensitiveTypeName, screenTitle } from './words.ts';

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

test('two field names that end in the same word say it once (U019)', () => {
  const fields = [
    { label: 'Contract Number', primary: true, searchable: true },
    { label: 'Member Number', primary: false, searchable: true },
  ];
  assert.equal(caseSearchPlaceholder(fields), 'Filter by contract or member number');
  assert.equal(caseSearchPlaceholder(fields, true), 'Contract or member number, or an action…');
  // A shared word only when both names have more than one word, and the last words match exactly.
  assert.equal(caseSearchPlaceholder([{ label: 'Number', primary: true }, { label: 'Member number', searchable: true }]),
    'Filter by number or member number');
  assert.equal(caseSearchPlaceholder([{ label: 'Loan number', primary: true }, { label: 'SSN last 4', searchable: true }]),
    'Filter by loan number or SSN last 4');
});

test('the filter tries the full wording, then the main field alone, then "Filter cases" (owner\'s U019 answer 1)', () => {
  const loan = [
    { label: 'Loan number', primary: true, searchable: true },
    { label: 'Borrower name', primary: false, searchable: true },
  ];
  assert.deepEqual(caseSearchPlaceholders(loan), ['Filter by loan number or borrower name', 'Filter by loan number', 'Filter cases']);
  assert.deepEqual(caseSearchPlaceholders([{ label: 'Loan number', primary: true }]), ['Filter by loan number', 'Filter cases']);
  assert.deepEqual(caseSearchPlaceholders([{ label: 'Borrower name', searchable: true }]), ['Filter by borrower name', 'Filter cases']);
  assert.deepEqual(caseSearchPlaceholders([]), ['Filter cases']);
  assert.deepEqual(caseSearchPlaceholders(undefined), ['Filter cases']);
});

test('the filter placeholder is the first text that fits; unknown widths fall back to the last (U019, U020)', () => {
  const texts = ['Filter by loan number or borrower name', 'Filter by loan number', CASE_SEARCH_FALLBACK];
  const widths: Record<string, number> = { [texts[0]]: 249.5, [texts[1]]: 140, [texts[2]]: 80 };
  const widthOf = (t: string) => widths[t] ?? null;
  assert.equal(firstThatFits(texts, widthOf, 280), texts[0]);
  assert.equal(firstThatFits(texts, widthOf, 249.5), texts[0]);
  assert.equal(firstThatFits(texts, widthOf, 249), texts[1]);
  assert.equal(firstThatFits(texts, widthOf, 100), 'Filter cases');
  assert.equal(firstThatFits(texts, () => null, 280), 'Filter cases');
  assert.equal(firstThatFits(texts, widthOf, Number.NaN), 'Filter cases');
  assert.equal(firstThatFits(texts, widthOf, null), 'Filter cases');
});

test('search placeholders use the configured field labels, never "contract"', () => {
  const fields = [
    { label: 'Loan number', primary: true, searchable: true },
    { label: 'Borrower name', primary: false, searchable: true },
  ];
  assert.equal(caseSearchPlaceholder(fields), 'Filter by loan number or borrower name');
  assert.equal(caseSearchPlaceholder(fields, true), 'Loan number or borrower name, or an action…');
  assert.equal(caseSearchPlaceholder([{ label: 'Borrower Name', primary: true }, { label: 'SSN last 4', searchable: true }]),
    'Filter by borrower name or SSN last 4');
  assert.equal(caseSearchPlaceholder([]), 'Filter cases');
  assert.equal(caseSearchPlaceholder(undefined, true), 'A case or an action…');
  for (const text of [caseSearchPlaceholder(fields), caseSearchPlaceholder([]), caseSearchPlaceholder([], true)]) {
    assert.doesNotMatch(text, /contract/i);
  }
});

test('field names inside a sentence are lower case; acronyms and mixed-case words keep their spelling', () => {
  assert.equal(fieldNameInSentence('Loan number'), 'loan number');
  assert.equal(fieldNameInSentence(' Borrower Name '), 'borrower name');
  assert.equal(fieldNameInSentence('Contract ID'), 'contract ID');
  assert.equal(fieldNameInSentence('MERS number'), 'MERS number');
  assert.equal(fieldNameInSentence('eNote reference'), 'eNote reference');
  assert.equal(fieldNameInSentence("Owner's name"), "owner's name");
});

test('sensitive types are never called entities (an entity is only a legal entity)', () => {
  assert.equal(SENSITIVE_TYPES_HEADING, 'Sensitive types');
  assert.doesNotMatch(SENSITIVE_TYPES_HEADING, /entit/i);
});

test('Columns on Cases: LexaVault columns by key in sentence case, case fields as configured', () => {
  const sys = (key: string, label: string) => dashboardColumnName({ key, label, kind: 'SYSTEM' });
  assert.equal(sys('intakeSource', 'Intake Source'), 'Intake source');
  assert.equal(sys('exportStatus', 'Export Status'), 'Export status');
  assert.equal(sys('lastUpdated', 'Last Updated'), 'Last updated');
  assert.equal(sys('exportCycles', 'Export Cycles'), 'Export cycles');
  // The key decides, not the label the server sends.
  assert.equal(sys('received', 'RECEIVED AT'), 'Received');
  // An unknown LexaVault column falls back to its label in sentence case.
  assert.equal(sys('newColumn', 'Delivery Folder'), 'Delivery folder');
  // A configured case field is shown exactly as configured.
  assert.equal(dashboardColumnName({ key: 'identifier:x', label: 'Loan Number SDV', kind: 'IDENTIFIER' }), 'Loan Number SDV');
});

test('Cases groups and case actions take their words from words.ts, not their own spelling (U018 follow-up)', () => {
  const desk = readFileSync(resolve(here, 'Desk.tsx'), 'utf8');
  for (const word of ['Needs you', 'Stopped', 'Delivered']) {
    assert.doesNotMatch(desk, new RegExp(`'${word}'|>${word}\b`), `Desk.tsx spells "${word}" itself`);
  }
  const data = readFileSync(resolve(here, 'data.ts'), 'utf8');
  for (const word of ['Process again', 'Continue manually']) {
    assert.doesNotMatch(data, new RegExp(`'${word}'`), `data.ts spells "${word}" itself`);
  }
});

test('the brand link is named after the application, or "LexaVault home" when branding sends no name', () => {
  assert.equal(brandHomeName('Acme Redact'), 'Acme Redact home');
  assert.equal(brandHomeName('  Acme  '), 'Acme home');
  for (const missing of [undefined, null, '', '   ']) assert.equal(brandHomeName(missing), 'LexaVault home', String(missing));
});

test("U021: the Continue manually touch note is the owner's wording", () => {
  assert.equal(MANUAL_WORDS.touchNote, 'Marking values by hand works best on a computer.');
});

test('U026: every screen has its own page title, the product name last', () => {
  assert.equal(screenTitle('/', 'LexaVault'), 'Cases · LexaVault');
  assert.equal(screenTitle('/case/abc', 'LexaVault'), 'Case · Cases · LexaVault');
  assert.equal(screenTitle('/intake', 'LexaVault'), 'New case · LexaVault');
  assert.equal(screenTitle('/review', 'LexaVault'), 'Review · LexaVault');
  assert.equal(screenTitle('/reports', 'LexaVault'), 'Reports · LexaVault');
  assert.equal(screenTitle('/admin/policy', 'LexaVault'), 'Redaction & retention · Admin · LexaVault');
  assert.equal(screenTitle('/admin/audit', 'LexaVault'), 'Audit trail · Admin · LexaVault');
  assert.equal(screenTitle('/admin', 'LexaVault'), 'Admin · LexaVault');
  assert.equal(screenTitle('/support/incidents', 'Northwind'), 'Support · Northwind');
  assert.equal(screenTitle('/support/incidents/x', 'LexaVault'), 'Problem · Support · LexaVault');
  assert.equal(screenTitle('/technical-exceptions/x', 'LexaVault'), 'Problem · Support · LexaVault');
  assert.equal(screenTitle('/packages/x/manual-processing', 'LexaVault'), 'Continue manually · LexaVault');
  assert.equal(screenTitle('/somewhere', 'LexaVault'), 'LexaVault');
});

/* ---------- Task 0021: sensitivity levels ---------- */

test('0021: each level has its plain name, and nothing sent is Standard', () => {
  assert.deepEqual([...SENSITIVITY_LEVELS], ['STANDARD', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL']);
  assert.equal(sensitivityName('STANDARD'), 'Standard');
  assert.equal(sensitivityName('CONFIDENTIAL'), 'Confidential');
  assert.equal(sensitivityName('HIGHLY_CONFIDENTIAL'), 'Highly Confidential');
  assert.equal(sensitivityName(null), 'Standard');
  assert.equal(sensitivityName(undefined), 'Standard');
  assert.equal(sensitivityName(''), 'Standard');
});

test('0021/0022: the case panel line is the owner’s wording with the case’s own level, Standard when none was sent', () => {
  assert.equal(caseSensitivityLine('HIGHLY_CONFIDENTIAL'), 'Sensitivity: Highly Confidential, as sent by the source system');
  assert.equal(caseSensitivityLine('CONFIDENTIAL'), 'Sensitivity: Confidential, as sent by the source system');
  assert.equal(caseSensitivityLine(undefined), 'Sensitivity: Standard, as sent by the source system');
});

test('0021: a document shows its own level only when it differs from the case level', () => {
  assert.equal(documentSensitivityNote('CONFIDENTIAL', 'CONFIDENTIAL'), null);
  assert.equal(documentSensitivityNote(undefined, 'STANDARD'), null);
  assert.equal(documentSensitivityNote('STANDARD', undefined), null);
  assert.equal(documentSensitivityNote('HIGHLY_CONFIDENTIAL', 'CONFIDENTIAL'), 'Highly Confidential');
  assert.equal(documentSensitivityNote('STANDARD', 'CONFIDENTIAL'), 'Standard');
});

test('0021: a refused level reads as LV-DLV-003 in Support', () => {
  const reason = problemReason('DELIVERY_SENSITIVITY_REFUSED');
  assert.equal(reason.known, true);
  assert.equal(reason.title, 'Destination refused the sensitivity level');
  assert.match(reason.sentence, /LV-DLV-003/);
});

test('0021: the level appears only in the case panel, New case and the watched-folder setting (no badge elsewhere)', () => {
  const studio = resolve(here);
  const allowed = new Set(['CaseDrawer.tsx', 'Intake.tsx', join('admin', 'IntegrationsArea.tsx'), 'words.ts']);
  const pattern = /sensitivityName|caseSensitivityLine|documentSensitivityNote|SENSITIVITY_NAMES|sensitivityLevel|effectiveSensitivityLevel/;
  const offenders: string[] = [];
  const walk = (dir: string, rel: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      const relPath = rel ? join(rel, entry.name) : entry.name;
      if (entry.isDirectory()) { walk(path, relPath); continue; }
      if (!/\.tsx?$/.test(entry.name) || /\.test\.ts$/.test(entry.name)) continue;
      if (!allowed.has(relPath) && pattern.test(readFileSync(path, 'utf8'))) offenders.push(relPath);
    }
  };
  walk(studio, '');
  // UploadCapture and ScanCapture only pass the New case choice on (gate.sensitivity); they show nothing.
  assert.deepEqual(offenders.filter((f) => f !== 'UploadCapture.tsx' && f !== 'ScanCapture.tsx'), []);
});

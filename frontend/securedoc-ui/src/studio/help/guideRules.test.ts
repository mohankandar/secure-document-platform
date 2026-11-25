import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { LV_AREAS, LV_CODES } from '../lvErrorCodes.generated.ts';
import { HELP_PATH, KIND_WORDS, codeFromAddress, foundText, helpPath, isHelpPath, searchGuide, splitCodes, whoActsText } from './guideRules.ts';

const studio = join(dirname(fileURLToPath(import.meta.url)), '..');
const all = Object.values(LV_CODES);

test('0082: every code has its meaning, who acts, steps and support text, and stands in exactly one area', () => {
  for (const e of all) {
    assert.ok(e.title && e.description && KIND_WORDS[e.kind] && e.supportTells, e.code);
    assert.ok(e.whoActsGroups.length > 0, e.code);
    assert.ok(e.steps.length >= 2 && e.steps.length <= 4, e.code);
    assert.equal(e.steps.join(' '), e.whatToDo, e.code);
  }
  const listed = searchGuide('').flatMap((a) => a.entries.map((e) => e.code));
  assert.deepEqual(listed, all.map((e) => e.code));
  for (const a of LV_AREAS) assert.ok(a.name, a.key);
});

test('0082: search by code, part of a code and words, in any case', () => {
  const codes = (q: string) => searchGuide(q).flatMap((a) => a.entries.map((e) => e.code));
  assert.deepEqual(codes('LV-INT-003'), ['LV-INT-003']);
  assert.ok(codes('lv-dlv').length >= 15);
  assert.ok(codes('destination busy').includes('LV-DLV-002'));
  assert.ok(codes('BUSY destination').includes('LV-DLV-002'));
  assert.ok(codes('couldn’t be read').includes('LV-INT-001'));
  assert.deepEqual(codes('zzzz nothing'), []);
  assert.deepEqual(searchGuide('LV-INT-003').map((a) => a.name), ['Intake']);
  assert.equal(foundText(0), 'No code matches');
  assert.equal(foundText(1), '1 code');
  assert.equal(foundText(161), '161 codes');
});

test('0082: addresses', () => {
  assert.equal(helpPath('LV-DLV-002'), '/help/error-codes/LV-DLV-002');
  for (const odd of ['LV-XYZ-999', '', null, undefined, '../admin']) assert.equal(helpPath(odd), HELP_PATH);
  assert.equal(codeFromAddress('lv-dlv-002'), 'LV-DLV-002');
  for (const odd of ['LV-XYZ-999', '', undefined]) assert.equal(codeFromAddress(odd), null);
  for (const yes of [HELP_PATH, `${HELP_PATH}/`, `${HELP_PATH}/LV-DLV-002`]) assert.ok(isHelpPath(yes), yes);
  for (const no of ['/', '/help', '/help/error-codes2', '/admin/help/error-codes']) assert.ok(!isHelpPath(no), no);
});

test('0082: a known code in a text becomes a link; everything else stays as it is', () => {
  assert.deepEqual(splitCodes('Busy · LV-DLV-002.'), ['Busy · ', { code: 'LV-DLV-002' }, '.']);
  assert.deepEqual(splitCodes('LV-INT-001 and LV-INT-002'), [{ code: 'LV-INT-001' }, ' and ', { code: 'LV-INT-002' }]);
  for (const plain of ['', 'nothing here', 'PLT-LIC-001', 'LV-XYZ-999', 'LV-INT-0011']) {
    assert.ok(splitCodes(plain).every((p) => typeof p === 'string'), plain);
    assert.equal(splitCodes(plain).join(''), plain);
  }
});

test('0082: who acts is the group, with the catalogue’s own words when they say more', () => {
  assert.equal(whoActsText(LV_CODES['LV-DLV-001']), 'IT Admin');
  assert.equal(whoActsText(LV_CODES['LV-INT-008']), 'User, IT Admin (Source system, IT Admin)');
  assert.equal(whoActsText(LV_CODES['LV-AI-006']), 'Support (Platform Ops)');
  assert.equal(whoActsText(LV_CODES['LV-INT-017']), 'Nobody');
});

test('0082: no screen shows a code or a refusal’s words without the link', () => {
  const files: string[] = [];
  const walk = (dir: string) => readdirSync(dir, { withFileTypes: true }).forEach((e) => (e.isDirectory() ? walk(join(dir, e.name)) : e.name.endsWith('.tsx') && files.push(join(dir, e.name))));
  walk(studio);
  // The guide's own page is where the links lead.
  for (const file of files.filter((f) => !f.endsWith('ErrorCodes.tsx'))) {
    const text = readFileSync(file, 'utf8');
    // A helper that returns "Title · LV-…" or a server's message, as the direct child of anything but LvText.
    for (const m of text.matchAll(/(\S*)>\{(?:errorMessage|lvLabel|reasonLabel|failureReason|serviceStatusText|attentionLine|noAccessTitle|whatToDo)\(/g)) assert.ok(m[1].endsWith('<LvText'), `${file}: ${m[0]}`);
    for (const m of text.matchAll(/<code className="cf-(?:dr-)?code">\{[^}]*\.(?:code|lvCode)\}/g)) assert.fail(`${file}: ${m[0]}`);
    for (const m of text.matchAll(/role="alert">\{(?:problem|failure)\}</g)) if (!file.endsWith('ComplianceReport.tsx')) assert.fail(`${file}: ${m[0]}`);
  }
});

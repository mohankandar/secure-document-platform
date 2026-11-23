import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { LV_BY_DETAIL, LV_CODES } from './lvErrorCodes.generated.ts';
import { LV_FALLBACK, NO_ACCESS_CODE, errorText, hasLvCode, isLvCode, lvCodeFor, lvEntry, lvLabel, noAccessTitle, withLvCode } from './lvCodes.ts';

const here = dirname(fileURLToPath(import.meta.url));

test('0025: an entry is found by its LV code; anything else gets the fallback entry', () => {
  assert.equal(lvEntry('LV-DLV-002').title, 'Destination busy or down');
  assert.equal(lvEntry(' LV-DLV-002 ').code, 'LV-DLV-002');
  assert.equal(LV_FALLBACK, 'LV-SYS-001');
  for (const odd of ['LV-XYZ-999', 'lv-dlv-002', '', null, undefined, 'DELIVERY_IO']) assert.equal(lvEntry(odd).code, 'LV-SYS-001', String(odd));
  assert.equal(isLvCode('LV-CFG-001'), true);
  assert.equal(isLvCode('LV-CFG-999'), false);
  assert.equal(isLvCode(42), false);
  // Every registry entry is keyed by its own code and has words.
  for (const [code, e] of Object.entries(LV_CODES)) {
    assert.equal(e.code, code);
    assert.match(code, /^LV-[A-Z]{2,3}-\d{3}$/);
    assert.ok(e.title.trim() && e.description.trim(), code);
  }
});

test('0025: an internal identifier resolves to its LV code; an unknown one to LV-SYS-001; a known lvCode wins', () => {
  assert.equal(lvCodeFor('DELIVERY_IO'), 'LV-DLV-001');
  assert.equal(lvCodeFor(' delivery_io '), 'LV-DLV-001');
  assert.equal(lvCodeFor('INTAKE_MISSING_FILE'), 'LV-INT-032');
  assert.equal(lvCodeFor('NO_MAPPED_ROLE'), 'LV-AUT-002');
  assert.equal(lvCodeFor('BRAND_NEW_FAILURE'), 'LV-SYS-001');
  assert.equal(lvCodeFor(null), 'LV-SYS-001');
  assert.equal(lvCodeFor('DELIVERY_IO', 'LV-DLV-002'), 'LV-DLV-002');
  assert.equal(lvCodeFor('DELIVERY_IO', 'LV-NOPE-001'), 'LV-DLV-001');
  assert.equal(lvCodeFor(undefined, 'LV-AUT-003'), 'LV-AUT-003');
  assert.equal(hasLvCode('DELIVERY_IO'), true);
  assert.equal(hasLvCode('BRAND_NEW_FAILURE'), false);
  // Every mapping points at a code the registry has.
  for (const [detail, code] of Object.entries(LV_BY_DETAIL)) assert.ok(LV_CODES[code], `${detail} → ${code}`);
});

test('0025: "Title · LV-XXX-NNN" with a middle dot and spaces', () => {
  assert.equal(lvLabel('LV-DLV-002'), 'Destination busy or down · LV-DLV-002');
  assert.equal(lvLabel('LV-AUT-002'), 'No access to LexaVault · LV-AUT-002');
  assert.equal(lvLabel('nonsense'), 'Something went wrong · LV-SYS-001');
  assert.equal(withLvCode('Setting not valid: retries 1 to 5', 'LV-CFG-001'), 'Setting not valid: retries 1 to 5 · LV-CFG-001');
  assert.equal(withLvCode('Setting not valid · LV-CFG-001', 'LV-CFG-001'), 'Setting not valid · LV-CFG-001');
});

test('0025: an API error shows its message and LV code; an older server body shows the message alone', () => {
  const body = { errorCode: 'INVALID_ARGUMENT', code: 'LV-CFG-001', message: 'Setting not valid: timeoutSeconds must be between 1 and 300', correlationId: 'c-1', timestamp: 't' };
  assert.equal(errorText(body, 'x'), 'Setting not valid: timeoutSeconds must be between 1 and 300 · LV-CFG-001');
  assert.equal(errorText({ code: 'LV-DLV-002', message: 'Destination busy or down · LV-DLV-002' }, 'x'), 'Destination busy or down · LV-DLV-002');
  // No message: the code's own title.
  assert.equal(errorText({ code: 'LV-REV-001' }, 'x'), 'Someone else already decided this · LV-REV-001');
  assert.equal(errorText({ code: 'LV-REV-001', message: '  ' }, 'x'), 'Someone else already decided this · LV-REV-001');
  // Older server, unknown code or no body: as before.
  assert.equal(errorText({ errorCode: 'INVALID_ARGUMENT', message: 'Unable to save' }, 'x'), 'Unable to save');
  assert.equal(errorText({ code: 'NOT-AN-LV-CODE', message: 'Unable to save' }, 'x'), 'Unable to save');
  assert.equal(errorText({}, 'Fallback.'), 'Fallback.');
  assert.equal(errorText(null, 'Fallback.'), 'Fallback.');
  assert.equal(errorText(new Error('boom'), 'Fallback.'), 'boom');
  // The internal detail never reaches the words.
  assert.doesNotMatch(errorText(body, 'x'), /INVALID_ARGUMENT/);
});

test('0025: the no-access heading is the title and code only; LV-AUT-002 when the server gave none', () => {
  assert.equal(NO_ACCESS_CODE, 'LV-AUT-002');
  assert.equal(noAccessTitle(undefined), 'No access to LexaVault · LV-AUT-002');
  assert.equal(noAccessTitle(null), 'No access to LexaVault · LV-AUT-002');
  assert.equal(noAccessTitle('ACCESS_DENIED'), 'No access to LexaVault · LV-AUT-002');
  assert.equal(noAccessTitle('LV-AUT-003'), 'No access to this entity · LV-AUT-003');
  assert.equal(noAccessTitle('LV-AUT-005'), 'Entity not active · LV-AUT-005');
  // The screen takes its heading from here and says no exact reason.
  const screen = readFileSync(join(here, 'NoAccess.tsx'), 'utf8');
  assert.match(screen, /noAccessTitle\(code\)/);
  assert.doesNotMatch(screen, /LexaVault group|have access to anything|has\s+no LexaVault role/);
});

test('task 0026: 137 codes (139 with LV-INT-028 and 029 of task 0038); the 8 new ones and their mappings come from the registry', () => {
  assert.equal(Object.keys(LV_CODES).length, 140); // task 0044: + LV-RED-005
  assert.equal(LV_BY_DETAIL.REDACTION_VALUE_IN_OUTPUT_TEXT, 'LV-RED-005');
  for (const code of ['LV-INT-035', 'LV-SCN-019', 'LV-SCN-020', 'LV-SCN-021', 'LV-REV-009', 'LV-CFG-011', 'LV-SYS-008', 'LV-SYS-009']) {
    assert.ok(LV_CODES[code], code);
  }
  assert.equal(LV_BY_DETAIL.INTAKE_RECOVERY_CONFLICT, 'LV-INT-035');
  assert.equal(lvLabel('LV-SYS-009'), 'Unknown request · LV-SYS-009');
});

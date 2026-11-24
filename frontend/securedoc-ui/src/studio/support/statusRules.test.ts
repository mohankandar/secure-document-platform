import assert from 'node:assert/strict';
import test from 'node:test';
import { checkName, sortedChecks, stateTone, stateWord, statusTitle, type CheckShown } from './statusRules.ts';

const check = (name: string, state: string): CheckShown => ({ name, group: 'platform', state, code: 'X', cause: null, whatToDo: null, required: false, checkedAt: '2026-11-24T00:00:00Z' });

test('the three words, and anything else is not known', () => {
  assert.deepEqual(['WORKING', 'HAVING_PROBLEMS', 'DOWN', 'OTHER'].map(stateWord), ['Working', 'Having problems', 'Down', 'Not known']);
  assert.equal(stateTone('OTHER'), 'danger');
});

test('plain names, the worst first, and the title', () => {
  assert.equal(checkName('service-model-server'), 'AI model server');
  assert.equal(checkName('folder-intake'), 'Folder: intake');
  const list = [check('database', 'WORKING'), check('licence', 'HAVING_PROBLEMS'), check('storage', 'DOWN')];
  assert.deepEqual(sortedChecks(list).map((c) => c.name), ['storage', 'licence', 'database']);
  assert.equal(statusTitle({ overall: 'DOWN', checks: list, failures: [] }), '2 of 3 need attention');
  assert.equal(statusTitle({ overall: 'WORKING', checks: [list[0]], failures: [] }), 'Everything is working');
  assert.equal(statusTitle({ overall: 'WORKING', checks: [], failures: [] }), 'Not checked yet');
});

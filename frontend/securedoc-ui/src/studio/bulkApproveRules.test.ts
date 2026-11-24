import test from 'node:test';
import assert from 'node:assert/strict';
import { BULK_WORDS, approveSelectedLabel, blockedReason, confirmTitle, keepSelectable, resultLines } from './bulkApproveRules.ts';

const OFFER = { enabled: true, maximum: 2, reviewedByYou: ['own'] };

test('task 0076: an own case is never selectable, and the limit stops further ticks only', () => {
  assert.equal(blockedReason('own', [], OFFER), BULK_WORDS.own);
  assert.equal(blockedReason('a', ['b'], OFFER), null);
  assert.equal(blockedReason('a', ['b', 'c'], OFFER), 'Up to 2 cases at once.');
  assert.equal(blockedReason('b', ['b', 'c'], OFFER), null);
  assert.deepEqual(keepSelectable(['a', 'own', 'gone', 'b'], ['a', 'b', 'own'], OFFER), ['a', 'b']);
});

test('task 0076: the words count the cases and name each one that stayed', () => {
  assert.equal(approveSelectedLabel(3), 'Approve selected (3)');
  assert.equal(confirmTitle(1), 'Approve 1 case for delivery?');
  assert.equal(confirmTitle(4), 'Approve 4 cases for delivery?');
  const lines = resultLines([{ packageId: 'a', approved: true }, { packageId: 'b', approved: false, reasonCode: 'LV-REV-015' }], (id) => `LN-${id}`);
  assert.equal(lines.done, '1 case approved.');
  assert.deepEqual(lines.failed, ['LN-b: You reviewed this case yourself · LV-REV-015']);
});

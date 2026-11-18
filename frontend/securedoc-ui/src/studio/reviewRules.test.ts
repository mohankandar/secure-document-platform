import assert from 'node:assert/strict';
import test from 'node:test';
import { bulkButtonText, bulkPlan, bulkResultText, bulkWaitingText, maskFoundText, nextZoom } from './reviewRules.ts';
import { whyAsked } from './words.ts';

const item = (findingId: string, packageId: string, entityType: string, hasBoundingBox = true) =>
  ({ findingId, packageId, entityType, hasBoundingBox });

test('bulk: counts only the same type in the same case', () => {
  const selected = item('a', 'case-1', 'SSN');
  const items = [
    selected,
    item('b', 'case-1', 'SSN'),
    item('c', 'case-1', 'US_SSN'), // alias of the same type
    item('d', 'case-1', 'DATE_OF_BIRTH'), // other type
    item('e', 'case-2', 'SSN'), // other case
  ];
  const plan = bulkPlan(items, selected)!;
  assert.deepEqual(plan.all.map((i) => i.findingId), ['a', 'b', 'c']);
  assert.equal(bulkWaitingText(plan), '2 more Social Security Numbers are waiting in this case.');
  assert.equal(bulkButtonText(plan), 'Redact all 3 like this');
});

test('bulk: not offered when no other finding of the type waits in the case', () => {
  const selected = item('a', 'case-1', 'SSN');
  assert.equal(bulkPlan([selected, item('d', 'case-1', 'CREDIT_SCORE'), item('e', 'case-2', 'SSN')], selected), null);
  assert.equal(bulkPlan([], null), null);
});

test('bulk: skips findings whose place is not found; a drawn mark places the selected one', () => {
  const selected = item('a', 'case-1', 'DATE_OF_BIRTH', false);
  const items = [selected, item('b', 'case-1', 'DATE_OF_BIRTH'), item('c', 'case-1', 'DATE_OF_BIRTH', false)];

  const plan = bulkPlan(items, selected)!;
  assert.deepEqual(plan.toRedact.map((i) => i.findingId), ['b']);
  assert.deepEqual(plan.skipped.map((i) => i.findingId), ['a', 'c']);
  assert.equal(bulkWaitingText(plan), '2 more Dates of birth are waiting in this case.');

  const marked = bulkPlan(items, selected, true)!;
  assert.deepEqual(marked.toRedact.map((i) => i.findingId), ['a', 'b']);
  assert.deepEqual(marked.skipped.map((i) => i.findingId), ['c']);
});

test('bulk: one more uses the singular', () => {
  const selected = item('a', 'case-1', 'SSN');
  assert.equal(bulkWaitingText(bulkPlan([selected, item('b', 'case-1', 'SSN')], selected)!),
    '1 more Social Security Number is waiting in this case.');
});

test('bulk: the result says what was skipped and what failed', () => {
  assert.equal(bulkResultText(3, 0, 0), 'Redacted 3 findings.');
  assert.equal(bulkResultText(1, 1, 0), 'Redacted 1 finding. 1 skipped because its place was not found; it stays in the queue.');
  assert.equal(bulkResultText(2, 2, 1), 'Redacted 2 findings. 2 skipped because their place was not found; they stay in the queue. 1 could not be saved and stays in the queue.');
});

test('queue text: masked except the last 4 characters, never in full', () => {
  assert.equal(maskFoundText('412-55-0193'), '••••0193');
  assert.equal(maskFoundText('4532 0151 1283 0366'), '••••0366');
  assert.equal(maskFoundText('712'), '••••', 'a short value is masked in full');
  assert.equal(maskFoundText('1234'), '••••');
  assert.equal(maskFoundText(''), null);
  assert.equal(maskFoundText(undefined), null);
  assert.ok(!maskFoundText('412-55-0193')!.includes('412'));
});

test('why a person is asked names the policy level', () => {
  assert.equal(whyAsked('LOW_CONFIDENCE', 0.62, 0.85),
    'LexaVault is 62% sure this is sensitive. Your policy redacts automatically from 85%, so a person decides.');
  assert.equal(whyAsked('LOW_CONFIDENCE', 0.62, null),
    'LexaVault is 62% sure this is sensitive. That is below your policy’s level for automatic redaction, so a person decides.');
  assert.match(whyAsked('LOCATION_UNCERTAIN', 0.9, 0.85), /not its exact place/);
  assert.match(whyAsked('POLICY_REVIEW', 0.97, 0.85), /^LexaVault is 97% sure/);
});

test('zoom steps up and down and stops at the ends', () => {
  assert.equal(nextZoom(100, 1), 125);
  assert.equal(nextZoom(100, -1), 75);
  assert.equal(nextZoom(88, 1), 100, 'from a fit percentage to the next step');
  assert.equal(nextZoom(88, -1), 75);
  assert.equal(nextZoom(300, 1), 300);
  assert.equal(nextZoom(50, -1), 50);
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { axisTicks, bucketDate, hours, labelledBuckets, pct } from './reportRules.ts';

test('durations read as minutes, hours or days with a space', () => {
  assert.equal(hours(0.5), '30 min');
  assert.equal(hours(0.001), '1 min');
  assert.equal(hours(2.4), '2.4 h');
  assert.equal(hours(30.5), '30.5 h');
  assert.equal(hours(4), '4 h');
  assert.equal(hours(72), '3 days');
  assert.equal(hours(75), '3.1 days');
  assert.equal(hours(0), '0 min');
  assert.equal(hours(Number.NaN), '0 min');
  assert.equal(hours(-3), '0 min');
});

test('rates read as whole percentages whether sent as 0–1 or 0–100', () => {
  assert.equal(pct(0.68), '68%');
  assert.equal(pct(91), '91%');
  assert.equal(pct(Number.NaN), '0%');
});

test('the left axis starts at 0, steps by 1, 2 or 5 × 10ⁿ, and its top holds the largest bar', () => {
  assert.deepEqual(axisTicks(0), [0, 1]);
  assert.deepEqual(axisTicks(3), [0, 1, 2, 3]);
  assert.deepEqual(axisTicks(7), [0, 2, 4, 6, 8]);
  assert.deepEqual(axisTicks(20), [0, 5, 10, 15, 20]);
  assert.deepEqual(axisTicks(23), [0, 10, 20, 30]);
  assert.deepEqual(axisTicks(512), [0, 200, 400, 600]);
  assert.deepEqual(axisTicks(Number.NaN), [0, 1]);
  for (const max of [1, 9, 41, 99, 101, 1234, 99999]) {
    const ticks = axisTicks(max);
    assert.ok(ticks.length - 1 <= 4, `at most 4 gaps for ${max}`);
    assert.ok(ticks[ticks.length - 1] >= max, `top holds ${max}`);
    const step = ticks[1];
    assert.ok(ticks.every((t, i) => t === i * step), `regular steps for ${max}`);
  }
});

test('dates under the chart sit at regular intervals, at most 7 of them', () => {
  assert.deepEqual(labelledBuckets(0), []);
  assert.deepEqual(labelledBuckets(1), [0]);
  assert.deepEqual(labelledBuckets(7), [0, 1, 2, 3, 4, 5, 6]);
  assert.deepEqual(labelledBuckets(30), [0, 5, 10, 15, 20, 25]);
  assert.deepEqual(labelledBuckets(92), [0, 14, 28, 42, 56, 70, 84]);
  for (const n of [2, 8, 13, 31, 60, 92, 366]) {
    const at = labelledBuckets(n);
    assert.ok(at.length <= 7 && at[0] === 0);
    const step = at[1] - at[0];
    assert.ok(at.every((v, i) => v === i * step), `regular for ${n}`);
  }
});

test('a bucket reads as its day in the database zone, whatever the offset', () => {
  assert.equal(bucketDate('2026-11-03T00:00:00Z'), 'Nov 3');
  assert.equal(bucketDate('2026-11-03T05:00:00Z'), 'Nov 3');
  assert.equal(bucketDate('2026-11-02T18:30:00Z'), 'Nov 3');
  assert.equal(bucketDate('2026-12-31T00:00:00Z'), 'Dec 31');
  assert.equal(bucketDate('not a date'), 'not a date');
});

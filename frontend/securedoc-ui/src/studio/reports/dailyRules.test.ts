import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { PRESETS, TABS, cases, defaultPreset, everyText, inPeriod, isDailyReport, periodFor, quietText, reasonLink, tabFrom, usualText, whenText } from './dailyRules.ts';

const here = dirname(fileURLToPath(import.meta.url));
const ZONE = 'America/New_York';
const NOW = new Date('2026-11-24T16:40:00Z');

test('three tabs, and the periods of the design', () => {
  assert.deepEqual(TABS.map((t) => t.label), ['Overview', 'Daily intake and delivery', 'Operations']);
  assert.deepEqual(PRESETS.map((p) => p.label), ['Today', 'Yesterday', '7 days', '30 days', 'Custom']);
  assert.equal(tabFrom('daily'), 'daily');
  assert.equal(tabFrom('nothing'), 'overview');
  assert.equal(tabFrom(null), 'overview');
  assert.equal(defaultPreset('daily'), 'TODAY');
  assert.equal(defaultPreset('overview'), '30D');
});

test('a period is days of the entity’s zone', () => {
  const today = periodFor('TODAY', '', '', ZONE, NOW)!;
  assert.equal(today.from.toISOString(), '2026-11-24T05:00:00.000Z');
  assert.equal(today.to.toISOString(), '2026-11-25T05:00:00.000Z');
  const yesterday = periodFor('YESTERDAY', '', '', ZONE, NOW)!;
  assert.equal(yesterday.from.toISOString(), '2026-11-23T05:00:00.000Z');
  assert.equal(yesterday.to.toISOString(), today.from.toISOString());
  assert.equal(periodFor('7D', '', '', ZONE, NOW)!.fromDay.d, 18);
  assert.equal(periodFor('CUSTOM', '2026-11-05', '2026-11-04', ZONE, NOW), null);
});

test('the words of a tile', () => {
  assert.equal(inPeriod('Received', 'TODAY'), 'Received today');
  assert.equal(inPeriod('Exported', 'YESTERDAY'), 'Exported yesterday');
  assert.equal(inPeriod('Exported', '30D'), 'Exported');
  assert.equal(cases(1), '1 case');
  assert.equal(cases(1200), '1,200 cases');
  const tuesday = { y: 2026, m: 11, d: 24 };
  assert.equal(usualText({ low: 38, high: 52 }, tuesday), 'Usual for a Tuesday: 38–52');
  assert.equal(usualText({ low: 40, high: 40 }, tuesday), 'Usual for a Tuesday: 40');
  assert.equal(usualText(null, tuesday), null);
});

test('the quiet source warning', () => {
  const q = { sourceKey: 'c', name: 'Branch scanners', lastReceivedAt: '2026-11-24T14:12:00Z', usualMinutes: 60 };
  assert.equal(quietText(q, ZONE, NOW.toISOString()).replace(/ /g, ' '), 'Branch scanners has sent nothing since 9:12 AM. It usually sends every hour on a Tuesday.');
  assert.equal(quietText({ ...q, lastReceivedAt: '2026-11-23T21:05:00Z' }, ZONE, NOW.toISOString()).replace(/ /g, ' '),
    'Branch scanners has sent nothing since Nov 23, 4:05 PM. It usually sends every hour on a Tuesday.');
  assert.equal(everyText(1), 'every minute');
  assert.equal(everyText(20), 'every 20 minutes');
  assert.equal(everyText(130), 'every 2 hours');
  assert.equal(whenText(null, ZONE, NOW.toISOString()), 'Never');
});

test('a reason opens its cases only for someone who may, and never a refused one', () => {
  assert.equal(reasonLink({ code: 'LV-PRC-004', where: 'STOPPED_IN_PROCESSING', cases: 2 }, true), '/?code=LV-PRC-004');
  assert.equal(reasonLink({ code: 'LV-PRC-004', where: 'STOPPED_IN_PROCESSING', cases: 2 }, false), null);
  assert.equal(reasonLink({ code: 'LV-INT-003', where: 'REFUSED_AT_INTAKE', cases: 2 }, true), null);
});

test('an answer that is not the day is refused', () => {
  assert.equal(isDailyReport({}), false);
  assert.equal(isDailyReport(null), false);
});

test('wording (owner): source system and destination, never the sending side or another product', () => {
  const names = new RegExp(readFileSync(resolve(here, '..', '..', '..', '..', '..', 'scripts', 'ci', 'product-names.txt'), 'utf8').split(/\r?\n/)[0], 'i');
  const java = resolve(here, '..', '..', '..', '..', '..', 'backend', 'securedoc-api', 'src', 'main', 'java', 'com', 'securedoc', 'platform', 'api', 'reports');
  const files = [...readdirSync(here).filter((f) => /\.(tsx?|css)$/.test(f) && !/test/.test(f)).map((f) => join(here, f)), ...readdirSync(java).map((f) => join(java, f))];
  assert.ok(files.length >= 6);
  for (const f of files) {
    const text = readFileSync(f, 'utf8');
    assert.doesNotMatch(text, /sending system/i, f);
    assert.doesNotMatch(text, names, f);
  }
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { HOUR_UNITS, MINUTE_UNITS, bundleLabel, bundleName, bundleNames, durationText, fieldRule, pickUnit, splitValueLines, toBase, valueLines } from './adminRules.ts';

test('a stored duration shows in the largest unit it is a whole number of', () => {
  assert.equal(pickUnit(168, HOUR_UNITS).name, 'days');
  assert.equal(pickUnit(336, HOUR_UNITS).name, 'days');
  assert.equal(pickUnit(36, HOUR_UNITS).name, 'hours');
  assert.equal(pickUnit(12, HOUR_UNITS).name, 'hours');
  assert.equal(pickUnit(240, MINUTE_UNITS).name, 'hours');
  assert.equal(pickUnit(90, MINUTE_UNITS).name, 'minutes');
  assert.equal(pickUnit(2880, MINUTE_UNITS).name, 'days');
  assert.equal(pickUnit(Number.NaN, HOUR_UNITS).name, 'hours');
});

test('what is typed is stored only when it is a whole number of the stored unit', () => {
  const days = HOUR_UNITS[1];
  const hours = MINUTE_UNITS[1];
  assert.equal(toBase(7, days), 168);
  assert.equal(toBase(1.5, days), 36);
  assert.ok(Number.isNaN(toBase(0.01, days)));
  assert.equal(toBase(1.5, hours), 90);
  assert.ok(Number.isNaN(toBase(0.5, HOUR_UNITS[0])));
  assert.ok(Number.isNaN(toBase(Number.NaN, days)));
});

test('durations read in plain words', () => {
  assert.equal(durationText(720, HOUR_UNITS), '30 days');
  assert.equal(durationText(24, HOUR_UNITS), '1 day');
  assert.equal(durationText(72, HOUR_UNITS), '3 days');
  assert.equal(durationText(60, MINUTE_UNITS), '1 hour');
  assert.equal(durationText(90, MINUTE_UNITS), '90 minutes');
});

test('an AI version is named from a year and month in its version string', () => {
  const n = bundleName({ version: '2026.11.1', promotedAt: '2026-11-01T15:00:00Z' }, 'UTC');
  assert.deepEqual(n, { name: 'November 2026 update', released: 'Nov 1, 2026', from: 'version' });
  assert.equal(bundleLabel(n), 'November 2026 update · released Nov 1, 2026');
  assert.equal(bundleName({ version: 'lv-2026-10-r2' }).name, 'October 2026 update');
  assert.equal(bundleName({ version: 'lv-2026-10-r2' }).released, null);
});

test('without a date in the version, the release date gives the month; without either, the version stays', () => {
  assert.deepEqual(bundleName({ version: 'r11-baseline-v1', promotedAt: '2026-11-03T09:00:00Z' }, 'UTC'),
    { name: 'November 2026 update', released: 'Nov 3, 2026', from: 'release date' });
  assert.deepEqual(bundleName({ version: 'r11-baseline-v1', promotedAt: null }), { name: 'r11-baseline-v1', released: null, from: 'version only' });
  assert.deepEqual(bundleName({ version: 'r11-baseline-v1', promotedAt: 'not a date' }), { name: 'r11-baseline-v1', released: null, from: 'version only' });
  // A number that only looks like a month is not one.
  assert.equal(bundleName({ version: 'r2026.13' }).name, 'r2026.13');
});

test('two versions that would read the same also show their version', () => {
  const names = bundleNames([
    { version: 'r11-baseline-v1', promotedAt: '2026-11-03T09:00:00Z' },
    { version: 'r12-v1', promotedAt: '2026-11-20T09:00:00Z' },
    { version: '2026.10.1', promotedAt: '2026-10-01T09:00:00Z' },
  ], 'UTC');
  assert.equal(names.get('r11-baseline-v1')?.name, 'November 2026 update (r11-baseline-v1)');
  assert.equal(names.get('r12-v1')?.name, 'November 2026 update (r12-v1)');
  assert.equal(names.get('2026.10.1')?.name, 'October 2026 update');
});

const field = (p: Partial<Parameters<typeof fieldRule>[0]>) => ({
  validationType: 'TEXT', exactLength: null, minLength: null, maxLength: null, caseNormalization: 'PRESERVE', stripSeparators: false, allowedValues: [], ...p,
});

test('case field rules are spelled out', () => {
  assert.equal(fieldRule(field({ validationType: 'TEXT', customPattern: '[A-Za-z0-9]+', minLength: 6, maxLength: 12, caseNormalization: 'UPPER', stripSeparators: true })),
    'Letters and numbers · 6–12 characters · saved in capitals · spaces and dashes removed');
  assert.equal(fieldRule(field({ maxLength: 120 })), 'Text · up to 120 characters');
  assert.equal(fieldRule(field({ validationType: 'DROPDOWN', allowedValues: ['CA', 'NV', 'AZ', 'OR', ' '], caseNormalization: 'UPPER' })),
    'One of 4 allowed values · saved in capitals');
  assert.equal(fieldRule(field({ validationType: 'NUMBER', customPattern: '[0-9]+', exactLength: 12 })), 'Numbers only · exactly 12 characters');
  assert.equal(fieldRule(field({ validationType: 'MONEY', currency: 'USD', multiValue: true, sensitive: true })), 'Amount in USD · several values · sensitive');
  assert.equal(fieldRule(field({ minLength: 1 })), 'Text · at least 1 character');
  assert.equal(fieldRule(field({ minLength: 4, maxLength: 4 })), 'Text · exactly 4 characters');
  assert.equal(fieldRule(field({ validationType: 'EMAIL', caseNormalization: 'LOWER' })), 'Email address · saved in lower case');
  assert.equal(fieldRule(field({ validationType: 'SOMETHING_NEW' })), 'Text');
  for (const t of ['TEXT', 'NUMBER', 'DECIMAL', 'MONEY', 'DATE', 'DATE_TIME', 'EMAIL', 'UUID', 'DROPDOWN', 'ADDRESS', 'CUSTOM_PATTERN']) {
    assert.doesNotMatch(fieldRule(field({ validationType: t, allowedValues: ['A'] })), /[A-Z]{3,}/, `${t} shows no code`);
  }
});

test('0113 (U10): allowed values are edited as "code = name" lines; the codes stay, in order', () => {
  assert.deepEqual(valueLines(['000', '001', '002'], { '000': 'Club', '002': 'Asia Pacific' }), ['000 = Club', '001', '002 = Asia Pacific']);
  assert.deepEqual(splitValueLines(['000 = Club', ' 001 ', '002 =  Asia Pacific ', '', '003 =']), {
    allowedValues: ['000', '001', '002', '003'],
    allowedValueNames: { '000': 'Club', '002': 'Asia Pacific' },
  });
});

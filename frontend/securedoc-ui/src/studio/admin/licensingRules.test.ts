import assert from 'node:assert/strict';
import test from 'node:test';
import { allowanceText, dayText, levelTone, licenceKind, licenceTerm, licenceTitle, monthText, type LicenceShown } from './licensingRules.ts';

const base: LicenceShown = { licenceId: 'LIC-1', version: 1, customer: 'C', type: 'SUBSCRIPTION', kind: 'PRODUCTION', testEnvironment: false, state: 'VALID', message: '', startsOn: '2026-01-01', expiresOn: '2026-06-30', daysUntilExpiry: 90, graceEndsOn: '2026-07-30' };

test('days and months are the licence’s own, whatever the browser’s time zone', () => {
  assert.equal(dayText('2026-06-30'), '30 June 2026');
  assert.equal(dayText(null), 'Not given');
  assert.equal(monthText('2026-11'), 'November 2026');
});

test('the title says whether new cases are taken in', () => {
  assert.equal(licenceTitle({ intakeAllowed: true, licence: base }), 'The licence is valid');
  assert.equal(licenceTitle({ intakeAllowed: true, licence: { ...base, state: 'EXPIRING' } }), 'The licence ends soon');
  assert.equal(licenceTitle({ intakeAllowed: true, licence: { ...base, state: 'EXPIRED_IN_GRACE' } }), 'The licence has ended: renew it now');
  assert.equal(licenceTitle({ intakeAllowed: false, licence: null }), 'New cases are not taken in');
});

test('the term, its last days and its grace', () => {
  assert.equal(licenceTerm(base), '30 June 2026');
  assert.equal(licenceTerm({ ...base, daysUntilExpiry: 1 }), '30 June 2026 (1 day left)');
  assert.equal(licenceTerm({ ...base, state: 'EXPIRED_IN_GRACE' }), '30 June 2026 (grace period until 30 July 2026)');
  assert.equal(licenceTerm({ ...base, expiresOn: null, updatesUntil: '2036-12-31' }), '31 December 2036');
  assert.equal(licenceKind('NON_PRODUCTION'), 'Test environment (not for production)');
});

test('use against the licence', () => {
  assert.deepEqual(allowanceText({ type: 'PAGES_PER_MONTH', unit: 'pages', used: 1204, limit: 1000, level: 'OVER_LIMIT' }), { name: 'Pages this month', use: '1,204 of 1,000 pages', level: 'Over the limit' });
  assert.equal(allowanceText({ type: 'PAGES_PER_MONTH', unit: 'pages', used: 3, limit: 0, level: 'OVER_LIMIT' }).level, 'Not in the licence');
  assert.equal(levelTone('AT_LIMIT'), 'danger');
  assert.equal(levelTone('WITHIN'), undefined);
});

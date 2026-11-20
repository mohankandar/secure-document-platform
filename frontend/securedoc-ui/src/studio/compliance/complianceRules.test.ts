import { test } from 'node:test';
import assert from 'node:assert/strict';
import { capabilitiesFor } from '../../../e2e-oidc/policyCapabilities.ts';
import {
  complianceEdition, DEFAULT_PERIOD, FALLBACK_FILE_NAME, PERIODS, customRangeProblem, daysInclusive, defaultCustomRange, errorText,
  fileNameFromDisposition, localToday, presetRange, rangeLabel, requestBody,
} from './complianceRules.ts';

/** UI task U022: the browser's period maths matches CompliancePeriod.java. */

test('the button shows only to the role whose permission allows it', () => {
  const caps = (role: string) => capabilitiesFor(role);
  assert.equal(complianceEdition('reports', caps('BUSINESS_ADMIN')), 'SUMMARY');
  assert.equal(complianceEdition('reports', caps('REPORT_VIEWER')), null);
  assert.equal(complianceEdition('audit', caps('TENANT_IT_ADMIN')), 'FULL');
  assert.equal(complianceEdition('audit', caps('PLATFORM_OPERATIONS_ADMIN')), null);
  // Neither sees the other page's button, and nobody else sees one.
  assert.equal(complianceEdition('audit', caps('BUSINESS_ADMIN')), null);
  for (const role of ['DOCUMENT_PROCESSOR', 'DOCUMENT_SUPERVISOR', 'REPORT_VIEWER', 'PLATFORM_OPERATIONS_ADMIN']) {
    assert.equal(complianceEdition('reports', caps(role)), null, role);
    assert.equal(complianceEdition('audit', caps(role)), null, role);
  }
});

test('the four periods, Last quarter first chosen', () => {
  assert.deepEqual(PERIODS.map((p) => p.label), ['Last month', 'Last quarter', 'Last year', 'Custom']);
  assert.equal(DEFAULT_PERIOD, 'LAST_QUARTER');
});

test('last month is the last complete calendar month, across a year end', () => {
  assert.deepEqual(presetRange('LAST_MONTH', '2026-11-20'), { from: '2026-10-01', to: '2026-10-31' });
  assert.deepEqual(presetRange('LAST_MONTH', '2026-01-01'), { from: '2025-12-01', to: '2025-12-31' });
  assert.deepEqual(presetRange('LAST_MONTH', '2026-03-31'), { from: '2026-02-01', to: '2026-02-28' });
  assert.deepEqual(presetRange('LAST_MONTH', '2028-03-01'), { from: '2028-02-01', to: '2028-02-29' }, 'leap year');
});

test('last quarter is the last complete calendar quarter, at every quarter edge', () => {
  // The server's own test date: Nov 19, 2026 gives Jul 1 – Sep 30 (ComplianceReportApiTest).
  assert.deepEqual(presetRange('LAST_QUARTER', '2026-11-19'), { from: '2026-07-01', to: '2026-09-30' });
  assert.deepEqual(presetRange('LAST_QUARTER', '2026-01-01'), { from: '2025-10-01', to: '2025-12-31' });
  assert.deepEqual(presetRange('LAST_QUARTER', '2026-03-31'), { from: '2025-10-01', to: '2025-12-31' });
  assert.deepEqual(presetRange('LAST_QUARTER', '2026-04-01'), { from: '2026-01-01', to: '2026-03-31' });
  assert.deepEqual(presetRange('LAST_QUARTER', '2026-06-30'), { from: '2026-01-01', to: '2026-03-31' });
  assert.deepEqual(presetRange('LAST_QUARTER', '2026-07-01'), { from: '2026-04-01', to: '2026-06-30' });
  assert.deepEqual(presetRange('LAST_QUARTER', '2026-10-01'), { from: '2026-07-01', to: '2026-09-30' });
  assert.deepEqual(presetRange('LAST_QUARTER', '2026-12-31'), { from: '2026-07-01', to: '2026-09-30' });
});

test('last year is the last complete calendar year, also on Jan 1 and Dec 31', () => {
  assert.deepEqual(presetRange('LAST_YEAR', '2026-01-01'), { from: '2025-01-01', to: '2025-12-31' });
  assert.deepEqual(presetRange('LAST_YEAR', '2026-12-31'), { from: '2025-01-01', to: '2025-12-31' });
});

test('today is the browser-local calendar day, not the UTC one', () => {
  // 23:30 local on Dec 31 is still Dec 31, whatever the UTC date is.
  assert.equal(localToday(new Date(2026, 11, 31, 23, 30)), '2026-12-31');
  assert.equal(localToday(new Date(2027, 0, 1, 0, 5)), '2027-01-01');
});

test('custom: must end before today and be 1 to 366 days long', () => {
  const today = '2026-11-20';
  assert.equal(customRangeProblem('2026-11-01', '2026-11-19', today), null, 'ending yesterday is fine');
  assert.equal(customRangeProblem('2026-11-19', '2026-11-19', today), null, 'one day is fine');
  assert.equal(customRangeProblem('2026-11-01', '2026-11-20', today), 'The period must end before today.');
  assert.equal(customRangeProblem('2026-11-01', '2026-12-01', today), 'The period must end before today.');
  assert.equal(customRangeProblem('2026-11-10', '2026-11-09', today), 'The end date must be on or after the start date.');
  assert.equal(customRangeProblem('2025-01-01', '2025-12-31', today), null, '365 days');
  assert.equal(customRangeProblem('2024-01-01', '2024-12-31', today), null, '366 days in a leap year');
  assert.equal(customRangeProblem('2024-01-01', '2025-01-01', today), 'The period can be at most 366 days.');
  assert.equal(customRangeProblem('', '2026-11-01', today), 'Choose a start and an end date.');
  assert.equal(customRangeProblem('2026-02-30', '2026-03-01', today), 'Enter the dates as a day, month and year.');
  assert.equal(daysInclusive('2026-03-08', '2026-03-09'), 2, 'a daylight-saving change does not move a day');
});

test('the default custom range is valid: 30 days ending yesterday', () => {
  const r = defaultCustomRange('2026-03-01');
  assert.deepEqual(r, { from: '2026-01-30', to: '2026-02-28' });
  assert.equal(customRangeProblem(r.from, r.to, '2026-03-01'), null);
});

test('the range reads like the canvas', () => {
  assert.equal(rangeLabel('2026-07-01', '2026-09-30'), 'Jul 1 – Sep 30, 2026');
  assert.equal(rangeLabel('2025-12-01', '2026-01-31'), 'Dec 1, 2025 – Jan 31, 2026');
});

test('the body: period and time zone always; from/to only for Custom; full trail only for the full edition', () => {
  const custom = { from: '2026-11-01', to: '2026-11-19' };
  assert.deepEqual(requestBody('LAST_QUARTER', custom, 'America/New_York', 'SUMMARY', true),
    { period: 'LAST_QUARTER', timeZone: 'America/New_York', includeFullAuditTrail: false });
  assert.deepEqual(requestBody('CUSTOM', custom, 'Europe/London', 'SUMMARY', false),
    { period: 'CUSTOM', from: '2026-11-01', to: '2026-11-19', timeZone: 'Europe/London', includeFullAuditTrail: false });
  assert.deepEqual(requestBody('LAST_YEAR', custom, 'Asia/Kolkata', 'FULL', true),
    { period: 'LAST_YEAR', timeZone: 'Asia/Kolkata', includeFullAuditTrail: true });
  assert.deepEqual(requestBody('LAST_MONTH', custom, 'UTC', 'FULL', false),
    { period: 'LAST_MONTH', timeZone: 'UTC', includeFullAuditTrail: false });
});

test("the server's file name is used; anything odd falls back to a fixed name", () => {
  assert.equal(fileNameFromDisposition('attachment; filename="compliance-report-residential-lending-2026-07-01-2026-09-30.pdf"'),
    'compliance-report-residential-lending-2026-07-01-2026-09-30.pdf');
  assert.equal(fileNameFromDisposition("attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf"), 'résumé.pdf');
  assert.equal(fileNameFromDisposition('attachment; filename=plain.pdf'), 'plain.pdf');
  for (const odd of [null, '', 'attachment', 'attachment; filename="../../etc/x.pdf"', 'attachment; filename="a.exe"',
    'attachment; filename="C:\\x.pdf"', 'attachment; filename=".pdf"']) {
    assert.equal(fileNameFromDisposition(odd), FALLBACK_FILE_NAME, String(odd));
  }
});

test('the error words', () => {
  assert.equal(errorText(400), "This period can't be used. Choose another period.");
  assert.equal(errorText(403), "You don't have access to create this report.");
  for (const other of [500, 503, 401, undefined]) {
    assert.equal(errorText(other), "The report couldn't be created. Try again. If it keeps failing, tell your IT Administrator.");
  }
});

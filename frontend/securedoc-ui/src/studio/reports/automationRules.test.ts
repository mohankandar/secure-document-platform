import { test } from 'node:test';
import assert from 'node:assert/strict';
import { AUTOMATION_PERIODS, automationHeadline, foundByLine, isAutomationReport } from './automationRules.ts';

const report = {
  period: 'TODAY' as const, from: '2026-11-27T05:00:00Z', to: '2026-11-28T05:00:00Z', automaticPercent: 90,
  automatic: 103, placedByPerson: 9, drawnByPerson: 2, byText: 92, byVision: 20,
};

test('task 0105: the Automation card offers today, this week and this month', () => {
  assert.deepEqual(AUTOMATION_PERIODS.map((p) => p.label), ['Today', 'This week', 'This month']);
});

test('task 0105: the headline is the automatic share, or says nothing was redacted', () => {
  assert.equal(automationHeadline(report), 'Redacted automatically: 90%');
  assert.equal(automationHeadline({ ...report, automaticPercent: null }), 'Nothing redacted yet in this period');
});

test('task 0105: the line under the counts gives the share found by text and by the vision model', () => {
  assert.equal(foundByLine(report), "Found by the page's text or OCR: 82%. By the vision model: 18%.");
  assert.equal(foundByLine({ ...report, byText: 0, byVision: 0 }), 'Nothing was found by the text or the vision model in this period.');
});

test('task 0105: an answer without the counts is not understood', () => {
  assert.ok(isAutomationReport(report));
  assert.ok(isAutomationReport({ ...report, automaticPercent: null }));
  assert.ok(!isAutomationReport({ ...report, automatic: '103' }));
  assert.ok(!isAutomationReport(null));
});

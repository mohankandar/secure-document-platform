import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  PRESETS, PREVIEW_WORDS, applyPreset, confirmWords, estimateQuery, latestOnly, needsConfirm, presetName, presetOf,
  previewHeadline, previewTiles, reviewChanged, saveLabel, type PolicyEstimate, type PolicyValues,
} from './policyRules.ts';

const BALANCED: PolicyValues = { reviewMode: 'UNCERTAIN_ONLY', autoRedactThreshold: 0.8, enabledEntityTypes: ['SSN', 'DATE_OF_BIRTH'], maskSsnLastFour: true };

const estimate = (current: [number, number], proposed: [number, number], extra: Partial<PolicyEstimate> = {}): PolicyEstimate => ({
  windowDays: 30, cases: 512, enoughData: true,
  current: { reviewMode: 'UNCERTAIN_ONLY', preset: 'BALANCED', casesToPerson: current[0], findingsToPerson: current[1] },
  proposed: { reviewMode: 'UNCERTAIN_ONLY', preset: 'LIGHT', casesToPerson: proposed[0], findingsToPerson: proposed[1] },
  ...extra,
});

test('the preset rule matches the server (PolicyPresetTest, including its edges)', () => {
  const cases: [PolicyValues['reviewMode'], number, string][] = [
    ['REVIEW_ALL', 0.80, 'STRICT'], ['REVIEW_ALL', 0.70, 'STRICT'], ['REVIEW_ALL', 0.95, 'STRICT'], ['REVIEW_ALL', 0.50, 'STRICT'],
    ['UNCERTAIN_ONLY', 0.80, 'BALANCED'], ['UNCERTAIN_ONLY', 0.79, 'CUSTOM'], ['UNCERTAIN_ONLY', 0.81, 'CUSTOM'],
    ['UNCERTAIN_ONLY', 0.70, 'LIGHT'], ['UNCERTAIN_ONLY', 0.69, 'CUSTOM'], ['UNCERTAIN_ONLY', 0.71, 'CUSTOM'],
    ['UNCERTAIN_ONLY', 0.75, 'CUSTOM'], ['UNCERTAIN_ONLY', 0.85, 'CUSTOM'], ['UNCERTAIN_ONLY', 0.50, 'CUSTOM'],
    ['UNCERTAIN_ONLY', 0.99, 'CUSTOM'], ['UNCERTAIN_ONLY', 0.801, 'CUSTOM'], ['UNCERTAIN_ONLY', 0.799, 'CUSTOM'],
  ];
  for (const [mode, threshold, expected] of cases) assert.equal(presetOf(mode, threshold), expected, `${mode} at ${threshold}`);
  // Floating-point noise around a saved value still matches, as on the server; so does the slider's n / 100.
  assert.equal(presetOf('UNCERTAIN_ONLY', 0.1 * 8), 'BALANCED');
  assert.equal(presetOf('UNCERTAIN_ONLY', 0.1 * 7), 'LIGHT');
  assert.equal(presetOf('UNCERTAIN_ONLY', 80 / 100), 'BALANCED');
  assert.equal(presetOf('UNCERTAIN_ONLY', 70 / 100), 'LIGHT');
});

test('the preset names and lines are the canvas words, with the owner’s Strict wording', () => {
  assert.deepEqual(PRESETS.map((p) => p.name), ['Strict', 'Balanced', 'Light']);
  assert.equal(PRESETS[0].line, 'A person checks every finding. Cases with nothing found are delivered without a check.');
  assert.equal(PRESETS[1].line, 'A person checks findings below 80% confidence. The rest are redacted automatically.');
  assert.equal(PRESETS[2].line, 'A person checks findings below 70% confidence. The rest are redacted automatically.');
  assert.equal(presetName('CUSTOM'), 'Custom');
});

test('choosing a preset changes only the review mode and the threshold', () => {
  const start = { ...BALANCED, autoRedactThreshold: 0.86, enabledEntityTypes: ['SSN'], maskSsnLastFour: false };
  assert.deepEqual(applyPreset(start, 'STRICT'), { ...start, reviewMode: 'REVIEW_ALL', autoRedactThreshold: 0.86 });
  assert.deepEqual(applyPreset(start, 'BALANCED'), { ...start, reviewMode: 'UNCERTAIN_ONLY', autoRedactThreshold: 0.8 });
  assert.deepEqual(applyPreset({ ...start, reviewMode: 'REVIEW_ALL' }, 'LIGHT'), { ...start, reviewMode: 'UNCERTAIN_ONLY', autoRedactThreshold: 0.7 });
  for (const p of PRESETS) assert.equal(presetOf(applyPreset(start, p.key).reviewMode, applyPreset(start, p.key).autoRedactThreshold), p.key);
});

test('a hand-edited threshold is Custom', () => {
  assert.equal(presetOf('UNCERTAIN_ONLY', 77 / 100), 'CUSTOM');
});

test('the save button names the preset; "Save" for Custom or for types and the switch only', () => {
  assert.equal(saveLabel(BALANCED, applyPreset(BALANCED, 'STRICT')), 'Save Strict');
  assert.equal(saveLabel(BALANCED, applyPreset(BALANCED, 'LIGHT')), 'Save Light');
  assert.equal(saveLabel({ ...BALANCED, autoRedactThreshold: 0.75 }, BALANCED), 'Save Balanced');
  assert.equal(saveLabel(BALANCED, { ...BALANCED, autoRedactThreshold: 0.83 }), 'Save');
  assert.equal(saveLabel(BALANCED, { ...BALANCED, enabledEntityTypes: ['SSN'] }), 'Save');
  assert.equal(saveLabel(BALANCED, { ...BALANCED, maskSsnLastFour: false }), 'Save');
  assert.equal(saveLabel(BALANCED, BALANCED), 'Save');
});

test('the preview shows only when the review mode or threshold changed', () => {
  assert.equal(reviewChanged(BALANCED, BALANCED), false);
  assert.equal(reviewChanged(BALANCED, { ...BALANCED, enabledEntityTypes: [], maskSsnLastFour: false }), false);
  assert.equal(reviewChanged(BALANCED, { ...BALANCED, autoRedactThreshold: 0.1 * 8 }), false);
  assert.equal(reviewChanged(BALANCED, { ...BALANCED, autoRedactThreshold: 0.79 }), true);
  assert.equal(reviewChanged(BALANCED, { ...BALANCED, reviewMode: 'REVIEW_ALL' }), true);
});

test('the estimate query sends the threshold with two decimals, and none for Strict', () => {
  assert.deepEqual(estimateQuery({ reviewMode: 'UNCERTAIN_ONLY', autoRedactThreshold: 0.7 }), { reviewMode: 'UNCERTAIN_ONLY', threshold: '0.70' });
  assert.deepEqual(estimateQuery({ reviewMode: 'UNCERTAIN_ONLY', autoRedactThreshold: 0.1 * 8 }), { reviewMode: 'UNCERTAIN_ONLY', threshold: '0.80' });
  assert.deepEqual(estimateQuery({ reviewMode: 'REVIEW_ALL', autoRedactThreshold: 0.8 }), { reviewMode: 'REVIEW_ALL' });
});

test('preview words: the headline names the preset or "these settings"', () => {
  assert.equal(previewHeadline({ reviewMode: 'REVIEW_ALL', autoRedactThreshold: 0.8 }, { windowDays: 30, cases: 512 }, 'Residential lending'),
    'If you save Strict, based on the last 30 days in Residential lending (512 cases)');
  assert.equal(previewHeadline({ reviewMode: 'UNCERTAIN_ONLY', autoRedactThreshold: 0.77 }, { windowDays: 30, cases: 1240 }, 'Commercial'),
    'If you save these settings, based on the last 30 days in Commercial (1,240 cases)');
  assert.equal(previewHeadline({ reviewMode: 'UNCERTAIN_ONLY', autoRedactThreshold: 0.7 }, { windowDays: 30, cases: 1 }, 'X'),
    'If you save Light, based on the last 30 days in X (1 case)');
});

test('preview words: more, fewer and no change', () => {
  const more = previewTiles(estimate([148, 340], [512, 2410]))!;
  assert.deepEqual(more.map((t) => t.label), ['Cases a person would have checked', 'Findings a person would have checked']);
  assert.deepEqual(more[0], { label: 'Cases a person would have checked', current: '148', proposed: '512', change: { direction: 'more', text: '▲ 364 more' } });
  assert.deepEqual(more[1].change, { direction: 'more', text: '▲ 2,070 more' });
  assert.equal(more[1].proposed, '2,410');
  const fewer = previewTiles(estimate([148, 340], [96, 205]))!;
  assert.deepEqual(fewer.map((t) => t.change.text), ['▼ 52 fewer', '▼ 135 fewer']);
  const same = previewTiles(estimate([148, 340], [148, 340]))!;
  assert.deepEqual(same.map((t) => t.change), [{ direction: 'none', text: 'no change' }, { direction: 'none', text: 'no change' }]);
});

test('preview words: not enough data, loading and failure', () => {
  const few: PolicyEstimate = { windowDays: 30, cases: 12, enoughData: false, current: { reviewMode: 'UNCERTAIN_ONLY', preset: 'BALANCED' }, proposed: { reviewMode: 'UNCERTAIN_ONLY', preset: 'LIGHT' } };
  assert.equal(previewTiles(few), null);
  // A count missing although enoughData is set is never read as zero.
  assert.equal(previewTiles({ ...estimate([1, 1], [1, 1]), proposed: { reviewMode: 'UNCERTAIN_ONLY', preset: 'LIGHT', casesToPerson: 1 } }), null);
  assert.equal(PREVIEW_WORDS.notEnough, 'Not enough recent cases to estimate.');
  assert.equal(PREVIEW_WORDS.loading, 'Working out the estimate…');
  assert.equal(PREVIEW_WORDS.failed, 'The estimate couldn’t be worked out. You can still save.');
  assert.equal(PREVIEW_WORDS.note, 'An estimate from past findings (type and confidence only). Nothing changes until you save, and only cases received after saving use it.');
});

test('only the latest estimate answer is used', () => {
  const latest = latestOnly();
  const first = latest.take();
  const second = latest.take();
  assert.equal(latest.isLatest(first), false);
  assert.equal(latest.isLatest(second), true);
  const third = latest.take();
  assert.equal(latest.isLatest(second), false);
  assert.equal(latest.isLatest(third), true);
});

test('the confirm step shows only when fewer findings would go to a person', () => {
  assert.equal(needsConfirm(estimate([148, 340], [96, 205])), true);
  assert.equal(needsConfirm(estimate([148, 340], [148, 339])), true);
  assert.equal(needsConfirm(estimate([148, 340], [140, 340])), false, 'fewer cases, same findings: no confirm');
  assert.equal(needsConfirm(estimate([148, 340], [512, 2410])), false);
  assert.equal(needsConfirm(estimate([148, 340], [148, 340])), false);
  assert.equal(needsConfirm(null), false);
  assert.equal(needsConfirm(undefined), false);
  assert.equal(needsConfirm({ ...estimate([148, 340], [96, 205]), enoughData: false }), false);
});

test('confirm words: names, numbers, threshold and the audit line', () => {
  const light = applyPreset(BALANCED, 'LIGHT');
  const w = confirmWords(BALANCED, light, estimate([148, 340], [96, 205]));
  assert.deepEqual(w, {
    oldName: 'Balanced', newName: 'Light', fewerFindings: '135', fewerCases: '52', days: 30, threshold: '70%',
    auditLine: 'This change, and who made it, is recorded in the audit trail and shown in the compliance report as Balanced → Light.',
  });
  // Cases that don't drop are left out.
  assert.equal(confirmWords(BALANCED, light, estimate([148, 340], [148, 205])).fewerCases, null);
  // Custom on either side.
  const custom = confirmWords({ ...BALANCED, autoRedactThreshold: 0.9 }, { ...BALANCED, autoRedactThreshold: 0.74 }, estimate([200, 500], [150, 400]));
  assert.equal(custom.oldName, 'Custom');
  assert.equal(custom.newName, 'Custom');
  assert.equal(custom.threshold, '74%');
  assert.match(custom.auditLine, /as Custom → Custom\.$/);
});

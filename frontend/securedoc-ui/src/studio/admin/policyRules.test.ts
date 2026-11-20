import { test } from 'node:test';
import assert from 'node:assert/strict';
import {
  MODE_WORDS, PRESETS, PREVIEW_WORDS, applyPreset, confirmWords, estimateQuery, estimateRunner, needsConfirm, presetName, presetOf,
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

/** A runner with hand-driven timers and answers: nothing happens until the test says so. */
function harness() {
  const timers: { run: () => void; ms: number; cleared: boolean }[] = [];
  const asked: { query: Record<string, string>; signal: AbortSignal; answer: (e: PolicyEstimate) => void; fail: () => void }[] = [];
  const runner = estimateRunner((query, signal) => new Promise<PolicyEstimate>((resolve, reject) => {
    asked.push({ query, signal, answer: resolve, fail: () => reject(new Error('failed')) });
    signal.addEventListener('abort', () => reject(new Error('aborted')));
  }), 300, {
    set: (run, ms) => { const t = { run, ms, cleared: false }; timers.push(t); return t; },
    clear: (h) => { (h as { cleared: boolean }).cleared = true; },
  });
  const tick = () => timers.filter((t) => !t.cleared).forEach((t) => { t.cleared = true; t.run(); });
  const heard: [string, string][] = [];
  runner.subscribe((key, r) => heard.push([key, r.status]));
  return { runner, timers, asked, tick, heard };
}
const flush = () => new Promise((r) => setTimeout(r, 0));
const Q = (t: string) => ({ reviewMode: 'UNCERTAIN_ONLY', threshold: t });

test('estimate runner: asks once, 300 ms after the last change, and only the latest answer is heard', async () => {
  const h = harness();
  h.runner.request('a', Q('0.84'));
  h.runner.request('b', Q('0.81'));
  assert.equal(h.timers[0].cleared, true, 'the first wait is cancelled');
  assert.equal(h.timers[1].ms, 300);
  assert.equal(h.asked.length, 0, 'nothing asked before the delay');
  h.tick();
  assert.deepEqual(h.asked.map((a) => a.query.threshold), ['0.81']);
  h.runner.request('b', Q('0.81'));
  assert.equal(h.asked.length, 1, 'the same values are not asked again');
  h.runner.request('c', Q('0.70'));
  assert.equal(h.asked[0].signal.aborted, true, 'the old request is cancelled');
  h.tick();
  h.asked[1].answer(estimate([148, 340], [96, 205]));
  await flush();
  assert.deepEqual(h.heard, [['c', 'ready']]);
  assert.equal(h.runner.answer('c')?.status, 'ready');
  assert.equal(h.runner.answer('b'), null);
});

test('estimate runner: Save during the 300 ms wait asks at once and gets that answer', async () => {
  const h = harness();
  h.runner.request('a', Q('0.70'));
  const waiting = h.runner.now('a', Q('0.70'));
  assert.equal(h.timers[0].cleared, true, 'the delay is skipped');
  assert.equal(h.asked.length, 1);
  h.asked[0].answer(estimate([148, 340], [96, 205]));
  const r = await waiting;
  assert.equal(r.status, 'ready');
  // Asked again for the same values, Save reuses the answer.
  assert.equal((await h.runner.now('a', Q('0.70'))).status, 'ready');
  assert.equal(h.asked.length, 1);
});

test('estimate runner: Save while the request runs waits for it; a slow or failed answer is waited for, not asked twice', async () => {
  const h = harness();
  h.runner.request('a', Q('0.70'));
  h.tick();
  const first = h.runner.now('a', Q('0.70'));
  const second = h.runner.now('a', Q('0.70'));
  assert.equal(h.asked.length, 1, 'one request, however often Save is pressed');
  let done = false;
  void first.then(() => { done = true; });
  await flush();
  assert.equal(done, false, 'still waiting while the answer is slow');
  h.asked[0].fail();
  assert.equal((await first).status, 'failed');
  assert.equal((await second).status, 'failed');
  h.runner.request('a', Q('0.70'));
  assert.equal(h.asked.length, 1, 'no retry after a failure');
});

test('estimate runner: an answer for other values never answers a Save', async () => {
  const h = harness();
  const waiting = h.runner.now('a', Q('0.70'));
  h.runner.request('b', Q('0.75'));
  assert.equal((await waiting).status, 'dropped');
  assert.equal(h.asked[0].signal.aborted, true);
  h.asked[0].answer(estimate([148, 340], [96, 205]));
  await flush();
  assert.equal(h.runner.answer('a'), null);
  assert.deepEqual(h.heard, []);
  // Closing the card drops what is waiting.
  const closing = h.runner.now('b', Q('0.75'));
  h.runner.drop();
  assert.equal((await closing).status, 'dropped');
});

test('Fine-tune words: "Every finding", with Strict’s hint; the other mode keeps its hint', () => {
  assert.equal(MODE_WORDS.REVIEW_ALL.button, 'Every finding');
  assert.equal(MODE_WORDS.REVIEW_ALL.hint, 'Every finding goes to a person. Cases with nothing found are delivered without a check.');
  assert.equal(MODE_WORDS.UNCERTAIN_ONLY.button, 'Only uncertain findings');
  assert.equal(MODE_WORDS.UNCERTAIN_ONLY.hint, 'Findings below this go to a reviewer.');
  assert.ok(PRESETS[0].line.endsWith(MODE_WORDS.REVIEW_ALL.hint.replace('Every finding goes to a person. ', '')));
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
    oldName: 'Balanced', newName: 'Light', newPhrase: 'Light', keepLabel: 'Keep Balanced', saveLabel: 'Save Light',
    fewerFindings: '135', fewerCases: '52', days: 30, threshold: '70%',
    auditLine: 'This change, and who made it, is recorded in the audit trail and shown in the compliance report as Balanced → Light.',
  });
  // Cases that don't drop are left out.
  assert.equal(confirmWords(BALANCED, light, estimate([148, 340], [148, 205])).fewerCases, null);
  // Custom on either side (task 0020 A2): "your current settings" / "these settings"; the audit line keeps the names.
  const custom = confirmWords({ ...BALANCED, autoRedactThreshold: 0.9 }, { ...BALANCED, autoRedactThreshold: 0.74 }, estimate([200, 500], [150, 400]));
  assert.equal(custom.oldName, 'Custom');
  assert.equal(custom.newName, 'Custom');
  assert.equal(custom.threshold, '74%');
  assert.match(custom.auditLine, /as Custom → Custom\.$/);
  const customWords = { newPhrase: 'these settings', keepLabel: 'Keep your current settings', saveLabel: 'Save these settings' };
  assert.deepEqual({ newPhrase: custom.newPhrase, keepLabel: custom.keepLabel, saveLabel: custom.saveLabel }, customWords);
  const fromCustom = confirmWords({ ...BALANCED, autoRedactThreshold: 0.85 }, light, estimate([200, 500], [150, 400]));
  assert.deepEqual({ newPhrase: fromCustom.newPhrase, keepLabel: fromCustom.keepLabel, saveLabel: fromCustom.saveLabel }, customWords);
  assert.match(fromCustom.auditLine, /as Custom → Light\.$/);
  const toCustom = confirmWords(BALANCED, { ...BALANCED, autoRedactThreshold: 0.74 }, estimate([200, 500], [150, 400]));
  assert.deepEqual({ newPhrase: toCustom.newPhrase, keepLabel: toCustom.keepLabel, saveLabel: toCustom.saveLabel }, customWords);
  assert.match(toCustom.auditLine, /as Balanced → Custom\.$/);
});

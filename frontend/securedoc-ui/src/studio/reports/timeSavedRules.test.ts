import { test } from 'node:test';
import assert from 'node:assert/strict';
import { SENSITIVE_TYPES } from '../words.ts';
import {
  MINUTES_PER_PAGE_ERROR, SHORT_TYPE_NAMES, formulaNote, isTimeSaved, minutesBeforeRounding, minutesPerPageError,
  minutesPerPageWords, shortTypeList, shortTypeName, timeSavedWords, timeWords, type TimeSaved,
} from './timeSavedRules.ts';

const sample: TimeSaved = {
  casesDelivered: 512, casesNoPerson: 348, percentNoPerson: 67, pagesChecked: 14220, documentsChecked: 1873, itemsRedacted: 3912,
  itemTypes: ['Social Security Number', 'Date of birth', 'Credit card number', 'Credit score'], pagesNoPerson: 9690, minutesPerPage: 1, minutesSaved: 9690,
};

test('U024: the four tiles in the canvas words', () => {
  const w = timeSavedWords(sample);
  assert.deepEqual(w.noPerson, { value: '67%', sub: '348 of 512 cases' });
  assert.deepEqual(w.pages, { value: '14,220', sub: 'in 1,873 delivered documents' });
  assert.deepEqual(w.items, { value: '3,912', sub: 'SSN, date of birth, card, credit score' });
  assert.deepEqual(w.saved, { value: '≈ 161 h' });
});

test('U024: no delivered cases: "—" for the percent and the types, 0 min saved', () => {
  const w = timeSavedWords({ ...sample, casesDelivered: 0, casesNoPerson: 0, percentNoPerson: null, pagesChecked: 0, documentsChecked: 0,
    itemsRedacted: 0, itemTypes: [], pagesNoPerson: 0, minutesSaved: 0 });
  assert.deepEqual(w.noPerson, { value: '—', sub: '0 of 0 cases' });
  assert.deepEqual(w.pages, { value: '0', sub: 'in 0 delivered documents' });
  assert.deepEqual(w.items, { value: '0', sub: '—' });
  assert.equal(w.saved.value, '0 min');
  assert.equal(w.formula.result, '= 0 minutes, rounded down');
  assert.equal(w.formula.resultValue, '0 min');
});

test('U024: time saved in the PDF words (TimeSavedRule.timeWords): minutes under an hour, whole hours rounded down', () => {
  assert.equal(timeWords(0), '0 min');
  assert.equal(timeWords(-5), '0 min');
  assert.equal(timeWords(Number.NaN), '0 min');
  assert.equal(timeWords(1), '≈ 1 min');
  assert.equal(timeWords(59), '≈ 59 min');
  assert.equal(timeWords(60), '≈ 1 h');
  assert.equal(timeWords(119), '≈ 1 h');
  assert.equal(timeWords(302), '≈ 5 h');
  assert.equal(timeWords(9690), '≈ 161 h');
  assert.equal(timeWords(600_000), '≈ 10,000 h');
});

test('U024: one short name per vocabulary type, and the names join with ", "', () => {
  for (const t of SENSITIVE_TYPES) assert.ok(SHORT_TYPE_NAMES[t.key], `short name for ${t.key}`);
  assert.equal(Object.keys(SHORT_TYPE_NAMES).length, SENSITIVE_TYPES.length);
  assert.equal(shortTypeName('Social Security Number'), 'SSN');
  assert.equal(shortTypeName('Date of birth'), 'date of birth');
  assert.equal(shortTypeName('Credit card number'), 'card');
  assert.equal(shortTypeName('Credit score'), 'credit score');
  assert.equal(shortTypeName('Bank account'), 'Bank account', 'an unknown name stays as sent');
  assert.equal(shortTypeList(['Social Security Number', 'Credit card number']), 'SSN, card');
  assert.equal(shortTypeList([]), '—');
});

test('U024: the formula panel: the numbers, 1 decimal only when needed, the same words as the tile', () => {
  const one = timeSavedWords(sample).formula;
  assert.deepEqual(one, {
    pages: '9,690', minutesPerPage: '1', result: '= 9,690 minutes, rounded down', resultValue: '≈ 161 h',
    note: 'Pages a person reviewed count as zero. The 1 minute per page is deliberately low and is set by your Business Administrator in Admin › Redaction & retention › Report targets. This is an estimate, not a measurement.',
  });
  const half = timeSavedWords({ ...sample, pagesNoPerson: 121, minutesPerPage: 2.5, minutesSaved: 302 }).formula;
  assert.equal(half.pages, '121');
  assert.equal(half.minutesPerPage, '2.5');
  assert.equal(half.result, '= 302.5 minutes, rounded down');
  assert.equal(half.resultValue, '≈ 5 h');
  assert.equal(minutesBeforeRounding(3, 0.5), '1.5');
  assert.equal(minutesBeforeRounding(7, 0.1 + 0.2 + 1.2), '10.5', 'no binary noise');
  assert.equal(minutesPerPageWords(1), '1');
  assert.equal(minutesPerPageWords(1.5), '1.5');
  assert.equal(minutesPerPageWords(30), '30');
});

test('U024: "deliberately low" only for the default of 1 minute', () => {
  assert.match(formulaNote(1), /The 1 minute per page is deliberately low and is set by/);
  for (const m of [0.5, 1.5, 2.5, 30]) {
    const note = formulaNote(m);
    assert.doesNotMatch(note, /deliberately low/);
    assert.match(note, new RegExp(`The ${String(m).replace('.', '\\.')} minutes per page is set by your Business Administrator in Admin › Redaction & retention › Report targets\\.`));
    assert.match(note, /^Pages a person reviewed count as zero\. .* This is an estimate, not a measurement\.$/);
  }
});

test('U024: minutes per page: 0.5 to 30 in steps of 0.5', () => {
  for (const ok of [0.5, 1, 1.5, 2.5, 29.5, 30]) assert.equal(minutesPerPageError(ok), null, String(ok));
  for (const bad of [0, 0.4, 1.25, 30.5, 31, -1, Number.NaN, Number.POSITIVE_INFINITY, undefined]) assert.equal(minutesPerPageError(bad), MINUTES_PER_PAGE_ERROR, String(bad));
  assert.equal(MINUTES_PER_PAGE_ERROR, 'Enter a number from 0.5 to 30, in steps of 0.5.');
});

test('U024: an answer the tiles cannot read is refused, not guessed', () => {
  assert.ok(isTimeSaved(sample));
  assert.ok(isTimeSaved({ ...sample, percentNoPerson: null, itemTypes: [] }));
  assert.ok(!isTimeSaved(undefined));
  assert.ok(!isTimeSaved({ ...sample, minutesPerPage: null }));
  assert.ok(!isTimeSaved({ ...sample, minutesPerPage: 1.25 }));
  assert.ok(!isTimeSaved({ ...sample, pagesChecked: '14220' }));
  assert.ok(!isTimeSaved({ ...sample, casesNoPerson: -1 }));
  assert.ok(!isTimeSaved({ ...sample, itemTypes: [3] }));
});

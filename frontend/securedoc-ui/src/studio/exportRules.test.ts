import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { LV_CODES } from './lvErrorCodes.generated.ts';
import { STAGE_NAMES } from './words.ts';
import {
  CASE_EXPORT_WORDS, DESTINATION_CODE_LABEL, EXPORT_STATES, RESEND_WORDS, destinationCodeLine, destinationCodeOf, destinationCodesOf, internalReasonOf, SUPPORT_EXPORT_WORDS, WAITING_WORDS, dayAndTime, destinationKind,
  destinationStateWords, exportNeedsYou, exportStateOf, exportStatusLabel, exportTone, groupCount, groupKind, isExporting,
  itGroupTitle, jobForState, mostFrequentCode, reasonLabel, refusedHeading, refusedWhen, resendAllLabel, resendConfirmLabel,
  resendReasonLines, resendTitle, resendToast, retentionPlaceholder, retentionValueError, retryAllLabel, showWaitingNav,
  signInBody, signInTitle, supportBadgeCount, reachAlertWords, triedLine, waitingText, type ExportJobView,
  caseTile, checkStillFailing, destinationsNote, everyWords, itCasesLine, itReason, overWords, pausedBody, spanWords, waitingParts, UNTITLED_CASE,
  type ExportTimings,
} from './exportRules.ts';

const here = dirname(fileURLToPath(import.meta.url));
const job = (over: Partial<ExportJobView>): ExportJobView => ({ jobId: 'j', packageId: 'p', stage: 'EXPORT', status: 'READY', attempts: 0, ...over });

test('0029: the Exporting status uses the owner’s name and the four details; Exported; nothing for NONE', () => {
  assert.equal(exportStatusLabel('READY'), 'Exporting · ready');
  assert.equal(exportStatusLabel('DESTINATION_PAUSED'), 'Exporting · destination paused');
  assert.equal(exportStatusLabel('WAITING_FOR_YOU'), 'Exporting · waiting for you');
  assert.equal(exportStatusLabel('NEEDS_IT'), 'Exporting · needs IT');
  assert.equal(exportStatusLabel('EXPORTED'), 'Exported');
  assert.equal(exportStatusLabel('NONE'), null);
  assert.deepEqual(EXPORT_STATES.filter(isExporting), ['READY', 'DESTINATION_PAUSED', 'WAITING_FOR_YOU', 'NEEDS_IT']);
  assert.deepEqual(EXPORT_STATES.map(exportTone), [null, 'ready', 'paused', 'you', 'it', 'done']);
});

test('0029: an unknown or missing export state is NONE (the case shows its status as today)', () => {
  for (const raw of [undefined, null, '', 'READY_FOR_EXPORT', 'WAITING_TO_RETRY', 'ready']) assert.equal(exportStateOf(raw), 'NONE', String(raw));
  assert.equal(exportStateOf('NEEDS_IT'), 'NEEDS_IT');
});

test('0029: the 0028 states match the backend enum exactly', () => {
  const java = readFileSync(resolve(here, '..', '..', '..', '..', 'backend', 'securedoc-application', 'src', 'main', 'java', 'com', 'securedoc',
    'platform', 'application', 'pipeline', 'ExportJobService.java'), 'utf8');
  const block = /enum CaseExportState \{([\s\S]*?)\}/.exec(java);
  assert.ok(block);
  const names = block[1].replace(/\/\*\*[\s\S]*?\*\//g, '').split(',').map((s) => s.trim()).filter(Boolean);
  assert.deepEqual(names, [...EXPORT_STATES]);
});

test('0029: a refused case needs only a person who may resend', () => {
  assert.equal(exportNeedsYou('WAITING_FOR_YOU', ['EXPORT_RESEND']), true);
  assert.equal(exportNeedsYou('WAITING_FOR_YOU', ['DASHBOARD_ACCESS']), false);
  assert.equal(exportNeedsYou('NEEDS_IT', ['EXPORT_RESEND']), false);
});

test('0029: Waiting for you shows in the nav only when something waits and the person may resend', () => {
  assert.equal(showWaitingNav(['EXPORT_RESEND'], 3), true);
  assert.equal(showWaitingNav(['EXPORT_RESEND'], 0), false);
  assert.equal(showWaitingNav(['EXPORT_RESEND'], undefined), false);
  assert.equal(showWaitingNav(['DASHBOARD_ACCESS'], 3), false);
});

test('0029: Waiting for you words are the approved wording', () => {
  assert.equal(WAITING_WORDS.intro, 'A destination refused these cases. Once the cause is fixed with the destination’s team, send them again. Open a case to send just that one.');
  assert.equal(`${WAITING_WORDS.footnoteBefore}${WAITING_WORDS.footnoteCode}${WAITING_WORDS.footnoteAfter}`,
    'Each case is sent once more. If the destination refuses it again, it goes to IT with code LV-DLV-015. Cases waiting for a destination that is down are not listed here: they go out on their own when it is back.');
});

test('0029: a group says its kind and count; the most frequent reason wins, ties go to the lower code', () => {
  const full = { total: 3, jobs: [job({}), job({}), job({})] };
  const split = { total: 2, jobs: [job({ stage: 'SPLIT_EXPORT' }), job({ stage: 'SPLIT_EXPORT' })] };
  assert.equal(groupKind(full), 'full case');
  assert.equal(groupKind(split), 'split by barcode');
  assert.equal(groupCount(full), '3 cases');
  assert.equal(groupCount(split), '2 documents');
  assert.equal(groupCount({ total: 1, jobs: [job({})] }), '1 case');
  assert.equal(mostFrequentCode({ 'LV-DLV-004': 1, 'LV-DLV-003': 2 }), 'LV-DLV-003');
  assert.equal(mostFrequentCode({ 'LV-DLV-004': 1, 'LV-DLV-003': 1 }), 'LV-DLV-003');
  assert.equal(mostFrequentCode({}), null);
  assert.equal(resendAllLabel(3), 'Resend all 3');
});

test('0029: every code reads "Title · LV-XXX-NNN" from the generated list', () => {
  for (const code of ['LV-DLV-003', 'LV-DLV-004', 'LV-DLV-005', 'LV-DLV-007', 'LV-DLV-015']) {
    assert.equal(reasonLabel(code), `${LV_CODES[code].title} · ${code}`);
  }
  assert.equal(reasonLabel('DS-SEN-006'), `${LV_CODES['LV-SYS-001'].title} · LV-SYS-001`, 'a code not in the list is LV-SYS-001');
});

test('0029: Resend all: the confirmation and the toast are the approved wording', () => {
  assert.equal(resendTitle(3, 'Document vault'), 'Resend 3 cases to Document vault?');
  assert.equal(resendTitle(1, 'Document vault'), 'Resend 1 case to Document vault?');
  assert.equal(RESEND_WORDS.body, 'They go back in the export queue and are sent one by one, in the order they were refused. You can leave this page; it carries on.');
  assert.equal(RESEND_WORDS.check, 'Has the destination’s team fixed the cause? If not, they will be refused again. Each case is sent once more; a case refused again goes to IT.');
  assert.equal(RESEND_WORDS.notNow, 'Not now');
  assert.equal(resendConfirmLabel(3), 'Resend 3 cases');
  const lines = resendReasonLines({ 'LV-DLV-004': 1, 'LV-DLV-003': 2 }).map((l) => `${l.lead} ${l.what} · ${l.code}`);
  assert.deepEqual(lines, ['2 refused for the sensitivity level · LV-DLV-003', '1 refused for the file · LV-DLV-004']);
  assert.equal(resendReasonLines({ 'LV-DLV-008': 1 }).map((l) => `${l.lead} ${l.what} · ${l.code}`)[0], `1 · ${LV_CODES['LV-DLV-008'].title} · LV-DLV-008`);
  assert.equal(resendToast(3, 'Document vault'), '3 cases queued for Document vault. They’ll show as “Exporting · ready” until sent.');
});

test('0029: the case panel says who refused it and when', () => {
  const now = new Date(2026, 10, 21, 15, 0).getTime();
  assert.equal(refusedHeading('Document vault'), 'Document vault refused this case');
  assert.match(refusedWhen(new Date(2026, 10, 21, 9, 41).toISOString(), now), /^Refused today at 9:41\sAM\.$/);
  assert.match(refusedWhen(new Date(2026, 10, 20, 16, 12).toISOString(), now), /^Refused yesterday at 4:12\sPM\.$/);
  assert.equal(CASE_EXPORT_WORDS.waitingEyebrow, 'Exporting · waiting for you');
  assert.equal(CASE_EXPORT_WORDS.resendThis, 'Resend this case');
  assert.equal(CASE_EXPORT_WORDS.afterRefusal, 'Once the destination’s team has fixed it, send it again. It gets one more attempt.');
  assert.match(dayAndTime(new Date(2026, 10, 21, 9, 41).toISOString(), now), /^Today 9:41\sAM$/);
  assert.match(dayAndTime(new Date(2026, 10, 20, 16, 12).toISOString(), now), /^Yesterday 4:12\sPM$/);
  const jobs = [job({ jobId: 'a', status: 'DONE' }), job({ jobId: 'b', status: 'WAITING_FOR_YOU' })];
  assert.equal(jobForState(jobs, 'WAITING_FOR_YOU')?.jobId, 'b');
});

test('0029: Support: grouped IT cards, the sign-in card, what was tried, the badge counts groups', () => {
  const now = new Date(2026, 10, 21, 15, 0).getTime();
  assert.match(itGroupTitle({ count: 4, destinationName: 'Document vault', firstFailedAt: new Date(2026, 10, 21, 10, 0).toISOString() }, now),
    /^4 cases failing to Document vault since 10:00\sAM$/);
  // Task 0036: the span comes from the settings (the default schedule adds up to 17 h 20 min, not 24 h).
  assert.equal(triedLine('LV-DLV-007', DEFAULT_TIMINGS), 'The destination answers its health check, so it’s these cases. Each was retried over about 17 hours and rebuilt once.');
  assert.equal(triedLine('LV-DLV-003'), null);
  assert.equal(signInTitle('Title company'), 'Title company refused LexaVault’s sign-in');
  assert.equal(signInBody(12), 'Exports to it are on hold: 12 waiting, nothing lost. Fix the credential in Admin › Integrations, then set it back to working.');
  assert.equal(retryAllLabel(4), 'Retry all 4');
  assert.equal(supportBadgeCount({ groups: [{} as never, {} as never] }), 2);
  assert.equal(supportBadgeCount(undefined), 0);
  assert.equal(SUPPORT_EXPORT_WORDS.alertTitle, 'LexaVault can’t reach its destinations');
  assert.equal(SUPPORT_EXPORT_WORDS.alertLine, 'The problem is likely LexaVault’s own network, not the destinations.');
});

test('0029: Support: destination states and the waiting column', () => {
  const now = new Date(2026, 10, 21, 11, 0).getTime();
  const paused = destinationStateWords({ state: 'PAUSED', pausedAt: new Date(2026, 10, 21, 10, 42).toISOString(), nextCheckAt: new Date(2026, 10, 21, 11, 7).toISOString() }, now);
  assert.equal(paused.tone, 'paused');
  assert.match(paused.state, /^Paused since 10:42\sAM$/);
  assert.match(paused.detail ?? '', /^Health check failing · next check 11:07\sAM$/);
  assert.deepEqual(destinationStateWords({ state: 'WORKING' }, now), { tone: 'working', state: 'Working' });
  assert.deepEqual(destinationStateWords({ state: 'NEEDS_IT', lastLvCode: 'LV-DLV-005' }, now),
    { tone: 'it', state: 'Needs IT', detail: `${LV_CODES['LV-DLV-005'].title} · LV-DLV-005` });
  assert.equal(waitingText('WORKING', 3), '3 ready');
  assert.equal(waitingText('PAUSED', 27), '27 waiting');
  assert.equal(waitingText('PAUSED', 0), 'Nothing waiting');
  assert.equal(destinationKind('DERIVED_DOCUMENT_DELIVERY'), 'split by barcode');
  assert.equal(destinationKind('DOCUMENT_STORE_DELIVERY'), 'full case');
  assert.equal(destinationKind('LOCAL_FOLDER'), 'full case');
});

test('0029: retention per destination: the default reads "Same as …"; 1 to 72 whole hours or empty', () => {
  assert.equal(retentionPlaceholder('master', 24), 'Same as redacted copies (24 hours)');
  assert.equal(retentionPlaceholder('split', 1), 'Same as the main export (1 hour)');
  assert.equal(retentionValueError(null, 1, 72), null);
  assert.equal(retentionValueError(72, 1, 72), null);
  assert.match(retentionValueError(73, 1, 72) ?? '', /from 1 to 72/);
  assert.match(retentionValueError(0, 1, 72) ?? '', /from 1 to 72/);
  assert.match(retentionValueError(2.5, 1, 72) ?? '', /whole number/);
});

test('0029/0045: no screen names another product (the destination is named by its connection)', () => {
  // The names are the product-name guard's list (scripts/ci/product-names.txt), so the screens and CI check the same
  // words. Since task 0045 no source file names another product, not even in a pattern (0030: LV-CFG-005 is "More
  // than one main export destination").
  const names = new RegExp(readFileSync(resolve(here, '..', '..', '..', '..', 'scripts', 'ci', 'product-names.txt'), 'utf8')
    .split(/\r?\n/)[0]);
  const known: Record<string, number> = {};
  const root = resolve(here, '..');
  const found: Record<string, number> = {};
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) { walk(path); continue; }
      if (!/\.tsx?$/.test(entry.name) || /\.test\.ts$/.test(entry.name)) continue;
      const lines = readFileSync(path, 'utf8').split('\n').filter((l) => names.test(l)).length;
      if (lines) found[path.slice(root.length + 1)] = lines;
    }
  };
  walk(root);
  assert.deepEqual(found, known);
  assert.equal(LV_CODES['LV-CFG-005'].title, 'More than one main export destination');
  assert.equal(LV_CODES['LV-CFG-005'].description, 'Only one enabled main export destination is allowed per entity.');
});

test('0030: the destination’s own code is read from the job’s detail; only a safe code is shown', () => {
  assert.equal(internalReasonOf('DELIVERY_SENSITIVITY_REFUSED; destination=DS-SEN-006'), 'DELIVERY_SENSITIVITY_REFUSED');
  assert.equal(destinationCodeOf('DELIVERY_SENSITIVITY_REFUSED; destination=DS-SEN-006'), 'DS-SEN-006');
  assert.equal(destinationCodeLine('DS-SEN-006'), 'Destination code DS-SEN-006');
  assert.equal(DESTINATION_CODE_LABEL, 'Destination code');
  // No code, or a detail from before task 0030: the reason alone.
  assert.equal(internalReasonOf('DELIVERY_IO'), 'DELIVERY_IO');
  assert.equal(destinationCodeOf('DELIVERY_IO'), null);
  for (const empty of [null, undefined, '']) {
    assert.equal(internalReasonOf(empty), null);
    assert.equal(destinationCodeOf(empty), null);
  }
  // Not a safe code (letters, digits, '-', '_', up to 40): dropped.
  for (const unsafe of ['<b>DS</b>', 'DS SEN 006', 'DS-SEN-006;x', 'A'.repeat(41), '', 'ÄBC', 'a/b']) {
    assert.equal(destinationCodeOf(`DELIVERY_SENSITIVITY_REFUSED; destination=${unsafe}`), null, unsafe);
    assert.equal(internalReasonOf(`DELIVERY_SENSITIVITY_REFUSED; destination=${unsafe}`), 'DELIVERY_SENSITIVITY_REFUSED', unsafe);
  }
  assert.equal(destinationCodeOf(`R; destination=${'A'.repeat(40)}`), 'A'.repeat(40));
  // A group's codes, each once, in the order met.
  assert.deepEqual(destinationCodesOf([
    job({ detail: 'DELIVERY_IO; destination=DS-SUB-017' }), job({ detail: 'DELIVERY_IO' }),
    job({ detail: 'DELIVERY_IO; destination=DS-SUB-017' }), job({ detail: 'DELIVERY_IO; destination=DS-SUB-018' }),
    job({ detail: 'DELIVERY_IO; destination=<x>' }),
  ]), ['DS-SUB-017', 'DS-SUB-018']);
});

test('0030: one word for finished cases: "Exported" (the stage, the chip, the stage tile, the Cases groups)', () => {
  assert.equal(STAGE_NAMES.delivered, 'Exported');
  assert.equal(exportStatusLabel('EXPORTED'), 'Exported');
  const studio = resolve(here);
  const data = readFileSync(join(studio, 'data.ts'), 'utf8');
  assert.doesNotMatch(data, /label: 'Delivered'/);
  for (const file of ['Desk.tsx', 'CaseDrawer.tsx', 'atoms.tsx', 'data.ts']) {
    const text = readFileSync(join(studio, file), 'utf8');
    assert.doesNotMatch(text, /'Delivered|`Delivered|>Delivered|\bdelivered<\/|\} delivered\b/, `${file} still names a finished case "Delivered"`);
  }
});

/** The defaults: probe every 5 min; 5 min + 15 min + 1 h + 4 h + 12 h = 17 h 20 min; rebuilt before the 3rd retry; 3 interruptions. */
const DEFAULT_TIMINGS: ExportTimings = { probeIntervalSeconds: 300, retrySpanSeconds: 62_400, retries: 5, rebuiltOnce: true, maxInterruptions: 3 };
/** The L5 test profile: probe every 4 s, five 2 s retries. */
const TEST_TIMINGS: ExportTimings = { probeIntervalSeconds: 4, retrySpanSeconds: 10, retries: 5, rebuiltOnce: true, maxInterruptions: 3 };

test('0036: timings in plain words: seconds under a minute, minutes under an hour, hours above', () => {
  assert.deepEqual(spanWords(300), { about: false, text: '5 minutes' });
  assert.deepEqual(spanWords(62_400), { about: true, text: '17 hours' });
  assert.deepEqual(spanWords(4), { about: false, text: '4 seconds' });
  assert.deepEqual(spanWords(1), { about: false, text: '1 second' });
  assert.deepEqual(spanWords(90), { about: true, text: '2 minutes' });
  assert.deepEqual(spanWords(3600), { about: false, text: '1 hour' });
  assert.deepEqual(spanWords(3599), { about: true, text: '1 hour' }, '59 min 59 s is about an hour, not 60 minutes');
  for (const bad of [0, -5, NaN, Infinity, null, undefined]) assert.equal(spanWords(bad as number), null, String(bad));
  assert.equal(everyWords(300), 'every 5 minutes');
  assert.equal(everyWords(60), 'every minute');
  assert.equal(everyWords(3600), 'every hour');
  assert.equal(everyWords(4), 'every 4 seconds');
  assert.equal(everyWords(7000), 'about every 2 hours');
  assert.equal(overWords(62_400), 'about 17 hours');
  assert.equal(overWords(300), '5 minutes');
});

test('0036: every sentence that names a probe interval or a retry span takes it from the settings', () => {
  assert.equal(pausedBody(DEFAULT_TIMINGS), 'The destination isn’t answering. LexaVault checks it every 5 minutes and sends this case on its own when it is back. Nothing is lost.');
  assert.equal(pausedBody(TEST_TIMINGS), 'The destination isn’t answering. LexaVault checks it every 4 seconds and sends this case on its own when it is back. Nothing is lost.');
  assert.equal(destinationsNote(DEFAULT_TIMINGS), 'A paused destination is checked every 5 minutes and resumes on its own, in order, when it answers.');
  assert.equal(destinationsNote(TEST_TIMINGS), 'A paused destination is checked every 4 seconds and resumes on its own, in order, when it answers.');
  assert.equal(checkStillFailing(DEFAULT_TIMINGS), 'still fails its health check. It stays paused and is checked again in 5 minutes.');
  assert.equal(triedLine('LV-DLV-001', TEST_TIMINGS), 'The destination answers its health check, so it’s these cases. Each was retried over 10 seconds and rebuilt once.');
  assert.equal(triedLine('LV-DLV-002', { ...DEFAULT_TIMINGS, rebuiltOnce: false }), 'The destination answers its health check, so it’s these cases. Each was retried over about 17 hours.');
  assert.equal(triedLine('LV-PRC-008', DEFAULT_TIMINGS), 'The export was interrupted 3 times while being sent.');
  assert.equal(triedLine('LV-PRC-008', { ...DEFAULT_TIMINGS, maxInterruptions: 1 }), 'The export was interrupted 1 time while being sent.');
  assert.equal(triedLine('LV-DLV-015', DEFAULT_TIMINGS), 'A person sent these again after the destination refused them, and it refused them again.');
  // Without the settings (an older server) no sentence names a number it does not know.
  for (const text of [pausedBody(null), destinationsNote(undefined), checkStillFailing(null), triedLine('LV-DLV-001'), triedLine('LV-PRC-008')]) {
    assert.doesNotMatch(text ?? '', /\d/, text ?? '');
  }
  // No fixed timing is left in the export words.
  const source = readFileSync(join(here, 'exportRules.ts'), 'utf8');
  assert.doesNotMatch(source, /every 5 minutes'|over 24 hours|again in 5 minutes'|interrupted 3 times/);
});

test('0036: Support › Destinations, Waiting: each state on its own; Needs IT is never ready', () => {
  assert.equal(waitingText('WORKING', 5, { queued: 2, waitingForYou: 1, needsIt: 2 }), '2 ready · 1 waiting for you · 2 need IT');
  assert.deepEqual(waitingParts('WORKING', 2, { queued: 0, waitingForYou: 0, needsIt: 2 }), [{ text: '2 need IT', tone: 'it' }], '0035 picture 05 said "2 ready"');
  assert.equal(waitingText('WORKING', 1, { queued: 0, waitingForYou: 0, needsIt: 1 }), '1 needs IT');
  assert.equal(waitingText('PAUSED', 27, { queued: 27, waitingForYou: 0, needsIt: 0 }), '27 waiting');
  assert.equal(waitingText('WORKING', 0, { queued: 0, waitingForYou: 0, needsIt: 0 }), 'Nothing waiting');
  assert.equal(waitingText('WORKING', 3, null), '3 ready', 'an older server: the one total as before');
});

test('0036: the IT card says the cases can’t be delivered when the destination answers, and lists case numbers', () => {
  const now = new Date(2026, 10, 22, 15, 0).getTime();
  const group = { count: 2, destinationName: 'Local export folder', firstFailedAt: new Date(2026, 10, 22, 2, 12).toISOString() };
  assert.match(itGroupTitle(group, now, true), /^2 cases can’t be delivered to Local export folder since 2:12\sAM$/);
  assert.match(itGroupTitle(group, now, false), /^2 cases failing to Local export folder since 2:12\sAM$/, 'paused: the wording stays');
  assert.deepEqual(itReason('LV-DLV-001', true), { text: 'Delivering these cases fails', code: 'LV-DLV-001' });
  assert.notEqual(itReason('LV-DLV-001', true).text, LV_CODES['LV-DLV-001'].title);
  assert.deepEqual(itReason('LV-DLV-001', false), { text: LV_CODES['LV-DLV-001'].title, code: 'LV-DLV-001' });
  assert.deepEqual(itReason('LV-DLV-007', true), { text: LV_CODES['LV-DLV-007'].title, code: 'LV-DLV-007' });

  const ref = (n: number) => ({ packageId: `p-${n}`, caseNumber: `1002003004${String(n).padStart(2, '0')}` });
  assert.equal(itCasesLine([ref(1)]), 'Case 100200300401.');
  assert.equal(itCasesLine([ref(1), { packageId: 'p-x', caseNumber: null }]), `Cases 100200300401, ${UNTITLED_CASE}.`);
  assert.equal(itCasesLine([1, 2, 3, 4, 5, 6, 7, 8].map(ref)),
    'Cases 100200300401, 100200300402, 100200300403, 100200300404, 100200300405 and 3 more.');
  assert.equal(itCasesLine([1, 2, 3, 4, 5].map(ref)), 'Cases 100200300401, 100200300402, 100200300403, 100200300404, 100200300405.');
  assert.equal(itCasesLine([]), null);
  assert.equal(itCasesLine(undefined), null);
  const line = itCasesLine([ref(1), { packageId: 'c12725cb-0000-4000-8000-000000000001', caseNumber: null }]);
  assert.doesNotMatch(line ?? '', /c12725cb/, 'never the internal id');
});

test('0036: Cases page: a case whose chip says "Exporting · …" counts under Exporting, not Redacting', () => {
  assert.equal(STAGE_NAMES.exporting, 'Exporting');
  for (const state of ['READY', 'DESTINATION_PAUSED', 'WAITING_FOR_YOU', 'NEEDS_IT']) {
    assert.equal(caseTile('redacting', state), 'exporting', state);
    assert.equal(caseTile('delivered', state), 'exporting', state);
  }
  assert.equal(caseTile('redacting', 'NONE'), 'redacting');
  assert.equal(caseTile('redacting', null), 'redacting');
  assert.equal(caseTile('delivered', 'EXPORTED'), 'delivered');
  // The tiles, in order, and the Exporting tile in the Exporting chip's colour family.
  const data = readFileSync(join(here, 'data.ts'), 'utf8');
  assert.match(data, /CASE_TILES: StageKey\[\] = \['intake', 'redacting', 'review', 'approval', 'exporting', 'delivered', 'stopped'\]/);
  // Task 0108 (approved six stage boxes): the Cases page counts by tileOf, and an exporting case counts in the
  // Exported box (there is no separate Exporting box on the Cases page any more).
  const desk = readFileSync(join(here, 'Desk.tsx'), 'utf8');
  assert.match(desk, /const tile = tileOf\(pkg\);/, 'the boxes count by tileOf');
  assert.match(desk, /if \(tile === 'exporting'\) return 'delivered';/, 'an exporting case counts in the Exported box');
  const css = readFileSync(join(here, 'studio.css'), 'utf8');
  const chip = /\.cf-stage\[data-export\] \{ --stage: ([^;]+); --stage-soft: ([^;]+); \}/.exec(css);
  const tile = /\[data-stage='exporting'\] \{ --stage: ([^;]+); --stage-soft: ([^;]+); \}/.exec(css);
  assert.ok(chip && tile);
  assert.deepEqual(tile.slice(1), chip.slice(1));
});

test('0037: the "can\'t reach" alert names a single destination; the network line only with 2 or more', () => {
  assert.deepEqual(reachAlertWords([{ name: 'Local export folder' }]),
    { title: 'LexaVault can’t reach Local export folder', line: 'Its health check is failing.' });
  assert.deepEqual(reachAlertWords([{ name: 'Vault' }, { name: 'Folder' }]),
    { title: 'LexaVault can’t reach its destinations', line: 'The problem is likely LexaVault’s own network, not the destinations.' });
  assert.deepEqual(reachAlertWords([{ name: 'A' }, { name: 'B' }, { name: 'C' }]).line,
    'The problem is likely LexaVault’s own network, not the destinations.');
  // A destination without a name, and destinations not loaded yet: never blame the network on no evidence.
  assert.equal(reachAlertWords([{ name: null }]).title, 'LexaVault can’t reach Its destination');
  assert.deepEqual(reachAlertWords(undefined), { title: 'LexaVault can’t reach its destinations', line: null });
  assert.deepEqual(reachAlertWords([]), { title: 'LexaVault can’t reach its destinations', line: null });
});

test('0037: Support shows its red count while the "can\'t reach" alert is raised', () => {
  assert.equal(supportBadgeCount({ groups: [] }, { raised: true }), 1);
  assert.equal(supportBadgeCount(undefined, { raised: true }), 1);
  assert.equal(supportBadgeCount({ groups: [{} as never] }, { raised: true }), 2);
  assert.equal(supportBadgeCount({ groups: [{} as never] }, { raised: false }), 1);
  assert.equal(supportBadgeCount({ groups: [] }, null), 0);
  // Both the Support link in the header and the Export tab use the alert in their count.
  const app = readFileSync(join(here, 'CaseFileApp.tsx'), 'utf8');
  assert.match(app, /supportBadgeCount\(needsIt, reachAlert\)/);
  const tabs = readFileSync(join(here, 'support', 'SupportExport.tsx'), 'utf8');
  assert.match(tabs, /supportBadgeCount\(data, alert\)/);
  assert.doesNotMatch(tabs, /<p>\{W\.alertLine\}<\/p>/);
});

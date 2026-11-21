import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { LV_CODES } from './lvErrorCodes.generated.ts';
import {
  CASE_EXPORT_WORDS, EXPORT_STATES, RESEND_WORDS, SUPPORT_EXPORT_WORDS, WAITING_WORDS, dayAndTime, destinationKind,
  destinationStateWords, exportNeedsYou, exportStateOf, exportStatusLabel, exportTone, groupCount, groupKind, isExporting,
  itGroupTitle, jobForState, mostFrequentCode, reasonLabel, refusedHeading, refusedWhen, resendAllLabel, resendConfirmLabel,
  resendReasonLines, resendTitle, resendToast, retentionPlaceholder, retentionValueError, retryAllLabel, showWaitingNav,
  signInBody, signInTitle, supportBadgeCount, triedLine, waitingText, type ExportJobView,
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
  assert.equal(reasonLabel('SDV-SEN-006'), `${LV_CODES['LV-SYS-001'].title} · LV-SYS-001`, 'a code not in the list is LV-SYS-001');
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
  assert.equal(triedLine('LV-DLV-007'), 'The destination answers its health check, so it’s these cases. Each was retried over 24 hours and rebuilt once.');
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
  assert.equal(destinationKind('SDV_DELIVERY'), 'full case');
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

test('0029: no screen says "Vero" (the destination is named by its connection)', () => {
  // Known and pinned, so anything new fails: the registry title of LV-CFG-005 (generated from the backend; a question for
  // the owner in report 0029) and a pattern in supportRules.ts that keeps a reason's leading product name (not words).
  const known: Record<string, number> = { [join('studio', 'lvErrorCodes.generated.ts')]: 1, [join('studio', 'support', 'supportRules.ts')]: 1 };
  const root = resolve(here, '..');
  const found: Record<string, number> = {};
  const walk = (dir: string) => {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      const path = join(dir, entry.name);
      if (entry.isDirectory()) { walk(path); continue; }
      if (!/\.tsx?$/.test(entry.name) || /\.test\.ts$/.test(entry.name)) continue;
      const lines = readFileSync(path, 'utf8').split('\n').filter((l) => /\bVero\b/.test(l)).length;
      if (lines) found[path.slice(root.length + 1)] = lines;
    }
  };
  walk(root);
  assert.deepEqual(found, known);
  assert.match(readFileSync(join(here, 'lvErrorCodes.generated.ts'), 'utf8').split('\n').find((l) => /\bVero\b/.test(l)) ?? '', /'LV-CFG-005'/);
});

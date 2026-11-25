import test from 'node:test';
import assert from 'node:assert/strict';
import { approvalToken, eventTiming, mailForm, mailRequest, needsSecret, peopleText, roleNames } from './notificationRules.ts';

const VIEW = { enabled: true, mode: 'OWN_SERVER', host: 'mail.example', port: 587, security: 'STARTTLS', username: 'svc', fromAddress: 'n@example.com', fromName: null, directoryId: null, clientId: null, credentialType: null, secretNamed: true };

test('the form never opens with a secret, and starts with Microsoft 365 switched off', () => {
  assert.equal(mailForm(VIEW).secretReference, '');
  const fresh = mailForm(null);
  assert.equal(fresh.mode, 'MICROSOFT_365');
  assert.equal(fresh.enabled, false);
});

test('only the fields of the chosen way are sent', () => {
  const own = mailRequest(mailForm(VIEW));
  assert.deepEqual(Object.keys(own).sort(), ['enabled', 'fromAddress', 'fromName', 'host', 'mode', 'port', 'secretReference', 'security', 'username']);
  assert.equal(own.secretReference, null);
  assert.equal(own.port, 587);
  const microsoft = mailRequest({ ...mailForm(VIEW), mode: 'MICROSOFT_365' });
  assert.equal('host' in microsoft || 'username' in microsoft || 'port' in microsoft, false);
});

test('a secret must be named for a login that has none stored', () => {
  assert.equal(needsSecret(mailForm(VIEW), VIEW), false);
  assert.equal(needsSecret({ ...mailForm(VIEW), mode: 'MICROSOFT_365' }, VIEW), true);
  assert.equal(needsSecret({ ...mailForm(VIEW), username: '' }, null), false);
  assert.equal(needsSecret(mailForm(VIEW), null), true);
});

test('the words', () => {
  assert.equal(peopleText(0), 'Nobody yet');
  assert.equal(peopleText(1), '1 person');
  assert.equal(roleNames(['TENANT_IT_ADMIN', 'NEW_ROLE']), 'IT Admin, NEW_ROLE');
});

test('when a message is sent, in words', () => {
  assert.equal(eventTiming({ frequency: 'IMMEDIATELY', dailyAt: null, alertAfterMinutes: 15, repeatMinutes: 240 }), 'Immediately, after 15 min, again every 4 h');
  assert.equal(eventTiming({ frequency: 'ONCE_A_DAY', dailyAt: '06:30', alertAfterMinutes: null, repeatMinutes: null }), 'Once a day at 06:30');
});

test('only a link of the right shape is an approval link', () => {
  assert.equal(approvalToken('/approvals/link/AQEB_x-1.sig_2'), 'AQEB_x-1.sig_2');
  for (const p of ['/approvals/link', '/approvals/link/', '/approvals/link/a', '/approvals/link/a.b/c', '/approvals/link/a%2F.b', '/admin']) assert.equal(approvalToken(p), null, p);
});

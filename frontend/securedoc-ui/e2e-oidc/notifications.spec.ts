import { test, expect, type Page, type Route } from '@playwright/test';
import { APPROVAL_READY, APPROVAL_TOKEN, ENTITY, NOTIFICATIONS_IT, mockApi, mockIdentityProvider, signInAs } from '../e2e-a11y/harness';

/** Task 0059: Admin › Notifications and the approval page, with the API mocked. */

async function record(page: Page, pattern: string, answer: (call: string, body: unknown) => { status?: number; body: unknown } | null) {
  const calls: Array<{ call: string; body: unknown }> = [];
  await page.route(pattern, async (route: Route) => {
    const r = route.request();
    const call = `${r.method()} ${new URL(r.url()).pathname.replace('/api/v1', '')}`;
    const body = r.method() === 'GET' || !r.postData() ? null : r.postDataJSON();
    const a = answer(call, body);
    if (!a) return route.fallback();
    calls.push({ call, body });
    return route.fulfill({ status: a.status ?? 200, contentType: 'application/json', body: JSON.stringify(a.body) });
  });
  return calls;
}

test('the IT Admin sets up Microsoft 365: only its fields, a named secret, and never one shown back', async ({ page }) => {
  await signInAs(page, 'it', '/admin/notifications', { notificationsIt: { ...NOTIFICATIONS_IT, mail: null } });
  const calls = await record(page, '**/api/v1/admin/notifications/it/**', () => ({ body: NOTIFICATIONS_IT }));
  const mail = page.getByRole('region', { name: 'E-mail' });
  await mail.getByRole('switch', { name: 'Send messages by e-mail' }).check();
  await mail.getByLabel('Directory (tenant) ID').fill('00000000-0000-0000-0000-000000000001');
  await mail.getByLabel('Application (client) ID').fill('00000000-0000-0000-0000-000000000002');
  await mail.getByLabel('Sender mailbox').fill('notices@northwind.example');
  await mail.getByLabel('Name of the stored secret').fill(`env:${NOTIFICATIONS_IT.secretNamePrefix}MAIL`);
  await mail.getByRole('button', { name: 'Save' }).click();
  await expect(page.getByText('The e-mail settings are saved.')).toBeVisible();
  expect(calls[0]).toEqual({ call: 'PUT /admin/notifications/it/mail', body: {
    enabled: true, mode: 'MICROSOFT_365', fromAddress: 'notices@northwind.example', fromName: null, secretReference: `env:${NOTIFICATIONS_IT.secretNamePrefix}MAIL`,
    directoryId: '00000000-0000-0000-0000-000000000001', clientId: '00000000-0000-0000-0000-000000000002', credentialType: 'CERTIFICATE' } });
  await expect(mail.getByLabel('Name of the stored secret')).toHaveValue('');
  await expect(mail).toContainText('One is stored; leave this empty to keep it.');
});

test('your own server has its own fields, and a refusal says what to change', async ({ page }) => {
  await signInAs(page, 'it', '/admin/notifications');
  await record(page, '**/api/v1/admin/notifications/it/mail', () => ({ status: 400, body: { code: 'LV-CFG-001', message: 'The port is not one of the mail ports this installation allows' } }));
  const mail = page.getByRole('region', { name: 'E-mail' });
  await mail.getByLabel('Mail account').selectOption('OWN_SERVER');
  await expect(mail.getByLabel('Directory (tenant) ID')).toHaveCount(0);
  await mail.getByLabel('Server').fill('exchange.northwind.example');
  await expect(mail.getByLabel('Name of the stored secret')).toHaveCount(0);
  await mail.getByLabel('User name').fill('svc-mail');
  await expect(mail.getByLabel('Name of the stored secret')).toBeVisible();
  await mail.getByRole('button', { name: 'Save' }).click();
  await expect(mail).toContainText('The port is not one of the mail ports');
});

test('a test message goes only with saved settings', async ({ page }) => {
  await signInAs(page, 'it', '/admin/notifications');
  const calls = await record(page, '**/api/v1/admin/notifications/it/test', () => ({ status: 202, body: {} }));
  const mail = page.getByRole('region', { name: 'E-mail' });
  await mail.getByLabel('Sender name').fill('Other');
  await expect(mail.getByRole('button', { name: 'Send me a test message' })).toBeDisabled();
  await mail.getByLabel('Sender name').fill('LexaVault');
  await mail.getByRole('button', { name: 'Send me a test message' }).click();
  await expect(page.getByText('A test message is on its way to your own address.')).toBeVisible();
  expect(calls).toEqual([{ call: 'POST /admin/notifications/it/test', body: null }]);
});

test('messages: what is always on can not be switched off; a choice is sent at once', async ({ page }) => {
  await signInAs(page, 'business', '/admin/notifications');
  await expect(page.getByRole('region', { name: 'E-mail' })).toHaveCount(0);
  const calls = await record(page, '**/api/v1/admin/notifications/business/**', () => ({ body: { mailOn: true, people: [], events: [] } }));
  const table = page.getByRole('table', { name: 'Business messages' });
  await expect(table.getByRole('row').filter({ hasText: 'Intake stopped' })).toContainText('Always on');
  await expect(table.getByRole('row').filter({ hasText: 'Cases waiting for review' })).toContainText('Every 4 hours, again every 4 h');
  await page.getByRole('button', { name: 'Change: Intake stopped, licence expired' }).click();
  await expect(page.getByRole('dialog').getByRole('switch')).toHaveCount(0);
  await page.getByRole('dialog').getByRole('button', { name: 'Cancel' }).click();
  await page.getByRole('button', { name: 'Change: Usage near limit' }).click();
  const sheet = page.getByRole('dialog', { name: 'Usage near limit' });
  await sheet.getByLabel('Time of day').fill('06:30');
  await sheet.getByLabel('Warn at percent used').fill('75');
  await sheet.getByRole('button', { name: 'Save' }).click();
  await expect(sheet).toHaveCount(0);
  expect(calls).toEqual([{ call: 'PUT /admin/notifications/business/events/licensing.usage-near-limit', body: { enabled: true, frequency: 'ONCE_A_DAY', dailyAt: '06:30', thresholds: { nearLimitPercent: 75 } } }]);
});

test('opening an approval link decides nothing; one more click does, with the token in the body only', async ({ page }) => {
  await signInAs(page, 'supervisor');
  const calls = await record(page, '**/api/v1/approvals/link/**', (call) => ({ body: call.endsWith('/open') ? APPROVAL_READY : { ...APPROVAL_READY, state: 'APPROVED', expiresAt: null, message: 'Approved. The case is being delivered.' } }));
  const urls: string[] = [];
  page.on('request', (r) => urls.push(r.url()));
  await page.goto(`/approvals/link/${APPROVAL_TOKEN}`);
  await expect(page.getByRole('heading', { name: 'Deliver case LN-3002?' })).toBeVisible();
  expect(new URL(page.url()).pathname).toBe('/approvals/link');
  expect(calls.map((c) => c.call)).not.toContain('POST /approvals/link/decide');
  await expect(page.locator('.cf-approval')).toContainText('Maria Alvarez');
  await page.getByRole('button', { name: 'Approve delivery' }).click();
  await expect(page.getByText('Approved. The case is being delivered.')).toBeVisible();
  expect(calls.at(-1)).toEqual({ call: 'POST /approvals/link/decide', body: { token: APPROVAL_TOKEN, action: 'APPROVE', reason: null } });
  expect(urls.filter((u) => u.includes(APPROVAL_TOKEN)).every((u) => new URL(u).pathname === `/approvals/link/${APPROVAL_TOKEN}`)).toBe(true);
  expect(await page.evaluate(() => sessionStorage.getItem('lexavault.approval-link'))).toBeNull();
});

test('a decline needs one of the reasons', async ({ page }) => {
  await signInAs(page, 'supervisor');
  const calls = await record(page, '**/api/v1/approvals/link/**', (call) => ({ body: { ...APPROVAL_READY, action: 'DECLINE', ...(call.endsWith('/decide') ? { state: 'DECLINED', message: 'Declined.' } : {}) } }));
  await page.goto(`/approvals/link/${APPROVAL_TOKEN}`);
  await expect(page.getByRole('button', { name: 'Decline delivery' })).toBeDisabled();
  await page.getByLabel('Reason').selectOption('CHECK_REDACTION');
  await page.getByRole('button', { name: 'Decline delivery' }).click();
  await expect(page.getByText('Declined.', { exact: true })).toBeVisible();
  expect(calls.at(-1)!.body).toEqual({ token: APPROVAL_TOKEN, action: 'DECLINE', reason: 'CHECK_REDACTION' });
});

test('a refused link says why and offers nothing to click', async ({ page }) => {
  await signInAs(page, 'supervisor');
  await record(page, '**/api/v1/approvals/link/**', () => ({ body: { state: 'REFUSED', action: null, caseId: null, expiresAt: null, code: 'LINK_NOT_FOR_YOU', message: 'This link was sent to someone else.' } }));
  await page.goto(`/approvals/link/${APPROVAL_TOKEN}`);
  await expect(page.getByText('This link was sent to someone else.')).toBeVisible();
  await expect(page.getByRole('button', { name: /delivery/ })).toHaveCount(0);
});

test('someone who is not a Supervisor here is told so, and nothing is asked', async ({ page }) => {
  await signInAs(page, 'business');
  const calls = await record(page, '**/api/v1/approvals/link/**', () => ({ body: APPROVAL_READY }));
  await page.goto(`/approvals/link/${APPROVAL_TOKEN}`);
  await expect(page.getByText('Only a Supervisor of this entity can use an approval link.')).toBeVisible();
  expect(calls).toEqual([]);
});

test('a link opened before signing in is shown after it, and never reaches the login system', async ({ page }) => {
  await mockIdentityProvider(page);
  await mockApi(page, 'supervisor');
  const referrers: string[] = [];
  page.on('request', (r) => { if (r.url().startsWith('http://idp.test')) referrers.push(`${r.url()} ${r.headers().referer ?? ''}`); });
  await page.goto(`/approvals/link/${APPROVAL_TOKEN}`);
  await page.getByRole('button', { name: /sign in/i }).first().click();
  await expect(page.getByRole('heading', { name: 'Deliver case LN-3002?' })).toBeVisible();
  await expect(page.locator('.cf-approval')).toContainText(ENTITY.name);
  expect(referrers.length).toBeGreaterThan(0);
  expect(referrers.filter((r) => r.includes(APPROVAL_TOKEN))).toEqual([]);
});

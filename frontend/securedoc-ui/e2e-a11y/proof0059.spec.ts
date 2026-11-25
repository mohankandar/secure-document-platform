import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { APPROVAL_READY, APPROVAL_TOKEN, NOTIFICATIONS_IT, signInAs } from './harness';

/**
 * Task 0059: the proof pictures (docs/work/0059-proof/). Mocked harness, fake data only.
 * Registered only on request: P0059_PROOF=1 npx playwright test --config playwright.a11y.config.ts proof0059 --project light
 */
if (process.env.P0059_PROOF) {
  const OUT = '../../docs/work/0059-proof';
  mkdirSync(OUT, { recursive: true });

  test('Microsoft 365', async ({ page }) => {
    await signInAs(page, 'it', '/admin/notifications');
    await expect(page.getByRole('region', { name: 'Last messages' })).toBeVisible();
    await page.screenshot({ path: `${OUT}/1-it-microsoft-365.png`, fullPage: true });
  });

  test('own server', async ({ page }) => {
    await signInAs(page, 'it', '/admin/notifications', { notificationsIt: { ...NOTIFICATIONS_IT, approvalLinksOn: false, mail: { ...NOTIFICATIONS_IT.mail, mode: 'OWN_SERVER', host: 'exchange.northwind.example', port: 587, security: 'STARTTLS', username: 'svc-lexavault', directoryId: null, clientId: null, credentialType: null } } });
    await expect(page.getByLabel('Server')).toBeVisible();
    await page.screenshot({ path: `${OUT}/2-it-own-server.png`, fullPage: true });
  });

  test('business messages', async ({ page }) => {
    await signInAs(page, 'business', '/admin/notifications');
    await expect(page.getByRole('table', { name: 'Business messages' })).toBeVisible();
    await page.screenshot({ path: `${OUT}/3-business.png`, fullPage: true });
    await page.getByRole('button', { name: 'Change: Usage near limit' }).click();
    await page.screenshot({ path: `${OUT}/7-change-a-message.png` });
  });

  test('approve', async ({ page }) => {
    await signInAs(page, 'supervisor');
    await page.goto(`/approvals/link/${APPROVAL_TOKEN}`);
    await expect(page.getByRole('heading', { name: 'Deliver case LN-3002?' })).toBeVisible();
    await page.screenshot({ path: `${OUT}/4-approve.png` });
  });

  test('decline', async ({ page }) => {
    await signInAs(page, 'supervisor');
    await page.route('**/api/v1/approvals/link/open', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...APPROVAL_READY, action: 'DECLINE' }) }));
    await page.goto(`/approvals/link/${APPROVAL_TOKEN}`);
    await expect(page.getByLabel('Reason')).toBeVisible();
    await page.screenshot({ path: `${OUT}/5-decline.png` });
  });

  test('refused', async ({ page }) => {
    await signInAs(page, 'supervisor');
    await page.route('**/api/v1/approvals/link/open', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ state: 'REFUSED', action: null, caseId: null, caseNumber: null, askedBy: null, expiresAt: null, code: 'CASE_CHANGED', message: 'The case no longer waits for this approval: it was approved on the case, or it has changed since the message was sent.' }) }));
    await page.goto(`/approvals/link/${APPROVAL_TOKEN}`);
    await expect(page.getByText('no longer waits')).toBeVisible();
    await page.screenshot({ path: `${OUT}/6-refused.png` });
  });
}

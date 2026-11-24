import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { ENTITY, mockApi, mockIdentityProvider, signInAs } from './harness';

/**
 * Task 0058: the proof pictures (docs/work/0058-proof/). Mocked harness, fake data only.
 * Registered only on request: P0058_PROOF=1 npx playwright test --config playwright.a11y.config.ts proof0058 --project light
 */
if (process.env.P0058_PROOF) {
  const OUT = '../../docs/work/0058-proof';
  mkdirSync(OUT, { recursive: true });

  test('Entities', async ({ page }) => {
    await signInAs(page, 'business', '/admin/entities');
    await expect(page.getByRole('table', { name: 'Entities you administer' })).toBeVisible();
    await page.screenshot({ path: `${OUT}/1-entities.png` });
    await page.getByRole('button', { name: 'Suspend: Commercial lending' }).click();
    await page.screenshot({ path: `${OUT}/2-suspend.png` });
    await page.keyboard.press('Escape');
    await page.getByRole('button', { name: 'Time zone: Commercial lending' }).click();
    await page.screenshot({ path: `${OUT}/3-time-zone.png` });
  });

  test('entity switch in the top bar', async ({ page }) => {
    await signInAs(page, 'supervisor', '/', { twoEntities: true });
    await page.getByRole('button', { name: /switch entity/i }).click();
    await expect(page.getByRole('menu', { name: 'Entities' })).toBeVisible();
    await page.screenshot({ path: `${OUT}/4-entity-switch.png` });
  });

  test('entity chooser', async ({ page }) => {
    await mockIdentityProvider(page);
    await mockApi(page, 'supervisor', { twoEntities: true });
    await page.goto('/');
    await page.getByRole('button', { name: /sign in/i }).first().click();
    await expect(page.getByRole('heading', { name: 'Choose an entity' })).toBeVisible();
    await page.screenshot({ path: `${OUT}/5-chooser.png` });
  });

  test('reports in the entity’s time zone', async ({ page }) => {
    await signInAs(page, 'business', '/reports', { timeZone: 'America/Chicago' });
    await expect(page.getByText('days in America/Chicago')).toBeVisible();
    await page.screenshot({ path: `${OUT}/6-reports-time-zone.png` });
  });

  test('upload result names the entity', async ({ page }) => {
    await signInAs(page, 'processor', '/intake');
    await page.route('**/api/v1/packages/**', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }));
    await page.route('**/api/v1/packages', (route) => (route.request().method() === 'POST'
      ? route.fulfill({ status: 201, contentType: 'application/json', body: JSON.stringify({ packageId: 'c9c9c9c9-0000-4000-8000-000000009999' }) }) : route.fallback()));
    await page.locator('main input[type="text"], main .cf-field input').first().fill('LN-5858');
    await page.locator('input[type="file"]').setInputFiles({ name: 'note.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj 2 0 obj<</Type/Pages/Count 1/Kids[3 0 R]>>endobj 3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 9 9]>>endobj\ntrailer<</Root 1 0 R>>\n%%EOF') });
    await page.getByRole('button', { name: /create case/i }).click();
    await expect(page.getByText(`1 file uploaded to ${ENTITY.name}.`)).toBeVisible();
    await page.screenshot({ path: `${OUT}/7-upload-result.png` });
  });

  test('sign-in draft', async ({ page }) => {
    const active = { connectionId: 'c', providerId: 'customer-oidc', providerType: 'OIDC', displayName: 'customer-oidc', issuerUri: 'https://login.northwind.example/oauth2', browserClientId: 'lexavault-web', apiAudience: 'lexavault-api', status: 'ACTIVE', configurationVersion: 4, matchesSettings: true };
    const history = [{ issuerUri: active.issuerUri, version: 4, status: 'ACTIVE', source: 'SETUP', changedBy: 'setup', changedAt: '2026-11-20T10:00:00Z' }];
    await signInAs(page, 'it', '/admin/identity', { identity: { active, draft: { ...active, connectionId: 'd', status: 'VALIDATED', configurationVersion: 2, browserClientId: 'lexavault-web-2' }, previous: null, history } });
    await expect(page.getByRole('button', { name: 'Switch to it' })).toBeEnabled();
    await page.screenshot({ path: `${OUT}/8-sign-in-draft.png`, fullPage: true });
  });

  test('sign-in draft of another login system', async ({ page }) => {
    const active = { connectionId: 'c', providerId: 'customer-oidc', providerType: 'OIDC', displayName: 'customer-oidc', issuerUri: 'https://login.northwind.example/oauth2', browserClientId: 'lexavault-web', apiAudience: 'lexavault-api', status: 'ACTIVE', configurationVersion: 4, matchesSettings: true };
    await signInAs(page, 'it', '/admin/identity', { identity: { active, draft: { ...active, connectionId: 'd', status: 'VALIDATED', issuerUri: 'https://login2.northwind.example', matchesSettings: false }, previous: null, history: [] } });
    await expect(page.getByText('it can be tested here but not switched to')).toBeVisible();
    await page.screenshot({ path: `${OUT}/9-sign-in-other-login-system.png`, fullPage: true });
  });
}

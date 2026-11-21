import { test, expect, type Page, type Route } from '@playwright/test';
import { existsSync, mkdirSync, readFileSync } from 'node:fs';
import { ISSUER, go, mockApi, mockIdentityProvider } from './harness';

/**
 * Task 0026: the proof pictures for the report, saved to ../../docs/work/compare/0026/ (not committed). The same
 * mocked harness as the accessibility suite: no backend, no secrets, no real data. Registered only on request, so
 * normal runs and counts are unchanged:
 *   $env:P0026_PROOF='1'; npx playwright test --config playwright.a11y.config.ts proof0026 --project light
 * The 404 picture shows the body the real handler wrote in ClientMistakeResponsesTest (run with -Dp0026.proof=<folder>).
 */
if (process.env.P0026_PROOF) {
  const OUT = '../../docs/work/compare/0026';
  mkdirSync(OUT, { recursive: true });

  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
  /** An ID token that carries the person's email, as a real login system's does. */
  async function tokenWithEmail(page: Page, email: string) {
    const jwt = encode({ alg: 'none', typ: 'JWT' }) + '.' + encode({ sub: 'proof-user', email, exp: Math.floor(Date.now() / 1000) + 3600 }) + '.signature';
    await page.route(`${ISSUER}/protocol/openid-connect/token`, (route: Route) => route.fulfill({
      status: 200, contentType: 'application/json', headers: { 'Access-Control-Allow-Origin': '*' },
      body: JSON.stringify({ access_token: jwt, id_token: jwt, refresh_token: 'proof-refresh', token_type: 'Bearer', expires_in: 3600 }),
    }));
  }

  test('no-access: title and code, then "Signed in as <email>"', async ({ page }) => {
    await mockIdentityProvider(page);
    await tokenWithEmail(page, 'wrong.account@example.test');
    await mockApi(page, 'none');
    await page.route('**/api/v1/me', (route) => route.fulfill({ status: 403, contentType: 'application/json',
      body: JSON.stringify({ errorCode: 'NO_MAPPED_ROLE', code: 'LV-AUT-002', message: 'No access to LexaVault', correlationId: 'c0ffee00-0000-4000-8000-000000000026' }) }));
    await page.goto('/');
    await page.getByRole('button', { name: /sign in/i }).first().click();
    await expect(page.getByRole('heading', { name: 'No access to LexaVault · LV-AUT-002' })).toBeVisible();
    await expect(page.getByTestId('no-access-who')).toHaveText('Signed in as wrong.account@example.test');
    await page.waitForTimeout(250);
    await page.screenshot({ path: `${OUT}/no-access-signed-in-as.png` });
  });

  test('health: a failed check shows Down · LV-SYS-006', async ({ page }) => {
    const minsAgo = (m: number) => new Date(Date.now() - m * 60_000).toISOString();
    // The harness's overview with the database check failed (registered after the harness, so it wins).
    const overview = {
      generatedAt: minsAgo(1),
      providers: [
        { key: 'database', label: 'PostgreSQL', category: 'Database', provider: 'postgresql', status: 'UNHEALTHY', configured: true, detail: 'Connection unavailable', lvCode: 'LV-SYS-006' },
        { key: 'ocr', label: 'OCR', category: 'OCR', provider: 'tesseract', status: 'HEALTHY', configured: true, detail: 'Language: eng', lvCode: null },
        { key: 'delivery', label: 'Final delivery', category: 'Delivery', provider: 'sdv', status: 'HEALTHY', configured: true, detail: 'Tenant SDV connection configured; secrets are protected', lvCode: null },
      ],
      watchedFolder: {
        enabled: true, schedulingEnabled: true, scanIntervalMs: 60000, rootPath: 'D:/LexaVault/inbound', triggerFileName: 'READY', incompleteVisibilityMinutes: 60,
        acceptedBehavior: 'MOVE', rejectedBehavior: 'MOVE', processingDirectory: 'D:/LexaVault/processing', coordinationEnabled: true,
        nodeId: 'node-a', scannerRole: 'LEADER', leaseOwner: 'node-a/123', fencingToken: 42, leaseExpiresAt: minsAgo(-1), takeoverCount: 2,
        lastScanAt: minsAgo(1), lastScanOutcome: 'SUCCESS', lastScanResult: { discovered: 3, waitingForTrigger: 1, accepted: 2, rejected: 0, duplicates: 0 },
      },
      resilience: { maxAttempts: 3, initialBackoffMs: 1000, packageProcessingMaxAttempts: 3, aiSecondaryEnabled: false, aiSecondaryProvider: 'none', aiSecondaryModel: '' },
      integrations: { deliveryType: 'sdv', sdvConfigured: true, identityMode: 'OIDC', productionIdentityConfigured: true },
      technicalExceptions: [],
    };
    await mockIdentityProvider(page);
    await mockApi(page, 'it');
    await page.route(/\/api\/v1\/platform-admin\/overview(\?|$)/, (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(overview) }));
    await page.goto('/');
    await page.getByRole('button', { name: /sign in/i }).first().click();
    await page.locator('#cf-main h1').first().waitFor();
    await go(page, '/admin/health');
    const services = page.locator('.cf-ad-services');
    await expect(services).toContainText('Down · LV-SYS-006');
    await page.waitForTimeout(250);
    await page.locator('section.cf-ad-card').first().screenshot({ path: `${OUT}/health-failed-check.png` });
  });

  test('api-404: the body of an unknown endpoint', async ({ page }) => {
    const file = `${OUT}/404-unknown-endpoint.json`;
    test.skip(!existsSync(file), `run ClientMistakeResponsesTest with -Dp0026.proof first (${file})`);
    const text = readFileSync(file, 'utf8').replace(/&/g, '&amp;').replace(/</g, '&lt;');
    await page.setContent(`<html><body style="margin:0;background:#fff"><pre style="font:14px/1.5 Consolas,monospace;padding:24px;margin:0">${text}</pre></body></html>`);
    await page.locator('pre').screenshot({ path: `${OUT}/404-unknown-endpoint.png` });
  });
}

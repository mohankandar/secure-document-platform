import { test, expect, type Page, type Route } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { capabilitiesFor } from '../e2e-oidc/policyCapabilities';
import { ENTITY, INCIDENT, STOPPED_CASE, go, mockApi, mockIdentityProvider } from './harness';

/**
 * Task 0025: the proof pictures for the report (LV error codes on screen), saved to ../../docs/work/compare/0025/
 * (not committed). The same mocked harness as the accessibility suite: no backend, no secrets, no real data.
 * Registered only on request, so normal runs and counts are unchanged:
 *   $env:P0025_PROOF='1'; npx playwright test --config playwright.a11y.config.ts proof0025 --project light
 */
if (process.env.P0025_PROOF) {
  const OUT = '../../docs/work/compare/0025';
  mkdirSync(OUT, { recursive: true });

  type Answer = { status?: number; body: unknown };
  /** Answers registered after the harness's own, so they win; anything else falls through to the harness. */
  async function override(page: Page, answers: Record<string, Answer | ((method: string) => Answer | undefined)>) {
    await page.route('**/api/v1/**', async (route: Route) => {
      const req = route.request();
      const path = new URL(req.url()).pathname.replace(/^\/api\/v1/, '');
      const entry = answers[path];
      const answer = typeof entry === 'function' ? entry(req.method()) : entry;
      if (!answer) return route.fallback();
      return route.fulfill({ status: answer.status ?? 200, contentType: 'application/json', body: JSON.stringify(answer.body) });
    });
  }

  async function signIn(page: Page) {
    await page.goto('/');
    await page.getByRole('button', { name: /sign in/i }).first().click();
    await page.locator('#cf-main h1').first().waitFor();
  }

  const minsAgo = (m: number) => new Date(Date.now() - m * 60000).toISOString();
  const BUSY = { errorCode: 'DELIVERY_RUNTIME_ERROR', code: 'LV-DLV-002', message: 'Destination busy or down', correlationId: 'c0ffee00-0000-4000-8000-000000000025', timestamp: minsAgo(0) };

  test('support-problem: the Support list shows title · code', async ({ page }) => {
    await mockIdentityProvider(page);
    await mockApi(page, 'it');
    const row = (n: number, loan: string, reasonCode: string, lvCode: string | null, stage: string, attemptCount: number, m: number) => ({
      incidentId: n === 1 ? INCIDENT : `e9e9e9e9-0000-4000-8000-00000000002${n}`, tenantId: ENTITY.tenantId, packageId: STOPPED_CASE.packageId, documentId: null,
      primaryIdentifierLabel: 'Loan number', primaryIdentifierValue: loan, stage, component: 'worker', reasonCode, lvCode, status: 'OPEN', businessOutcome: null,
      businessContinuityUsed: false, retryable: true, attemptCount, correlationId: `c0ffee00-0000-4000-8000-00000000002${n}`, businessImpact: 'BLOCKED',
      firstObservedAt: minsAgo(m + 20), lastObservedAt: minsAgo(m), resolvedAt: null,
    });
    await override(page, { '/support/incidents': { body: { items: [
      row(1, 'LN-3003', 'DELIVERY_IO', 'LV-DLV-002', 'DELIVERY', 3, 30),
      row(2, 'LN-3011', 'OCR_TIMEOUT', null, 'OCR', 1, 90),
      row(3, 'LN-3012', 'REDACTION_TEXT_LAYER_REMAINS', null, 'VERIFICATION', 1, 140),
    ], total: 3, page: 0, size: 100 } } });
    await signIn(page);
    await go(page, '/support/incidents');
    const list = page.getByRole('list', { name: 'Problems' });
    await expect(list).toContainText('Destination busy or down · LV-DLV-002');
    await page.waitForTimeout(250);
    await page.locator('#cf-main').screenshot({ path: `${OUT}/support-problem.png` });
  });

  test('case-panel-problem: the case panel shows the problem as title · code', async ({ page }) => {
    await mockIdentityProvider(page);
    await mockApi(page, 'supervisor');
    // A person with both the Supervisor and the IT Administrator role sees "What went wrong" in the case panel.
    const roles = ['DOCUMENT_SUPERVISOR', 'TENANT_IT_ADMIN'];
    await override(page, {
      '/me/tenants': { body: [{ ...ENTITY, roles }] },
      '/me': { body: { username: 'supervisor.user@example.test', roles, tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC', capabilities: capabilitiesFor(...roles) } },
      [`/technical-exceptions/${STOPPED_CASE.packageId}`]: { body: {
        packageId: STOPPED_CASE.packageId, status: 'FAILED', failureCategory: 'DEPENDENCY_UNAVAILABLE', failedStage: 'DELIVERY', retryable: true,
        failedAt: minsAgo(30), correlationId: 'c0ffee00-0000-4000-8000-000000000025', originalRetainedUntil: minsAgo(-2880), explanation: 'The case could not be sent.',
        lvCode: 'LV-DLV-002', incidentEvents: [], operatorDiagnostics: { dependency: 'delivery', internalErrorCode: 'DELIVERY_IO', attempts: 3, environment: 'test' },
      } },
    });
    await signIn(page);
    await go(page, `/case/${STOPPED_CASE.packageId}`);
    const what = page.locator('details.cf-disclosure').filter({ has: page.locator('summary', { hasText: 'What went wrong' }) });
    await what.locator('summary').first().click();
    await expect(what.getByTestId('case-problem')).toHaveText('Destination busy or down · LV-DLV-002');
    await page.waitForTimeout(250);
    await what.screenshot({ path: `${OUT}/case-panel-problem.png` });
  });

  test('toast-with-code: a refused Retry shows the server error with its code', async ({ page }) => {
    await mockIdentityProvider(page);
    await mockApi(page, 'it');
    await override(page, { '/derived-deliveries/dd-1/technical-retry': (m) => (m === 'POST' ? { status: 503, body: BUSY } : undefined) });
    await signIn(page);
    await go(page, '/admin/integrations');
    await page.getByRole('group', { name: 'Connection groups' }).getByRole('button', { name: /^Sending by barcode/ }).click();
    const failed = page.getByRole('region', { name: "Files that couldn't be sent" });
    await failed.getByRole('button', { name: 'Retry' }).click();
    const toast = page.locator('.cf-toast').filter({ hasText: 'LV-DLV-002' });
    await expect(toast).toHaveText('Destination busy or down · LV-DLV-002');
    await page.screenshot({ path: `${OUT}/toast-with-code.png` });
  });

  test('no-access: the no-access screen shows only the title and the code', async ({ page }) => {
    await mockIdentityProvider(page);
    await mockApi(page, 'none');
    await override(page, { '/me': { status: 403, body: { errorCode: 'NO_MAPPED_ROLE', code: 'LV-AUT-002', message: 'No access to LexaVault', correlationId: 'c0ffee00-0000-4000-8000-000000000026' } } });
    await page.goto('/');
    await page.getByRole('button', { name: /sign in/i }).first().click();
    await expect(page.getByRole('heading', { name: 'No access to LexaVault · LV-AUT-002' })).toBeVisible();
    await expect(page.getByText('NO_MAPPED_ROLE')).toHaveCount(0);
    await page.waitForTimeout(250);
    await page.screenshot({ path: `${OUT}/no-access.png` });
  });

  test('admin-cfg-001: an admin validation message shows LV-CFG-001', async ({ page }) => {
    await mockIdentityProvider(page);
    await mockApi(page, 'it');
    await override(page, { '/integrations/c-poll': (m) => (m === 'PUT' ? { status: 400, body: {
      errorCode: 'INVALID_ARGUMENT', code: 'LV-CFG-001', message: 'Setting not valid: timeoutSeconds must be between 1 and 300',
      correlationId: 'c0ffee00-0000-4000-8000-000000000027', timestamp: minsAgo(0) } } : undefined) });
    await signIn(page);
    await go(page, '/admin/integrations');
    await page.getByRole('listitem').filter({ hasText: 'Servicing outbox' }).getByRole('button', { name: 'Edit' }).click();
    const sheet = page.getByRole('dialog');
    await sheet.getByRole('button', { name: /^Next/ }).click();
    await sheet.getByRole('button', { name: 'Save', exact: true }).click();
    await expect(sheet.getByText('Setting not valid: timeoutSeconds must be between 1 and 300 · LV-CFG-001')).toBeVisible();
    await page.waitForTimeout(250);
    await sheet.screenshot({ path: `${OUT}/admin-cfg-001.png` });
  });
}

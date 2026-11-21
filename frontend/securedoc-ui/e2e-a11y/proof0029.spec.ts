import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { signInAs } from './harness';

/**
 * Task 0029: the proof pictures for the report, saved to ../../docs/work/compare/0029/ (not committed): each approved
 * canvas board as built, the retention settings, and Waiting for you in dark. The mocked harness with its export
 * answers: no backend, no secrets, no real data. Registered only on request, so normal runs and counts are unchanged:
 *   $env:P0029_PROOF='1'; npx playwright test --config playwright.a11y.config.ts proof0029
 * (light: every picture; dark: Waiting for you only).
 */
if (process.env.P0029_PROOF) {
  const OUT = '../../docs/work/compare/0029';
  mkdirSync(OUT, { recursive: true });
  const settle = async (page: import('@playwright/test').Page) => { await page.waitForTimeout(400); };

  test('board 1: Waiting for you (Processor)', async ({ page }, info) => {
    await signInAs(page, 'processor', '/waiting', { exports: true });
    await expect(page.getByTestId('waiting-row').nth(4)).toContainText('LN-7002');
    await settle(page);
    await page.screenshot({ path: `${OUT}/1-waiting-for-you-${info.project.name}.png`, fullPage: true });
  });

  test('board 2: Resend all confirmation, then the toast', async ({ page }, info) => {
    test.skip(info.project.name !== 'light', 'light only');
    await signInAs(page, 'processor', '/waiting', { exports: true });
    await page.route('**/api/v1/export-jobs/resend-all', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ destinationId: 'x', queued: 3, jobIds: [] }) }));
    await page.getByRole('button', { name: 'Resend all 3' }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await settle(page);
    await page.screenshot({ path: `${OUT}/2-resend-all-confirmation.png` });
    await page.getByRole('dialog').getByRole('button', { name: 'Resend 3 cases' }).click();
    await expect(page.locator('.cf-toast')).toBeVisible();
    await page.screenshot({ path: `${OUT}/2b-resend-all-toast.png` });
  });

  test('board 3: Cases list and case panel: Exporting', async ({ page }, info) => {
    test.skip(info.project.name !== 'light', 'light only');
    await signInAs(page, 'processor', '/case/c9c9c9c9-0000-4000-8000-000000007004', { exports: true });
    await expect(page.getByRole('complementary', { name: 'Case panel' })).toContainText('Document vault refused this case');
    await settle(page);
    await page.screenshot({ path: `${OUT}/3-cases-and-case-panel-exporting.png`, fullPage: true });
  });

  test('board 4: Support (IT Admin): destinations and export errors', async ({ page }, info) => {
    test.skip(info.project.name !== 'light', 'light only');
    await signInAs(page, 'it', '/support/export', { exports: true });
    await expect(page.getByTestId('it-group').first()).toContainText('LN-6990');
    await settle(page);
    await page.screenshot({ path: `${OUT}/4-support-export.png`, fullPage: true });
  });

  test('retention per destination (Business Admin)', async ({ page }, info) => {
    test.skip(info.project.name !== 'light', 'light only');
    await signInAs(page, 'business', '/admin/policy');
    const card = page.getByRole('region', { name: 'Keeping exported copies' });
    await card.scrollIntoViewIfNeeded();
    await settle(page);
    await card.screenshot({ path: `${OUT}/5-retention-per-destination.png` });
  });
}

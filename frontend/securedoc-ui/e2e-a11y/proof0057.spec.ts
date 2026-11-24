import { test, expect } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { LICENSING_STOPPED, signInAs } from './harness';

/**
 * Task 0057: the proof pictures (docs/work/0057-proof/): Licensing and Support status. Mocked harness, fake data only.
 * Registered only on request: P0057_PROOF=1 npx playwright test --config playwright.a11y.config.ts proof0057 --project light
 */
if (process.env.P0057_PROOF) {
  const OUT = '../../docs/work/0057-proof';
  mkdirSync(OUT, { recursive: true });

  test('Licensing', async ({ page }) => {
    await signInAs(page, 'business', '/admin/licensing');
    await expect(page.getByText('412 of 1,000 pages')).toBeVisible();
    await page.screenshot({ path: `${OUT}/licensing.png`, fullPage: true });
  });
  test('Licensing, intake stopped', async ({ page }) => {
    await signInAs(page, 'business', '/admin/licensing', { licensing: LICENSING_STOPPED });
    await expect(page.getByText('Licence expired · LV-LIC-002')).toBeVisible();
    await page.screenshot({ path: `${OUT}/licensing-intake-stopped.png`, fullPage: true });
  });
  test('Support status', async ({ page }) => {
    await signInAs(page, 'it', '/support/status');
    await expect(page.getByText('R-0J5K2Q7M9X4D')).toBeVisible();
    await page.screenshot({ path: `${OUT}/support-status.png`, fullPage: true });
  });
}

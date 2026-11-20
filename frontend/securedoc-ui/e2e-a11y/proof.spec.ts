import { test, expect, type Page } from '@playwright/test';
import { mkdirSync } from 'node:fs';
import { APPROVAL_CASE, signInAs } from './harness';

/**
 * UI task U026: the proof pictures for the report, saved to ../../compare/U026/ (not committed).
 * Run only on request: U026_PROOF=1 npx playwright test --config playwright.a11y.config.ts proof --project light
 *
 * "Before" pictures of the colour fixes are taken by putting the old token values back with a style tag, so the
 * rest of the screen is exactly the same as the "after" picture.
 */

const OUT = '../../compare/U026';
mkdirSync(OUT, { recursive: true });

const BEFORE = `
  .cf { --cf-faint: #9aa0a8 !important; --cf-warn-ink: #9a7a00 !important; --cf-focus: #f4d03f !important; }
  :root[data-theme='dark'] .cf { --cf-faint: #636973 !important; }
  .cf-btn .cf-kbd { opacity: 0.55 !important; }
  .cf-field input:focus, .cf-field select:focus, .cf-ad-duration input:focus-visible { box-shadow: 0 0 0 3px color-mix(in srgb, var(--cf-ink) 12%, transparent) !important; }
`;

async function theme(page: Page, t: 'light' | 'dark') {
  await page.evaluate((v) => { document.documentElement.dataset.theme = v; }, t);
}
async function shot(page: Page, name: string, el?: string) {
  await page.waitForTimeout(250);
  if (el) await page.locator(el).first().screenshot({ path: `${OUT}/${name}.png` });
  else await page.screenshot({ path: `${OUT}/${name}.png` });
}
async function beforeAfter(page: Page, name: string, el?: string) {
  await shot(page, `${name}-after`, el);
  const tag = await page.addStyleTag({ content: BEFORE });
  await shot(page, `${name}-before`, el);
  await tag.evaluate((n) => n.remove());
}

for (const t of ['light', 'dark'] as const) {
  test(`colour fixes, ${t}`, async ({ page }) => {
    await signInAs(page, 'business', '/reports');
    await theme(page, t);
    await expect(page.getByRole('heading', { name: 'How the work is going' })).toBeVisible();
    await beforeAfter(page, `reports-chart-axes-${t}`, '[data-testid="trend-chart"]');
    await page.goto('about:blank');
  });

  test(`Cases hint and timeline, ${t}`, async ({ page }) => {
    await signInAs(page, 'supervisor', `/case/${APPROVAL_CASE.packageId}`);
    await theme(page, t);
    await expect(page.getByRole('complementary', { name: 'Case panel' })).toBeVisible();
    await beforeAfter(page, `case-panel-timeline-${t}`, '.cf-timeline');
    await beforeAfter(page, `cases-hint-${t}`, '.cf-hint');
  });

  test(`policy preview "fewer" and field focus, ${t}`, async ({ page }) => {
    await signInAs(page, 'business', '/admin/policy');
    await theme(page, t);
    const card = page.getByRole('region', { name: 'Sensitive data' });
    await card.getByText('Light', { exact: true }).click();
    await expect(card.getByRole('status').locator('.cf-sp-change').first()).toBeVisible();
    await beforeAfter(page, `policy-preview-${t}`, '.cf-sp-preview');
    const field = page.getByRole('region', { name: 'Keeping and deleting' }).getByRole('spinbutton', { name: 'Keep originals for' });
    await field.focus();
    await beforeAfter(page, `field-focus-${t}`, '.cf-ad-pair');
  });

  test(`account menu focus and Review keys, ${t}`, async ({ page }) => {
    await signInAs(page, 'processor', '/review');
    await theme(page, t);
    await expect(page.getByRole('complementary', { name: 'Decision' })).toContainText('412-55-0193');
    await beforeAfter(page, `review-decision-${t}`, '.cf-decision');
    await page.locator('body').focus();
    await page.keyboard.press('Tab');
    for (let i = 0; i < 30; i += 1) {
      if (await page.getByRole('button', { name: /account menu/i }).evaluate((b) => b === document.activeElement)) break;
      await page.keyboard.press('Tab');
    }
    await beforeAfter(page, `account-button-focus-${t}`, '.cf-top');
  });

  test(`"?" help open on three Admin cards, ${t}`, async ({ page }) => {
    await signInAs(page, 'business', '/admin/policy');
    await theme(page, t);
    for (const [name, file] of [['Sensitive data', 'sensitive-data'], ['Keeping and deleting', 'keeping'], ['Minutes per page', 'minutes-per-page']] as const) {
      const button = page.getByRole('button', { name: `About ${name}`, exact: true });
      await button.scrollIntoViewIfNeeded();
      await button.click();
      await page.waitForTimeout(150);
      const box = (await button.boundingBox())!;
      await page.screenshot({ path: `${OUT}/help-${file}-${t}.png`, clip: { x: Math.max(0, box.x - 360), y: Math.max(0, box.y - 60), width: 760, height: 280 } });
      await page.keyboard.press('Escape');
    }
  });
}

test('200% zoom of Cases and Reports, 320 px Cases, Reports, Approve and a dialog', async ({ page }) => {
  // 200% zoom of a 1280 px window is a 640 px wide layout at twice the size.
  await page.setViewportSize({ width: 640, height: 450 });
  await signInAs(page, 'supervisor');
  await expect(page.locator('li.cf-case-row').first()).toBeVisible();
  await page.screenshot({ path: `${OUT}/zoom200-cases.png`, fullPage: true });
  await page.setViewportSize({ width: 320, height: 640 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${OUT}/reflow320-cases.png`, fullPage: true });
  await page.locator('li.cf-case-row').filter({ hasText: 'LN-3002' }).getByRole('button', { name: 'Approve LN-3002' }).click();
  await expect(page.getByRole('dialog', { name: 'Release redacted documents?' })).toBeVisible();
  await page.screenshot({ path: `${OUT}/reflow320-approve-dialog.png` });
});

test('200% zoom and 320 px of Reports, and the Compliance report dialog at 320 px', async ({ page }) => {
  await page.setViewportSize({ width: 640, height: 450 });
  await signInAs(page, 'business', '/reports');
  await expect(page.getByRole('heading', { name: 'How the work is going' })).toBeVisible();
  await page.screenshot({ path: `${OUT}/zoom200-reports.png`, fullPage: true });
  await page.setViewportSize({ width: 320, height: 640 });
  await page.waitForTimeout(300);
  await page.screenshot({ path: `${OUT}/reflow320-reports.png`, fullPage: true });
  await page.getByRole('button', { name: 'Compliance report', exact: true }).click();
  await expect(page.getByRole('dialog', { name: 'Compliance report' })).toBeVisible();
  await page.screenshot({ path: `${OUT}/reflow320-compliance-dialog.png` });
});

test('text spacing (WCAG 1.4.12) on Cases, Reports and Admin', async ({ page }) => {
  const spacing = '* { line-height: 1.5 !important; letter-spacing: 0.12em !important; word-spacing: 0.16em !important; } p { margin-bottom: 2em !important; }';
  await signInAs(page, 'business', '/reports');
  await expect(page.getByRole('heading', { name: 'How the work is going' })).toBeVisible();
  await page.addStyleTag({ content: spacing });
  await page.screenshot({ path: `${OUT}/text-spacing-reports.png`, fullPage: true });
});

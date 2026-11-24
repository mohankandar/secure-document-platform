import { test, expect, type Page } from '@playwright/test';
import { signInAs } from './harness';
import { HELP } from '../src/studio/admin/helpTexts';

/**
 * UI task U026 part B: the "?" help on the Admin settings screens. One shared toggletip: named "About <setting>",
 * opens a note announced by a live region, closes by the button again, Esc or a click outside, and works by keyboard.
 * (Touch is covered in e2e-tablet/tablet.spec.ts.) The note text comes from helpTexts.ts, the one file of texts.
 */

const help = (page: Page, name: string) => page.getByRole('button', { name: `About ${name}`, exact: true });
const noteOf = async (page: Page, name: string) => page.locator(`[id="${await help(page, name).getAttribute("aria-controls")}"]`);

test('every Business Administrator setting card has its "?"', async ({ page }) => {
  await signInAs(page, 'business', '/admin/policy');
  await expect(page.getByRole('region', { name: 'Sensitive data' })).toBeVisible();
  for (const k of ['sensitiveData', 'presets', 'documentIntelligence', 'keepingAndDeleting', 'keepOriginals', 'keepRedacted', 'keepStopped', 'requireApproval',
    'reportTargets', 'minutesPerPage', 'reportBreakdown'] as const) {
    await expect(help(page, HELP[k].name), k).toBeVisible();
  }
  // Fine-tune's "?" sits inside the section, so it shows once the section is open.
  await page.getByText('Fine-tune by hand').click();
  await expect(help(page, HELP.fineTune.name)).toBeVisible();
  await page.getByRole('navigation', { name: 'Administration areas' }).getByRole('link', { name: /Case fields/ }).click();
  for (const k of ['caseFields', 'columnsOnCases'] as const) await expect(help(page, HELP[k].name), k).toBeVisible();
});

test('keyboard: Enter opens the note, it is announced, Esc closes it and focus stays on the "?"', async ({ page }) => {
  await signInAs(page, 'business', '/admin/policy');
  const button = help(page, HELP.keepingAndDeleting.name);
  await button.focus();
  await expect(button).toHaveAttribute('aria-expanded', 'false');
  const note = await noteOf(page, HELP.keepingAndDeleting.name);
  await expect(note).toHaveAttribute('aria-live', 'polite');
  await expect(note).toBeEmpty();
  await page.keyboard.press('Enter');
  await expect(button).toHaveAttribute('aria-expanded', 'true');
  await expect(note).toHaveText(HELP.keepingAndDeleting.text);
  await page.keyboard.press('Escape');
  await expect(note).toBeEmpty();
  await expect(button).toBeFocused();
  // Space opens it as well; the button again closes it.
  await page.keyboard.press('Space');
  await expect(note).toHaveText(HELP.keepingAndDeleting.text);
  await page.keyboard.press('Space');
  await expect(note).toBeEmpty();
});

test('a click outside closes the note; a click on the note does not', async ({ page }) => {
  await signInAs(page, 'business', '/admin/policy');
  await help(page, HELP.minutesPerPage.name).click();
  const note = await noteOf(page, HELP.minutesPerPage.name);
  await expect(note).toHaveText(HELP.minutesPerPage.text);
  await note.click();
  await expect(note).toHaveText(HELP.minutesPerPage.text);
  await page.getByRole('heading', { name: 'Administration', level: 1 }).click();
  await expect(note).toBeEmpty();
  await expect(help(page, HELP.minutesPerPage.name)).toHaveAttribute('aria-expanded', 'false');
});

test('the note stays inside the window, and the "?" has a visible focus ring', async ({ page }) => {
  await signInAs(page, 'business', '/admin/policy');
  const button = help(page, HELP.reportTargets.name);
  await button.click();
  const box = (await (await noteOf(page, HELP.reportTargets.name)).locator('.cf-help-note').boundingBox())!;
  const width = page.viewportSize()!.width;
  expect(box.x).toBeGreaterThanOrEqual(0);
  expect(box.x + box.width).toBeLessThanOrEqual(width);
  await page.keyboard.press('Escape');
  await button.focus();
  await page.keyboard.press('Shift+Tab');
  await page.keyboard.press('Tab');
  const ring = await button.evaluate((el) => { const s = getComputedStyle(el); return { style: s.outlineStyle, width: parseFloat(s.outlineWidth) }; });
  expect(ring.style).toBe('solid');
  expect(ring.width).toBeGreaterThanOrEqual(2);
});

test('Esc is used by the note first: the note closes, nothing else does', async ({ page }) => {
  await signInAs(page, 'it', '/admin/integrations');
  await expect(page.getByRole('region', { name: 'Receiving documents' })).toBeVisible();
  const button = help(page, HELP.receiving.name);
  await button.click();
  await expect(await noteOf(page, HELP.receiving.name)).toHaveText(HELP.receiving.text);
  await page.keyboard.press('Escape');
  await expect(await noteOf(page, HELP.receiving.name)).toBeEmpty();
  await expect(page.getByRole('region', { name: 'Receiving documents' })).toBeVisible();
});

test('IT Administrator and Platform Operations cards have their "?"', async ({ page }) => {
  await signInAs(page, 'it', '/admin/integrations');
  await expect(help(page, HELP.receiving.name)).toBeVisible();
  await page.getByRole('group', { name: 'Connection groups' }).getByRole('button', { name: /^Sending to the document store \d/ }).click();
  await expect(help(page, HELP.sendingToDocumentStore.name)).toBeVisible();
  await page.getByRole('group', { name: 'Connection groups' }).getByRole('button', { name: /^Sending by barcode \d/ }).click();
  await expect(help(page, HELP.sendingByBarcode.name)).toBeVisible();
  await expect(help(page, HELP.routingRules.name)).toBeVisible();
  await page.getByRole('group', { name: 'Connection groups' }).getByRole('button', { name: /^All \d/ }).click();
  await expect(help(page, HELP.allConnections.name)).toBeVisible();
  await page.getByRole('navigation', { name: 'Administration areas' }).getByRole('link', { name: /Sign-in/ }).click();
  await expect(help(page, HELP.signIn.name)).toBeVisible();
});

for (const [section, k] of [['storage', 'storage'], ['branding', 'branding'], ['telemetry', 'telemetry']] as const) {
  test(`Platform Operations: ${section} has its "?"`, async ({ page }) => {
    await signInAs(page, 'ops', `/admin/health?section=${section}`);
    await expect(help(page, HELP[k].name)).toBeVisible();
  });
}

for (const [who, path, ready] of [
  ['business', '/reports', 'How the work is going'],
  ['supervisor', '/', 'Cases'],
  ['processor', '/review', 'Review'],
] as const) {
  test(`no "?" on ${path === '/' ? 'Cases' : path.slice(1)}`, async ({ page }) => {
    await signInAs(page, who, path);
    await expect(page.getByRole('heading', { name: ready }).first()).toBeVisible();
    await page.waitForTimeout(300);
    await expect(page.locator('.cf-help')).toHaveCount(0);
  });
}

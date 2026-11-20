import { test, expect, type Page } from '@playwright/test';
import { APPROVAL_CASE, signInAs } from './harness';

/**
 * UI task U026 A2.2: the five main tasks by keyboard only (no mouse after sign-in). Each Tab stop on the way is checked
 * for a visible focus indicator: an outline or a ring of at least 2 px.
 */

interface Focused { name: string; tag: string; ring: number }

/** What has focus, and how thick its visible focus indicator is (outline, or a box-shadow ring). */
async function focused(page: Page): Promise<Focused> {
  return page.evaluate(() => {
    const el = document.activeElement as HTMLElement | null;
    if (!el || el === document.body) return { name: '', tag: 'body', ring: 0 };
    const s = getComputedStyle(el);
    const outline = s.outlineStyle !== 'none' ? parseFloat(s.outlineWidth) : 0;
    // box-shadow rings: the largest spread among "0 0 0 Npx" parts.
    const spreads = [...s.boxShadow.matchAll(/(?:^|,)\s*(?:rgba?\([^)]*\)|#\w+|[a-z]+)?\s*0px 0px 0px (\d+(?:\.\d+)?)px/g)].map((m) => parseFloat(m[1]));
    const inset = /inset/.test(s.boxShadow) ? Math.max(0, ...spreads) : 0;
    // A focus ring drawn on a sibling (the switch track) counts for the hidden switch input.
    let sibling = 0;
    if (el instanceof HTMLInputElement && el.nextElementSibling) {
      const n = getComputedStyle(el.nextElementSibling);
      sibling = n.outlineStyle !== 'none' ? parseFloat(n.outlineWidth) : 0;
    }
    const label = el instanceof HTMLInputElement ? el.labels?.[0]?.textContent : null;
    const name = (el.getAttribute('aria-label') || label || el.textContent || el.getAttribute('title') || '').replace(/\s+/g, ' ').trim();
    return { name, tag: el.tagName.toLowerCase(), ring: Math.max(outline, inset, sibling, ...spreads) };
  });
}

/** Presses Tab until focus is on something whose name matches, checking every stop on the way has a visible ring. */
async function tabTo(page: Page, name: RegExp, max = 60, key: 'Tab' | 'Shift+Tab' = 'Tab') {
  for (let i = 0; i < max; i += 1) {
    await page.keyboard.press(key);
    const f = await focused(page);
    if (f.tag !== 'body') expect(f.ring, `focus ring on "${f.name}" (${f.tag})`).toBeGreaterThanOrEqual(2);
    if (name.test(f.name)) return f;
  }
  throw new Error(`No Tab stop matching ${name} within ${max} presses`);
}

test('open a case: Tab to the row, Enter opens the case panel, Esc closes it', async ({ page }) => {
  await signInAs(page, 'supervisor');
  await expect(page.locator('li.cf-case-row').first()).toBeVisible();
  await page.locator('body').focus();
  await tabTo(page, /LN-3002/);
  await page.keyboard.press('Enter');
  const panel = page.getByRole('complementary', { name: 'Case panel' });
  await expect(panel).toBeVisible();
  await expect(page).toHaveURL(new RegExp(`/case/${APPROVAL_CASE.packageId}`));
  await page.keyboard.press('Escape');
  await expect(panel).toHaveCount(0);
});

test('approve: Tab to the row\'s Approve, Enter asks, Tab to Approve delivery, Enter approves', async ({ page }) => {
  const approvals: string[] = [];
  await signInAs(page, 'supervisor');
  await page.route('**/api/v1/packages/*/approve-delivery', (route) => { approvals.push(route.request().url()); return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...APPROVAL_CASE, status: 'COMPLETED' }) }); });
  await expect(page.locator('li.cf-case-row').first()).toBeVisible();
  await page.locator('body').focus();
  await tabTo(page, /^Approve LN-3002$/);
  await page.keyboard.press('Enter');
  const dialog = page.getByRole('dialog', { name: 'Release redacted documents?' });
  await expect(dialog).toBeVisible();
  // Focus moves into the dialog and never reaches the page behind it. (It is a native modal dialog: past its last
  // button, Tab goes to the browser itself, shown here as the body, and then back into the dialog.)
  expect(await dialog.evaluate((d) => d.contains(document.activeElement))).toBe(true);
  for (let i = 0; i < 6; i += 1) {
    await page.keyboard.press('Tab');
    expect(await dialog.evaluate((d) => d.contains(document.activeElement) || document.activeElement === document.body)).toBe(true);
  }
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('button', { name: 'Approve LN-3002' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(dialog).toBeVisible();
  await tabTo(page, /^Approve delivery$/, 10);
  await page.keyboard.press('Enter');
  await expect.poll(() => approvals.length).toBe(1);
});

test('decide findings in Review: R redacts, V keeps visible, and Tab reaches Redact and Keep visible', async ({ page }) => {
  const decisions: string[] = [];
  await signInAs(page, 'processor', '/review');
  await page.route('**/api/v1/review-items/*/*', (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    const [, id, action] = new URL(route.request().url()).pathname.match(/review-items\/([^/]+)\/(confirm|dismiss)/) ?? [];
    decisions.push(`${id}:${action}`);
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ findingId: id, status: action === 'confirm' ? 'CONFIRMED' : 'DISMISSED', processingResumed: false }) });
  });
  const panel = page.getByRole('complementary', { name: 'Decision' });
  await expect(panel).toContainText('412-55-0193');
  // The queue is a list of buttons; the decision panel names the finding, the text found and why it was asked.
  await page.locator('body').focus();
  await tabTo(page, /Social Security Number.*0193/);
  await tabTo(page, /^Redact/);
  await page.keyboard.press('Enter');
  await expect.poll(() => decisions).toEqual(['f1:confirm']);
  await expect(panel).toContainText('518-22-7741');
  // U027: no mouse and no refocusing: focus stays in the Review zone after a decision, so V works straight away.
  await page.keyboard.press('v');
  await expect.poll(() => decisions).toEqual(['f1:confirm', 'f2:dismiss']);
});

test('create a compliance report: Tab to the button, choose a period, Create PDF; Esc closes the dialog', async ({ page }) => {
  await signInAs(page, 'business', '/reports');
  await page.route('**/api/v1/compliance-reports', (route) => route.fulfill({ status: 200, contentType: 'application/pdf', body: Buffer.from('%PDF-1.7\n%%EOF\n', 'latin1'),
    headers: { 'Content-Disposition': 'attachment; filename="compliance-report.pdf"' } }));
  await expect(page.getByRole('heading', { name: 'How the work is going' })).toBeVisible();
  await page.locator('body').focus();
  await tabTo(page, /^Compliance report$/);
  await page.keyboard.press('Enter');
  const dialog = page.getByRole('dialog', { name: 'Compliance report' });
  await expect(dialog).toBeVisible();
  await page.keyboard.press('Escape');
  await expect(dialog).toBeHidden();
  await expect(page.getByRole('button', { name: 'Compliance report', exact: true })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(dialog).toBeVisible();
  await tabTo(page, /^Last quarter$/, 10);
  await page.keyboard.press('Enter');
  await expect(dialog.getByRole('button', { name: 'Last quarter' })).toHaveAttribute('aria-pressed', 'true');
  const download = page.waitForEvent('download');
  await tabTo(page, /^Create PDF$/, 15);
  await page.keyboard.press('Enter');
  await (await download).delete();
  await expect(dialog).toBeHidden();
});

test('change a policy and confirm: arrow keys choose Light, Tab to Save, the confirm step by keyboard', async ({ page }) => {
  const saves: unknown[] = [];
  await signInAs(page, 'business', '/admin/policy');
  await page.route('**/api/v1/business-admin/sensitive-data-policy', (route) => { saves.push(route.request().postDataJSON()); return route.fulfill({ status: 200, contentType: 'application/json', body: '{}' }); });
  const card = page.getByRole('region', { name: 'Sensitive data' });
  await expect(card.getByRole('radio', { name: /^Balanced/ })).toBeChecked();
  await page.locator('body').focus();
  await tabTo(page, /^Balanced/, 60);
  await page.keyboard.press('ArrowDown');
  await expect(card.getByRole('radio', { name: /^Light/ })).toBeChecked();
  await expect(card.getByRole('status').locator('.cf-sp-change').first()).toBeVisible();
  await tabTo(page, /^Save Light$/, 60);
  await page.keyboard.press('Enter');
  const dialog = page.getByRole('alertdialog', { name: 'A person would check fewer findings' });
  await expect(dialog).toBeVisible();
  await expect(dialog.getByRole('button', { name: 'Keep Balanced' })).toBeFocused();
  // Esc is "Keep Balanced": nothing is saved, the card goes back to Balanced, and focus returns to its Save button.
  await page.keyboard.press('Escape');
  await expect(dialog).toHaveCount(0);
  await expect(card.getByRole('radio', { name: /^Balanced/ })).toBeChecked();
  expect(saves).toHaveLength(0);
  await tabTo(page, /^Balanced/, 60, 'Shift+Tab');
  await page.keyboard.press('ArrowDown');
  await tabTo(page, /^Save Light$/, 60);
  await page.keyboard.press('Enter');
  await expect(dialog).toBeVisible();
  await page.keyboard.press('Tab');
  await expect(dialog.getByRole('button', { name: 'Save Light' })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect.poll(() => saves.length).toBe(1);
});

test('the account menu opens with Enter, arrows move, Esc closes it and focus returns', async ({ page }) => {
  await signInAs(page, 'supervisor');
  await page.locator('body').focus();
  await tabTo(page, /account menu/i, 20);
  await page.keyboard.press('Enter');
  const menu = page.getByRole('menu', { name: 'Account' });
  await expect(menu).toBeVisible();
  await page.keyboard.press('ArrowDown');
  expect(await menu.evaluate((m) => m.contains(document.activeElement))).toBe(true);
  await page.keyboard.press('Escape');
  await expect(menu).toHaveCount(0);
  await expect(page.getByRole('button', { name: /account menu/i })).toBeFocused();
});

test('Skip to content is the first Tab stop and moves focus to the main area', async ({ page }) => {
  await signInAs(page, 'supervisor');
  await page.locator('body').focus();
  await page.keyboard.press('Tab');
  const skip = page.getByRole('link', { name: 'Skip to content' });
  await expect(skip).toBeFocused();
  await expect(skip).toBeInViewport();
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/#cf-main$/);
});

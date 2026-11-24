import { test, expect, type Page } from '@playwright/test';
import { ENTITY, mockApi, mockIdentityProvider, signInAs } from './harness';

/**
 * UI task U027.
 * A2 (WCAG 2.1.4): the single-key shortcuts work only in their zone (Review: the queue, the page viewer, the decision
 * panel; Cases: the case list), never in a field, a dialog or the account menu; Review opens with focus on the
 * current finding; Ctrl K stays page-wide.
 * A5 (WCAG 2.1.1): the Reports chart is one Tab stop; the arrow keys move between days and show the tip.
 * A4 (WCAG 1.3.1, 2.4.2): the sign-in and entity chooser screens have one main landmark, the product name and a title.
 * U028: "/" on Cases is scoped to the case list too; the chooser says "Choose an entity".
 */

async function openReview(page: Page) {
  const decisions: string[] = [];
  await signInAs(page, 'processor', '/review');
  await page.route('**/api/v1/review-items/*/*', (route) => {
    if (route.request().method() !== 'POST') return route.fallback();
    const [, id, action] = new URL(route.request().url()).pathname.match(/review-items\/([^/]+)\/(confirm|dismiss)/) ?? [];
    decisions.push(`${id}:${action}`);
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ findingId: id, status: action === 'confirm' ? 'CONFIRMED' : 'DISMISSED', processingResumed: false }) });
  });
  await expect(page.getByRole('complementary', { name: 'Decision' })).toContainText('412-55-0193');
  return decisions;
}

/** Moves focus to the page body (body.focus() does nothing while a button has focus). */
const toBody = (page: Page) => page.evaluate(() => (document.activeElement as HTMLElement | null)?.blur());
const activeId = (page: Page) => page.evaluate(() => document.activeElement?.id ?? '');

test.describe('Review shortcuts (A2)', () => {
  test('Review opens with focus on the current finding in the queue', async ({ page }) => {
    await openReview(page);
    await expect(page.locator('#cf-q-f1')).toBeFocused();
    await expect(page.locator('#cf-q-f1')).toHaveAttribute('aria-current', 'true');
  });

  test('the keys work straight away from the queue, and focus follows the current finding', async ({ page }) => {
    const decisions = await openReview(page);
    await page.keyboard.press('j');
    await expect(page.locator('#cf-q-f2')).toBeFocused();
    await page.keyboard.press('k');
    await expect(page.locator('#cf-q-f1')).toBeFocused();
    await page.keyboard.press('v');
    await expect.poll(() => decisions).toEqual(['f1:dismiss']);
    // The decided finding left the queue; focus is on the next one, so the next key still works.
    await expect(page.locator('#cf-q-f2')).toBeFocused();
    await page.keyboard.press('r');
    await expect.poll(() => decisions).toEqual(['f1:dismiss', 'f2:confirm']);
  });

  test('the keys work in the page viewer and the decision panel', async ({ page }) => {
    const decisions = await openReview(page);
    await page.getByRole('group', { name: /scroll with the arrow keys/ }).focus();
    await page.keyboard.press('j');
    await expect(page.getByRole('complementary', { name: 'Decision' })).toContainText('518-22-7741');
    await page.getByRole('button', { name: /^Keep visible/ }).focus();
    await page.keyboard.press('k');
    await expect(page.getByRole('complementary', { name: 'Decision' })).toContainText('412-55-0193');
    await page.keyboard.press('v');
    await expect.poll(() => decisions).toEqual(['f1:dismiss']);
  });

  test('the keys do nothing outside the zone: page body, top bar, account menu, Find dialog', async ({ page }) => {
    const decisions = await openReview(page);
    const panel = page.getByRole('complementary', { name: 'Decision' });
    await toBody(page);
    for (const key of ['r', 'v', 'j', 'Shift+R']) await page.keyboard.press(key);
    await page.getByRole('link', { name: 'Review' }).first().focus();
    for (const key of ['r', 'v', 'j']) await page.keyboard.press(key);
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await expect(panel).toContainText('412-55-0193');

    const account = page.getByRole('button', { name: /account menu/i });
    await account.focus();
    await page.keyboard.press('Enter');
    const menu = page.getByRole('menu', { name: 'Account' });
    await expect(menu).toBeVisible();
    await page.keyboard.press('ArrowDown');
    for (const key of ['r', 'v', 'j']) await page.keyboard.press(key);
    await page.keyboard.press('Escape');
    await expect(menu).toHaveCount(0);

    // Ctrl K is not a single-key shortcut: it still opens "Find a case or action" from anywhere; typing there is typing.
    await toBody(page);
    await page.keyboard.press('Control+k');
    const find = page.getByRole('dialog');
    await expect(find).toBeVisible();
    // The dialog focuses its field just after it shows; a key typed before that goes nowhere.
    await expect(find.locator('input')).toBeFocused();
    await page.keyboard.type('rvj');
    await expect(find.locator('input')).toHaveValue('rvj');
    await page.keyboard.press('Escape');

    await page.waitForTimeout(300);
    expect(decisions).toEqual([]);
    await expect(panel).toContainText('412-55-0193');
  });

  test('Shift R asks first only from inside the zone', async ({ page }) => {
    await openReview(page);
    await toBody(page);
    await page.keyboard.press('Shift+R');
    await expect(page.getByRole('dialog')).toHaveCount(0);
    await page.locator('#cf-q-f1').focus();
    await page.keyboard.press('Shift+R');
    await expect(page.getByRole('dialog', { name: /^Redact \d+ Social Security Number/ })).toBeVisible();
    // Inside the dialog the keys do nothing more.
    await page.keyboard.press('r');
    await page.keyboard.press('Escape');
    await expect(page.getByRole('dialog')).toHaveCount(0);
  });
});

test.describe('Cases shortcuts (A2)', () => {
  test('J and K work while focus is in the case list, and focus moves with them', async ({ page }) => {
    await signInAs(page, 'supervisor');
    const rows = page.locator('li.cf-case-row a.cf-case');
    await expect(rows.first()).toBeVisible();
    await rows.first().focus();
    await page.keyboard.press('j');
    await expect(page).toHaveURL(/\/case\//);
    const first = new URL(page.url()).pathname;
    await expect.poll(() => page.evaluate(() => (document.activeElement as HTMLAnchorElement | null)?.getAttribute('href'))).toBe(first);
    await page.keyboard.press('j');
    await expect.poll(() => new URL(page.url()).pathname).not.toBe(first);
    const second = new URL(page.url()).pathname;
    await expect.poll(() => page.evaluate(() => (document.activeElement as HTMLAnchorElement | null)?.getAttribute('href'))).toBe(second);
    await page.keyboard.press('k');
    await expect.poll(() => new URL(page.url()).pathname).toBe(first);
  });

  test('J and K do nothing outside the case list or in the filter field', async ({ page }) => {
    await signInAs(page, 'supervisor');
    await expect(page.locator('li.cf-case-row').first()).toBeVisible();
    await toBody(page);
    await page.keyboard.press('j');
    await page.getByRole('button', { name: /^All/ }).focus();
    await page.keyboard.press('j');
    await page.getByRole('button', { name: /account menu/i }).focus();
    await page.keyboard.press('k');
    await page.waitForTimeout(300);
    await expect(page).not.toHaveURL(/\/case\//);
    const filter = page.getByRole('textbox', { name: 'Filter cases' });
    await filter.focus();
    await page.keyboard.type('jk');
    await expect(filter).toHaveValue('jk');
    await expect(page).not.toHaveURL(/\/case\//);
    expect(await activeId(page)).toBe('');
  });

  // U028 A2: "/" is scoped like J and K.

  test('"/" in the case list moves focus to the filter (U028)', async ({ page }) => {
    await signInAs(page, 'supervisor');
    const filter = page.getByRole('textbox', { name: 'Filter cases' });
    const rows = page.locator('li.cf-case-row a.cf-case');
    await expect(rows.first()).toBeVisible();
    await rows.first().focus();
    await page.keyboard.press('/');
    await expect(filter).toBeFocused();
    await expect(filter).toHaveValue('');
    await expect(page).not.toHaveURL(/\/case\//);
  });

  test('"/" does nothing outside the case list, and types in a field (U028)', async ({ page }) => {
    await signInAs(page, 'supervisor');
    const filter = page.getByRole('textbox', { name: 'Filter cases' });
    await expect(page.locator('li.cf-case-row').first()).toBeVisible();
    await toBody(page);
    await page.keyboard.press('/');
    await expect(filter).not.toBeFocused();
    await page.getByRole('button', { name: /^All/ }).focus();
    await page.keyboard.press('/');
    await expect(page.getByRole('button', { name: /^All/ })).toBeFocused();
    await page.getByRole('button', { name: /account menu/i }).focus();
    await page.keyboard.press('/');
    await expect(filter).not.toBeFocused();
    await filter.focus();
    await page.keyboard.type('a/b');
    await expect(filter).toHaveValue('a/b');
  });
});

test.describe('Reports chart by keyboard (A5)', () => {
  test('one Tab stop, arrow keys move between days with the tip, Esc leaves the day', async ({ page }) => {
    await signInAs(page, 'business', '/reports');
    await expect(page.getByRole('heading', { name: 'How the work is going' })).toBeVisible();
    const days = page.getByRole('group', { name: /Received and delivered per day/ });
    const tip = page.getByTestId('trend-tip');
    // No tip and no visible change until focused; the days themselves are not Tab stops.
    await expect(tip).toHaveCount(0);
    await expect(days).toHaveAttribute('tabindex', '0');
    expect(await page.locator('.cf-rp-trend-col[tabindex="0"]').count()).toBe(0);

    await days.focus();
    await expect(tip).toHaveCount(0);
    await page.keyboard.press('ArrowRight');
    const first = page.getByRole('img', { name: 'Oct 20: 10 received, 8 delivered' });
    await expect(first).toBeFocused();
    await expect(tip).toBeVisible();
    await expect(tip).toContainText('Oct 20');
    await expect(tip).toContainText('10 received');
    await expect(tip).toContainText('8 delivered');
    await page.keyboard.press('ArrowRight');
    await expect(page.getByRole('img', { name: 'Oct 21: 11 received, 9 delivered' })).toBeFocused();
    await expect(tip).toContainText('Oct 21');
    await page.keyboard.press('ArrowLeft');
    await expect(first).toBeFocused();
    await page.keyboard.press('End');
    await expect(page.getByRole('img', { name: 'Nov 18: 11 received, 12 delivered' })).toBeFocused();
    await expect(tip).toContainText('Nov 18');
    await page.keyboard.press('Home');
    await expect(first).toBeFocused();

    // Esc leaves the day: the tip goes and focus is back on the chart.
    await page.keyboard.press('Escape');
    await expect(tip).toHaveCount(0);
    await expect(days).toBeFocused();

    // One Tab stop: from a day, Tab leaves the chart (it does not step through the days).
    await page.keyboard.press('ArrowRight');
    await page.keyboard.press('Tab');
    expect(await page.evaluate(() => !!document.activeElement?.closest('.cf-rp-trend'))).toBe(false);
    await expect(tip).toHaveCount(0);
    await expect(days).toHaveAttribute('tabindex', '0');
  });
});

test.describe('Sign-in and entity chooser markup (A4)', () => {
  test('sign-in: one main landmark, the product name, the title "Sign in · LexaVault"', async ({ page }) => {
    await mockIdentityProvider(page);
    await mockApi(page, 'supervisor');
    await page.goto('/');
    await expect(page.getByRole('button', { name: /sign in/i })).toBeVisible();
    await expect(page.getByRole('main')).toHaveCount(1);
    await expect(page.getByRole('main').getByRole('heading', { level: 1, name: 'LexaVault' })).toBeVisible();
    await expect(page).toHaveTitle('Sign in · LexaVault');
    await expect(page.getByText('Secure Document Platform')).toHaveCount(0);
  });

  test('entity chooser: one main landmark and the title "Choose an entity · LexaVault"', async ({ page }) => {
    await mockIdentityProvider(page);
    await mockApi(page, 'supervisor', { twoEntities: true });
    await page.goto('/');
    await page.getByRole('button', { name: /sign in/i }).click();
    const choice = page.getByRole('button', { name: ENTITY.name });
    await expect(choice).toBeVisible();
    await expect(page.getByRole('main')).toHaveCount(1);
    await expect(page.getByRole('main').getByRole('heading', { level: 1, name: 'Choose an entity' })).toBeVisible();
    await expect(page.getByText('Choose an entity to continue.')).toBeVisible();
    await expect(page.getByText(/organization/i)).toHaveCount(0);
    await expect(page).toHaveTitle('Choose an entity · LexaVault');
    await expect(page.getByText('Secure Document Platform')).toHaveCount(0);
  });

  // Task 0022 (A3): before sign-in the chooser follows the computer's setting (the U028 "dark" picture was the light one).
  // --cf-surface in each theme (tokens.css): the chooser's background.
  for (const [scheme, surface] of [['dark', 'rgb(21, 23, 26)'], ['light', 'rgb(255, 255, 255)']] as const) {
    test(`entity chooser: ${scheme} when the computer is set to ${scheme}`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: scheme });
      await mockIdentityProvider(page);
      await mockApi(page, 'supervisor', { twoEntities: true });
      await page.goto('/');
      await page.getByRole('button', { name: /sign in/i }).click();
      await expect(page.getByRole('heading', { level: 1, name: 'Choose an entity' })).toBeVisible();
      await expect(page.locator('html')).toHaveAttribute('data-theme', scheme);
      expect(await page.locator('.cf-boot').evaluate((el) => getComputedStyle(el).backgroundColor)).toBe(surface);
    });
  }

  // Task 0023 (A2): the sign-in screen itself follows the computer's setting too (same approach as the chooser).
  for (const [scheme, surface] of [['dark', 'rgb(21, 23, 26)'], ['light', 'rgb(255, 255, 255)']] as const) {
    test(`sign-in: ${scheme} when the computer is set to ${scheme}`, async ({ page }) => {
      await page.emulateMedia({ colorScheme: scheme });
      await mockIdentityProvider(page);
      await mockApi(page, 'supervisor');
      await page.goto('/');
      await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible();
      await expect(page.locator('html')).toHaveAttribute('data-theme', scheme);
      expect(await page.locator('.cf-boot').evaluate((el) => getComputedStyle(el).backgroundColor)).toBe(surface);
    });
  }

  test('entity chooser: the line above the buttons has room, and the buttons do not touch', async ({ page }) => {
    await mockIdentityProvider(page);
    await mockApi(page, 'supervisor', { twoEntities: true });
    await page.goto('/');
    await page.getByRole('button', { name: /sign in/i }).click();
    const buttons = page.getByRole('main').getByRole('button');
    await expect(buttons).toHaveCount(2);
    const line = await page.getByText('Choose an entity to continue.').boundingBox();
    const first = await buttons.nth(0).boundingBox();
    const second = await buttons.nth(1).boundingBox();
    expect(first!.y - (line!.y + line!.height)).toBeGreaterThanOrEqual(16);
    expect(second!.y - (first!.y + first!.height)).toBeGreaterThanOrEqual(8);
  });
});

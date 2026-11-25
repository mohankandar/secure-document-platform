import { test, expect, type Page } from '@playwright/test';
import { APPROVAL_CASE, APPROVAL_READY, ENTITY_SLOTS, signInAs } from './harness';
import { expectWalk, focusName, focused, staysInDialog, tabTo } from './keys';
import { BULK, SECOND_APPROVAL, adminFields, adminQueue, answer, approvalLink, bulkApprove, catalog, drawMark, entities, newCase, openAt, pageCheck, reportsTab, sources } from './states0089';

/**
 * Task 0089: the keyboard-only pass of the screens added since U026, with real key presses (no mouse, except to draw a mark:
 * drawing needs a pointer, a documented exception). `expectWalk` tabs through the whole screen or dialog: every control is
 * reached, in reading order, inside the window, with a focus ring of at least 2 px, and Tab leaves at the end (no trap).
 * Written up in docs/work/0089-keyboard.md.
 */

/** From the top of the page. */
const top = (page: Page) => page.evaluate(() => document.querySelector<HTMLElement>('.cf-skip')?.focus());
const open = (page: Page) => page.locator('dialog[open]');
/** Presses `button` (which has focus) with `key`, closes the dialog with Esc, and checks focus is back on it. */
async function opensAndEscReturns(page: Page, name: RegExp, key: 'Enter' | 'Space' = 'Enter') {
  await page.keyboard.press(key);
  await expect(open(page)).toHaveCount(1);
  expect(await open(page).evaluate((d) => d.contains(document.activeElement))).toBe(true);
  await staysInDialog(page);
  await page.keyboard.press('Escape');
  await expect(open(page)).toHaveCount(0);
  expect(await focusName(page), 'focus goes back to the button that opened the dialog').toMatch(name);
}
/** The combobox with focus gets `value` by typing its first letters. */
const choose = async (page: Page, text: string) => { await page.keyboard.type(text); };

test.describe('0058 Entities and the switcher', () => {
  test('Entities: the whole screen, each dialog with Enter and Space, Esc returns focus', async ({ page }) => {
    await entities(page);
    await expect(page.getByRole('table', { name: 'Entities you administer' })).toBeVisible();
    const stops = await expectWalk(page);
    expect(stops.join(' ')).toMatch(/Suspend: Commercial lending.*Activate: ENT-LEX-0003.*Resume: Northwind Trust/);
    for (const [button, key] of [['Suspend: Commercial lending', 'Enter'], ['Rename: Commercial lending', 'Space'], ['Time zone: Commercial lending', 'Enter'], ['Activate: ENT-LEX-0003', 'Space'], ['Resume: Northwind Trust', 'Enter']] as const) {
      await top(page);
      await tabTo(page, new RegExp(`^${button}$`));
      await opensAndEscReturns(page, new RegExp(`^${button}$`), key);
    }
  });

  test('Entities: a dialog by Tab; a rename is typed and saved by keyboard', async ({ page }) => {
    const sent: unknown[] = [];
    await entities(page);
    await page.route('**/api/v1/admin/entities/**', (r) => { sent.push(r.request().postDataJSON()); return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ done: true, entity: ENTITY_SLOTS.entities[1] }) }); });
    await tabTo(page, /^Rename: Commercial lending$/);
    await page.keyboard.press('Enter');
    await expect(open(page)).toHaveCount(1);
    await expectWalk(page);
    await tabTo(page, /^Name/, 10);
    await page.keyboard.press('Control+a');
    await page.keyboard.type('Commercial desk');
    await tabTo(page, /^Save$/, 10);
    await page.keyboard.press('Enter');
    await expect(open(page)).toHaveCount(0);
    expect(sent).toHaveLength(1);
    expect(await focusName(page)).toBe('Rename: Commercial lending');
  });

  test('switcher: Enter or an arrow opens it, arrows, Home and End move, Enter chooses, Esc and Tab close', async ({ page }) => {
    await signInAs(page, 'processor', '/', { twoEntities: true });
    const button = page.getByRole('button', { name: /switch entity/i });
    const menu = page.getByRole('menu', { name: 'Entities' });
    await expect(button).toBeVisible();
    await top(page);
    await tabTo(page, /switch entity/i, 20);
    await page.keyboard.press('Enter');
    await expect(menu).toBeVisible();
    await expect(page.getByRole('menuitemradio', { name: /Residential/ })).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(page.getByRole('menuitemradio', { name: /Commercial/ })).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(page.getByRole('menuitemradio', { name: /Residential/ })).toBeFocused();
    await page.keyboard.press('End');
    await expect(page.getByRole('menuitemradio', { name: /Commercial/ })).toBeFocused();
    await page.keyboard.press('Home');
    await expect(page.getByRole('menuitemradio', { name: /Residential/ })).toBeFocused();
    expect((await focused(page)).ring).toBeGreaterThanOrEqual(2);
    await page.keyboard.press('Escape');
    await expect(menu).toHaveCount(0);
    await expect(button).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await expect(menu).toBeVisible();
    await page.keyboard.press('Tab');
    await expect(menu).toHaveCount(0);
  });

  test('switcher with unsaved work: Stay has focus, Esc keeps the work', async ({ page }) => {
    await signInAs(page, 'processor', '/intake', { twoEntities: true });
    await top(page);
    await tabTo(page, /^Loan number/, 40);
    await page.keyboard.type('LN-5858');
    await top(page);
    await tabTo(page, /switch entity/i, 20);
    await page.keyboard.press('Enter');
    await expect(page.getByRole('menuitemradio', { name: /Residential/ })).toBeFocused();
    await page.keyboard.press('ArrowDown');
    await page.keyboard.press('Enter');
    await expect(page.getByRole('alertdialog', { name: 'Unsaved work' })).toBeVisible();
    await expect(page.getByRole('button', { name: 'Stay' })).toBeFocused();
    await page.keyboard.press('Tab');
    await expect(page.getByRole('button', { name: 'Switch anyway' })).toBeFocused();
    await page.keyboard.press('Escape');
    await expect(page.getByRole('alertdialog')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /switch entity/i })).toBeFocused();
    await expect(page.locator('.cf-fields input').first()).toHaveValue('LN-5858');
  });
});

test.describe('0059 Notifications and the approval page', () => {
  test('Notifications (IT): the whole screen; a message is changed in its sheet', async ({ page }) => {
    const sent: unknown[] = [];
    await openAt(page, 'it', '/admin/notifications');
    await expect(page.getByRole('region', { name: 'Last messages' })).toBeVisible();
    await page.route('**/api/v1/admin/notifications/it/**', (r) => { sent.push(r.request().postDataJSON()); return r.fallback(); });
    await expectWalk(page);
    await top(page);
    await tabTo(page, /^Change: Repeated processing failures$/, 90);
    await opensAndEscReturns(page, /^Change: Repeated processing failures$/, 'Space');
    await page.keyboard.press('Enter');
    await expect(open(page)).toHaveCount(1);
    const stops = await expectWalk(page);
    expect(stops.join(' ')).toMatch(/Send this message.*How often.*Tell after.*Tell again every.*Problems in an hour.*Cancel.*Save/);
    await tabTo(page, /^Send this message/, 20);
    await page.keyboard.press('Space');
    await expect(page.getByRole('switch', { name: /^Send this message/ })).not.toBeChecked();
    await tabTo(page, /^Save$/, 20);
    await page.keyboard.press('Enter');
    await expect.poll(() => sent.length).toBe(1);
  });

  test('Notifications (business): the whole screen', async ({ page }) => {
    await openAt(page, 'business', '/admin/notifications');
    await expect(page.getByRole('table', { name: 'Business messages' })).toBeVisible();
    await expectWalk(page);
  });

  test('approval page: read in order, a reason enables Decline, Enter decides', async ({ page }) => {
    const sent: string[] = [];
    await approvalLink(page, APPROVAL_READY);
    await page.route('**/api/v1/approvals/link/*', (r) => { if (r.request().url().endsWith('/open')) return r.fallback(); sent.push(JSON.stringify(r.request().postDataJSON())); return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...APPROVAL_READY, state: 'DECLINED', message: 'Delivery was declined.' }) }); });
    await expect(page.getByRole('button', { name: 'Approve delivery' })).toBeVisible();
    const stops = await expectWalk(page);
    expect(stops.join(' ')).toMatch(/Look at the case first.*Reason.*Approve delivery/);
    await top(page);
    await tabTo(page, /^Reason/, 30);
    await choose(page, 'Wrong');
    await page.keyboard.press('Tab');
    expect(await focusName(page)).toBe('Approve delivery');
    await page.keyboard.press('Tab');
    expect(await focusName(page)).toBe('Decline delivery');
    await page.keyboard.press('Enter');
    await expect(page.getByText('Delivery was declined.')).toBeVisible();
    expect(sent.join()).toContain('WRONG_DESTINATION');
    await tabTo(page, /^Open the case$/, 40);
  });
});

test.describe('0061 Case fields', () => {
  test('New case: every kind of field is reached and filled; a second value is added and removed', async ({ page }) => {
    await newCase(page);
    const stops = await expectWalk(page);
    expect(stops.join(' ')).toMatch(/Loan number.*Branch.*Amount.*Opened.*Street.*City.*State.*ZIP.*Borrower name.*Add another.*Sensitivity.*Priority.*Upload PDFs/);
    await top(page);
    await tabTo(page, /^Loan number/, 30);
    await page.keyboard.type('204860');
    await page.keyboard.press('Tab');
    await choose(page, 'South');
    await expect(page.getByLabel('Branch')).toHaveValue('South');
    await tabTo(page, /^Add another$/, 30);
    await page.keyboard.press('Enter');
    expect(await focusName(page), 'the new value is where you type next').toMatch(/^Borrower name 2/);
    await page.keyboard.type('Synthetic Heron');
    await page.keyboard.press('Tab');
    expect(await focusName(page)).toBe('Remove Borrower name 2');
    await page.keyboard.press('Enter');
    expect(await focusName(page), 'focus is not lost when a value is removed').toMatch(/Borrower name/);
  });

  test('New case: the capture tabs take the arrow keys', async ({ page }) => {
    await newCase(page);
    await tabTo(page, /^Upload PDFs$/, 40);
    await page.keyboard.press('ArrowRight');
    await expect(page.getByRole('tab', { name: 'Scan paper' })).toBeFocused();
    await expect(page.getByRole('tab', { name: 'Scan paper' })).toHaveAttribute('aria-selected', 'true');
    await page.keyboard.press('ArrowLeft');
    await expect(page.getByRole('tab', { name: 'Upload PDFs' })).toBeFocused();
    await expect(page.getByRole('tab', { name: 'Upload PDFs' })).toHaveAttribute('aria-selected', 'true');
  });

  test('Admin fields: the whole screen; the editor by keyboard; Esc, Cancel and Apply return focus', async ({ page }) => {
    await adminFields(page);
    await expectWalk(page);
    await top(page);
    await tabTo(page, /^Edit Branch$/, 120);
    await opensAndEscReturns(page, /^Edit Branch$/);
    await page.keyboard.press('Enter');
    await expect(open(page)).toHaveCount(1);
    await expectWalk(page);
    await tabTo(page, /^Required when a case is created/, 30);
    await page.keyboard.press('Space');
    await tabTo(page, /^Apply$/, 30);
    await page.keyboard.press('Enter');
    await expect(open(page)).toHaveCount(0);
    expect(await focusName(page)).toBe('Edit Branch');
  });

  test('Admin fields: moving a field with Enter keeps focus on that field', async ({ page }) => {
    await adminFields(page);
    await tabTo(page, /^Move Amount down$|^Move amount down$/i, 120);
    await page.keyboard.press('Enter');
    expect(await focusName(page)).toMatch(/^Move amount down$/i);
    await page.keyboard.press('Enter');
    expect(await focusName(page)).toMatch(/^Move amount down$/i);
  });
});

test.describe('0062 Redaction catalog and packs', () => {
  test('packs, locked items and styles: the whole screen; Space switches a pack; a locked item is read and does nothing', async ({ page }) => {
    await catalog(page);
    await expectWalk(page);
    await top(page);
    await tabTo(page, /^PCI DSS/, 60);
    await page.keyboard.press('Space');
    await expect(page.getByRole('switch', { name: /^PCI DSS/ })).toBeChecked();
    await tabTo(page, /locked|always/i, 40);
    const before = await page.evaluate(() => document.activeElement!.getAttribute('aria-pressed'));
    await page.keyboard.press('Space');
    expect(await page.evaluate(() => document.activeElement!.getAttribute('aria-pressed'))).toBe(before);
    expect(await page.evaluate(() => document.activeElement!.getAttribute('aria-disabled'))).toBe('true');
  });

  test('Send to review: one Tab stop, arrows choose', async ({ page }) => {
    await catalog(page);
    await tabTo(page, /^Fine-tune by hand$/, 60);
    await page.keyboard.press('Enter');
    const group = page.getByRole('radiogroup', { name: 'Review mode' });
    await expect(group).toBeVisible();
    const first = group.getByRole('radio').first();
    await tabTo(page, new RegExp(`^${(await first.textContent())!.trim().slice(0, 8)}`), 10);
    await expect(first).toBeFocused();
    await page.keyboard.press('ArrowRight');
    await expect(group.getByRole('radio').last()).toBeFocused();
    await expect(group.getByRole('radio').last()).toHaveAttribute('aria-checked', 'true');
    await page.keyboard.press('Tab');
    expect(await group.evaluate((g) => g.contains(document.activeElement)), 'Tab leaves the group').toBe(false);
  });
});

test.describe('0071 Reject', () => {
  for (const [where, who, path, name] of [['case panel', 'supervisor', `/case/${APPROVAL_CASE.packageId}`, 'LN-3002'], ['Review', 'processor', '/review', 'LN-3001']] as const) {
    test(`from ${where}: Enter opens, a reason is needed, Esc returns focus, Enter rejects`, async ({ page }) => {
      const sent: string[] = [];
      await openAt(page, who, path);
      await page.route('**/api/v1/packages/*/*/reject', (r) => { sent.push(r.request().postDataJSON().reasonCode); return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ status: 'REJECTED' }) }); });
      await expect(page.getByRole('button', { name: 'Reject case' })).toBeVisible();
      await top(page);
      await tabTo(page, /^Reject case$/, 80);
      await opensAndEscReturns(page, /^Reject case$/);
      await page.keyboard.press('Space');
      await expect(page.getByRole('dialog', { name: `Reject case ${name}?` })).toBeVisible();
      await expectWalk(page);
      await tabTo(page, /reason/i, 10);
      await page.keyboard.press('ArrowDown');
      await tabTo(page, /^Reject/, 10);
      await page.keyboard.press('Enter');
      await expect.poll(() => sent.length).toBe(1);
      await expect(open(page)).toHaveCount(0);
    });
  }
});

test.describe('0073 Priority', () => {
  test('the case panel: the whole panel; a change by keyboard keeps focus on the field', async ({ page }) => {
    const sent: string[] = [];
    await openAt(page, 'supervisor', `/case/${APPROVAL_CASE.packageId}`);
    await page.route('**/api/v1/packages/*/priority', (r) => { sent.push(r.request().postDataJSON().priority); return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ priority: 'LOW' }) }); });
    await expect(page.getByTestId('case-priority')).toBeVisible();
    await expectWalk(page);
    await top(page);
    await tabTo(page, /^Priority/, 80);
    await page.keyboard.press('ArrowDown');
    await expect.poll(() => sent.length).toBe(1);
    await expect(page.getByText(/Priority is now/)).toBeVisible();
    expect((await focused(page)).name).toMatch(/^Priority/);
  });

  test('a source: a change by keyboard keeps focus on the field', async ({ page }) => {
    await sources(page, []);
    await page.route('**/api/v1/source-priorities/*', (r) => r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sources: { 'c-los': 'NORMAL' } }) }));
    await tabTo(page, /^Priority of cases from this source/, 60);
    await page.keyboard.press('ArrowUp');
    await expect(page.getByText(/Cases from this source will be/)).toBeVisible();
    expect((await focused(page)).name).toMatch(/^Priority of cases from this source/);
  });
});

test.describe('0074 Page to check', () => {
  test('the whole screen; confirmed with Space and finished with Enter without a mark', async ({ page }) => {
    const sent: unknown[] = [];
    await pageCheck(page);
    await page.route('**/api/v1/review-items/*/page-checked', (r) => { sent.push(r.request().postDataJSON()); return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ findingId: 'p1', status: 'CONFIRMED', processingResumed: false }) }); });
    await expectWalk(page);
    await top(page);
    await tabTo(page, /Page to check.*p\.2/, 40);
    await tabTo(page, /I.*(checked|looked|page)/, 20);
    await page.keyboard.press('Space');
    await tabTo(page, /R$/, 10);
    await page.keyboard.press('Enter');
    await expect.poll(() => sent).toEqual([{ reviewed: true, boxes: [] }]);
  });

  test('a drawn mark is removed with Enter and focus stays in the decision panel', async ({ page }) => {
    await pageCheck(page);
    await drawMark(page); // drawing needs a pointer: documented exception
    await top(page);
    await tabTo(page, /^Remove.* 1$/, 40);
    await page.keyboard.press('Enter');
    await expect(page.locator('.cf-page-marks')).toHaveCount(0);
    expect(await page.evaluate(() => Boolean(document.activeElement?.closest('.cf-decision')))).toBe(true);
  });
});

test.describe('0076 Bulk approve', () => {
  test('Space ticks, the person’s own case is skipped and explained, the dialog, Esc returns focus, Enter approves', async ({ page }) => {
    const sent: string[][] = [];
    await bulkApprove(page);
    await page.route('**/api/v1/bulk-approval', (r) => { sent.push(r.request().postDataJSON().packageIds); return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify([{ packageId: APPROVAL_CASE.packageId, approved: true }, { packageId: SECOND_APPROVAL.packageId, approved: false, reasonCode: 'LV-REV-002' }]) }); });
    const stops = await expectWalk(page);
    expect(stops.join(' ')).not.toContain('Select LN-3013');
    await top(page);
    for (const loan of ['LN-3012', 'LN-3002']) {
      await top(page);
      await tabTo(page, new RegExp(`^Select ${loan}$`), 40);
      await page.keyboard.press('Space');
      await expect(page.getByRole('checkbox', { name: `Select ${loan}` })).toBeChecked();
    }
    await top(page);
    await tabTo(page, /^Approve selected \(2\)$/, 40);
    await opensAndEscReturns(page, /^Approve selected \(2\)$/);
    await page.keyboard.press('Enter');
    await expectWalk(page);
    await tabTo(page, /^Approve 2$/, 10);
    await page.keyboard.press('Enter');
    await expect(page.getByText('1 case approved.')).toBeVisible();
    expect(sent).toHaveLength(1);
    await tabTo(page, /LV-REV-002/, 40);
  });
});

test.describe('0078 Queue and time left', () => {
  test('admin card: the whole screen; the table is a Tab stop that scrolls; Enter shows how it is worked out', async ({ page }) => {
    await adminQueue(page);
    await expect(page.getByRole('heading', { name: 'Queue, every entity together' })).toBeVisible();
    await expectWalk(page);
    await top(page);
    await tabTo(page, /^Queue by step$/, 60);
    await tabTo(page, /^How is this worked out\?$/, 3);
    await page.keyboard.press('Enter');
    await expect(page.locator('#cf-pq-how')).toBeVisible();
    await page.keyboard.press('Space');
    await expect(page.locator('#cf-pq-how')).toHaveCount(0);
    expect(await focusName(page)).toBe('How is this worked out?');
  });

  test('Reports › Operations: the whole screen', async ({ page }) => {
    await reportsTab(page, 'operations');
    await expect(page.getByRole('heading', { name: 'Queue', exact: true })).toBeVisible();
    await expectWalk(page);
  });
});

test.describe('0079 Bulk sources', () => {
  test('Space marks a source, times are typed, Enter saves, ending asks first and Esc returns focus', async ({ page }) => {
    const sent: string[] = [];
    let bulk: object[] = [];
    await sources(page, []);
    await page.route('**/api/v1/bulk-sources**', (r) => {
      const m = r.request().method();
      if (m === 'PUT') bulk = [{ ...BULK, ...r.request().postDataJSON() }];
      if (m === 'DELETE') bulk = [];
      if (m !== 'GET') sent.push(m);
      return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sources: bulk }) });
    });
    await page.route('**/api/v1/bulk-sources/**', (r) => { const m = r.request().method(); bulk = m === 'DELETE' ? [] : [{ ...BULK, ...r.request().postDataJSON() }]; sent.push(m); return r.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ sources: bulk }) }); });
    await tabTo(page, /^Migration \(Bulk\)/, 60);
    await page.keyboard.press('Space');
    await expect(page.getByTestId('bulk-progress')).toBeVisible();
    expect(await focusName(page), 'focus stays on the switch').toMatch(/^Migration \(Bulk\)/);
    await top(page);
    await expectWalk(page);
    await top(page);
    await tabTo(page, /^Weeknights from/, 60);
    await page.keyboard.press('ArrowUp');
    await expect(page.getByRole('button', { name: 'Save times' })).toBeVisible();
    await tabTo(page, /^Save times$/, 20);
    await page.keyboard.press('Enter');
    await expect.poll(() => sent).toEqual(['PUT', 'PUT']);
    await expect(page.getByRole('button', { name: 'Save times' })).toHaveCount(0);
    await top(page);
    await tabTo(page, /^Migration \(Bulk\)/, 60);
    await opensAndEscReturns(page, /^Migration \(Bulk\)/, 'Space');
    await page.keyboard.press('Space');
    await tabTo(page, /^End migration$/, 10);
    await page.keyboard.press('Enter');
    await expect.poll(() => sent).toEqual(['PUT', 'PUT', 'DELETE']);
    await expect(open(page)).toHaveCount(0);
    expect(await focusName(page)).toMatch(/^Migration \(Bulk\)/);
  });
});

test.describe('0081 Reports tabs', () => {
  test('tabs: one Tab stop; arrows, Home and End choose', async ({ page }) => {
    await reportsTab(page, 'overview');
    await tabTo(page, /^Overview$/, 30);
    for (const [key, name] of [['ArrowRight', 'Daily intake and delivery'], ['ArrowRight', 'Operations'], ['ArrowRight', 'Overview'], ['ArrowLeft', 'Operations'], ['Home', 'Overview'], ['End', 'Operations']] as const) {
      await page.keyboard.press(key);
      await expect(page.getByRole('tab', { name })).toBeFocused();
      await expect(page.getByRole('tab', { name })).toHaveAttribute('aria-selected', 'true');
    }
  });

  test('Period: one Tab stop; arrows, Home and End choose; the dates of an own range are typed', async ({ page }) => {
    await reportsTab(page, 'daily');
    const group = page.getByRole('radiogroup', { name: 'Period' });
    await expect(group.getByRole('radio', { name: 'Today' })).toHaveAttribute('aria-checked', 'true');
    await tabTo(page, /^Today$/, 30);
    await page.keyboard.press('ArrowRight');
    await expect(group.getByRole('radio').nth(1)).toBeFocused();
    await expect(group.getByRole('radio').nth(1)).toHaveAttribute('aria-checked', 'true');
    await page.keyboard.press('End');
    await expect(group.getByRole('radio', { name: 'Custom' })).toBeFocused();
    await expect(group.getByRole('radio', { name: 'Custom' })).toHaveAttribute('aria-checked', 'true');
    await page.keyboard.press('Tab');
    expect(await focusName(page)).toBe('From');
    await page.keyboard.type('11102026');
    await expect(page.locator('.cf-rp-range input').first()).toHaveValue('2026-11-10');
    await tabTo(page, /^To$/, 6);
    await tabTo(page, /^Source system/, 8);
  });

  for (const tab of ['overview', 'daily'] as const) {
    test(`${tab}: the whole screen`, async ({ page }) => {
      await reportsTab(page, tab);
      await expect(page.locator('.cf-rp-body')).toBeVisible();
      await expectWalk(page);
    });
  }

  test('daily: a tile’s link goes to the reasons, and a reason’s code opens the guide in a new tab', async ({ page }) => {
    await reportsTab(page, 'daily');
    await expect(page.locator('.cf-rp-body')).toBeVisible();
    await tabTo(page, /^6 cases$/, 60);
    await page.keyboard.press('Enter');
    await expect(page.locator('#cf-dr-reasons')).toBeInViewport();
    await tabTo(page, /^LV-INT-003$/, 12);
    const opened = page.context().waitForEvent('page');
    await page.keyboard.press('Enter');
    await expect(await opened).toHaveURL(/error-codes.LV-INT-003$/);
  });
});

test.describe('0082 Error code guide', () => {
  test('in the app: the whole screen', async ({ page }) => {
    await openAt(page, 'viewer', '/help/error-codes');
    await expectWalk(page);
  });

  test('search, clear, an area link, an entry with Enter and Space', async ({ page }) => {
    await openAt(page, 'viewer', '/help/error-codes');
    await tabTo(page, /^Find a code or words/, 30);
    await page.keyboard.type('LV-SYS-006');
    await expect(page.locator('details[data-code="LV-SYS-006"]')).toHaveAttribute('open', '');
    await page.keyboard.press('Tab');
    expect(await focusName(page)).toBe('Clear search');
    await page.keyboard.press('Enter');
    expect(await focusName(page), 'focus goes back to the search box').toMatch(/^Find a code or words/);
    const area = (await page.locator('.cf-help-areas a').nth(2).textContent())!;
    const key = (await page.locator('.cf-help-areas a').nth(2).getAttribute('href'))!;
    await tabTo(page, new RegExp(`^${area}$`), 10);
    await page.keyboard.press('Enter');
    await page.keyboard.press('Tab');
    expect(await page.evaluate((k) => Boolean(document.activeElement?.closest(k)), key), 'Tab goes on in the area').toBe(true);
    const entry = page.locator(`${key} details`).first();
    await page.keyboard.press('Enter');
    await expect(entry).toHaveAttribute('open', '');
    await page.keyboard.press('Space');
    await expect(entry).not.toHaveAttribute('open', '');
  });

  test('without a session: the entry asked for has focus; the whole page', async ({ page }) => {
    await page.goto('/help/error-codes/LV-INT-001');
    await expect.poll(() => focusName(page)).toMatch(/^LV-INT-001/);
    await expectWalk(page);
  });
});

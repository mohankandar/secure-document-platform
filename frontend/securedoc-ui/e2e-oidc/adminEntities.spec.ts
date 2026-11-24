import { test, expect, type Page, type Route } from '@playwright/test';
import { ENTITY_SLOTS, signInAs } from '../e2e-a11y/harness';

/** Task 0058: Admin › Entities (Business Admin), with the API mocked. */

type Answer = { done: boolean; code: string; message: string; whatToDo: string | null };
const DONE: Answer = { done: true, code: 'OK', message: '', whatToDo: null };

async function open(page: Page, answers: Record<string, Answer> = {}, overview: object = ENTITY_SLOTS) {
  const writes: Array<{ call: string; body: unknown }> = [];
  await signInAs(page, 'business');
  await page.route('**/api/v1/admin/entities**', async (route: Route) => {
    const request = route.request();
    const path = new URL(request.url()).pathname.replace('/api/v1/admin/entities', '');
    if (request.method() === 'GET') return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(overview) });
    const call = `${request.method()} ${path}`;
    writes.push({ call, body: request.postDataJSON() });
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(answers[call] ?? DONE) });
  });
  await page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Admin' }).click();
  await page.getByRole('navigation', { name: 'Administration areas' }).getByRole('link', { name: /Entities/ }).click();
  await expect(page.getByRole('table', { name: 'Entities you administer' })).toBeVisible();
  return writes;
}

const row = (page: Page, text: string) => page.getByRole('row').filter({ hasText: text });

test('each entity shows its name, slot, status and time zone, and only what can be done', async ({ page }) => {
  await open(page);
  const here = row(page, 'ENT-LEX-0001');
  await expect(here).toContainText('Residential lending');
  await expect(here).toContainText('You are here');
  await expect(here).toContainText('Active');
  await expect(here).toContainText('America/Chicago (the customer’s)');
  await expect(here.getByRole('button', { name: /^Suspend/ })).toHaveCount(0);
  await expect(row(page, 'ENT-LEX-0002').getByRole('button', { name: 'Suspend: Commercial lending' })).toBeVisible();
  await expect(row(page, 'ENT-LEX-0003').getByRole('button')).toHaveText(['Activate']);
  await expect(row(page, 'ENT-LEX-0004').getByRole('button', { name: /^Resume/ })).toBeVisible();
});

test('activating needs a name, and sends exactly what was typed', async ({ page }) => {
  const writes = await open(page);
  await row(page, 'ENT-LEX-0003').getByRole('button', { name: 'Activate: ENT-LEX-0003' }).click();
  const sheet = page.getByRole('dialog', { name: 'Activate ENT-LEX-0003' });
  await expect(sheet.getByRole('button', { name: 'Activate', exact: true })).toBeDisabled();
  await sheet.getByLabel('Name').fill('Northwind Trust');
  await sheet.getByRole('button', { name: 'Activate', exact: true }).click();
  await expect(sheet).toHaveCount(0);
  await expect(page.getByText('Northwind Trust is active.')).toBeVisible();
  expect(writes).toEqual([{ call: 'POST /ENT-LEX-0003/activate', body: { name: 'Northwind Trust' } }]);
});

test('a refusal says what to do and keeps the dialog open', async ({ page }) => {
  await open(page, { 'PUT /ENT-LEX-0002/name': { done: false, code: 'NAME_TAKEN', message: 'Another entity already has this name.', whatToDo: 'Choose another name.' } });
  await row(page, 'ENT-LEX-0002').getByRole('button', { name: /^Rename/ }).click();
  const sheet = page.getByRole('dialog', { name: 'Rename Commercial lending' });
  await sheet.getByLabel('Name').fill('Residential lending');
  await sheet.getByRole('button', { name: 'Save' }).click();
  await expect(sheet).toContainText('Another entity already has this name. Choose another name.');
});

test('suspending is confirmed with what it does; resuming too', async ({ page }) => {
  const writes = await open(page);
  await row(page, 'ENT-LEX-0002').getByRole('button', { name: /^Suspend/ }).click();
  const dialog = page.getByRole('dialog', { name: 'Suspend Commercial lending?' });
  await expect(dialog).toContainText('nothing new is taken in from any source');
  await expect(dialog).toContainText('Nothing is deleted');
  await dialog.getByRole('button', { name: 'Cancel' }).click();
  expect(writes).toEqual([]);
  await row(page, 'ENT-LEX-0002').getByRole('button', { name: /^Suspend/ }).click();
  await dialog.getByRole('button', { name: 'Suspend', exact: true }).click();
  await expect(dialog).toHaveCount(0);
  await row(page, 'ENT-LEX-0004').getByRole('button', { name: /^Resume/ }).click();
  await page.getByRole('dialog').getByRole('button', { name: 'Resume', exact: true }).click();
  await expect(page.getByRole('dialog')).toHaveCount(0);
  expect(writes.map((w) => w.call)).toEqual(['POST /ENT-LEX-0002/suspend', 'POST /ENT-LEX-0004/resume']);
});

test('a time zone is chosen by name, or the customer’s again', async ({ page }) => {
  const writes = await open(page);
  await row(page, 'ENT-LEX-0002').getByRole('button', { name: /^Time zone/ }).click();
  const sheet = page.getByRole('dialog', { name: 'Time zone of Commercial lending' });
  await expect(sheet).toContainText('When stored copies are deleted does not change');
  await sheet.getByLabel('Time zone').selectOption('America/New_York');
  await sheet.getByRole('button', { name: 'Save' }).click();
  await expect(sheet).toHaveCount(0);
  await row(page, 'ENT-LEX-0002').getByRole('button', { name: /^Time zone/ }).click();
  await sheet.getByLabel('Time zone').selectOption('');
  await sheet.getByRole('button', { name: 'Save' }).click();
  await expect(sheet).toHaveCount(0);
  expect(writes).toEqual([
    { call: 'PUT /ENT-LEX-0002/time-zone', body: { timeZone: 'America/New_York' } },
    { call: 'PUT /ENT-LEX-0002/time-zone', body: { timeZone: null } },
  ]);
});

test('while people can’t choose an entity at sign-in, the screen says only one can be active', async ({ page }) => {
  await open(page, {}, { ...ENTITY_SLOTS, entityChoiceOn: false });
  await expect(page.getByText('Only one entity can be active until people can choose an entity when they sign in.')).toBeVisible();
});

test('nobody but a Business Admin has the area', async ({ page }) => {
  await signInAs(page, 'it', '/admin/entities');
  await expect(page.getByRole('navigation', { name: 'Administration areas' }).getByRole('link', { name: /Entities/ })).toHaveCount(0);
  await expect(page.getByRole('table', { name: 'Entities you administer' })).toHaveCount(0);
});

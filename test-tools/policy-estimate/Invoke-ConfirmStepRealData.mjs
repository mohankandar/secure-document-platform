// TASK 0020 A4: the U023 confirm step against real data. Needs Invoke-PolicyEstimateCheck.ps1 -KeepRunning (21 real
// cases, saved policy UNCERTAIN_ONLY 0.99) and the UI from vite.realdata.config.mjs on 15180, in LOCAL mode. Acts as
// the Business Administrator (LOCAL mode: the role header on each API call), chooses Light, checks the confirm step
// with the real numbers, presses Keep (nothing saved), then Save (saved). Pictures go to the folder given as the
// first argument. Prints one JSON line with what it saw. Exit code 0 only when every step passed.
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const frontendRoot = path.resolve(here, '..', '..', 'frontend', 'securedoc-ui');
const { chromium } = createRequire(path.join(frontendRoot, 'package.json'))('@playwright/test');
const out = path.resolve(process.argv[2] ?? path.join(here, '..', '..', 'docs', 'work', 'ui', 'compare', 'U023'));
fs.mkdirSync(out, { recursive: true });
const ui = 'http://127.0.0.1:15180';

const seen = { steps: [], writes: [] };
const step = (name, ok, detail = '') => { seen.steps.push({ name, ok, detail }); if (!ok) throw new Error(`${name}: ${detail}`); };

const browser = await chromium.launch();
let failed = false;
try {
  const page = await browser.newPage({ viewport: { width: 1280, height: 1000 } });
  await page.route('**/api/v1/**', (route) => {
    const request = route.request();
    if (request.method() === 'PUT') seen.writes.push(new URL(request.url()).pathname);
    return route.continue({ headers: { ...request.headers(), 'x-actor-roles': 'BUSINESS_ADMIN' } });
  });
  const estimates = [];
  page.on('response', async (r) => {
    if (r.request().method() === 'PUT') seen.putStatus = r.status();
    if (r.request().method() === 'PUT' && r.status() >= 400) seen.putError = (await r.text().catch(() => '')).slice(0, 300);
    if (r.url().includes('/sensitive-data-policy/estimate')) {
      estimates.push({ query: new URL(r.url()).search, status: r.status(), body: await r.json().catch(() => null) });
    }
  });
  await page.goto(`${ui}/admin/policy`);
  const card = page.getByRole('region', { name: 'Sensitive data' });
  await card.waitFor({ timeout: 30000 });
  const radio = (name) => card.getByRole('radio', { name: new RegExp(`^${name}\\b`) });
  const inUse = async (name) => (await radio(name).evaluate((el) => el.closest('label').textContent)).includes('In use');
  step('saved policy is Custom (0.99): no preset chosen',
    !(await radio('Light').isChecked()) && !(await radio('Balanced').isChecked()) && !(await radio('Strict').isChecked()));
  await page.screenshot({ path: path.join(out, 'realdata-1-saved-custom.png'), fullPage: true });

  await radio('Light').check();
  await card.locator('.cf-sp-preview[data-state="ready"]').waitFor({ timeout: 15000 });
  seen.preview = { head: await card.locator('.cf-sp-preview-head').innerText(), tiles: await card.locator('.cf-sp-tile').allInnerTexts() };
  await page.screenshot({ path: path.join(out, 'realdata-2-light-preview.png'), fullPage: true });

  const est = estimates.at(-1)?.body;
  seen.estimate = est;
  step('estimate answered for Light, with enough data', !!est && est.enoughData === true && est.proposed.preset === 'LIGHT', JSON.stringify(est));
  const fewer = est.current.findingsToPerson - est.proposed.findingsToPerson;
  const fewerCases = est.current.casesToPerson - est.proposed.casesToPerson;
  step('fewer findings would go to a person', fewer > 0, `${est.current.findingsToPerson} -> ${est.proposed.findingsToPerson}`);

  await card.getByRole('button', { name: 'Save Light' }).click();
  const dialog = page.getByRole('alertdialog', { name: 'A person would check fewer findings' });
  await dialog.waitFor({ timeout: 15000 });
  const description = (await dialog.locator('p').first().innerText()).replace(/\s+/g, ' ').trim();
  const buttons = await dialog.getByRole('button').allInnerTexts();
  const auditLine = await dialog.locator('.cf-sp-dialog-note').innerText();
  seen.dialog = { description, buttons, auditLine };
  await page.screenshot({ path: path.join(out, 'realdata-3-confirm.png'), fullPage: true });
  const expected = `With these settings, about ${fewer} fewer findings${fewerCases > 0 ? ` and ${fewerCases} fewer cases` : ''} in the last ${est.windowDays} days would have had a person check them.`;
  step('the confirm step shows the real numbers', description === expected, `${description} | expected ${expected}`);
  step('Custom wording on the buttons', JSON.stringify(buttons) === JSON.stringify(['Keep your current settings', 'Save these settings']), JSON.stringify(buttons));
  step('the audit line names Custom → Light', auditLine.endsWith('as Custom → Light.'), auditLine);

  await dialog.getByRole('button', { name: 'Keep your current settings' }).click();
  await dialog.waitFor({ state: 'detached' });
  await page.waitForTimeout(500);
  step('Keep: nothing saved, the card back to the saved policy', seen.writes.length === 0 && !(await radio('Light').isChecked()), JSON.stringify(seen.writes));
  await page.screenshot({ path: path.join(out, 'realdata-4-kept.png'), fullPage: true });

  await radio('Light').check();
  await card.locator('.cf-sp-preview[data-state="ready"]').waitFor({ timeout: 15000 });
  await card.getByRole('button', { name: 'Save Light' }).click();
  await dialog.waitFor({ timeout: 15000 });
  await dialog.getByRole('button', { name: 'Save these settings' }).click();
  await page.getByText('Redaction policy saved. New cases use it immediately.').waitFor({ timeout: 15000 });
  await card.locator('.cf-sp-inuse').waitFor();
  step('Save: one write, and Light is now in use', seen.writes.length === 1 && (await inUse('Light')), JSON.stringify(seen.writes));
  await page.screenshot({ path: path.join(out, 'realdata-5-saved-light.png'), fullPage: true });
} catch (e) {
  failed = true;
  seen.error = String(e?.message ?? e);
} finally {
  await browser.close();
}
console.log(JSON.stringify(seen));
process.exit(failed ? 1 : 0);

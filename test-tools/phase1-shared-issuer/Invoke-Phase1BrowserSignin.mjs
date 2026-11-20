// PHASE 1 TEST ENVIRONMENT ONLY. Drives the real browser sign-in (Authorization Code + PKCE) against the Phase 1 UI
// in headless Microsoft Edge (Playwright from the frontend's node_modules; no browser download needed).
//
// Usage (from the repository root):
//   node test-tools/phase1-shared-issuer/Invoke-Phase1BrowserSignin.mjs [--mode dev|prod] [--user <name>]
//     --mode dev   http://localhost:15173/ (Vite dev server, Start-Phase1Ui.ps1)            [default]
//     --mode prod  http://localhost:15174/ (production build, Start-Phase1UiProd.ps1)
//     --user       one user; default: carol.ab, alice.a, bob.b, dave.none
//
// Each user runs in a fresh browser context (no shared Keycloak session). Prints HTTP statuses, the visible
// screen text and the app's own console messages only. Never prints or saves a token or a password.
// Results: C:\dev\lexavault\phase1-run\results\browser-<mode>-<time>.json (statuses and outcomes only).
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const frontendRoot = path.resolve(here, '..', '..', 'frontend', 'securedoc-ui');
const { chromium } = createRequire(path.join(frontendRoot, 'package.json'))('@playwright/test');

const arg = (name, fallback) => { const i = process.argv.indexOf(`--${name}`); return i > 0 ? process.argv[i + 1] : fallback; };
const mode = arg('mode', 'dev');
const baseUrl = mode === 'prod' ? 'http://localhost:15174/' : 'http://localhost:15173/';
const users = arg('user', null) ? [arg('user')] : ['carol.ab', 'alice.a', 'bob.b', 'dave.none'];
const runRoot = 'C:/dev/lexavault/phase1-run';
const passwords = JSON.parse(fs.readFileSync(`${runRoot}/secrets/users.json`, 'utf8').replace(/^\uFEFF/, ''));

// What each user should end up seeing. carol is asked to choose Entity A on the chooser, then (newest design)
// switches to Entity B and back without signing out.
const expected = {
  'carol.ab': { choose: 'Phase1 Entity A', switchTo: ['Phase1 Entity B', 'Phase1 Entity A'],
    outcome: 'WORKSPACE Phase1 Entity A (after the chooser); switch to Phase1 Entity B ok; switch to Phase1 Entity A ok' },
  'alice.a': { outcome: 'WORKSPACE Phase1 Entity A; no switch control' },
  'bob.b': { outcome: 'WORKSPACE Phase1 Entity B; no switch control' },
  'dave.none': { outcome: 'REFUSED' },
};

const redact = (s) => String(s)
  .replace(/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*/g, '<jwt>')
  .replace(/(code|state|session_state|code_verifier|refresh_token|access_token|id_token)=[^&\s"]+/g, '$1=<x>');

// inWorkspace: the signed-in workspace is on screen (its account button, which holds Sign out since UI task U002).
function classify(text, sawChooser, inWorkspace) {
  const entity = /Phase1 Entity [AB]/.exec(text)?.[0];
  if (text.includes('Choose an entity')) return 'CHOOSER';
  if ((inWorkspace || text.includes('Sign out')) && entity) return `WORKSPACE ${entity}${sawChooser ? ' (after the chooser)' : ''}`;
  // UI task U013: the refusal is the Case File no-access screen (it replaces "does not have access" and, for no entity, "Sign-in could not be completed").
  if (text.includes("You don't have access to anything in LexaVault yet")) return 'REFUSED';
  return 'UNKNOWN';
}

// Phase 1 seeds and the check script give entity A contract numbers ending in 1 and entity B ending in 2.
const caseNumbers = (text) => [...text.matchAll(/\b[89]\d{10}([12])\b/g)].map((m) => m[1]);

// The entity switch is in the account menu at the top right (UI task U002): entities are menu items in the
// "Entities" group. With one entity the group is absent.
const accountButton = (page) => page.getByRole('button', { name: /account menu/i });
const entityItems = (page) => page.getByRole('menu', { name: 'Account' }).getByRole('group', { name: 'Entities' }).getByRole('menuitemradio');

async function hasSwitchControl(page) {
  await accountButton(page).click();
  await page.getByRole('menu', { name: 'Account' }).waitFor();
  await page.waitForTimeout(1000); // the entity list is read fresh when the menu opens
  const count = await entityItems(page).count();
  await page.keyboard.press('Escape');
  return count > 0;
}

// Uses the switch in the account menu (newest design). Returns 'ok' or a short reason; never signs out.
async function switchEntity(page, target) {
  const control = accountButton(page);
  if ((await control.count()) === 0) return 'FAILED: no account menu';
  await control.click();
  const item = entityItems(page).filter({ hasText: target });
  try { await item.waitFor({ timeout: 10000 }); } catch { await page.keyboard.press('Escape'); return 'FAILED: no switch control'; }
  await item.click();
  const confirm = page.getByRole('alertdialog');
  if (await confirm.isVisible().catch(() => false)) return 'FAILED: unexpected unsaved-work confirmation';
  await page.waitForTimeout(5000);
  const label = (await control.getAttribute('aria-label').catch(() => '')) ?? '';
  if (!label.includes(target)) return `FAILED: still on "${label}"`;
  const main = (await page.locator('main').innerText().catch(() => '')).replace(/\s+/g, ' ');
  const digits = caseNumbers(main);
  const want = target.endsWith('A') ? '1' : '2';
  if (digits.length === 0) return 'FAILED: no cases shown';
  if (digits.some((d) => d !== want)) return `FAILED: another entity's case shown (${digits.join(',')})`;
  return 'ok';
}

const results = [];
const browser = await chromium.launch({ channel: 'msedge', headless: true });
try {
  for (const user of users) {
    const context = await browser.newContext();
    const page = await context.newPage();
    const http = [];
    const consoleErrors = [];
    page.on('response', (r) => {
      const u = r.url();
      if (u.includes('/api/v1/me') || u.includes('/protocol/openid-connect/token')) {
        http.push(`${r.request().method()} ${redact(u.replace(/^https?:\/\/[^/]+/, '').split('?')[0])} -> ${r.status()}`);
      }
    });
    page.on('console', (m) => { if (m.type() === 'error' && m.text().includes('LexaVault')) consoleErrors.push(redact(m.text().split('\n')[0]).slice(0, 220)); });
    page.on('pageerror', (e) => consoleErrors.push(`pageerror: ${redact(e.message).slice(0, 200)}`));

    let outcome = 'UNKNOWN';
    let screen = '';
    try {
      await page.goto(baseUrl, { waitUntil: 'networkidle' });
      await page.getByRole('button', { name: /sign in/i }).first().click();
      await page.waitForSelector('#username', { timeout: 20000 });
      await page.fill('#username', user);
      await page.fill('#password', passwords[user]);
      await page.click('#kc-login');
      await page.waitForURL((u) => u.toString().startsWith(baseUrl), { timeout: 20000 });
      await page.waitForTimeout(6000);
      let text = (await page.locator('body').innerText()).replace(/\s+/g, ' ');
      let sawChooser = false;
      if (text.includes('Choose an entity') && expected[user]?.choose) {
        sawChooser = true;
        await page.getByRole('button', { name: expected[user].choose }).click();
        await page.waitForTimeout(6000);
        text = (await page.locator('body').innerText()).replace(/\s+/g, ' ');
      }
      outcome = classify(text, sawChooser, (await accountButton(page).count()) > 0);
      for (const target of (outcome.startsWith('WORKSPACE') ? expected[user]?.switchTo ?? [] : [])) {
        outcome += `; switch to ${target} ${await switchEntity(page, target)}`;
      }
      if (outcome.startsWith('WORKSPACE') && !expected[user]?.switchTo) {
        outcome += (await hasSwitchControl(page)) ? '; switch control shown' : '; no switch control';
      }
      screen = redact(text).slice(0, 160);
    } catch (e) {
      outcome = `ERROR ${redact(e.message).split('\n')[0].slice(0, 120)}`;
    }
    const want = expected[user]?.outcome ?? '?';
    const pass = outcome === want;
    results.push({ user, mode, expected: want, outcome, result: pass ? 'PASS' : 'FAIL', http, consoleErrors, screen });
    console.log(`[${pass ? 'PASS' : 'FAIL'}] ${mode} ${user}: ${outcome} (expected: ${want})`);
    for (const h of http) console.log(`        http    ${h}`);
    for (const c of consoleErrors) console.log(`        console ${c}`);
    await context.close();
  }
} finally {
  await browser.close();
}

const stamp = new Date().toISOString().replace(/[-:]/g, '').replace(/\..*/, '').replace('T', '-');
fs.mkdirSync(`${runRoot}/results`, { recursive: true });
const file = `${runRoot}/results/browser-${mode}-${stamp}.json`;
fs.writeFileSync(file, JSON.stringify({ mode, baseUrl, results }, null, 2));
const failed = results.filter((r) => r.result !== 'PASS').length;
console.log(`[phase1] browser ${mode}: ${results.length - failed} passed, ${failed} failed. Results: ${file}`);
process.exitCode = failed > 0 ? 1 : 0;

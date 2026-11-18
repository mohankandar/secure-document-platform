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

// What each user should end up seeing. carol is asked to choose Entity A on the chooser.
const expected = {
  'carol.ab': { choose: 'Phase1 Entity A', outcome: 'WORKSPACE Phase1 Entity A (after the chooser)' },
  'alice.a': { outcome: 'WORKSPACE Phase1 Entity A' },
  'bob.b': { outcome: 'WORKSPACE Phase1 Entity B' },
  'dave.none': { outcome: 'REFUSED' },
};

const redact = (s) => String(s)
  .replace(/eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*/g, '<jwt>')
  .replace(/(code|state|session_state|code_verifier|refresh_token|access_token|id_token)=[^&\s"]+/g, '$1=<x>');

function classify(text, sawChooser) {
  const entity = /Phase1 Entity [AB]/.exec(text)?.[0];
  if (text.includes('Select organization')) return 'CHOOSER';
  if (text.includes('Sign out') && entity) return `WORKSPACE ${entity}${sawChooser ? ' (after the chooser)' : ''}`;
  if (text.includes('Sign-in could not be completed') || text.includes('does not have access')) return 'REFUSED';
  return 'UNKNOWN';
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
      if (text.includes('Select organization') && expected[user]?.choose) {
        sawChooser = true;
        await page.getByRole('button', { name: expected[user].choose }).click();
        await page.waitForTimeout(6000);
        text = (await page.locator('body').innerText()).replace(/\s+/g, ' ');
      }
      outcome = classify(text, sawChooser);
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

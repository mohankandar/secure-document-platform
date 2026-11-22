import { test, expect, type Page, type Request } from '@playwright/test';
import { ENTITY, EXPORT_CASES, go, mockApi, mockIdentityProvider, signInAs } from '../e2e-a11y/harness';
import { ARCHIVE, TITLE_CO, VAULT, chipProblems, exportApi, exportCases, panelLineProblems, retentionSettings } from './exportFixtures';
import { capabilitiesFor } from './policyCapabilities';

/**
 * Task 0029: the export screens on the real app (Vite dev server, OIDC mode), identity provider and API mocked by the
 * accessibility harness with its export answers (MockOptions.exports). One test per approved board and rule.
 */

const caseOf = (loan: string) => EXPORT_CASES.find((c) => c.businessIdentifiers[0].value === loan)!;

function recordWrites(page: Page) {
  const writes: Array<{ method: string; path: string; body: unknown }> = [];
  page.on('request', (r: Request) => {
    if (!r.url().includes('/api/v1/') || r.method() === 'GET' || r.method() === 'OPTIONS') return;
    writes.push({ method: r.method(), path: new URL(r.url()).pathname.replace(/^\/api\/v1/, ''), body: r.postDataJSON?.() ?? null });
  });
  return writes;
}

async function answer(page: Page, method: string, path: string | RegExp, status: number, body: unknown) {
  await page.route((url) => (typeof path === 'string' ? url.pathname === `/api/v1${path}` : path.test(url.pathname)), (route) => (
    route.request().method() === method
      ? route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) })
      : route.fallback()));
}

test.describe('Waiting for you (Processor and Supervisor)', () => {
  test('the nav item shows the count; one group per destination; rows have no buttons and open the case', async ({ page }) => {
    await signInAs(page, 'processor', '/', { exports: true });
    const nav = page.getByRole('navigation', { name: 'Workspace' });
    const item = nav.getByRole('link', { name: /^Waiting for you/ });
    await expect(item).toBeVisible();
    await expect(item.locator('.cf-nav-count')).toHaveText('5');
    // Right after Review.
    await expect(nav.locator('a.cf-nav-link').nth(3)).toHaveText(/^Waiting for you/);

    await item.click();
    await expect(page.getByRole('heading', { level: 1, name: 'Waiting for you' })).toBeVisible();
    await expect(page.getByText('A destination refused these cases. Once the cause is fixed with the destination’s team, send them again. Open a case to send just that one.')).toBeVisible();

    const groups = page.getByTestId('waiting-group');
    await expect(groups).toHaveCount(2);
    const vault = groups.nth(0);
    await expect(vault.getByRole('heading', { level: 2 })).toHaveText('Document vault · full case · 3 cases');
    await expect(vault).toContainText('Most often: Destination refused the sensitivity level · LV-DLV-003');
    await expect(vault.getByRole('button')).toHaveText(['Resend all 3']);
    const title = groups.nth(1);
    await expect(title.getByRole('heading', { level: 2 })).toHaveText('Title company · split by barcode · 2 documents');
    await expect(title.getByRole('button')).toHaveText(['Resend all 2']);
    await expect(title.getByTestId('waiting-row').first()).toContainText('LN-7002 · doc 3');
    await expect(title.getByTestId('waiting-row').nth(1)).toContainText('LN-7002 · doc 5');
    await expect(page.locator('#cf-main input[type="checkbox"]')).toHaveCount(0);
    await expect(page.getByTestId('waiting-row').locator('button')).toHaveCount(0);

    const row = vault.getByTestId('waiting-row').first();
    await expect(row).toContainText('LN-7004');
    await expect(row).toContainText('Destination refused the sensitivity level · LV-DLV-003');
    await expect(row).toHaveAttribute('href', `/case/${caseOf('LN-7004').packageId}`);
    await expect(page.getByText('Each case is sent once more. If the destination refuses it again, it goes to IT with code LV-DLV-015.', { exact: false })).toBeVisible();

    await row.click();
    await expect(page).toHaveURL(new RegExp(`/case/${caseOf('LN-7004').packageId}$`));
    await expect(page.getByRole('complementary', { name: 'Case panel' })).toContainText('Document vault refused this case');
  });

  test('Resend all asks first; "Not now" sends nothing; confirming queues them and says so', async ({ page }) => {
    const writes = recordWrites(page);
    await signInAs(page, 'supervisor', '/waiting', { exports: true });
    await answer(page, 'POST', '/export-jobs/resend-all', 200, { destinationId: VAULT, queued: 3, jobIds: ['j-7004', 'j-7011', 'j-6987'] });

    await page.getByRole('button', { name: 'Resend all 3' }).click();
    const dialog = page.getByRole('dialog', { name: 'Resend 3 cases to Document vault?' });
    await expect(dialog).toBeVisible();
    await expect(dialog).toContainText('They go back in the export queue and are sent one by one, in the order they were refused. You can leave this page; it carries on.');
    await expect(dialog.getByRole('list', { name: 'Why they were refused' }).getByRole('listitem'))
      .toHaveText(['2 refused for the sensitivity level · LV-DLV-003', '1 refused for the file · LV-DLV-004']);
    await expect(dialog).toContainText('Has the destination’s team fixed the cause? If not, they will be refused again. Each case is sent once more; a case refused again goes to IT.');

    await dialog.getByRole('button', { name: 'Not now' }).click();
    await expect(dialog).toBeHidden();
    expect(writes).toEqual([]);

    await page.getByRole('button', { name: 'Resend all 3' }).click();
    await page.getByRole('dialog').getByRole('button', { name: 'Resend 3 cases' }).click();
    await expect(page.locator('.cf-toast')).toHaveText('3 cases queued for Document vault. They’ll show as “Exporting · ready” until sent.');
    await expect(page.getByRole('dialog')).toBeHidden();
    expect(writes).toEqual([{ method: 'POST', path: '/export-jobs/resend-all', body: { destinationId: VAULT } }]);
  });

  test('the case panel: waiting for you, one "Resend this case", the destination’s answer under Technical details', async ({ page }) => {
    const writes = recordWrites(page);
    const pkg = caseOf('LN-7004');
    // Task 0035: a fixed time, so "Refused today" holds at any hour (the fixture's time is relative to now, and
    // between 00:00 and about 00:55 it fell on the day before). The page's clock and the refusal are both fixed.
    const refusedAt = new Date(2026, 10, 20, 14, 5);
    await page.clock.setFixedTime(new Date(2026, 10, 20, 15, 0));
    const fixed = exportApi(exportCases('c9c9c9c9')).caseExport(pkg.packageId);
    fixed.jobs = fixed.jobs.map((j) => (j.jobId === 'j-7004' ? { ...j, since: refusedAt.toISOString(), firstFailedAt: refusedAt.toISOString() } : j));
    await signInAs(page, 'processor', '/', { exports: true });
    await answer(page, 'GET', `/packages/${pkg.packageId}/export-jobs`, 200, fixed);
    await answer(page, 'POST', '/export-jobs/resend', 200, [{ jobId: 'j-7004', status: 'DONE', lvCode: null, saved: true }]);
    await go(page, `/case/${pkg.packageId}`);

    const panel = page.getByRole('complementary', { name: 'Case panel' });
    const box = panel.getByRole('region', { name: 'Next step' });
    await expect(box.locator('.cf-eyebrow')).toHaveText('Exporting · waiting for you');
    await expect(box).toContainText('Document vault refused this case');
    await expect(box.getByTestId('export-reason')).toHaveText('Destination refused the sensitivity level · LV-DLV-003');
    await expect(box).toContainText(/Refused today at 2:05\sPM\. Once the destination’s team has fixed it, send it again\. It gets one more attempt\./);
    await expect(box.getByRole('button')).toHaveText(['Resend this case']);
    await expect(panel.getByRole('list', { name: 'Stages' }).getByRole('listitem').last()).toHaveText('Exporting');

    await panel.getByText('Technical details').first().click();
    await expect(panel.getByTestId('export-technical')).toContainText('DELIVERY_SENSITIVITY_REFUSED');
    // Task 0030: the destination's own code, next to LexaVault's reason ("Destination code" | "SDV-SEN-006").
    await expect(panel.getByTestId('export-destination-code').locator('dt')).toHaveText('Destination code');
    await expect(panel.getByTestId('export-destination-code').locator('dd')).toHaveText('SDV-SEN-006');
    await expect(panel.getByTestId('export-technical')).not.toContainText('destination=');

    await box.getByRole('button', { name: 'Resend this case' }).click();
    await expect(page.locator('.cf-toast')).toHaveText('Sent again.');
    expect(writes).toEqual([{ method: 'POST', path: '/export-jobs/resend', body: { jobIds: ['j-7004'] } }]);
  });

  test('a resend refused again says it went to IT with LV-DLV-015', async ({ page }) => {
    await signInAs(page, 'processor', `/case/${caseOf('LN-7004').packageId}`, { exports: true });
    await answer(page, 'POST', '/export-jobs/resend', 200, [{ jobId: 'j-7004', status: 'NEEDS_IT', lvCode: 'LV-DLV-015', saved: true }]);
    await page.getByRole('button', { name: 'Resend this case' }).click();
    await expect(page.getByRole('alert').filter({ hasText: 'LV-DLV-015' })).toHaveText('The destination refused it again, so it went to IT · LV-DLV-015');
  });

  test('hidden without EXPORT_RESEND: no nav item, no Resend button, /waiting goes home', async ({ page }) => {
    await mockIdentityProvider(page);
    await mockApi(page, 'processor', { exports: true });
    // The same person without EXPORT_RESEND (registered after the harness, so it wins).
    await page.route('**/api/v1/me', (route) => route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({
      username: 'processor.user@example.test', roles: ['DOCUMENT_PROCESSOR'], tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC',
      capabilities: capabilitiesFor('DOCUMENT_PROCESSOR').filter((c) => c !== 'EXPORT_RESEND'),
    }) }));
    await page.goto('/');
    await page.getByRole('button', { name: /sign in/i }).first().click();
    await expect(page.getByRole('heading', { level: 1, name: 'Cases' })).toBeVisible();
    await expect(page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: /Waiting for you/ })).toHaveCount(0);
    await go(page, `/case/${caseOf('LN-7004').packageId}`);
    const box = page.getByRole('complementary', { name: 'Case panel' }).getByRole('region', { name: 'Next step' });
    await expect(box).toContainText('Document vault refused this case');
    await expect(box.getByRole('button')).toHaveCount(0);
    await go(page, '/waiting');
    await expect(page.getByRole('heading', { level: 1, name: 'Cases' })).toBeVisible();
  });

  test('hidden when nothing waits (inline mode answers) and for the IT Admin', async ({ page }) => {
    await signInAs(page, 'processor', '/');
    await expect(page.getByRole('heading', { level: 1, name: 'Cases' })).toBeVisible();
    await page.waitForTimeout(500);
    await expect(page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: /Waiting for you/ })).toHaveCount(0);
  });

  test('the IT Admin has no Waiting for you', async ({ page }) => {
    await signInAs(page, 'it', '/support/incidents', { exports: true });
    await expect(page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: /Waiting for you/ })).toHaveCount(0);
  });
});

test.describe('The "Exporting" status', () => {
  test('Cases shows Exporting · <detail> or Exported instead of the review status', async ({ page }) => {
    await signInAs(page, 'processor', '/', { exports: true });
    const chip = (loan: string) => page.locator(`#cf-row-${caseOf(loan).packageId}`).locator('.cf-case-stage');
    await expect(chip('LN-7020')).toHaveText('Exporting · ready');
    await expect(chip('LN-7015')).toHaveText('Exporting · destination paused');
    await expect(chip('LN-7004')).toHaveText('Exporting · waiting for you');
    await expect(chip('LN-6990')).toHaveText('Exporting · needs IT');
    await expect(chip('LN-6985')).toHaveText('Exported');
    // NONE: the status as today.
    await expect(page.locator(`#cf-row-c9c9c9c9-0000-4000-8000-000000000001 .cf-case-stage`)).toHaveText('Review');
    // A reviewed case waiting to go out no longer looks like it needs a reviewer; a refusal needs the person who can resend.
    await expect(page.locator(`#cf-row-${caseOf('LN-7004').packageId}`)).toHaveAttribute('data-you', 'true');
    await expect(page.locator(`#cf-row-${caseOf('LN-7004').packageId} .cf-case-act button`)).toHaveCount(0);
  });

  for (const [loan, eyebrow, heading] of [
    ['LN-7020', 'Exporting · ready', 'Waiting to be sent'],
    ['LN-7015', 'Exporting · destination paused', 'Archive service is paused'],
    ['LN-7004', 'Exporting · waiting for you', 'Document vault refused this case'],
    ['LN-6990', 'Exporting · needs IT', 'Waiting for IT'],
  ] as const) {
    test(`the case panel for ${eyebrow}`, async ({ page }) => {
      await signInAs(page, 'processor', `/case/${caseOf(loan).packageId}`, { exports: true });
      const panel = page.getByRole('complementary', { name: 'Case panel' });
      const box = panel.getByRole('region', { name: 'Next step' });
      await expect(box.locator('.cf-eyebrow')).toHaveText(eyebrow);
      await expect(box.locator('.cf-next-heading')).toHaveText(heading);
      const steps = panel.getByRole('list', { name: 'Stages' }).getByRole('listitem');
      await expect(steps).toHaveText(['New', 'Redacting', 'Review', 'Approval', 'Exporting']);
      await expect(steps.last()).toHaveAttribute('aria-current', 'step');
    });
  }

  test('an exported case reads Exported at the end of the stage strip and in the heading (task 0030: one word)', async ({ page }) => {
    await signInAs(page, 'processor', `/case/${caseOf('LN-6985').packageId}`, { exports: true });
    const panel = page.getByRole('complementary', { name: 'Case panel' });
    const steps = panel.getByRole('list', { name: 'Stages' }).getByRole('listitem');
    await expect(steps.last()).toHaveText('Exported');
    await expect(panel).not.toContainText('Delivered');
    // The Cases list names finished cases the same way: the chips, the stage tiles and the line under the title.
    await expect(page.locator('.cf-lede')).toContainText(/\d+ exported$/);
    await expect(page.locator('.cf-desk-main')).not.toContainText('Delivered');
  });

  test('a destination code that is not safe is not shown; the reason still is (task 0030)', async ({ page }) => {
    await signInAs(page, 'processor', `/case/${caseOf('LN-7011').packageId}`, { exports: true });
    const panel = page.getByRole('complementary', { name: 'Case panel' });
    await panel.getByText('Technical details').first().click();
    await expect(panel.getByTestId('export-technical')).toContainText('DELIVERY_SENSITIVITY_REFUSED');
    await expect(panel.getByTestId('export-destination-code')).toHaveCount(0);
    await expect(panel.getByTestId('export-technical')).not.toContainText('SEN 006');
  });

  // Task 0030: every chip on one line, whole and inside its column, at 1440 and 1024, with and without the case panel.
  for (const width of [1440, 1024]) {
    for (const withPanel of [false, true]) {
      test(`status chips stay on one line at ${width} px${withPanel ? ' with the case panel open' : ''}`, async ({ page }) => {
        await page.setViewportSize({ width, height: width > 1024 ? 900 : 768 });
        await signInAs(page, 'processor', withPanel ? `/case/${caseOf('LN-7015').packageId}` : '/', { exports: true });
        await expect(page.locator(`#cf-row-${caseOf('LN-7015').packageId} .cf-case-stage`)).toHaveText('Exporting · destination paused');
        for (const loan of ['LN-7020', 'LN-7015', 'LN-7004', 'LN-6990', 'LN-6985']) {
          await expect(page.locator(`#cf-row-${caseOf(loan).packageId} .cf-case-stage .cf-stage`)).toBeVisible();
        }
        expect(await chipProblems(page)).toEqual([]);
        if (withPanel) {
          await expect(page.getByRole('complementary', { name: 'Case panel' }).locator('.cf-export-step .cf-eyebrow')).toHaveText('Exporting · destination paused');
          expect(await panelLineProblems(page)).toEqual([]);
        }
      });
    }
  }
});

test.describe('Support (IT Admin): export', () => {
  test('the badge counts groups; the alert; grouped cards with Retry all; the sign-in card; destination states', async ({ page }) => {
    const writes = recordWrites(page);
    await signInAs(page, 'it', '/support/incidents', { exports: true });
    await answer(page, 'POST', '/support/export-jobs/retry-group', 200, [{ jobId: 'j-6990', status: 'READY' }]);
    await answer(page, 'POST', `/support/export-destinations/${TITLE_CO}/working`, 200, { destinationId: TITLE_CO, state: 'WORKING' });

    const support = page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: /^Support/ });
    await expect(support.locator('.cf-nav-count')).toHaveText('2');
    await page.getByRole('navigation', { name: 'Support' }).getByRole('link', { name: /^Export/ }).click();
    await expect(page.getByRole('heading', { level: 1, name: 'Export' })).toBeVisible();

    const alert = page.getByRole('alert').filter({ hasText: 'LexaVault can’t reach its destinations' });
    await expect(alert).toContainText('The problem is likely LexaVault’s own network, not the destinations.');

    const cards = page.getByTestId('it-group');
    await expect(cards).toHaveCount(2);
    await expect(cards.nth(0)).toContainText(/1 case failing to Document vault since /);
    await expect(cards.nth(0)).toContainText("Destination's answer not understood · LV-DLV-007");
    await expect(cards.nth(0)).toContainText('The destination answers its health check, so it’s these cases. Each was retried over 24 hours and rebuilt once.');
    await expect(cards.nth(0)).toContainText('Cases LN-6990.');
    // Task 0030: the destination's own code under Technical details.
    await cards.nth(0).getByText('Technical details').click();
    await expect(cards.nth(0).getByTestId('it-destination-codes')).toHaveText('Destination code SDV-SUB-017');
    await expect(cards.nth(1).getByTestId('it-destination-codes')).toHaveCount(0);
    await expect(cards.nth(1)).toContainText('Title company refused LexaVault’s sign-in');
    await expect(cards.nth(1)).toContainText('Exports to it are on hold: 12 waiting, nothing lost. Fix the credential in Admin › Integrations, then set it back to working.');

    await cards.nth(0).getByRole('button', { name: 'Retry all 1' }).click();
    await expect(page.locator('.cf-toast').last()).toHaveText('1 case queued again for Document vault.');
    await cards.nth(1).getByRole('button', { name: 'Set back to working' }).click();
    await expect(page.locator('.cf-toast').last()).toHaveText('Title company is set back to working.');
    expect(writes.slice(0, 2)).toEqual([
      { method: 'POST', path: '/support/export-jobs/retry-group', body: { destinationId: VAULT, code: 'LV-DLV-007' } },
      { method: 'POST', path: `/support/export-destinations/${TITLE_CO}/working`, body: null },
    ]);

    const rows = page.getByTestId('export-destination');
    await expect(rows.nth(0)).toContainText('Document vault · full case');
    await expect(rows.nth(0)).toContainText('Working');
    await expect(rows.nth(0)).toContainText('3 ready');
    await expect(rows.nth(1)).toContainText(/Paused since .+Health check failing · next check /);
    await expect(rows.nth(1)).toContainText('27 waiting');
    await expect(rows.nth(2)).toContainText('Needs IT');
    await expect(rows.nth(2)).toContainText('Sign-in to the destination failed · LV-DLV-005');
    await expect(rows.getByRole('button')).toHaveText(['Check now']);
  });

  test('Check now runs the destination’s check and says what it found', async ({ page }) => {
    const writes = recordWrites(page);
    await signInAs(page, 'it', '/support/export', { exports: true });
    await answer(page, 'POST', `/support/export-destinations/${ARCHIVE}/check`, 200, { destinationId: ARCHIVE, passed: true, resumed: true, state: 'WORKING' });
    await page.getByRole('button', { name: 'Check now: Archive service' }).click();
    await expect(page.locator('.cf-toast')).toHaveText('Archive service answers its health check again. Its waiting exports go out in order.');
    expect(writes).toEqual([{ method: 'POST', path: `/support/export-destinations/${ARCHIVE}/check`, body: null }]);
  });

  test('Platform Operations can read the export page but has no action buttons', async ({ page }) => {
    await signInAs(page, 'ops', '/support/export', { exports: true });
    await expect(page.getByRole('heading', { level: 1, name: 'Export' })).toBeVisible();
    await expect(page.getByTestId('it-group').first()).toBeVisible();
    await expect(page.locator('#cf-main').getByRole('button', { name: /Retry all|Set back to working|Check now/ })).toHaveCount(0);
  });
});

test.describe('Business Admin: retention per destination', () => {
  test('each destination with its hours, the default as "Same as …"; saving sends the hours', async ({ page }) => {
    const writes = recordWrites(page);
    await signInAs(page, 'business', '/admin/policy');
    await answer(page, 'PUT', '/business-admin/export-retention', 200, retentionSettings(null, 48));
    const card = page.getByRole('region', { name: 'Keeping exported copies' });
    await expect(card).toBeVisible();
    const master = card.getByLabel('Main export (Document vault)');
    await expect(master).toHaveValue('');
    await expect(master).toHaveAttribute('placeholder', '24');
    await expect(card).toContainText('Same as redacted copies (24 hours). 1 to 72 hours.');
    await expect(card.getByLabel('Title company')).toHaveValue('12');
    await expect(card).toContainText('Same as the main export (24 hours). 1 to 72 hours.');

    await card.getByLabel('Title company').fill('48');
    await card.getByRole('button', { name: 'Save changes' }).click();
    await expect(page.locator('.cf-toast')).toHaveText('Keeping exported copies saved.');
    expect(writes).toEqual([{ method: 'PUT', path: '/business-admin/export-retention', body: {
      masterHours: null, splitDestinations: [{ destinationId: TITLE_CO, hours: 48 }, { destinationId: ARCHIVE, hours: null }],
    } }]);
  });

  test('out of range: the field says so and the API’s LV-CFG-001 message is shown', async ({ page }) => {
    await signInAs(page, 'business', '/admin/policy');
    await answer(page, 'PUT', '/business-admin/export-retention', 400,
      { errorCode: 'INVALID_ARGUMENT', code: 'LV-CFG-001', message: 'Setting not valid: export retention must be 1 to 72 hours' });
    const card = page.getByRole('region', { name: 'Keeping exported copies' });
    await card.getByLabel('Archive service').fill('80');
    await expect(card).toContainText('Enter a whole number of hours from 1 to 72, or leave it empty.');
    await card.getByRole('button', { name: 'Save changes' }).click();
    await expect(card.getByRole('alert')).toHaveText('Setting not valid: export retention must be 1 to 72 hours · LV-CFG-001');
  });
});

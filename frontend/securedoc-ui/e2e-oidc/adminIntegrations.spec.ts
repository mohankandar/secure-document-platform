import { test, expect, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * UI task U008: Admin › Integrations and the connection editor (UI spec findings 16, 24, 25), for the IT Administrator:
 * plain view names, one tag and one status line per connection, codes under Technical details, the watched folder
 * once, barcode rules as sentences, failed files by the case's main ID, and the three-step editor.
 *
 * Same harness as adminBusiness.spec.ts: the real app on the Vite dev server (OIDC mode), identity provider and API mocked.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c8c8c8c8-0000-4000-8000-00000000000d', name: 'IT Entity', slug: 'it-entity' };
const CAPS = capabilitiesFor('TENANT_IT_ADMIN');
const PACKAGE_ID = '6f1c2d3e-0000-4000-8000-000000000001';

const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,OPTIONS' };
const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
const fakeJwt = () => `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'it-user', exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;
const minsAgo = (m: number) => new Date(Date.now() - m * 60000).toISOString();

async function mockIdentityProvider(page: Page) {
  await page.route(`${ISSUER}/**`, async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    if (request.method() === 'OPTIONS') return route.fulfill({ status: 204, headers: cors });
    if (url.pathname.endsWith('/.well-known/openid-configuration')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        issuer: ISSUER, authorization_endpoint: `${ISSUER}/protocol/openid-connect/auth`,
        token_endpoint: `${ISSUER}/protocol/openid-connect/token`, end_session_endpoint: `${ISSUER}/protocol/openid-connect/logout`,
      }) });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/auth')) {
      const back = new URL(url.searchParams.get('redirect_uri') || '/');
      back.searchParams.set('code', 'it-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 302, headers: { Location: back.toString() } });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        access_token: fakeJwt(), id_token: fakeJwt(), refresh_token: 'it-refresh', token_type: 'Bearer', expires_in: 3600,
      }) });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

const connection = (over: Record<string, unknown>) => ({
  connectionId: 'c-x', name: 'X', description: null, purpose: 'INGESTION_SOURCE', direction: 'INBOUND', providerType: 'PUSH_API', authenticationType: 'NONE',
  clientPrincipal: null, endpoint: null, fileShareRoot: null, secretConfigured: false, timeoutSeconds: 30, retryMaxAttempts: 5, incompleteWindowHours: 24,
  maxDocumentsPerIntake: 50, maxPdfSizeBytes: 50 * 1048576, maxPdfPages: 500, pollingScheduleType: null, pollingIntervalMinutes: null, pollingLocalTime: null,
  pollingTimeZone: null, pollingBatchLimit: null, acknowledgementMode: 'NONE', enabled: true, configurationVersion: 3, healthStatus: 'HEALTHY',
  lastSuccessAt: null, lastFailureAt: null, lastFailureCategory: null, createdAt: minsAgo(9000), updatedAt: minsAgo(9000), ...over,
});

const CONNECTIONS = [
  connection({ connectionId: 'c-los', name: 'Loan origination system', clientPrincipal: 'los-service', lastSuccessAt: minsAgo(14) }),
  connection({ connectionId: 'c-poll', name: 'Servicing outbox', direction: 'OUTBOUND', providerType: 'HTTP_API', authenticationType: 'API_KEY', secretConfigured: true,
    endpoint: 'https://servicing.northwind.example/api/outbox', pollingScheduleType: 'INTERVAL', pollingIntervalMinutes: 15, pollingBatchLimit: 100,
    acknowledgementMode: 'SOURCE_PICKUP_ACK', healthStatus: 'DEGRADED', lastSuccessAt: minsAgo(60), lastFailureAt: minsAgo(5), lastFailureCategory: 'POLL_SOURCE_UNAVAILABLE' }),
  connection({ connectionId: 'c-folder', name: 'Watched folder', providerType: 'WATCHED_FOLDER' }),
  connection({ connectionId: 'c-sdv', name: 'Secure Document Vault', purpose: 'SDV_DELIVERY', direction: 'OUTBOUND', providerType: 'HTTP_API', authenticationType: 'OAUTH2_CLIENT_CREDENTIALS',
    secretConfigured: true, endpoint: 'https://sdv.northwind.example/api', acknowledgementMode: 'SDV_SUBMISSION_ACK', lastSuccessAt: minsAgo(30) }),
  connection({ connectionId: 'c-title', name: 'Title team folder', purpose: 'DERIVED_DOCUMENT_DELIVERY', direction: 'OUTBOUND', providerType: 'FILE_SHARE',
    fileShareRoot: '\\\\files01\\title\\inbound', acknowledgementMode: 'DURABLE_FILE_WRITE', enabled: false, healthStatus: 'DISABLED' }),
];

const OVERVIEW = {
  generatedAt: minsAgo(0), providers: [],
  watchedFolder: {
    enabled: true, schedulingEnabled: true, scanIntervalMs: 30000, rootPath: 'C:\\LexaVault\\inbound', triggerFileName: 'READY', incompleteVisibilityMinutes: 60,
    acceptedBehavior: 'MOVE', rejectedBehavior: 'MOVE', processingDirectory: 'C:\\LexaVault\\processing', coordinationEnabled: false,
    lastScanAt: minsAgo(1), lastScanOutcome: 'SUCCESS', lastScanResult: { discovered: 3, waitingForTrigger: 1, accepted: 2, rejected: 0, duplicates: 0 },
  },
  resilience: { maxAttempts: 3, initialBackoffMs: 1000, packageProcessingMaxAttempts: 3, aiSecondaryEnabled: false, aiSecondaryProvider: 'none', aiSecondaryModel: 'none' },
};

const ATTENTION = [{
  derivedDeliveryId: 'dd-1', packageId: PACKAGE_ID, primaryIdentifierLabel: 'Loan number', primaryIdentifierValue: 'LN204812', sourceDocumentId: 'doc-1', exportCycle: 1,
  destinationLabel: 'Title pages', markerType: 'BARCODE', markerValue: 'TITLE-001', pageNumbers: [3, 4], fileName: 'title-pages.pdf', status: 'ATTENTION_REQUIRED',
  attemptCount: 5, failureCategory: 'DELIVERY_IO', retryable: true,
}];

type Call = { method: string; path: string; search: string; body: Record<string, unknown> };

const pageErrors: string[] = [];
test.beforeEach(({ page }) => { pageErrors.length = 0; page.on('pageerror', (e) => pageErrors.push(e.message)); page.on('console', (m) => { if (m.type() === 'error') pageErrors.push(m.text()); }); });
// The Cases screen loads first and gets an empty mock answer; only errors from the Admin screens count here.
test.afterEach(() => { expect(pageErrors.filter((e) => !/Failed to load resource|GET \/packages returned/.test(e))).toEqual([]); });

async function openIntegrations(page: Page, calls: Call[], connections: readonly unknown[] = CONNECTIONS, folderSettings: unknown = { folderKey: 'installed', sensitivityLevel: 'STANDARD' }) {
  await mockIdentityProvider(page);
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (request.method() !== 'GET') calls.push({ method: request.method(), path: url.pathname, search: url.search, body: JSON.parse(request.postData() || '{}') });
    if (url.pathname === '/api/v1/me/tenants') return json([{ ...ENTITY, roles: ['TENANT_IT_ADMIN'] }]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'it.user@example.test', roles: ['TENANT_IT_ADMIN'], tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC', capabilities: CAPS });
    }
    if (url.pathname === '/api/v1/integrations' && request.method() === 'GET') return json(connections);
    if (url.pathname === '/api/v1/integrations' && request.method() === 'POST') return json({ ...connection({ connectionId: 'c-new' }), ...calls.at(-1)!.body });
    if (url.pathname === '/api/v1/platform-admin/overview') return json(OVERVIEW);
    // Task 0021: the watched folder's level (Standard until Tenant IT changes it).
    if (url.pathname === '/api/v1/platform-admin/watched-folder/settings') {
      return json(request.method() === 'PUT' ? { folderKey: 'installed', sensitivityLevel: (calls.at(-1)!.body as { sensitivityLevel: string }).sensitivityLevel } : folderSettings);
    }
    if (url.pathname === '/api/v1/derived-deliveries/attention') return json(ATTENTION);
    if (url.pathname === '/api/v1/derived-deliveries/dd-1/technical-retry') return json({ ...ATTENTION[0], status: 'DELIVERED' });
    if (url.pathname === '/api/v1/business-admin/routing-rules') {
      return json([{ ruleId: 'r1', destinationLabel: 'Title pages', markerType: 'BARCODE', markerValue: 'TITLE-001', scanRegion: 'TOP_RIGHT', destinationConnectionId: 'c-title', enabled: true }]);
    }
    if (url.pathname === '/api/v1/business-admin/routing-rules/destinations') return json([{ connectionId: 'c-title', name: 'Title team folder', providerType: 'FILE_SHARE', enabled: false, healthStatus: 'DISABLED' }]);
    if (url.pathname === '/api/v1/integrations/c-poll/polling') return json({ connectionId: 'c-poll', name: 'Servicing outbox', enabled: true, healthStatus: 'DEGRADED', nextScheduledAt: minsAgo(-10), recentRuns: [] });
    if (url.pathname === '/api/v1/integrations/test-draft') {
      const draft = calls.at(-1)!.body as { endpoint?: string };
      return json(draft.endpoint?.includes('169.254.169.254') ? { works: false, reasonCode: 'ADDRESS_NOT_ALLOWED' } : { works: true, reasonCode: null });
    }
    if (url.pathname === '/api/v1/integrations/c-sdv/sdv/test-connection') return json({ connectionId: 'c-sdv', healthStatus: 'HEALTHY', secretConfigured: true, configurationVersion: 3 });
    if (url.pathname.startsWith('/api/v1/branding')) return json({});
    return json({});
  });
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
  await page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Admin' }).click();
  await page.getByRole('navigation', { name: 'Administration areas' }).getByRole('link', { name: /Integrations/ }).click();
  await expect(page.getByRole('region', { name: 'Receiving documents' })).toBeVisible();
}

/** The card's text without Technical details and hidden options, where codes are allowed. */
const mainText = (card: ReturnType<Page['getByRole']>) =>
  card.evaluate((el) => { const c = el.cloneNode(true) as HTMLElement; c.querySelectorAll('details, option').forEach((d) => d.remove()); return c.innerText; });

test('Receiving: plain view names, one tag and one status line each, the watched folder once', async ({ page }) => {
  await openIntegrations(page, []);
  const groups = page.getByRole('group', { name: 'Connection groups' });
  for (const [name, n] of [['Receiving', 3], ['Sending to SDV', 1], ['Sending by barcode', 1], ['All', 5]] as const) {
    await expect(groups.getByRole('button', { name: `${name} ${n}`, exact: true })).toBeVisible();
  }
  const card = page.getByRole('region', { name: 'Receiving documents' });
  await expect(card.getByText('Systems, folders and scanners that bring documents into LexaVault.')).toBeVisible();
  const los = card.getByRole('listitem').filter({ hasText: 'Loan origination system' });
  await expect(los.locator('.cf-ad-tag')).toHaveText('Sends to LexaVault');
  await expect(los.locator('.cf-ad-conn-status')).toHaveText('Working · last worked 14 min ago');
  const poll = card.getByRole('listitem').filter({ hasText: 'Servicing outbox' });
  await expect(poll.locator('.cf-ad-tag')).toHaveText('LexaVault collects every 15 min');
  await expect(poll.locator('.cf-ad-conn-status')).toHaveText("Having problems · last collected 1 h ago · last try failed: Source system can't be reached · LV-INT-040");
  await expect(poll.locator('.cf-ad-conn-endpoint')).toHaveText('https://servicing.northwind.example/api/outbox');
  await expect(poll.locator('.cf-ad-conn-endpoint')).toHaveCSS('font-family', /mono/i);
  for (const b of ['Collect now', 'Switch off', 'Edit']) await expect(poll.getByRole('button', { name: b })).toBeVisible();

  // The watched folder once, as a Receiving connection with Check now and the folder's own status.
  await expect(page.getByRole('listitem').filter({ hasText: 'Watched folder' })).toHaveCount(1);
  await expect(page.getByText(/Watched-folder intake operations/)).toHaveCount(0);
  const folder = card.getByRole('listitem').filter({ hasText: 'Watched folder' });
  await expect(folder.locator('.cf-ad-tag')).toHaveText('Folder');
  await expect(folder.locator('.cf-ad-conn-status')).toHaveText('Working · checked 1 min ago · 2 accepted, 1 waiting for its READY file');
  await expect(folder.locator('.cf-ad-conn-endpoint')).toHaveText('C:\\LexaVault\\inbound');
  await expect(folder.getByRole('button', { name: 'Check now' })).toBeEnabled();

  // Codes only under Technical details, closed by default.
  const outside = await mainText(card);
  for (const code of [/API_KEY/, /POLL_SOURCE_UNAVAILABLE/, /HTTP_API|PUSH_API/, /\bv3\b/, /DEGRADED|HEALTHY/, /Intake sources|Master delivery|Derived/]) expect(outside).not.toMatch(code);
  const details = poll.locator('details');
  await expect(details).not.toHaveAttribute('open', '');
  await details.locator('summary').click();
  await expect(details).toContainText('API key · key stored');
  await expect(details).toContainText('POLL_SOURCE_UNAVAILABLE');
});

test('U019: the installed watched folder (no folder connection) has Check now only, and says why', async ({ page }) => {
  await openIntegrations(page, [], CONNECTIONS.filter((c) => c.connectionId !== 'c-folder'));
  const card = page.getByRole('region', { name: 'Receiving documents' });
  const folder = card.getByRole('listitem').filter({ hasText: 'Watched folder' });
  await expect(folder).toHaveCount(1);
  await expect(folder.getByRole('button', { name: 'Check now' })).toBeEnabled();
  await expect(folder.locator('.cf-ad-conn-note')).toHaveText('Set up by the install configuration');
  for (const b of ['Switch off', 'Switch on', 'Edit']) await expect(folder.getByRole('button', { name: b })).toHaveCount(0);
  // A connection row keeps its own actions.
  const los = card.getByRole('listitem').filter({ hasText: 'Loan origination system' });
  for (const b of ['Switch off', 'Edit']) await expect(los.getByRole('button', { name: b })).toBeVisible();
});

test('0021: Tenant IT sets the level every file from the watched folder gets (default Standard)', async ({ page }) => {
  const calls: Call[] = [];
  await openIntegrations(page, calls, CONNECTIONS.filter((c) => c.connectionId !== 'c-folder'));
  const folder = page.getByRole('region', { name: 'Receiving documents' }).getByRole('listitem').filter({ hasText: 'Watched folder' });
  const choice = folder.getByLabel('Sensitivity of files from this folder');
  await expect(choice).toHaveValue('STANDARD');
  await expect(folder).toContainText('Every file picked up from this folder gets this level.');
  await expect(folder.getByRole('button', { name: 'Save' })).toHaveCount(0);
  await choice.selectOption({ label: 'Highly Confidential' });
  await folder.getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => calls.find((c) => c.method === 'PUT' && c.path === '/api/v1/platform-admin/watched-folder/settings')?.body)
    .toEqual({ sensitivityLevel: 'HIGHLY_CONFIDENTIAL' });
  await expect(page.getByText('Files from this folder will be Highly Confidential.')).toBeVisible();
  await expect(folder.getByRole('button', { name: 'Save' })).toHaveCount(0);
  // The other connections get no level choice: it belongs to the folder only.
  const los = page.getByRole('region', { name: 'Receiving documents' }).getByRole('listitem').filter({ hasText: 'Loan origination system' });
  await expect(los.getByLabel('Sensitivity of files from this folder')).toHaveCount(0);
});

test('0021: the folder shown on its connection has the level choice too', async ({ page }) => {
  await openIntegrations(page, []);
  const folderConnection = page.getByRole('region', { name: 'Receiving documents' }).getByTestId('folder-sensitivity');
  await expect(folderConnection).toHaveCount(1);
  await expect(folderConnection.getByLabel('Sensitivity of files from this folder')).toHaveValue('STANDARD');
});

// Task 0023 (A4): Standard by default whether or not anything is saved. No setting row saved (no level, null, empty)
// or an unknown value: the choice shows Standard and nothing looks unsaved.
for (const [what, settings] of [
  ['no level in the answer', { folderKey: 'installed' }],
  ['a null level', { folderKey: 'installed', sensitivityLevel: null }],
  ['an empty level', { folderKey: 'installed', sensitivityLevel: '' }],
  ['an unknown level', { folderKey: 'installed', sensitivityLevel: 'TOP_SECRET' }],
] as const) {
  test(`0023 A4: the watched folder with ${what} shows Standard preselected, with no Save`, async ({ page }) => {
    await openIntegrations(page, [], CONNECTIONS.filter((c) => c.connectionId !== 'c-folder'), settings);
    const folder = page.getByRole('region', { name: 'Receiving documents' }).getByRole('listitem').filter({ hasText: 'Watched folder' });
    const choice = folder.getByLabel('Sensitivity of files from this folder');
    await expect(choice).toHaveValue('STANDARD');
    await expect(choice.locator('option:checked')).toHaveText('Standard');
    await expect(folder.getByRole('button', { name: 'Save' })).toHaveCount(0);
  });
}

test('Sending by barcode: rules as a sentence, failed files by loan number, Retry', async ({ page }) => {
  const calls: Call[] = [];
  await openIntegrations(page, calls);
  await page.getByRole('group', { name: 'Connection groups' }).getByRole('button', { name: /^Sending by barcode/ }).click();
  const card = page.getByRole('region', { name: 'Sending by barcode' });
  const title = card.getByRole('listitem').filter({ hasText: 'Title team folder' }).first();
  await expect(title.locator('.cf-ad-tag')).toHaveText('File share');
  await expect(title.locator('.cf-ad-conn-status')).toHaveText('Switched off');
  await expect(title.getByRole('button', { name: 'Switch on' })).toBeVisible();
  await expect(card.getByRole('region', { name: 'Rules' })).toContainText('Title pages: barcode “TITLE-001”, top right of the page → Title team folder · destination switched off');
  const failed = card.getByRole('region', { name: "Files that couldn't be sent" });
  await expect(failed).toContainText("1 file couldn't be sent");
  await expect(failed.locator('.cf-ad-conn-status')).toHaveText("LN204812 · pages 3–4 → Title pages · Destination can't be reached · LV-DLV-001 · tried 5 times");
  expect(await mainText(failed)).not.toContain(PACKAGE_ID);
  // Task 0023 B4: Technical details give the document id, never a file name (even if an older server still sends one).
  await failed.locator('summary', { hasText: 'Technical details' }).click();
  await expect(failed.locator('dl')).toContainText('Documentdoc-1');
  await expect(failed).not.toContainText('title-pages.pdf');
  await expect(failed.locator('dt', { hasText: /^File$/ })).toHaveCount(0);
  await expect(card.getByRole('button', { name: 'Technical retry' })).toHaveCount(0);
  await failed.getByRole('button', { name: 'Retry' }).click();
  await expect.poll(() => calls.some((c) => c.path === '/api/v1/derived-deliveries/dd-1/technical-retry')).toBe(true);
});

test('Add a connection: three steps, plain labels, Advanced closed, and what is saved', async ({ page }) => {
  const calls: Call[] = [];
  await openIntegrations(page, calls);
  await page.getByRole('button', { name: 'Add a connection' }).click();
  const sheet = page.getByRole('dialog');
  await expect(sheet).toContainText('Step 1 of 3');
  await expect(sheet.getByRole('radio', { name: /^Receive documents/ })).toBeChecked();
  await expect(sheet.getByRole('radio', { name: /^Send documents/ })).toBeVisible();
  await expect(sheet.getByText('To SDV, or pages by barcode to another place.')).toBeVisible();
  await sheet.getByRole('button', { name: 'Next', exact: true }).click();

  await expect(sheet).toContainText('Step 2 of 3');
  await sheet.getByLabel('Name').fill('Servicing outbox 2');
  await sheet.getByLabel('How documents arrive').selectOption({ label: 'LexaVault collects them on a schedule' });
  await sheet.getByLabel('Address').fill('https://servicing.northwind.example/api/outbox');
  await sheet.getByLabel('How LexaVault proves who it is').selectOption({ label: 'API key' });
  await expect(sheet.getByLabel('Where the key is stored')).toBeVisible();
  await expect(sheet.getByText('A reference to your secrets store. The key itself is never shown.')).toBeVisible();
  await sheet.getByLabel('Collect every: unit').selectOption('minutes');
  await sheet.getByLabel('Collect every', { exact: true }).fill('15');
  const advanced = sheet.locator('details').filter({ hasText: 'Advanced (usually leave as is)' });
  await expect(advanced).not.toHaveAttribute('open', '');
  await expect(sheet.getByLabel('Wait for an answer')).toBeHidden();
  for (const code of [/Purpose/, /Transfer method/, /Acknowledgement/, /client principal/i]) expect(await sheet.innerText()).not.toMatch(code);

  await sheet.getByRole('button', { name: 'Next: test and save' }).click();
  await expect(sheet).toContainText('Step 3 of 3');
  await expect(sheet.getByText('The test uses the settings above. Nothing is saved until you choose Save, and you can save without testing.')).toBeVisible();
  await sheet.getByRole('button', { name: 'Test connection' }).click();
  await expect(sheet.getByText('The connection works.')).toBeVisible();
  const draftTest = calls.find((c) => c.path === '/api/v1/integrations/test-draft');
  expect(draftTest?.search).toBe('');
  expect(draftTest?.body).toMatchObject({ endpoint: 'https://servicing.northwind.example/api/outbox', pollingIntervalMinutes: 15 });
  expect(calls.some((c) => c.method === 'POST' && c.path === '/api/v1/integrations')).toBe(false);
  await sheet.getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => calls.find((c) => c.method === 'POST' && c.path === '/api/v1/integrations')?.body).toMatchObject({
    name: 'Servicing outbox 2', purpose: 'INGESTION_SOURCE', direction: 'OUTBOUND', providerType: 'HTTP_API', authenticationType: 'API_KEY',
    endpoint: 'https://servicing.northwind.example/api/outbox', pollingScheduleType: 'INTERVAL', pollingIntervalMinutes: 15, acknowledgementMode: 'SOURCE_PICKUP_ACK', enabled: false,
    // Task 0023 D3: a new connection's size limit is the backend's 50 MB, not 250 MB.
    maxPdfSizeBytes: 52428800,
  });
});

test('Edit a saved connection: Test connection tests the saved settings, and after a change tests the new ones before saving', async ({ page }) => {
  const calls: Call[] = [];
  await openIntegrations(page, calls);
  await page.getByRole('group', { name: 'Connection groups' }).getByRole('button', { name: /^Sending to SDV/ }).click();
  await page.getByRole('region', { name: 'Sending to SDV' }).getByRole('button', { name: 'Edit' }).click();
  const sheet = page.getByRole('dialog');
  await expect(sheet).toContainText('Step 2 of 3');
  await expect(sheet.getByLabel('Where documents go')).toHaveValue('SDV');
  await sheet.getByRole('button', { name: 'Next: test and save' }).click();
  await sheet.getByRole('button', { name: 'Test connection' }).click();
  await expect(sheet.getByText('The connection works.')).toBeVisible();
  expect(calls.some((c) => c.path === '/api/v1/integrations/c-sdv/sdv/test-connection')).toBe(true);

  await sheet.getByRole('button', { name: 'Back' }).click();
  await sheet.getByLabel('Address').fill('https://sdv-new.northwind.example/api');
  await sheet.getByRole('button', { name: 'Next: test and save' }).click();
  await expect(sheet.getByText('The test uses the settings above. Nothing is saved until you choose Save, and you can save without testing.')).toBeVisible();
  await sheet.getByRole('button', { name: 'Test connection' }).click();
  await expect(sheet.getByText('The connection works.')).toBeVisible();
  const draftTest = calls.find((c) => c.path === '/api/v1/integrations/test-draft');
  expect(draftTest?.search).toBe('?connectionId=c-sdv');
  expect(draftTest?.body).toMatchObject({ endpoint: 'https://sdv-new.northwind.example/api' });

  await sheet.getByRole('button', { name: 'Back' }).click();
  await sheet.getByLabel('Address').fill('http://169.254.169.254/latest');
  await sheet.getByRole('button', { name: 'Next: test and save' }).click();
  await sheet.getByRole('button', { name: 'Test connection' }).click();
  await expect(sheet.getByText("The connection didn't work: Connection settings not valid · LV-CFG-003. Cloud metadata addresses are always refused.")).toBeVisible();
  await expect(sheet.getByRole('button', { name: 'Save' })).toBeEnabled();
  expect(calls.some((c) => c.method === 'PUT')).toBe(false);
});

test('0039: a receiving connection stops at the case limits (50 files, 500 pages) and says why; a source stored higher shows and saves the cap', async ({ page }) => {
  const calls: Call[] = [];
  // Stored before the cap with higher values (the server now sends them capped; the screen caps them too).
  const stored = CONNECTIONS.map((c) => (c.connectionId === 'c-los' ? { ...c, maxDocumentsPerIntake: 1000, maxPdfPages: 2000 } : c));
  await openIntegrations(page, calls, stored);
  await page.route('**/api/v1/integrations/c-los', async (route) => {
    const request = route.request();
    if (request.method() !== 'PUT') return route.fallback();
    const body = JSON.parse(request.postData() || '{}');
    calls.push({ method: 'PUT', path: '/api/v1/integrations/c-los', search: '', body });
    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ...stored[0], ...body }) });
  });
  await page.setViewportSize({ width: 1440, height: 1100 });
  const los = page.getByRole('region', { name: 'Receiving documents' }).getByRole('listitem').filter({ hasText: 'Loan origination system' });
  await los.getByRole('button', { name: 'Edit' }).click();
  const sheet = page.getByRole('dialog');
  const advanced = sheet.locator('details').filter({ hasText: 'Advanced (usually leave as is)' });
  await advanced.locator('summary').click();

  const files = sheet.getByLabel('Files per case, at most');
  const pages = sheet.getByLabel('Pages per file, at most');
  await expect(files).toHaveValue('50');
  await expect(pages).toHaveValue('500');
  await expect(files).toHaveAttribute('max', '50');
  await expect(pages).toHaveAttribute('max', '500');
  await expect(sheet.getByText('A case holds up to 50 files.')).toBeVisible();
  await expect(sheet.getByText('A case takes PDFs of up to 500 pages.')).toBeVisible();
  await expect(advanced.locator('.cf-ad-hint')).toContainText('up to 50 files, 50 MB, 500 pages');

  // A value over the limit is marked at once (the server refuses it too, LV-CFG-001).
  await pages.fill('600');
  await expect(sheet.getByText('Enter a value from 1 to 500.')).toBeVisible();
  await pages.fill('500');
  if (process.env.P0039_PROOF) {
    await advanced.scrollIntoViewIfNeeded();
    await page.waitForTimeout(250);
    await page.screenshot({ path: '../../docs/work/0039-proof/integrations-capped-fields.png' });
  }

  await sheet.getByRole('button', { name: 'Next: test and save' }).click();
  await sheet.getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => calls.find((c) => c.method === 'PUT')?.body).toMatchObject({ maxDocumentsPerIntake: 50, maxPdfPages: 500 });
});

// Task 0022 (A4): proof picture, saved to ../../docs/work/compare/0022/ (not committed). Registered only with P0022_PROOF=1.
if (process.env.P0022_PROOF) {
  test('proof: Admin › Integrations, the watched-folder "Sensitivity of files from this folder" setting', async ({ page }) => {
    await page.setViewportSize({ width: 1440, height: 900 });
    await openIntegrations(page, [], CONNECTIONS.filter((c) => c.connectionId !== 'c-folder'));
    const folder = page.getByRole('region', { name: 'Receiving documents' }).getByRole('listitem').filter({ hasText: 'Watched folder' });
    await expect(folder.getByLabel('Sensitivity of files from this folder')).toHaveValue('STANDARD');
    await folder.scrollIntoViewIfNeeded();
    await page.waitForTimeout(250);
    await page.screenshot({ path: '../../docs/work/compare/0022/a4-integrations-watched-folder-level-light-1440.png' });
    await folder.screenshot({ path: '../../docs/work/compare/0022/a4-integrations-watched-folder-level-card-light-1440.png' });
  });
}

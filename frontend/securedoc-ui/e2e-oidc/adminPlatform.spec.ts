import { test, expect, type Page, type Route } from '@playwright/test';
import { capabilitiesFor } from './policyCapabilities';

/**
 * UI task U010: Admin › Platform operations (UI spec findings 26, 27, 28). Overview for the IT Administrator and
 * Platform Operations; Processing storage, Branding and Usage data for Platform Operations only. Licensing is left
 * as it was (owner's decision: it waits for Phase 5).
 *
 * Same harness as adminIntegrations.spec.ts: the real app on the Vite dev server (OIDC mode), identity provider and API mocked.
 */

const ISSUER = 'http://idp.test/realms/signin-race';
const ENTITY = { tenantId: 'c8c8c8c8-0000-4000-8000-00000000000e', name: 'Ops Entity', slug: 'ops-entity' };
const IT_CAPS = capabilitiesFor('TENANT_IT_ADMIN');
const OPS_CAPS = capabilitiesFor('PLATFORM_OPERATIONS_ADMIN');

const cors = { 'Access-Control-Allow-Origin': '*', 'Access-Control-Allow-Headers': '*', 'Access-Control-Allow-Methods': 'GET,POST,PUT,DELETE,OPTIONS' };
const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
const fakeJwt = () => `${encode({ alg: 'none', typ: 'JWT' })}.${encode({ sub: 'ops-user', exp: Math.floor(Date.now() / 1000) + 3600 })}.signature`;
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
      back.searchParams.set('code', 'ops-code');
      back.searchParams.set('state', url.searchParams.get('state') || '');
      return route.fulfill({ status: 302, headers: { Location: back.toString() } });
    }
    if (url.pathname.endsWith('/protocol/openid-connect/token')) {
      return route.fulfill({ status: 200, headers: cors, contentType: 'application/json', body: JSON.stringify({
        access_token: fakeJwt(), id_token: fakeJwt(), refresh_token: 'ops-refresh', token_type: 'Bearer', expires_in: 3600,
      }) });
    }
    return route.fulfill({ status: 404, headers: cors });
  });
}

const OVERVIEW = {
  generatedAt: minsAgo(1),
  providers: [
    { key: 'database', label: 'PostgreSQL', category: 'Database', provider: 'postgresql', status: 'HEALTHY', configured: true, detail: 'Connection valid' },
    { key: 'ai', label: 'Sensitive-content AI', category: 'AI', provider: 'ollama', status: 'CONFIGURED', configured: true, detail: 'Default model: qwen3.5:9b; inference pool: disabled; tenant profiles: 0' },
    { key: 'ocr', label: 'OCR', category: 'OCR', provider: 'tesseract', status: 'HEALTHY', configured: true, detail: 'Language: eng' },
    { key: 'storage', label: 'Processing storage', category: 'Storage', provider: 'shared_filesystem', status: 'HEALTHY', configured: true, detail: 'Configured and validated on every active node' },
    { key: 'delivery', label: 'Final delivery', category: 'Delivery', provider: 'document-store', status: 'DEGRADED', configured: true, detail: 'Tenant document store connection configured; secrets are protected' },
  ],
  watchedFolder: {
    enabled: true, schedulingEnabled: true, scanIntervalMs: 60000, rootPath: 'C:\\LexaVault\\inbound', triggerFileName: 'READY', incompleteVisibilityMinutes: 60,
    acceptedBehavior: 'MOVE', rejectedBehavior: 'MOVE', processingDirectory: 'C:\\LexaVault\\processing', coordinationEnabled: true,
    nodeId: 'node-a', scannerRole: 'LEADER', leaseOwner: 'node-a/123', fencingToken: 42, leaseExpiresAt: minsAgo(-1), takeoverCount: 2,
    lastScanAt: minsAgo(1), lastScanOutcome: 'SUCCESS', lastScanResult: { discovered: 3, waitingForTrigger: 1, accepted: 2, rejected: 0, duplicates: 0 },
  },
  resilience: { maxAttempts: 3, initialBackoffMs: 1000, packageProcessingMaxAttempts: 3, aiSecondaryEnabled: false, aiSecondaryProvider: 'none', aiSecondaryModel: '' },
  integrations: { deliveryType: 'document-store', documentStoreConfigured: true, identityMode: 'OIDC', productionIdentityConfigured: true },
  technicalExceptions: [],
};

const TENANTS = [{ tenantId: 't-nw', name: 'Northwind Commercial', slug: 'nwc', status: 'ACTIVE' }, { tenantId: 't-co', name: 'Contoso Lending', slug: 'col', status: 'ACTIVE' }];
const STORAGE = {
  ready: true,
  deploymentDefault: { id: 'as-1', providerId: 'p-ssd', scope: 'DEPLOYMENT_DEFAULT', activatedAt: minsAgo(9000) },
  tenantOverrides: [{ id: 'as-2', tenantId: 't-nw', providerId: 'p-nas', scope: 'TENANT_OVERRIDE', activatedAt: minsAgo(900) }],
  providers: [
    { id: 'p-ssd', name: 'Local SSD', providerType: 'SHARED_FILESYSTEM', rootPath: 'D:\\lexavault\\work', status: 'AVAILABLE', configurationVersion: 1 },
    { id: 'p-nas', name: 'Shared NAS', providerType: 'SHARED_FILESYSTEM', rootPath: '\\\\nas01\\lexavault', status: 'AVAILABLE', configurationVersion: 2 },
    { id: 'p-san', name: 'New SAN volume', providerType: 'SHARED_FILESYSTEM', rootPath: '\\\\san02\\lv', status: 'CANDIDATE', configurationVersion: 1 },
  ],
  nodeValidations: [
    { nodeId: 'node-a', processInstanceId: 'i1', providerId: 'p-ssd', status: 'HEALTHY', failureCode: null, checkedAt: minsAgo(2) },
    { nodeId: 'node-a', processInstanceId: 'i1', providerId: 'p-nas', status: 'HEALTHY', failureCode: null, checkedAt: minsAgo(2) },
    { nodeId: 'node-a', processInstanceId: 'i1', providerId: 'p-san', status: 'UNHEALTHY', failureCode: 'ROOT_NOT_WRITABLE', checkedAt: minsAgo(2) },
  ],
  tenants: TENANTS,
  activeNodeCount: 1,
};

const DEPLOYMENT_BRAND = { id: 'b-1', tenantId: null, applicationName: 'LexaVault', shortName: null, organizationName: 'Northwind Title & Escrow', pageTitle: null,
  secondaryColor: null, primaryLogoDataUrl: null, compactLogoDataUrl: null, faviconDataUrl: null, updatedBy: 'ops', updatedAt: minsAgo(500) };
const BRANDING = { deployment: DEPLOYMENT_BRAND, tenants: [{ tenantId: 't-nw', tenantName: 'Northwind Commercial', tenantSlug: 'nwc', customBranding: false }] };

const TELEMETRY = {
  mode: 'BASIC', enabled: true, installationIdConfigured: true, privacyContract: 'ALLOW_LIST_ONLY',
  eventAllowList: ['package.processing.attempt', 'package.processing.finished'],
  prohibitedData: ['document/PDF/image content', 'actor identities'],
};

type Call = { method: string; path: string; body: Record<string, unknown> };

const pageErrors: string[] = [];
test.beforeEach(({ page }) => { pageErrors.length = 0; page.on('pageerror', (e) => pageErrors.push(e.message)); page.on('console', (m) => { if (m.type() === 'error') pageErrors.push(m.text()); }); });
test.afterEach(() => { expect(pageErrors.filter((e) => !/Failed to load resource|GET \/packages returned/.test(e))).toEqual([]); });

async function openPlatform(page: Page, role: 'TENANT_IT_ADMIN' | 'PLATFORM_OPERATIONS_ADMIN', calls: Call[] = []) {
  await mockIdentityProvider(page);
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request();
    const url = new URL(request.url());
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (request.method() !== 'GET') calls.push({ method: request.method(), path: url.pathname, body: JSON.parse(request.postData() || '{}') });
    if (url.pathname === '/api/v1/me/tenants') return json([{ ...ENTITY, roles: [role] }]);
    if (url.pathname === '/api/v1/me') {
      return json({ username: 'ops.user@example.test', roles: [role], tenantName: ENTITY.name, tenantSlug: ENTITY.slug, authenticationMode: 'OIDC', capabilities: role === 'TENANT_IT_ADMIN' ? IT_CAPS : OPS_CAPS });
    }
    // The IT Administrator lands on Integrations first.
    if (url.pathname === '/api/v1/integrations' || url.pathname === '/api/v1/derived-deliveries/attention') return json([]);
    if (url.pathname === '/api/v1/platform-admin/overview') return json(OVERVIEW);
    if (url.pathname === '/api/v1/platform-admin/health-check') return json(OVERVIEW);
    if (url.pathname === '/api/v1/platform/processing-storage') return json(STORAGE);
    if (url.pathname.startsWith('/api/v1/platform/processing-storage/')) return json(STORAGE.providers[2]);
    if (url.pathname === '/api/v1/platform/branding') return json(BRANDING);
    if (url.pathname === '/api/v1/platform/branding/tenants/t-nw') return json({ tenantId: 't-nw', customBranding: false, configuration: null });
    if (url.pathname === '/api/v1/platform/branding/deployment') return json(DEPLOYMENT_BRAND);
    if (url.pathname === '/api/v1/platform/product-telemetry/status') return json(TELEMETRY);
    if (url.pathname.startsWith('/api/v1/branding')) return json({});
    return json({});
  });
  await page.goto('/');
  await page.getByRole('button', { name: /sign in/i }).first().click();
  await page.getByRole('navigation', { name: 'Workspace' }).getByRole('link', { name: 'Admin' }).click();
  await page.getByRole('navigation', { name: 'Administration areas' }).getByRole('link', { name: /Platform operations/ }).click();
  await expect(page.getByRole('region', { name: '1 service needs attention' })).toBeVisible();
}

/** Text without Technical details and hidden options, where codes are allowed. */
const mainText = (el: ReturnType<Page['getByRole']>) =>
  el.evaluate((e) => { const c = e.cloneNode(true) as HTMLElement; c.querySelectorAll('details, option').forEach((d) => d.remove()); return c.innerText; });

test('IT Administrator: services and the watched folder in plain words, no sub-sections, no technical details', async ({ page }) => {
  await openPlatform(page, 'TENANT_IT_ADMIN');
  await expect(page.getByRole('group', { name: 'Platform operations sections' })).toHaveCount(0);
  const services = page.getByRole('region', { name: '1 service needs attention' });
  for (const [name, status] of [['Database', 'Working'], ['AI model', 'Set up · not checked yet'], ['Text recognition', 'Working'], ['Processing storage', 'Working'], ['Delivery to the document store', 'Having problems']]) {
    await expect(services.getByRole('listitem').filter({ hasText: name }).locator('.cf-ad-status')).toHaveText(status);
  }
  await expect(services.getByRole('button', { name: 'Run health checks' })).toHaveCount(0);
  const folder = page.getByRole('region', { name: 'Watched folder' });
  await expect(folder.getByText('C:\\LexaVault\\inbound · checked every minute')).toBeVisible();
  await expect(folder.locator('.cf-ad-pill')).toHaveText('Working · checked 1 min ago');
  await expect(folder.getByText(/^2 accepted, 1 waiting for its READY file\. Scan now/)).toBeVisible();
  await expect(page.getByText('Technical details')).toHaveCount(0);
  const text = await mainText(page.locator('.cf-ad-content'));
  expect(text).not.toMatch(/Slow|fencing|lease|node-a|HEALTHY|DEGRADED|Multi-node|Resilience|dependenc/i);
});

test('Platform Operations: overview technical details, closed, hold the codes', async ({ page }) => {
  const calls: Call[] = [];
  await openPlatform(page, 'PLATFORM_OPERATIONS_ADMIN', calls);
  const sections = page.getByRole('group', { name: 'Platform operations sections' });
  for (const name of ['Overview', 'Processing storage', 'Licensing', 'Branding', 'Usage data']) await expect(sections.getByRole('button', { name, exact: true })).toBeVisible();
  const details = page.locator('details.cf-disclosure');
  await expect(details).toHaveCount(2);
  for (const d of await details.all()) expect(await d.getAttribute('open')).toBeNull();
  await page.getByRole('region', { name: 'Watched folder' }).getByText('Technical details').click();
  await expect(page.getByText('Fencing generation')).toBeVisible();
  await page.getByRole('button', { name: 'Run health checks' }).click();
  expect(calls.map((c) => c.path)).toContain('/api/v1/platform-admin/health-check');
});

test('Processing storage: one table, plain status, Stop using asks first', async ({ page }) => {
  const calls: Call[] = [];
  await openPlatform(page, 'PLATFORM_OPERATIONS_ADMIN', calls);
  await page.getByRole('group', { name: 'Platform operations sections' }).getByRole('button', { name: 'Processing storage' }).click();
  const card = page.getByRole('region', { name: 'Where LexaVault works on documents' });
  await expect(card.getByText('Ready · 1 server')).toBeVisible();
  const row = (name: string) => card.getByRole('row').filter({ hasText: name });
  await expect(row('Local SSD')).toContainText('Default for everyone else');
  await expect(row('Local SSD')).toContainText('Working');
  await expect(row('Shared NAS')).toContainText('Northwind Commercial only');
  await expect(row('New SAN volume')).toContainText("Not used yet");
  await expect(row('New SAN volume')).toContainText("Not working · can't write here");
  await expect(row('New SAN volume').getByRole('button', { name: 'Make default' })).toBeDisabled();
  await expect(row('Local SSD').getByRole('button', { name: 'Stop using…' })).toHaveCount(0);
  await row('New SAN volume').getByRole('button', { name: 'Stop using…' }).click();
  const dialog = page.getByRole('dialog');
  await expect(dialog).toContainText('Stop using New SAN volume?');
  await dialog.getByRole('button', { name: 'Stop using it' }).click();
  await expect.poll(() => calls.map((c) => c.path)).toContain('/api/v1/platform/processing-storage/providers/p-san/retire');
  await expect(page.getByRole('region', { name: 'Give one customer its own storage' }).getByText('Uses Shared NAS')).toBeVisible();
  expect(await mainText(page.locator('.cf-ad-content'))).not.toMatch(/Temporary processing|Tenant override|Deployment default|LOCAL_FILESYSTEM|SHARED_FILESYSTEM|CANDIDATE|node-a|Retire/);
});

test('Branding: one form with Apply to, preview of the top bar; Usage data in plain words', async ({ page }) => {
  const calls: Call[] = [];
  await openPlatform(page, 'PLATFORM_OPERATIONS_ADMIN', calls);
  const sections = page.getByRole('group', { name: 'Platform operations sections' });
  await sections.getByRole('button', { name: 'Branding' }).click();
  const card = page.getByRole('region', { name: 'Branding' });
  const applyTo = card.getByLabel('Apply to');
  await expect(applyTo).toHaveValue('');
  await expect(card.getByRole('button', { name: 'Save' })).toHaveCount(1);
  await expect(card.getByRole('button', { name: 'Save' })).toBeDisabled();
  await card.getByLabel('Product name').fill('Northwind Vault');
  await expect(card.getByRole('img', { name: "Everyone's branding preview" })).toContainText('Northwind Vault');
  await expect(card.getByRole('img', { name: "Everyone's branding preview" })).toContainText('Cases');
  await card.getByRole('button', { name: 'Save' }).click();
  await expect.poll(() => calls.find((c) => c.path === '/api/v1/platform/branding/deployment')?.body.applicationName).toBe('Northwind Vault');
  // Task 0038: the accent colour is gone, from the screen and from what is saved.
  await expect(card.getByText(/accent/i)).toHaveCount(0);
  expect(Object.keys(calls.find((c) => c.path === '/api/v1/platform/branding/deployment')?.body ?? {})).not.toContain('accentColor');
  await applyTo.selectOption('t-nw');
  await expect(card.getByText("Blank fields use everyone's look.")).toBeVisible();
  await expect(card.getByLabel('Product name')).toHaveAttribute('placeholder', "Everyone's look");
  await expect(card.getByRole('button', { name: 'Save' })).toHaveCount(1);
  await expect(page.getByText(/Deployment branding|Tenant overrides|sidebar/i)).toHaveCount(0);

  await sections.getByRole('button', { name: 'Usage data' }).click();
  const usage = page.getByRole('region', { name: 'Usage data' });
  await expect(usage.getByRole('button', { name: 'Basic' })).toHaveAttribute('aria-pressed', 'true');
  await expect(usage.getByRole('button', { name: 'Detailed' })).toBeVisible();
  await expect(usage.getByText('Sends counts of when a case started processing, a case finished processing.')).toBeVisible();
  await expect(usage.getByText('Document content: PDFs and images')).toBeVisible();
  expect(await mainText(usage)).not.toMatch(/telemetry|package\.|ALLOW_LIST|ENHANCED/i);
});

param(
    [string]$FrontendPath = ".\frontend\securedoc-ui",
    [string]$Username = "platform.ops.admin",
    [string]$Password = "Test123!"
)

$ErrorActionPreference = "Stop"

$frontend = (Resolve-Path $FrontendPath).Path
$playwrightCli = Join-Path $frontend "node_modules\@playwright\test\cli.js"
$e2eDir = Join-Path $frontend "e2e"
$specPath = Join-Path $e2eDir "__oidc-session-continuity.acceptance.spec.ts"

if (-not (Test-Path $playwrightCli)) {
    throw "Playwright is not installed under $frontend. Run the normal frontend dependency install/build first."
}
if (-not (Test-Path $e2eDir)) {
    throw "LexaVault e2e directory was not found: $e2eDir"
}

function Assert-Reachable([string]$Name, [string]$Uri) {
    try {
        $response = Invoke-WebRequest -Uri $Uri -UseBasicParsing -TimeoutSec 5
        if ($response.StatusCode -lt 200 -or $response.StatusCode -ge 400) {
            throw "HTTP $($response.StatusCode)"
        }
    } catch {
        throw "$Name is not reachable at $Uri. Start/verify the required service before running this acceptance test. $($_.Exception.Message)"
    }
}

Assert-Reachable "LexaVault frontend" "http://localhost:5173/"
Assert-Reachable "Keycloak OIDC discovery" "http://localhost:8181/realms/document-platform-local/.well-known/openid-configuration"

$spec = @'
import { test, expect, type Page, type Request } from '@playwright/test';

const USERNAME = process.env.LV_OIDC_TEST_USERNAME || 'platform.ops.admin';
const PASSWORD = process.env.LV_OIDC_TEST_PASSWORD || 'Test123!';
const ACCESS = 'securedoc.oidc.access-token';
const REFRESH = 'securedoc.oidc.refresh-token';
const EXPIRES = 'securedoc.oidc.access-token-expires-at';

function isRefreshRequest(request: Request): boolean {
  if (request.method() !== 'POST') return false;
  const body = request.postData() || '';
  return body.includes('grant_type=refresh_token');
}

async function ensureSignedIn(page: Page): Promise<void> {
  await page.goto('/');

  const signIn = page.getByRole('button', { name: /^Sign in$/ });
  if (await signIn.isVisible().catch(() => false)) {
    await signIn.click();
  }

  // Keycloak may reuse an existing SSO session and return immediately. A fresh
  // Playwright context normally shows the login form. Do not use locator.isVisible
  // with a timeout here: isVisible is an immediate snapshot and can race the
  // redirect to Keycloak. Wait until either the login form or Dashboard is visible.
  const username = page.locator('#username');
  const dashboard = page.getByRole('heading', { name: 'Dashboard' });
  let authState: 'login' | 'dashboard' | null = null;
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (await username.isVisible().catch(() => false)) {
      authState = 'login';
      break;
    }
    if (await dashboard.isVisible().catch(() => false)) {
      authState = 'dashboard';
      break;
    }
    await page.waitForTimeout(200);
  }

  if (authState === 'login') {
    console.log('  Keycloak login form detected; signing in test browser.');
    await username.fill(USERNAME);
    await page.locator('#password').fill(PASSWORD);
    await Promise.all([
      page.waitForURL(url => url.origin === 'http://localhost:5173', { timeout: 30_000 }),
      page.locator('#kc-login').click(),
    ]);
  } else if (authState !== 'dashboard') {
    throw new Error(`Neither the Keycloak login form nor LexaVault Dashboard became visible. Current URL: ${page.url()}`);
  }

  await expect(dashboard).toBeVisible({ timeout: 30_000 });
  await expect.poll(async () => page.evaluate(([a, r]) => ({
    access: Boolean(sessionStorage.getItem(a)),
    refresh: Boolean(sessionStorage.getItem(r)),
  }), [ACCESS, REFRESH] as const)).toEqual({ access: true, refresh: true });
}

async function forceNearExpiry(page: Page): Promise<void> {
  await page.evaluate((key) => sessionStorage.setItem(key, String(Date.now() + 1_000)), EXPIRES);
}

async function clickDashboardRefresh(page: Page): Promise<void> {
  const button = page.getByRole('button', { name: /^Refresh$/ }).last();
  await expect(button).toBeVisible();
  await button.click();
}

test('OIDC session refreshes twice and fails closed when refresh is rejected', async ({ page }) => {
  test.setTimeout(90_000);

  const refreshRequests: Request[] = [];
  page.on('request', request => {
    if (isRefreshRequest(request)) refreshRequests.push(request);
  });

  console.log('  [1/4] Establishing OIDC browser session...');
  await ensureSignedIn(page);

  // Do not log token values. We only verify presence and expiry metadata.
  const initial = await page.evaluate(([a, r, e]) => ({
    access: Boolean(sessionStorage.getItem(a)),
    refresh: Boolean(sessionStorage.getItem(r)),
    expiresAt: Number(sessionStorage.getItem(e) || 0),
  }), [ACCESS, REFRESH, EXPIRES] as const);
  expect(initial.access).toBeTruthy();
  expect(initial.refresh).toBeTruthy();
  expect(initial.expiresAt).toBeGreaterThan(Date.now());

  // Ensure a refreshed JWT is issued in a later second than the login JWT.
  await page.waitForTimeout(1_200);

  // Cycle 1: make the SPA believe the access token is inside its 60-second refresh
  // window. A normal Dashboard API call must refresh it without changing Keycloak.
  console.log('  [2/4] Forcing near-expiry metadata and validating refresh #1...');
  await forceNearExpiry(page);
  const firstRefresh = page.waitForResponse(response =>
    isRefreshRequest(response.request()) && response.status() === 200);
  await clickDashboardRefresh(page);
  const firstRefreshResponse = await firstRefresh;
  await expect.poll(() => refreshRequests.length).toBe(1);
  const firstRefreshPayload = await firstRefreshResponse.json() as { expires_in?: number };
  const firstExpiresIn = Number(firstRefreshPayload.expires_in || 0);
  expect(firstExpiresIn).toBeGreaterThan(0);
  console.log(`    Refresh #1 succeeded; provider access-token lifetime: ${firstExpiresIn}s`);
  await expect.poll(async () => page.evaluate((e) =>
    Number(sessionStorage.getItem(e) || 0), EXPIRES), { timeout: 5_000 }).toBeGreaterThan(Date.now() + Math.max(1_000, (firstExpiresIn - 5) * 1000));
  const afterFirst = await page.evaluate(([r, e]) => ({
    refresh: Boolean(sessionStorage.getItem(r)),
    expiresAt: Number(sessionStorage.getItem(e) || 0),
  }), [REFRESH, EXPIRES] as const);
  expect(afterFirst.refresh).toBeTruthy();
  expect(afterFirst.expiresAt).toBeGreaterThan(Date.now() + 1_000);
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();

  await page.waitForTimeout(1_200);

  // Cycle 2: proves the stored refresh token (including provider rotation, when
  // supplied) can sustain another access-token refresh.
  console.log('  [3/4] Validating refresh #2 / refresh-token continuity...');
  await forceNearExpiry(page);
  const secondRefresh = page.waitForResponse(response =>
    isRefreshRequest(response.request()) && response.status() === 200);
  await clickDashboardRefresh(page);
  const secondRefreshResponse = await secondRefresh;
  await expect.poll(() => refreshRequests.length).toBe(2);
  const secondRefreshPayload = await secondRefreshResponse.json() as { expires_in?: number };
  const secondExpiresIn = Number(secondRefreshPayload.expires_in || 0);
  expect(secondExpiresIn).toBeGreaterThan(0);
  console.log(`    Refresh #2 succeeded; provider access-token lifetime: ${secondExpiresIn}s`);
  await expect.poll(async () => page.evaluate((e) =>
    Number(sessionStorage.getItem(e) || 0), EXPIRES), { timeout: 5_000 }).toBeGreaterThan(Date.now() + Math.max(1_000, (secondExpiresIn - 5) * 1000));
  const afterSecond = await page.evaluate(([r, e]) => ({
    refresh: Boolean(sessionStorage.getItem(r)),
    expiresAt: Number(sessionStorage.getItem(e) || 0),
  }), [REFRESH, EXPIRES] as const);
  expect(afterSecond.refresh).toBeTruthy();
  expect(afterSecond.expiresAt).toBeGreaterThan(Date.now() + 1_000);
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible();

  // Fail-closed check: corrupt only the browser's refresh token, force refresh,
  // and confirm the application clears the OIDC session and returns to Sign in.
  console.log('  [4/4] Validating fail-closed behavior for rejected refresh token...');
  await page.evaluate(([r, e]) => {
    sessionStorage.setItem(r, 'acceptance-test-invalid-refresh-token');
    sessionStorage.setItem(e, String(Date.now() - 1));
  }, [REFRESH, EXPIRES] as const);

  const rejectedRefresh = page.waitForResponse(response =>
    isRefreshRequest(response.request()) && (response.status() === 400 || response.status() === 401));
  await clickDashboardRefresh(page);
  await rejectedRefresh;

  await expect(page.getByRole('button', { name: /^Sign in$/ })).toBeVisible({ timeout: 15_000 });
  const cleared = await page.evaluate(([a, r, e]) => ({
    access: sessionStorage.getItem(a),
    refresh: sessionStorage.getItem(r),
    expires: sessionStorage.getItem(e),
  }), [ACCESS, REFRESH, EXPIRES] as const);
  expect(cleared).toEqual({ access: null, refresh: null, expires: null });
});
'@

Set-Content -Path $specPath -Value $spec -Encoding UTF8

$oldUser = $env:LV_OIDC_TEST_USERNAME
$oldPassword = $env:LV_OIDC_TEST_PASSWORD
$env:LV_OIDC_TEST_USERNAME = $Username
$env:LV_OIDC_TEST_PASSWORD = $Password

try {
    Write-Host "LexaVault OIDC Session Continuity Acceptance" -ForegroundColor Cyan
    Write-Host "  Frontend: $frontend"
    Write-Host "  App:      http://localhost:5173"
    Write-Host "  User:     $Username"
    Write-Host ""
    Write-Host "This test does NOT change Keycloak token lifetimes." -ForegroundColor DarkGray
    Write-Host "It forces only LexaVault's browser expiry metadata, validates two real refresh-token grants," -ForegroundColor DarkGray
    Write-Host "then intentionally invalidates the browser refresh token to verify fail-closed sign-out." -ForegroundColor DarkGray
    Write-Host ""

    Push-Location $frontend
    try {
        & node $playwrightCli test "e2e/__oidc-session-continuity.acceptance.spec.ts" --project=chromium --reporter=list
        if ($LASTEXITCODE -ne 0) {
            throw "OIDC session continuity acceptance failed with exit code $LASTEXITCODE."
        }
    }
    finally {
        Pop-Location
    }

    Write-Host ""
    Write-Host "PASS - OIDC session continuity acceptance completed." -ForegroundColor Green
}
finally {
    if (Test-Path $specPath) { Remove-Item $specPath -Force }
    $env:LV_OIDC_TEST_USERNAME = $oldUser
    $env:LV_OIDC_TEST_PASSWORD = $oldPassword
}

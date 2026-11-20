<#
.SYNOPSIS
    OIDC session-continuity acceptance for LexaVault (Case File screens): two real refresh-token grants, then a
    fail-closed sign-out when the browser's refresh token is rejected.

.DESCRIPTION
    Needs Keycloak (http://localhost:8181) and LexaVault in OIDC mode (http://localhost:5173) already running.
    The script clicks LexaVault's "Sign in" button, fills Keycloak's login form, waits for the Case File main
    heading (#cf-main h1) and reloads Cases to make the API calls that must refresh the session.

    It does not change Keycloak token lifetimes. It only shortens LexaVault's own browser expiry metadata.

    There is no built-in password. Give it in one of two ways (the script stops if neither is given):
      -PromptForPassword           the script asks for it; typing is hidden and nothing goes into the command history
      $env:LV_OIDC_TEST_PASSWORD   set by you in your own terminal before running the script
    -Password also works, but a password typed into a command line is kept in PowerShell's history file.
    The password is never printed, logged or written to a file. Playwright's trace is off for this run, and its
    output folder (screenshots on failure) is a temporary folder removed at the end.

.EXAMPLE
    # From the repository root, with Keycloak and the app running (see docs/work/ui/U028-report.md):
    .\Test-LexaVault-OidcSessionContinuity.ps1 -PromptForPassword
#>
param(
    [string]$FrontendPath = ".\frontend\securedoc-ui",
    [string]$Username = $(if ($env:LV_OIDC_TEST_USERNAME) { $env:LV_OIDC_TEST_USERNAME } else { "platform.ops.admin" }),
    [string]$Password,
    [switch]$PromptForPassword
)

$ErrorActionPreference = "Stop"

# Credentials first: refuse cleanly before touching anything.
$resolvedPassword = $null
if ($PromptForPassword) {
    $secure = Read-Host -AsSecureString "Keycloak password for $Username"
    $resolvedPassword = [System.Net.NetworkCredential]::new('', $secure).Password
} elseif ($Password) {
    $resolvedPassword = $Password
} elseif ($env:LV_OIDC_TEST_PASSWORD) {
    $resolvedPassword = $env:LV_OIDC_TEST_PASSWORD
}
if (-not $resolvedPassword) {
    Write-Host "STOPPED - no password for the Keycloak test user '$Username'." -ForegroundColor Yellow
    Write-Host "  Run it again with -PromptForPassword, or set LV_OIDC_TEST_PASSWORD in your own terminal first."
    Write-Host "  There is no built-in password. Nothing was run."
    exit 2
}

$frontend = (Resolve-Path $FrontendPath).Path
$playwrightCli = Join-Path $frontend "node_modules\@playwright\test\cli.js"
$e2eDir = Join-Path $frontend "e2e"
$specPath = Join-Path $e2eDir "__oidc-session-continuity.acceptance.spec.ts"
$outputDir = Join-Path ([System.IO.Path]::GetTempPath()) ("lv-oidc-continuity-" + [guid]::NewGuid().ToString("N"))

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

# The spec holds no credentials: it reads them from the environment of the Playwright process only.
$spec = @'
import { test, expect, type Page, type Request } from '@playwright/test';

const USERNAME = process.env.LV_OIDC_TEST_USERNAME || '';
const PASSWORD = process.env.LV_OIDC_TEST_PASSWORD || '';
const ACCESS = 'securedoc.oidc.access-token';
const REFRESH = 'securedoc.oidc.refresh-token';
const EXPIRES = 'securedoc.oidc.access-token-expires-at';

function isRefreshRequest(request: Request): boolean {
  if (request.method() !== 'POST') return false;
  const body = request.postData() || '';
  return body.includes('grant_type=refresh_token');
}

/** The Case File main heading: shown once signed in and inside an entity. */
const caseFileMain = (page: Page) => page.locator('#cf-main h1').first();

async function ensureSignedIn(page: Page): Promise<void> {
  if (!USERNAME || !PASSWORD) throw new Error('No test credentials in the environment; refusing to run.');
  await page.goto('/');

  // LexaVault's own sign-in screen comes first; its "Sign in" button redirects to Keycloak.
  const signIn = page.getByRole('button', { name: /^Sign in$/ });
  await expect(signIn.or(caseFileMain(page))).toBeVisible({ timeout: 20_000 });
  if (await signIn.isVisible().catch(() => false)) {
    await signIn.click();
  }

  // Keycloak may reuse an existing SSO session and return immediately. A fresh Playwright context normally shows
  // the login form. Do not use locator.isVisible with a timeout here: isVisible is an immediate snapshot and can
  // race the redirect to Keycloak. Wait until the login form, the Case File main or the entity chooser is visible.
  const username = page.locator('#username');
  const main = caseFileMain(page);
  const chooser = page.getByRole('heading', { name: 'Choose an entity' });
  let authState: 'login' | 'main' | 'chooser' | null = null;
  const deadline = Date.now() + 20_000;
  while (Date.now() < deadline) {
    if (await username.isVisible().catch(() => false)) { authState = 'login'; break; }
    if (await main.isVisible().catch(() => false)) { authState = 'main'; break; }
    if (await chooser.isVisible().catch(() => false)) { authState = 'chooser'; break; }
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
  } else if (authState === null) {
    throw new Error(`Neither the Keycloak login form nor the LexaVault Case File screens became visible. Current URL: ${page.url()}`);
  }

  await expect(main.or(chooser)).toBeVisible({ timeout: 30_000 });
  if (await chooser.isVisible().catch(() => false)) {
    throw new Error('LexaVault shows "Choose an entity": this test user has more than one entity. Use a user with one entity.');
  }
  await expect.poll(async () => page.evaluate(([a, r]) => ({
    access: Boolean(sessionStorage.getItem(a)),
    refresh: Boolean(sessionStorage.getItem(r)),
  }), [ACCESS, REFRESH] as const)).toEqual({ access: true, refresh: true });
}

async function forceNearExpiry(page: Page): Promise<void> {
  await page.evaluate((key) => sessionStorage.setItem(key, String(Date.now() + 1_000)), EXPIRES);
}

/** Reloads Cases: the app's start-up and Cases requests go through the API client, which refreshes the token. */
async function reloadCases(page: Page): Promise<void> {
  await page.reload();
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

  // Cycle 1: make the SPA believe the access token is inside its 60-second refresh window. The API calls made by
  // reloading Cases must refresh it without changing Keycloak.
  console.log('  [2/4] Forcing near-expiry metadata and validating refresh #1...');
  await forceNearExpiry(page);
  const firstRefresh = page.waitForResponse(response =>
    isRefreshRequest(response.request()) && response.status() === 200);
  await reloadCases(page);
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
  await expect(caseFileMain(page)).toBeVisible();

  await page.waitForTimeout(1_200);

  // Cycle 2: proves the stored refresh token (including provider rotation, when supplied) can sustain another
  // access-token refresh.
  console.log('  [3/4] Validating refresh #2 / refresh-token continuity...');
  await forceNearExpiry(page);
  const secondRefresh = page.waitForResponse(response =>
    isRefreshRequest(response.request()) && response.status() === 200);
  await reloadCases(page);
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
  await expect(caseFileMain(page)).toBeVisible();

  // Fail-closed check: corrupt only the browser's refresh token, force refresh, and confirm the application clears
  // the OIDC session and returns to Sign in.
  console.log('  [4/4] Validating fail-closed behavior for rejected refresh token...');
  await page.evaluate(([r, e]) => {
    sessionStorage.setItem(r, 'acceptance-test-invalid-refresh-token');
    sessionStorage.setItem(e, String(Date.now() - 1));
  }, [REFRESH, EXPIRES] as const);

  const rejectedRefresh = page.waitForResponse(response =>
    isRefreshRequest(response.request()) && (response.status() === 400 || response.status() === 401));
  await reloadCases(page);
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
$env:LV_OIDC_TEST_PASSWORD = $resolvedPassword

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
        # Trace off: a Playwright trace records the values typed into fields, including the password.
        & node $playwrightCli test "e2e/__oidc-session-continuity.acceptance.spec.ts" --project=chromium --reporter=list --trace=off --output="$outputDir"
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
    if (Test-Path $outputDir) { Remove-Item $outputDir -Recurse -Force }
    $env:LV_OIDC_TEST_USERNAME = $oldUser
    $env:LV_OIDC_TEST_PASSWORD = $oldPassword
    $resolvedPassword = $null
}

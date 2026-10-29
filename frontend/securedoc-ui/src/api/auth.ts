const MODE = String(import.meta.env.VITE_SECURITY_MODE || (import.meta.env.DEV ? 'LOCAL' : 'OIDC')).toUpperCase();
const TOKEN_KEY = 'securedoc.oidc.access-token';
const ID_TOKEN_KEY = 'securedoc.oidc.id-token';
const REFRESH_TOKEN_KEY = 'securedoc.oidc.refresh-token';
const ACCESS_TOKEN_EXPIRES_AT_KEY = 'securedoc.oidc.access-token-expires-at';
const STATE_KEY = 'securedoc.oidc.state';
const SELECTED_TENANT_KEY = 'lexavault.oidc.selected-tenant';
const VERIFIER_KEY = 'securedoc.oidc.pkce-verifier';
const REFRESH_SKEW_MS = 60_000;

const OIDC_DEBUG = import.meta.env.DEV;
function oidcDebug(message: string, details?: Record<string, unknown>): void {
  if (!OIDC_DEBUG) return;
  if (details) console.info(`[LexaVault OIDC] ${message}`, details);
  else console.info(`[LexaVault OIDC] ${message}`);
}

export function isLocalSecurityMode(): boolean {
  return MODE === 'LOCAL';
}

export function isOidcSecurityMode(): boolean {
  return !isLocalSecurityMode();
}

export function getAccessToken(): string | null {
  if (!isOidcSecurityMode()) return null;
  return sessionStorage.getItem(TOKEN_KEY);
}

export function clearOidcSession(): void {
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(ID_TOKEN_KEY);
  sessionStorage.removeItem(REFRESH_TOKEN_KEY);
  sessionStorage.removeItem(ACCESS_TOKEN_EXPIRES_AT_KEY);
  sessionStorage.removeItem(STATE_KEY);
  sessionStorage.removeItem(VERIFIER_KEY);
  sessionStorage.removeItem(SELECTED_TENANT_KEY);
}

export const OIDC_SESSION_EXPIRED_EVENT = 'lexavault:oidc-session-expired';

export function expireOidcSession(): void {
  clearOidcSession();
  window.dispatchEvent(new Event(OIDC_SESSION_EXPIRED_EVENT));
}

let oidcCallbackInFlight: Promise<boolean> | null = null;
let oidcRefreshInFlight: Promise<string | null> | null = null;

type OidcTokenResponse = {
  access_token?: string;
  id_token?: string;
  refresh_token?: string;
  expires_in?: number;
};

export function handleOidcCallback(): Promise<boolean> {
  if (!isOidcSecurityMode()) return Promise.resolve(false);
  const url = new URL(window.location.href);
  if (!url.searchParams.get('code')) {
    oidcDebug('No authorization callback code is present; using any existing session token.', { hasAccessToken: Boolean(getAccessToken()) });
    return Promise.resolve(false);
  }
  oidcDebug('Authorization callback detected.', { hasState: Boolean(url.searchParams.get('state')) });

  // React StrictMode intentionally invokes effects twice in local development.
  // Authorization codes are single-use, so both invocations must share one exchange.
  if (!oidcCallbackInFlight) {
    oidcDebug('Starting single-flight authorization-code exchange.');
    oidcCallbackInFlight = completeOidcCallback(url);
  } else {
    oidcDebug('Reusing in-flight authorization-code exchange.');
  }
  return oidcCallbackInFlight;
}

async function completeOidcCallback(url: URL): Promise<boolean> {
  const code = url.searchParams.get('code');
  const state = url.searchParams.get('state');
  if (!code) return false;

  const expectedState = sessionStorage.getItem(STATE_KEY);
  const verifier = sessionStorage.getItem(VERIFIER_KEY);
  oidcDebug('Validating callback state and PKCE verifier.', {
    callbackStatePresent: Boolean(state),
    expectedStatePresent: Boolean(expectedState),
    stateMatches: Boolean(state && expectedState && state === expectedState),
    verifierPresent: Boolean(verifier),
  });
  if (!state || !expectedState || state !== expectedState || !verifier) {
    clearOidcSession();
    throw new Error(`OIDC_CALLBACK_VALIDATION_FAILED statePresent=${Boolean(state)} expectedStatePresent=${Boolean(expectedState)} stateMatches=${Boolean(state && expectedState && state === expectedState)} verifierPresent=${Boolean(verifier)}`);
  }

  const config = oidcConfig();
  oidcDebug('Loading OIDC discovery document.', { issuer: config.issuer, clientId: config.clientId, redirectUri: config.redirectUri });
  const discovery = await loadDiscovery(config.issuer);
  oidcDebug('Discovery loaded; exchanging authorization code.', { tokenEndpoint: discovery.token_endpoint });
  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: config.clientId,
    code,
    redirect_uri: config.redirectUri,
    code_verifier: verifier,
  });
  const response = await fetch(discovery.token_endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body,
  });
  if (!response.ok) {
    const errorText = (await response.text()).slice(0, 500);
    oidcDebug('Authorization-code exchange failed.', { status: response.status, statusText: response.statusText, response: errorText });
    clearOidcSession();
    throw new Error(`OIDC_TOKEN_EXCHANGE_FAILED HTTP ${response.status} ${response.statusText}${errorText ? `: ${errorText}` : ''}`);
  }
  oidcDebug('Authorization-code exchange succeeded.', { status: response.status });
  const token = await response.json() as OidcTokenResponse;
  if (!token.access_token) {
    clearOidcSession();
    throw new Error('OIDC token response did not include an access token');
  }
  storeTokenResponse(token);
  oidcDebug('OIDC tokens stored in sessionStorage. Token values are intentionally not logged.', {
    hasIdToken: Boolean(token.id_token),
    hasRefreshToken: Boolean(token.refresh_token),
    expiresInSeconds: token.expires_in,
  });
  sessionStorage.removeItem(STATE_KEY);
  sessionStorage.removeItem(VERIFIER_KEY);
  window.history.replaceState({}, document.title, url.pathname || '/');
  oidcDebug('OIDC callback completed and callback parameters were removed from the URL.');
  return true;
}

/**
 * Returns a usable access token for API calls. Short-lived access tokens are
 * refreshed with the OIDC refresh token before expiry when the provider issued
 * one. This keeps the browser session alive across normal access-token rotation
 * without extending the IdP's own SSO/refresh-session policy.
 */
export async function getValidAccessToken(): Promise<string | null> {
  if (!isOidcSecurityMode()) return null;
  const accessToken = sessionStorage.getItem(TOKEN_KEY);
  if (!accessToken) return null;

  const expiresAt = accessTokenExpiresAt(accessToken);
  if (!expiresAt || expiresAt - Date.now() > REFRESH_SKEW_MS) return accessToken;

  const refreshToken = sessionStorage.getItem(REFRESH_TOKEN_KEY);
  if (!refreshToken) {
    if (expiresAt <= Date.now()) expireOidcSession();
    return expiresAt > Date.now() ? accessToken : null;
  }

  if (!oidcRefreshInFlight) {
    oidcDebug('Access token is near expiry; starting single-flight token refresh.');
    oidcRefreshInFlight = refreshAccessToken(refreshToken).finally(() => { oidcRefreshInFlight = null; });
  } else {
    oidcDebug('Reusing in-flight access-token refresh.');
  }
  return oidcRefreshInFlight;
}

async function refreshAccessToken(refreshToken: string): Promise<string | null> {
  const config = oidcConfig();
  const discovery = await loadDiscovery(config.issuer);
  let response: Response;
  try {
    response = await fetch(discovery.token_endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({
        grant_type: 'refresh_token',
        client_id: config.clientId,
        refresh_token: refreshToken,
      }),
    });
  } catch (error) {
    // A temporary network/IdP outage must not silently erase an otherwise
    // recoverable browser session. The caller can surface the request failure.
    oidcDebug('Access-token refresh could not reach the token endpoint.');
    throw error;
  }

  if (!response.ok) {
    const errorText = (await response.text()).slice(0, 300);
    oidcDebug('Access-token refresh was rejected.', { status: response.status, statusText: response.statusText, response: errorText });
    if (response.status === 400 || response.status === 401) {
      expireOidcSession();
      return null;
    }
    throw new Error(`OIDC_TOKEN_REFRESH_FAILED HTTP ${response.status} ${response.statusText}`);
  }

  const token = await response.json() as OidcTokenResponse;
  if (!token.access_token) {
    expireOidcSession();
    return null;
  }
  storeTokenResponse(token, refreshToken);
  oidcDebug('Access token refreshed successfully. Token values are intentionally not logged.', {
    refreshTokenRotated: Boolean(token.refresh_token),
    expiresInSeconds: token.expires_in,
  });
  return token.access_token;
}

function storeTokenResponse(token: OidcTokenResponse, existingRefreshToken?: string): void {
  if (!token.access_token) return;
  sessionStorage.setItem(TOKEN_KEY, token.access_token);
  if (token.id_token) sessionStorage.setItem(ID_TOKEN_KEY, token.id_token);
  const refreshToken = token.refresh_token || existingRefreshToken;
  if (refreshToken) sessionStorage.setItem(REFRESH_TOKEN_KEY, refreshToken);

  const expiresAt = Number.isFinite(token.expires_in) && Number(token.expires_in) > 0
    ? Date.now() + Number(token.expires_in) * 1000
    : jwtExpiresAt(token.access_token);
  if (expiresAt) sessionStorage.setItem(ACCESS_TOKEN_EXPIRES_AT_KEY, String(expiresAt));
  else sessionStorage.removeItem(ACCESS_TOKEN_EXPIRES_AT_KEY);
}

function accessTokenExpiresAt(accessToken: string): number | null {
  const stored = Number(sessionStorage.getItem(ACCESS_TOKEN_EXPIRES_AT_KEY));
  if (Number.isFinite(stored) && stored > 0) return stored;
  const decoded = jwtExpiresAt(accessToken);
  if (decoded) sessionStorage.setItem(ACCESS_TOKEN_EXPIRES_AT_KEY, String(decoded));
  return decoded;
}

function jwtExpiresAt(token: string): number | null {
  try {
    const payload = token.split('.')[1];
    if (!payload) return null;
    const normalized = payload.replace(/-/g, '+').replace(/_/g, '/');
    const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=');
    const json = JSON.parse(atob(padded)) as { exp?: number };
    return Number.isFinite(json.exp) ? Number(json.exp) * 1000 : null;
  } catch {
    return null;
  }
}

export async function endOidcSession(): Promise<void> {
  if (!isOidcSecurityMode()) return;
  const config = oidcConfig();
  const idToken = sessionStorage.getItem(ID_TOKEN_KEY);
  try {
    const discovery = await loadDiscovery(config.issuer);
    const endSessionEndpoint = discovery.end_session_endpoint;
    clearOidcSession();
    if (endSessionEndpoint) {
      const logout = new URL(endSessionEndpoint);
      if (idToken) logout.searchParams.set('id_token_hint', idToken);
      logout.searchParams.set('post_logout_redirect_uri', config.redirectUri);
      window.location.assign(logout.toString());
      return;
    }
  } catch {
    clearOidcSession();
  }
  window.location.assign(config.redirectUri);
}

export async function beginOidcLogin(forceInteraction = false): Promise<void> {
  const config = oidcConfig();
  const discovery = await loadDiscovery(config.issuer);
  const verifier = randomBase64Url(64);
  const challenge = await sha256Base64Url(verifier);
  const state = randomBase64Url(32);
  sessionStorage.setItem(STATE_KEY, state);
  sessionStorage.setItem(VERIFIER_KEY, verifier);

  const authorize = new URL(discovery.authorization_endpoint);
  authorize.searchParams.set('client_id', config.clientId);
  authorize.searchParams.set('response_type', 'code');
  authorize.searchParams.set('redirect_uri', config.redirectUri);
  authorize.searchParams.set('scope', config.scope);
  authorize.searchParams.set('state', state);
  authorize.searchParams.set('code_challenge', challenge);
  authorize.searchParams.set('code_challenge_method', 'S256');
  if (forceInteraction) { authorize.searchParams.set('prompt', 'login'); authorize.searchParams.set('max_age', '0'); }
  window.location.assign(authorize.toString());
}

function oidcConfig() {
  const issuer = String(import.meta.env.VITE_OIDC_ISSUER || '').replace(/\/$/, '');
  const clientId = String(import.meta.env.VITE_OIDC_CLIENT_ID || '');
  if (!issuer || !clientId) throw new Error('OIDC issuer/client ID are not configured for this UI build');
  return {
    issuer,
    clientId,
    scope: String(import.meta.env.VITE_OIDC_SCOPE || 'openid profile email'),
    redirectUri: String(import.meta.env.VITE_OIDC_REDIRECT_URI || `${window.location.origin}/`),
  };
}

async function loadDiscovery(issuer: string): Promise<{ authorization_endpoint: string; token_endpoint: string; end_session_endpoint?: string }> {
  const discoveryUrl = `${issuer}/.well-known/openid-configuration`;
  const response = await fetch(discoveryUrl, { cache: 'no-store' });
  if (!response.ok) {
    oidcDebug('OIDC discovery failed.', { url: discoveryUrl, status: response.status, statusText: response.statusText });
    throw new Error(`OIDC_DISCOVERY_FAILED HTTP ${response.status} ${response.statusText}`);
  }
  const json = await response.json() as { authorization_endpoint?: string; token_endpoint?: string; end_session_endpoint?: string };
  if (!json.authorization_endpoint || !json.token_endpoint) throw new Error('OIDC discovery document is incomplete');
  return { authorization_endpoint: json.authorization_endpoint, token_endpoint: json.token_endpoint, end_session_endpoint: json.end_session_endpoint };
}

function randomBase64Url(bytes: number): string {
  const data = new Uint8Array(bytes);
  crypto.getRandomValues(data);
  return toBase64Url(data);
}

async function sha256Base64Url(value: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  return toBase64Url(new Uint8Array(digest));
}

function toBase64Url(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach((b) => { binary += String.fromCharCode(b); });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

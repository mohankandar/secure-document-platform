import { isOidcSecurityMode } from './auth';

const TENANT_KEY = 'lexavault.oidc.selected-tenant';
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

export const TENANT_CONTEXT_CHANGED_EVENT = 'lexavault:tenant-context-changed';

/**
 * The selection is only a request hint. The server must independently validate
 * every request against the verified JWT, ACTIVE connection and tenant memberships.
 */
export function selectedOidcTenant(): string | null {
  if (!isOidcSecurityMode()) return null;
  const value = sessionStorage.getItem(TENANT_KEY);
  return value && UUID.test(value) ? value : null;
}

export function setSelectedOidcTenant(tenantId: string | null): void {
  if (!isOidcSecurityMode()) return;
  if (tenantId !== null && !UUID.test(tenantId)) throw new Error('Invalid tenant identifier');
  const previous = selectedOidcTenant();
  if (tenantId === null) sessionStorage.removeItem(TENANT_KEY);
  else sessionStorage.setItem(TENANT_KEY, tenantId);
  if (previous !== tenantId) {
    window.dispatchEvent(new CustomEvent(TENANT_CONTEXT_CHANGED_EVENT, {
      detail: { tenantId },
    }));
  }
}

export function clearSelectedOidcTenant(): void {
  sessionStorage.removeItem(TENANT_KEY);
}
